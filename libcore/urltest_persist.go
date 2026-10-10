package libcore

// 策略组测速结果持久化（参考 Exclave：启动就载入上次结果）。
//
// 之前每次启动测速记录都是空的：首轮测完之前策略组只能用第一个成员，它如果是死的，能卡住最多 15 秒。
// 现在主实例启动前把上次保存的 延迟/时间 写回 HistoryStorage，策略组一启动就能按上次结果选节点；
// 同时保留原始测速时间，超过测速间隔的记录照常会在首轮被重测，死节点仍由看门狗/拨号失败即时纠正。

import (
	"encoding/json"
	"log"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/urltest"
)

const (
	urlTestHistoryFileName = "urltest_history.json"
	urlTestHistoryMaxAge   = 12 * time.Hour
	urlTestHistorySaveTick = time.Minute
)

type persistedDelay struct {
	Delay uint16 `json:"d"`
	Time  int64  `json:"t"` // unix ms
}

var urlTestHistoryFileAccess sync.Mutex

func urlTestHistoryPath() string {
	return filepath.Join(internalAssetsPath, urlTestHistoryFileName)
}

// newPersistentHistoryStorage 创建 HistoryStorage 并载入上次保存的结果。
func newPersistentHistoryStorage() *urltest.HistoryStorage {
	storage := urltest.NewHistoryStorage()
	if internalAssetsPath == "" {
		return storage
	}
	urlTestHistoryFileAccess.Lock()
	data, err := os.ReadFile(urlTestHistoryPath())
	urlTestHistoryFileAccess.Unlock()
	if err != nil {
		return storage
	}
	var saved map[string]persistedDelay
	if json.Unmarshal(data, &saved) != nil {
		return storage
	}
	now := time.Now()
	loaded := 0
	for tag, item := range saved {
		at := time.UnixMilli(item.Time)
		if item.Delay == 0 || at.After(now) || now.Sub(at) > urlTestHistoryMaxAge {
			continue
		}
		storage.StoreURLTestHistory(tag, &adapter.URLTestHistory{Time: at, Delay: item.Delay})
		loaded++
	}
	if loaded > 0 {
		log.Println("urltest history: restored", loaded, "results from last run")
	}
	return storage
}

// snapshotURLTestHistory 收集当前实例里所有出站（含分组）的测速记录。
func (b *BoxInstance) snapshotURLTestHistory() map[string]persistedDelay {
	if b == nil || b.Box == nil || b.urlTestHistory == nil {
		return nil
	}
	out := make(map[string]persistedDelay)
	for _, o := range b.Outbound().Outbounds() {
		if o == nil {
			continue
		}
		if _, isGroup := o.(adapter.OutboundGroup); isGroup {
			continue
		}
		if h := b.urlTestHistory.LoadURLTestHistory(o.Tag()); h != nil && h.Delay > 0 {
			out[o.Tag()] = persistedDelay{Delay: h.Delay, Time: h.Time.UnixMilli()}
		}
	}
	return out
}

func (b *BoxInstance) saveURLTestHistory() {
	snapshot := b.snapshotURLTestHistory()
	if snapshot == nil || internalAssetsPath == "" {
		return
	}
	data, err := json.Marshal(snapshot)
	if err != nil {
		return
	}
	urlTestHistoryFileAccess.Lock()
	defer urlTestHistoryFileAccess.Unlock()
	if string(data) == b.lastSavedHistory {
		return
	}
	path := urlTestHistoryPath()
	tmp := path + ".tmp"
	if err = os.WriteFile(tmp, data, 0o600); err == nil {
		err = os.Rename(tmp, path)
	}
	if err != nil {
		log.Println("urltest history: save failed:", err)
		return
	}
	b.lastSavedHistory = string(data)
}

// startURLTestHistorySaver 主实例运行期间每分钟落盘一次（内容没变不写）。
func (b *BoxInstance) startURLTestHistorySaver() {
	if b == nil || b.urlTestHistory == nil || b.ctx == nil {
		return
	}
	go func() {
		ticker := time.NewTicker(urlTestHistorySaveTick)
		defer ticker.Stop()
		for {
			select {
			case <-b.ctx.Done():
				return
			case <-ticker.C:
				b.saveURLTestHistory()
			}
		}
	}()
}

// UrlTestResultsSince 返回 sinceMs（unix 毫秒）之后测出的单节点测速结果，JSON：{"tag":{"d":延迟,"t":毫秒时间}}。
// 后台进程每隔一段时间取一次，把策略组自动测速的结果写进节点列表（参考 Exclave 的 observatory 回写）。
// 只含成功的结果：sing-box 测速失败时直接删除该记录。没有新结果时返回空字符串。
func (b *BoxInstance) UrlTestResultsSince(sinceMs int64) string {
	snapshot := b.snapshotURLTestHistory()
	if len(snapshot) == 0 {
		return ""
	}
	fresh := make(map[string]persistedDelay)
	for tag, item := range snapshot {
		if item.Time > sinceMs {
			fresh[tag] = item
		}
	}
	if len(fresh) == 0 {
		return ""
	}
	data, err := json.Marshal(fresh)
	if err != nil {
		return ""
	}
	return string(data)
}
