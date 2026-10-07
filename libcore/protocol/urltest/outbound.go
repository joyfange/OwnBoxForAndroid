package urltest

import (
	"context"
	"io"
	"maps"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/interrupt"
	"github.com/sagernet/sing-box/common/urltest"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/protocol/group"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/batch"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/json/badoption"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/x/list"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

type URLTestOptions struct {
	Outbounds                 []string           `json:"outbounds" reference:"outbound"`
	URL                       string             `json:"url,omitempty"`
	Interval                  badoption.Duration `json:"interval,omitempty"`
	Tolerance                 *uint16            `json:"tolerance,omitempty"`
	IdleTimeout               badoption.Duration `json:"idle_timeout,omitempty"`
	InterruptExistConnections bool               `json:"interrupt_exist_connections,omitempty"`
}

func RegisterURLTest(registry *outbound.Registry) {
	outbound.Register[URLTestOptions](registry, C.TypeURLTest, NewURLTest)
}

var (
	_ adapter.OutboundGroup           = (*URLTest)(nil)
	_ adapter.InterfaceUpdateListener = (*URLTest)(nil)
	_ adapter.Referrer                = (*URLTest)(nil)
)

type URLTest struct {
	outbound.Adapter
	ctx                          context.Context
	outbound                     adapter.OutboundManager
	connection                   adapter.ConnectionManager
	logger                       log.ContextLogger
	tags                         []string
	link                         string
	interval                     time.Duration
	tolerance                    uint16
	idleTimeout                  time.Duration
	group                        *URLTestGroup
	checkAccess                  sync.Mutex
	lastInterfaceCheck           atomic.Int64
	interruptExternalConnections bool
}

func NewURLTest(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options URLTestOptions) (adapter.Outbound, error) {
	tolerance := uint16(50)
	if options.Tolerance != nil {
		tolerance = *options.Tolerance
	}
	interval := time.Duration(options.Interval)
	if interval == 0 {
		interval = C.DefaultURLTestInterval
	}
	idleTimeout := time.Duration(options.IdleTimeout)
	if idleTimeout == 0 {
		idleTimeout = C.DefaultURLTestIdleTimeout
	}
	outbound := &URLTest{
		Adapter:                      outbound.NewAdapter(C.TypeURLTest, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.Outbounds),
		ctx:                          ctx,
		outbound:                     service.FromContext[adapter.OutboundManager](ctx),
		connection:                   service.FromContext[adapter.ConnectionManager](ctx),
		logger:                       logger,
		tags:                         options.Outbounds,
		link:                         options.URL,
		interval:                     interval,
		tolerance:                    tolerance,
		idleTimeout:                  idleTimeout,
		interruptExternalConnections: options.InterruptExistConnections,
	}
	if len(outbound.tags) == 0 {
		return nil, E.New("missing tags")
	}
	return outbound, nil
}

func (s *URLTest) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	switch stage {
	case adapter.StartStateStart:
		outbounds := make([]adapter.Outbound, 0, len(s.tags))
		for i, tag := range s.tags {
			detour, loaded := s.outbound.Outbound(tag)
			if !loaded {
				return E.New("outbound ", i, " not found: ", tag)
			}
			outbounds = append(outbounds, detour)
		}
		grp, err := NewURLTestGroup(s.ctx, s.outbound, s.logger, outbounds, s.link, s.interval, s.tolerance, s.idleTimeout, s.interruptExternalConnections)
		if err != nil {
			return err
		}
		s.group = grp
	case adapter.StartStateStarted:
		s.group.PostStart()
		scope.Add(s.group.Close)
	}
	return nil
}

func (s *URLTest) Now() string {
	if s.group == nil {
		return ""
	}
	if s.group.selectedOutboundTCP != nil {
		return s.group.selectedOutboundTCP.Tag()
	} else if s.group.selectedOutboundUDP != nil {
		return s.group.selectedOutboundUDP.Tag()
	}
	if outbound, _ := s.group.Select(N.NetworkTCP); outbound != nil {
		return outbound.Tag()
	}
	return ""
}

func (s *URLTest) All() []string {
	return s.tags
}

func (s *URLTest) Selected(network string) adapter.Outbound {
	group := s.group
	if group == nil {
		return nil
	}
	var outbound adapter.Outbound
	switch network {
	case N.NetworkTCP:
		outbound = s.group.selectedOutboundTCP
	case N.NetworkUDP:
		outbound = s.group.selectedOutboundUDP
	}
	if outbound == nil {
		outbound, _ = s.group.Select(network)
	}
	return outbound
}

func (s *URLTest) AttachConnection(closer io.Closer) func() {
	s.group.Touch()
	return s.group.interruptGroup.Add(closer, true)
}

func (s *URLTest) References() []string {
	return s.tags
}

func (s *URLTest) URLTest(ctx context.Context) (map[string]uint16, error) {
	return s.group.URLTest(ctx)
}

func (s *URLTest) CheckOutbounds() {
	s.group.CheckOutbounds(s.ctx, true)
}

func (s *URLTest) PerformUpdateCheck() {
	s.group.performUpdateCheck()
}

func (s *URLTest) InterfaceUpdated(ctx context.Context) {
	grp := s.group
	if grp == nil {
		return
	}
	if grp.pause.IsDevicePaused() || grp.pause.IsNetworkPaused() {
		return
	}
	go func() {
		s.checkAccess.Lock()
		defer s.checkAccess.Unlock()
		if ctx.Err() != nil {
			return
		}
		now := time.Now().Unix()
		last := s.lastInterfaceCheck.Load()
		if now-last < 30 {
			return
		}
		s.lastInterfaceCheck.Store(now)
		grp.CheckOutbounds(ctx, false)
	}()
}

func (s *URLTest) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	s.group.Touch()
	var detour adapter.Outbound
	switch N.NetworkName(network) {
	case N.NetworkTCP:
		detour = s.group.selectedOutboundTCP
	case N.NetworkUDP:
		detour = s.group.selectedOutboundUDP
	default:
		return nil, E.Extend(N.ErrUnknownNetwork, network)
	}
	if detour == nil {
		detour, _ = s.group.Select(network)
	}
	if detour == nil {
		return nil, E.New("missing supported outbound")
	}
	// 首选节点 + 有有效测速记录的候选（按延迟），Happy Eyeballs 式错峰拨号：
	// 首选约 0.5 秒还没连上就同时试下一个，谁先连上用谁；首选直接失败则立刻试下一个。
	candidates := append([]adapter.Outbound{detour}, s.group.liveAlternatives(detour, network, 3)...)
	primaryFailed := false
	conn, winner, err := RaceDial(ctx, len(candidates), DefaultDialStagger, func(dialCtx context.Context, i int) (net.Conn, error) {
		return candidates[i].DialContext(dialCtx, network, destination)
	}, func(i int, dialErr error) {
		if i == 0 && ctx.Err() == nil {
			primaryFailed = true
		}
	})
	if primaryFailed || (err == nil && winner > 0) {
		// 真实流量在当前节点上拨号失败（或被候选节点抢先）：立刻核实当前节点，失效就切走，而不是等下一轮定时测速
		go s.group.verifySelectedThrottled("dial failed")
	}
	if err == nil {
		return s.group.interruptGroup.NewConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil
	}
	s.logger.ErrorContext(ctx, err)
	return nil, err
}

func (s *URLTest) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	s.group.Touch()
	detour := s.group.selectedOutboundUDP
	if detour == nil {
		detour, _ = s.group.Select(N.NetworkUDP)
	}
	if detour == nil {
		return nil, E.New("missing supported outbound")
	}
	conn, err := detour.ListenPacket(ctx, destination)
	if err == nil {
		return s.group.interruptGroup.NewPacketConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil
	}
	s.logger.ErrorContext(ctx, err)
	go s.group.verifySelectedThrottled("listen failed")

	// UDP 候选快速容灾（只用有有效测速记录的候选）
	for _, alt := range s.group.liveAlternatives(detour, N.NetworkUDP, 3) {
		altConn, altErr := alt.ListenPacket(ctx, destination)
		if altErr == nil {
			return s.group.interruptGroup.NewPacketConn(altConn, interrupt.IsExternalConnectionFromContext(ctx)), nil
		}
	}
	return nil, err
}

func (s *URLTest) NewConnection(ctx context.Context, conn net.Conn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	ctx = interrupt.ContextWithIsExternalConnection(ctx)
	s.connection.NewConnection(ctx, s, conn, metadata, onClose)
}

func (s *URLTest) NewPacketConnection(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	ctx = interrupt.ContextWithIsExternalConnection(ctx)
	s.connection.NewPacketConnection(ctx, s, conn, metadata, onClose)
}

type URLTestGroup struct {
	ctx                          context.Context
	outbound                     adapter.OutboundManager
	logger                       log.Logger
	outbounds                    []adapter.Outbound
	link                         string
	interval                     time.Duration
	tolerance                    uint16
	idleTimeout                  time.Duration
	history                      *urltest.HistoryStorage
	checking                     atomic.Bool
	pause                        pause.Manager
	pauseCallback                *list.Element[pause.Callback]
	interruptGroup               *interrupt.Group
	selectedOutboundTCP          adapter.Outbound
	selectedOutboundUDP          adapter.Outbound
	interruptExternalConnections bool
	access                       sync.Mutex
	updateAccess                 sync.Mutex
	ticker                       *time.Ticker
	close                        chan struct{}
	started                      bool
	lastActive                   common.TypedValue[time.Time]
	// 当前节点看门狗（参考 Exclave 的 observatory 思路）：只要分组在用，每 30 秒探测一次正在用的节点，
	// 真实连接拨号失败时也会立即触发；确认失效后删除它的测速记录、全组重测并切走、断开卡在死节点上的连接。
	lastUse     atomic.Int64
	lastVerify  atomic.Int64
	verifying   atomic.Bool
	watchStop   chan struct{}
	watchOnce   sync.Once
	watchActive atomic.Bool
}

func NewURLTestGroup(ctx context.Context, outboundManager adapter.OutboundManager, logger log.Logger, outbounds []adapter.Outbound, link string, interval time.Duration, tolerance uint16, idleTimeout time.Duration, interruptExternalConnections bool) (*URLTestGroup, error) {
	if interval == 0 {
		interval = C.DefaultURLTestInterval
	}
	if idleTimeout == 0 {
		idleTimeout = C.DefaultURLTestIdleTimeout
	}
	if interval > idleTimeout {
		return nil, E.New("interval must be less or equal than idle_timeout")
	}
	history := service.PtrFromContext[urltest.HistoryStorage](ctx)
	if history == nil {
		return nil, E.New("missing URL test history storage")
	}
	return &URLTestGroup{
		ctx:                          ctx,
		outbound:                     outboundManager,
		logger:                       logger,
		outbounds:                    outbounds,
		link:                         link,
		interval:                     interval,
		tolerance:                    tolerance,
		idleTimeout:                  idleTimeout,
		history:                      history,
		close:                        make(chan struct{}),
		pause:                        service.FromContext[pause.Manager](ctx),
		interruptGroup:               interrupt.NewGroup(),
		interruptExternalConnections: interruptExternalConnections,
		watchStop:                    make(chan struct{}),
	}, nil
}

func (g *URLTestGroup) PostStart() {
	g.access.Lock()
	defer g.access.Unlock()
	g.started = true
	g.lastActive.Store(time.Now())
	g.lastUse.Store(time.Now().UnixMilli())
	go g.CheckOutbounds(g.ctx, false)
	if g.watchActive.CompareAndSwap(false, true) {
		go g.watchSelected()
	}
}

func (g *URLTestGroup) Touch() {
	if !g.started {
		return
	}
	g.lastUse.Store(time.Now().UnixMilli())
	g.access.Lock()
	defer g.access.Unlock()
	if g.ticker != nil {
		g.lastActive.Store(time.Now())
		return
	}
	ticker := time.NewTicker(g.interval)
	g.ticker = ticker
	g.pauseCallback = pause.RegisterTicker(g.pause, ticker, g.interval, nil)
	go g.loopCheck(ticker, g.close)
}

func (g *URLTestGroup) Close() error {
	g.watchOnce.Do(func() { close(g.watchStop) })
	g.access.Lock()
	defer g.access.Unlock()
	if g.ticker == nil {
		return nil
	}
	g.ticker.Stop()
	g.ticker = nil
	g.pause.UnregisterCallback(g.pauseCallback)
	g.pauseCallback = nil
	close(g.close)
	return nil
}

func (g *URLTestGroup) Select(network string) (adapter.Outbound, bool) {
	var currentSelected adapter.Outbound
	var currentDelay uint16
	switch network {
	case N.NetworkTCP:
		currentSelected = g.selectedOutboundTCP
	case N.NetworkUDP:
		currentSelected = g.selectedOutboundUDP
	}
	if currentSelected != nil {
		if history := g.history.LoadURLTestHistory(group.RealTag(currentSelected, network)); history != nil && history.Delay > 0 {
			currentDelay = history.Delay
		}
	}

	// 阶段一：在所有候选节点中找出客观测量绝对最低延迟节点（无先手偏见）
	var bestOutbound adapter.Outbound
	var bestDelay uint16
	for _, detour := range g.outbounds {
		if !common.Contains(detour.Network(), network) {
			continue
		}
		history := g.history.LoadURLTestHistory(group.RealTag(detour, network))
		if history == nil || history.Delay == 0 {
			continue
		}
		if bestDelay == 0 || history.Delay < bestDelay {
			bestDelay = history.Delay
			bestOutbound = detour
		}
	}

	// 阶段二：平滑防抖决策
	if bestOutbound != nil {
		// 若当前无活跃节点，或当前活跃节点无有效测速/已失效，直接无条件采用最优节点
		if currentSelected == nil || currentDelay == 0 {
			return bestOutbound, true
		}
		// 若最优节点就是当前活跃节点，继续保持
		if bestOutbound == currentSelected {
			return currentSelected, true
		}
		// 容差判定：仅在最优节点比当前活跃节点快超过 tolerance 时才触发平滑切换
		if currentDelay > bestDelay+g.tolerance {
			return bestOutbound, true
		}
		// 微小抖动未超出 tolerance，保持当前活跃节点，避免频繁颠簸
		return currentSelected, true
	}

	// 阶段三：Fallback（若所有节点均无测速，返回首个支持该网络的节点作为临时占位）
	if currentSelected != nil && common.Contains(currentSelected.Network(), network) {
		return currentSelected, false
	}
	for _, detour := range g.outbounds {
		if common.Contains(detour.Network(), network) {
			return detour, false
		}
	}
	return nil, false
}

func (g *URLTestGroup) loopCheck(ticker *time.Ticker, closeChan <-chan struct{}) {
	if time.Since(g.lastActive.Load()) > g.interval {
		g.lastActive.Store(time.Now())
		g.CheckOutbounds(g.ctx, false)
	}
	for {
		select {
		case <-closeChan:
			return
		case <-ticker.C:
		}
		if time.Since(g.lastActive.Load()) > g.idleTimeout {
			g.access.Lock()
			if g.ticker == ticker {
				g.ticker.Stop()
				g.ticker = nil
				g.pause.UnregisterCallback(g.pauseCallback)
				g.pauseCallback = nil
			}
			g.access.Unlock()
			return
		}
		g.CheckOutbounds(g.ctx, false)
	}
}

func (g *URLTestGroup) CheckOutbounds(ctx context.Context, force bool) {
	_, _ = g.urlTest(ctx, force)
}

func (g *URLTestGroup) URLTest(ctx context.Context) (map[string]uint16, error) {
	return g.urlTest(ctx, true)
}

func (g *URLTestGroup) urlTest(ctx context.Context, force bool) (map[string]uint16, error) {
	if g.checking.Swap(true) {
		return make(map[string]uint16), nil
	}
	defer g.checking.Store(false)
	result := URLTestOutbounds(ctx, g.outbound, g.history, g.logger, g.outbounds, g.link, g.interval, force)
	g.performUpdateCheck()
	return result, nil
}

type urlTestResult struct {
	delay uint16
	err   error
}

type urlTestBatch struct {
	ctx      context.Context
	outbound adapter.OutboundManager
	history  *urltest.HistoryStorage
	logger   log.Logger
	batch    *batch.Batch[any]
	checked  map[string]bool
	groups   []adapter.OutboundGroup
	access   sync.Mutex
	result   map[string]uint16
}

func URLTestOutbounds(ctx context.Context, outboundManager adapter.OutboundManager, history *urltest.HistoryStorage, logger log.Logger, outbounds []adapter.Outbound, link string, interval time.Duration, force bool) map[string]uint16 {
	// 所有节点同时测（Exclave 同款）：之前一次只测 6 个，30 个节点的首轮要约 20 秒，
	// 首轮结束前策略组只能用第一个成员。上限防止超大订阅一次性打开过多连接。
	b, _ := batch.New(ctx, batch.WithConcurrencyNum[any](probeConcurrency(outboundManager, outbounds)))
	testBatch := &urlTestBatch{
		ctx:      ctx,
		outbound: outboundManager,
		history:  history,
		logger:   logger,
		batch:    b,
		checked:  make(map[string]bool),
		result:   make(map[string]uint16),
	}
	testBatch.test(outbounds, link, interval, force)
	b.Wait()
	for _, outboundGroup := range testBatch.groups {
		groupHistory := history.LoadURLTestHistory(group.RealTag(outboundGroup, N.NetworkTCP))
		if groupHistory != nil {
			testBatch.result[outboundGroup.Tag()] = groupHistory.Delay
		}
	}
	return testBatch.result
}

const maxProbeConcurrency = 128

// probeConcurrency 统计（含嵌套分组的）叶子节点数，作为并发数。
func probeConcurrency(manager adapter.OutboundManager, outbounds []adapter.Outbound) int {
	seen := make(map[string]bool)
	var count func(list []adapter.Outbound, depth int)
	count = func(list []adapter.Outbound, depth int) {
		for _, o := range list {
			if o == nil || seen[o.Tag()] {
				continue
			}
			seen[o.Tag()] = true
			if g, ok := o.(adapter.OutboundGroup); ok && depth < 8 && manager != nil {
				members := make([]adapter.Outbound, 0, len(g.All()))
				for _, tag := range g.All() {
					if m, loaded := manager.Outbound(tag); loaded {
						members = append(members, m)
					}
				}
				count(members, depth+1)
			}
		}
	}
	count(outbounds, 0)
	n := len(seen)
	if n < 1 {
		n = 1
	}
	if n > maxProbeConcurrency {
		n = maxProbeConcurrency
	}
	return n
}

func (b *urlTestBatch) test(outbounds []adapter.Outbound, link string, interval time.Duration, force bool) {
	for _, detour := range outbounds {
		tag := detour.Tag()
		if b.checked[tag] {
			continue
		}
		switch nested := detour.(type) {
		case *URLTest:
			b.checked[tag] = true
			b.groups = append(b.groups, nested)
			b.batch.Go(tag, func() (any, error) {
				nestedResult, _ := nested.group.urlTest(b.ctx, force)
				b.access.Lock()
				maps.Copy(b.result, nestedResult)
				b.access.Unlock()
				return nil, nil
			})
		case adapter.OutboundGroup:
			b.checked[tag] = true
			b.groups = append(b.groups, nested)
			b.test(common.FilterNotNil(common.Map(nested.All(), func(it string) adapter.Outbound {
				member, _ := b.outbound.Outbound(it)
				return member
			})), link, interval, force)
		default:
			history := b.history.LoadURLTestHistory(tag)
			if !force && history != nil && time.Since(history.Time) < interval {
				continue
			}
			b.checked[tag] = true
			b.batch.Go(tag, func() (any, error) {
				testCtx, cancel := context.WithTimeout(b.ctx, 4*time.Second)
				defer cancel()
				testChan := make(chan urlTestResult, 1)
				go func() {
					delay, testErr := ProbeOutbound(testCtx, detour, link, 3500*time.Millisecond)
					testChan <- urlTestResult{delay, testErr}
				}()
				var testResult urlTestResult
				select {
				case testResult = <-testChan:
				case <-testCtx.Done():
					testResult.err = testCtx.Err()
				}
				if testResult.err != nil {
					b.logger.Debug("outbound ", tag, " unavailable: ", testResult.err)
					recordProbeFailure(b.history, tag)
				} else {
					b.logger.Debug("outbound ", tag, " available: ", testResult.delay, "ms")
					recordProbeSuccess(b.history, tag)
					b.history.StoreURLTestHistory(tag, &adapter.URLTestHistory{
						Time:  time.Now(),
						Delay: testResult.delay,
					})
					b.access.Lock()
					b.result[tag] = testResult.delay
					b.access.Unlock()
				}
				return nil, nil
			})
		}
	}
}

func (g *URLTestGroup) performUpdateCheck() {
	g.updateAccess.Lock()
	defer g.updateAccess.Unlock()
	var (
		updated  bool
		selected bool
	)
	if outbound, exists := g.Select(N.NetworkTCP); outbound != nil && (g.selectedOutboundTCP == nil || (exists && outbound != g.selectedOutboundTCP)) {
		if g.selectedOutboundTCP != nil {
			updated = true
		}
		g.selectedOutboundTCP = outbound
		selected = true
	}
	if outbound, exists := g.Select(N.NetworkUDP); outbound != nil && (g.selectedOutboundUDP == nil || (exists && outbound != g.selectedOutboundUDP)) {
		if g.selectedOutboundUDP != nil {
			updated = true
		}
		g.selectedOutboundUDP = outbound
		selected = true
	}
	if updated && g.interruptExternalConnections {
		g.interruptGroup.Interrupt(true)
	}
	if selected {
		g.history.NotifyUpdated()
	}
}

// ---- 失效节点处理 ----
//
// 旧逻辑：测速失败时给记录 +300ms 并把时间戳刷新为“现在”，于是失效节点的记录永远不会过期，
// 延迟还会一路累加直到 uint16 溢出回绕成一个很小的值——死节点反而变成“最快”，长期被选中。
// 新逻辑：连续第一次失败只降级（+1000ms、封顶、保留原时间戳，下轮必重测）；连续第二次失败直接删除记录。

type failKey struct {
	history *urltest.HistoryStorage
	tag     string
}

var probeFailures sync.Map // failKey -> *atomic.Int32

func recordProbeSuccess(history *urltest.HistoryStorage, tag string) {
	probeFailures.Delete(failKey{history, tag})
}

func recordProbeFailure(history *urltest.HistoryStorage, tag string) {
	v, _ := probeFailures.LoadOrStore(failKey{history, tag}, new(atomic.Int32))
	n := v.(*atomic.Int32).Add(1)
	old := history.LoadURLTestHistory(tag)
	if n >= 2 || old == nil || old.Delay == 0 {
		history.DeleteURLTestHistory(tag)
		return
	}
	d := uint32(old.Delay) + 1000
	if d > 60000 {
		d = 60000
	}
	history.StoreURLTestHistory(tag, &adapter.URLTestHistory{Time: old.Time, Delay: uint16(d)})
}

func markDead(history *urltest.HistoryStorage, tag string) {
	v, _ := probeFailures.LoadOrStore(failKey{history, tag}, new(atomic.Int32))
	v.(*atomic.Int32).Store(2)
	history.DeleteURLTestHistory(tag)
}

// liveAlternatives 返回除 exclude 外、有有效测速记录的候选，按延迟从低到高，最多 limit 个。
func (g *URLTestGroup) liveAlternatives(exclude adapter.Outbound, network string, limit int) []adapter.Outbound {
	type cand struct {
		o adapter.Outbound
		d uint16
	}
	var list []cand
	for _, alt := range g.outbounds {
		if alt == exclude || !common.Contains(alt.Network(), network) {
			continue
		}
		h := g.history.LoadURLTestHistory(group.RealTag(alt, network))
		if h == nil || h.Delay == 0 {
			continue
		}
		list = append(list, cand{alt, h.Delay})
	}
	for i := 1; i < len(list); i++ {
		for j := i; j > 0 && list[j].d < list[j-1].d; j-- {
			list[j], list[j-1] = list[j-1], list[j]
		}
	}
	out := make([]adapter.Outbound, 0, limit)
	for i := 0; i < len(list) && i < limit; i++ {
		out = append(out, list[i].o)
	}
	return out
}

const (
	watchInterval   = 30 * time.Second
	watchIdleWindow = 2 * time.Minute
	verifyThrottle  = 10 * time.Second
)

func (g *URLTestGroup) paused() bool {
	if g.pause == nil {
		return false
	}
	return g.pause.IsDevicePaused() || g.pause.IsNetworkPaused()
}

// watchSelected 只在分组最近 2 分钟内有流量时探测当前节点（一次请求），息屏/断网时暂停，几乎不耗电。
func (g *URLTestGroup) watchSelected() {
	ticker := time.NewTicker(watchInterval)
	defer ticker.Stop()
	for {
		select {
		case <-g.watchStop:
			return
		case <-g.ctx.Done():
			return
		case <-ticker.C:
		}
		if g.paused() {
			continue
		}
		if time.Since(time.UnixMilli(g.lastUse.Load())) > watchIdleWindow {
			continue
		}
		g.verifySelected("watchdog")
	}
}

func (g *URLTestGroup) verifySelectedThrottled(reason string) {
	now := time.Now().UnixMilli()
	last := g.lastVerify.Load()
	if now-last < verifyThrottle.Milliseconds() || !g.lastVerify.CompareAndSwap(last, now) {
		return
	}
	g.verifySelected(reason)
}

func (g *URLTestGroup) probeOnce(detour adapter.Outbound) error {
	ctx, cancel := context.WithTimeout(g.ctx, 5*time.Second)
	defer cancel()
	_, err := ProbeOutbound(ctx, detour, g.link, 4*time.Second)
	return err
}

// verifySelected 探测正在使用的节点；连续两次失败（中间隔 1 秒，排除瞬时抖动）即判定失效并切换。
func (g *URLTestGroup) verifySelected(reason string) {
	if g.paused() || !g.verifying.CompareAndSwap(false, true) {
		return
	}
	defer g.verifying.Store(false)
	g.lastVerify.Store(time.Now().UnixMilli())

	selected := g.selectedOutboundTCP
	if selected == nil {
		return
	}
	// 嵌套分组（如 自动 -> 日本）：让内层组先核实并切换自己的节点，外层随后按新记录重新选择
	if nested, ok := selected.(*URLTest); ok && nested.group != nil {
		nested.group.verifySelected(reason)
		g.performUpdateCheck()
		return
	}
	realTag := group.RealTag(selected, N.NetworkTCP)
	target := selected
	if o, ok := g.outbound.Outbound(realTag); ok && o != nil {
		target = o
	}
	if g.probeOnce(target) == nil {
		recordProbeSuccess(g.history, realTag)
		return
	}
	select {
	case <-g.watchStop:
		return
	case <-time.After(time.Second):
	}
	if g.probeOnce(target) == nil {
		recordProbeSuccess(g.history, realTag)
		return
	}
	g.logger.Warn("selected outbound ", realTag, " is unreachable (", reason, "), switching")
	markDead(g.history, realTag)
	// 立即全组重测：切到一个“此刻确认可用”的节点，而不是凭旧记录
	_, _ = g.urlTest(g.ctx, true)
	g.performUpdateCheck()
	if g.selectedOutboundTCP != selected {
		// 卡在死节点上的连接不会自己恢复，主动断开让应用重连到新节点
		g.interruptGroup.Interrupt(true)
	}
}
