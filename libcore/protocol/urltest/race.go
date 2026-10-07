package urltest

import (
	"context"
	"net"
	"time"

	E "github.com/sagernet/sing/common/exceptions"
)

// DefaultDialStagger：首选节点约 0.5 秒还没连上，就同时试下一个候选，谁先连上用谁。
// 之前是一个个排队等（每个 2～4.5 秒，最后一个最长 15 秒），死节点会把新连接拖很久。
const DefaultDialStagger = 500 * time.Millisecond

// RaceDial 以 Happy Eyeballs 的方式依次启动 n 个候选：
//   - 先启动第 0 个；每过 stagger 仍无结果就再加一个；
//   - 某个候选失败时立即启动下一个（不必等满 stagger）；
//   - 第一个成功的胜出，其余仍在进行的拨号被取消，事后才成功的连接会被关闭。
//
// attempt 收到的 ctx 是该候选专属的（可被单独取消）；onFail 只在胜负未分之前、由本 goroutine 调用，
// 因为“输了被取消”的候选不是节点自身的问题，不会报给 onFail。
func RaceDial(ctx context.Context, n int, stagger time.Duration, attempt func(ctx context.Context, i int) (net.Conn, error), onFail func(i int, err error)) (net.Conn, int, error) {
	if n <= 0 {
		return nil, -1, E.New("no candidates")
	}
	if stagger <= 0 {
		stagger = DefaultDialStagger
	}
	type result struct {
		conn net.Conn
		i    int
		err  error
	}
	results := make(chan result, n)
	cancels := make([]context.CancelFunc, 0, n)
	started, pending := 0, 0
	launch := func() {
		i := started
		started++
		pending++
		attemptCtx, cancel := context.WithCancel(ctx)
		cancels = append(cancels, cancel)
		go func() {
			conn, err := attempt(attemptCtx, i)
			results <- result{conn, i, err}
		}()
	}
	// 收尾：取消除胜者外的所有候选，并在后台关闭输家晚到的成功连接
	finish := func(winner int) {
		for i, cancel := range cancels {
			if i != winner {
				cancel()
			}
		}
		if left := pending; left > 0 {
			go func() {
				for j := 0; j < left; j++ {
					if r := <-results; r.err == nil && r.conn != nil {
						_ = r.conn.Close()
					}
				}
			}()
		}
	}

	launch()
	timer := time.NewTimer(stagger)
	defer timer.Stop()
	var lastErr error
	for pending > 0 {
		select {
		case r := <-results:
			pending--
			if r.err == nil && r.conn != nil {
				finish(r.i)
				return r.conn, r.i, nil
			}
			if r.err == nil {
				r.err = E.New("nil connection")
			}
			lastErr = r.err
			if onFail != nil {
				onFail(r.i, r.err)
			}
			if started < n {
				launch()
				if !timer.Stop() {
					select {
					case <-timer.C:
					default:
					}
				}
				timer.Reset(stagger)
			}
		case <-timer.C:
			if started < n {
				launch()
				timer.Reset(stagger)
			}
		case <-ctx.Done():
			finish(-1)
			if lastErr == nil {
				lastErr = ctx.Err()
			}
			return nil, -1, lastErr
		}
	}
	finish(-1)
	return nil, -1, lastErr
}
