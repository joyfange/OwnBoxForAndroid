package libcore

// 官方内核的 local rule-set 只接受真实文件路径（.srs binary / .json source），
// 不认识 fork 私有的 "geoip:xxx" / "geosite:xxx" 伪路径。
//
// 本文件在 box.New 之前预处理配置中的 local rule-set：
//   - 官方格式（geoip-cn / geosite-cn，可带 .srs 后缀）：优先直接指向
//     <externalAssets>/geoip-cn.srs 等已存在的官方规则集文件；
//     文件不存在时回退到从本地 geoip.db / geosite.db 转换生成。
//   - 老 nb4a 格式（geoip:cn / geosite:cn）：兼容处理，从本地 db 转换生成 .srs。
//
// 生成的 .srs 缓存于 <externalAssets>/srs/，db 更新后自动重建。

import (
	"encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/sing-box/common/srs"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
)

// parseGeoRuleSetPath 识别 rule-set path 中的 geo 引用，
// 返回规则代码、是否 geoip、是否老 nb4a 格式；非 geo 引用返回 ok=false。
func parseGeoRuleSetPath(path string) (code string, isGeoIP bool, legacy bool, ok bool) {
	// 老 nb4a 格式：geoip:cn / geosite:cn
	if rest, found := strings.CutPrefix(path, "geoip:"); found {
		return rest, true, true, rest != ""
	}
	if rest, found := strings.CutPrefix(path, "geosite:"); found {
		return rest, false, true, rest != ""
	}
	// 官方格式：geoip-cn(.srs) / geosite-cn(.srs)
	name := strings.TrimSuffix(filepath.Base(path), ".srs")
	if rest, found := strings.CutPrefix(name, "geoip-"); found {
		return rest, true, false, rest != ""
	}
	if rest, found := strings.CutPrefix(name, "geosite-"); found {
		return rest, false, false, rest != ""
	}
	return "", false, false, false
}

func prepareLocalGeoRuleSets(ruleSets []option.RuleSet) error {
	// The databases may still be unpacking on the first start after install.
	waitAssetsReady(15 * time.Second)
	var (
		wait     sync.WaitGroup
		errMu    sync.Mutex
		firstErr error
	)
	for i := range ruleSets {
		rs := &ruleSets[i]
		if rs.Type != C.RuleSetTypeLocal {
			continue
		}
		code, isGeoIP, legacy, ok := parseGeoRuleSetPath(rs.LocalOptions.Path)
		if !ok {
			continue
		}
		var dbName string
		if isGeoIP {
			dbName = geoipDat
		} else {
			dbName = geositeDat
		}

		// 官方格式优先：已存在的官方 .srs 文件直接使用
		if !legacy {
			officialPath := filepath.Join(externalAssetsPath, fmt.Sprintf("%s-%s.srs", dbName[:len(dbName)-3], code))
			if _, err := os.Stat(officialPath); err == nil {
				rs.LocalOptions.Path = officialPath
				continue
			}
		}

		tag := ""
		if len(rs.Tag) > 0 {
			tag = rs.Tag[0]
		}
		// Rule-sets that still need building are converted side by side (a big geosite list takes a while).
		wait.Add(1)
		go func(rs *option.RuleSet, tag, code, dbName string, isGeoIP bool) {
			defer wait.Done()
			dstPath, err := convertGeoRuleSetToSRS(tag, code, filepath.Join(externalAssetsPath, dbName), isGeoIP)
			if err != nil {
				errMu.Lock()
				if firstErr == nil {
					firstErr = fmt.Errorf("rule-set %v: %w", rs.Tag, err)
				}
				errMu.Unlock()
				return
			}
			rs.LocalOptions.Path = dstPath
		}(rs, tag, code, dbName, isGeoIP)
	}
	wait.Wait()
	return firstErr
}

// PrewarmGeoRuleSets builds the .srs files the geo rule-sets of a config need ahead of time (the app calls it while
// idle), so pressing start does not wait for the conversion. Cached files are only checked, so a repeat is cheap.
func PrewarmGeoRuleSets(config string) {
	defer func() {
		if r := recover(); r != nil {
			log.Println("PrewarmGeoRuleSets:", r)
		}
	}()
	var parsed struct {
		Route *struct {
			RuleSet []struct {
				Type string `json:"type"`
				Tag  string `json:"tag"`
				Path string `json:"path"`
			} `json:"rule_set"`
		} `json:"route"`
	}
	if err := json.Unmarshal([]byte(config), &parsed); err != nil || parsed.Route == nil {
		return
	}
	var ruleSets []option.RuleSet
	for _, rs := range parsed.Route.RuleSet {
		if rs.Type != C.RuleSetTypeLocal {
			continue
		}
		item := option.RuleSet{Type: C.RuleSetTypeLocal, Tag: []string{rs.Tag}}
		item.LocalOptions.Path = rs.Path
		ruleSets = append(ruleSets, item)
	}
	if len(ruleSets) == 0 {
		return
	}
	started := time.Now()
	if err := prepareLocalGeoRuleSets(ruleSets); err != nil {
		log.Println("PrewarmGeoRuleSets:", err)
		return
	}
	log.Println("geo rule-sets ready in", time.Since(started))
}

// prepareRemoteRuleSets 预处理远端 rule-set，配置本地 initial_path 兜底文件。
// 当首次启动或无缓存时，生成空 SRS 占位，避免 sing-box 在 box.Start 同步下载超时（context deadline exceeded），
// 确保核心在几毫秒内秒启并畅通 VPN，随后由内置 RuleSetUpdater 在后台异步平滑拉取并更新规则。
func prepareRemoteRuleSets(ruleSets []option.RuleSet) error {
	dir := filepath.Join(externalAssetsPath, "srs")
	if err := os.MkdirAll(dir, 0755); err != nil {
		return err
	}
	for i := range ruleSets {
		rs := &ruleSets[i]
		if rs.Type != C.RuleSetTypeRemote {
			continue
		}
		if rs.RemoteOptions.InitialPath != "" {
			if _, err := os.Stat(rs.RemoteOptions.InitialPath); err == nil {
				continue
			}
		}
		tag := ""
		if len(rs.Tag) > 0 {
			tag = rs.Tag[0]
		}
		safeTag := strings.NewReplacer(":", "_", "/", "_", "\\", "_", "?", "_", "&", "_", "=", "_").Replace(tag)
		dstPath := filepath.Join(dir, safeTag+".srs")

		info, err := os.Stat(dstPath)
		if err != nil || info.Size() == 0 {
			file, createErr := os.Create(dstPath)
			if createErr == nil {
				_ = srs.Write(file, option.PlainRuleSet{}, C.RuleSetVersionCurrent)
				_ = file.Close()
			}
		}
		rs.RemoteOptions.InitialPath = dstPath
	}
	return nil
}

// convertGeoRuleSetToSRS 从 geoip.db/geosite.db 提取指定代码的规则并生成 .srs 缓存文件。
func convertGeoRuleSetToSRS(tag string, code string, dbPath string, isGeoIP bool) (string, error) {
	dir := filepath.Join(externalAssetsPath, "srs")
	if err := os.MkdirAll(dir, 0755); err != nil {
		return "", err
	}
	// tag 可能是 "geoip:cn" 等，含文件名不安全字符
	safeTag := strings.NewReplacer(":", "_", "/", "_", "\\", "_").Replace(tag)
	dst := filepath.Join(dir, safeTag+".srs")

	// 缓存复用：.srs 比 db 新则无需重建
	if dbInfo, err := os.Stat(dbPath); err == nil {
		if srsInfo, err := os.Stat(dst); err == nil && srsInfo.ModTime().After(dbInfo.ModTime()) {
			return dst, nil
		}
	}

	var rules []option.HeadlessRule
	var err error
	fallback := false
	if isGeoIP {
		rules, err = loadGeoIPRules(dbPath, code)
	} else {
		rules, err = loadGeoSiteRules(dbPath, code)
	}
	if err != nil {
		log.Printf("Warning: failed to load %s rule code '%s' from %s: %v, writing empty SRS fallback", tag, code, dbPath, err)
		rules = []option.HeadlessRule{}
		fallback = true
	}

	// Write next to the target and rename it into place: the app (prewarm) and the service may build the same
	// rule-set at once, and a start must never load a half-written file.
	tmp := fmt.Sprintf("%s.%d.%d.tmp", dst, os.Getpid(), time.Now().UnixNano())
	file, err := os.Create(tmp)
	if err != nil {
		return "", err
	}
	err = srs.Write(file, option.PlainRuleSet{Rules: rules}, C.RuleSetVersionCurrent)
	if closeErr := file.Close(); err == nil {
		err = closeErr
	}
	if err == nil {
		err = os.Rename(tmp, dst)
	}
	if err != nil {
		os.Remove(tmp)
		return "", err
	}
	if fallback {
		// An empty fallback must not pass as an up-to-date cache: date it back so the next start rebuilds it.
		_ = os.Chtimes(dst, time.Unix(0, 0), time.Unix(0, 0))
	}
	return dst, nil
}
