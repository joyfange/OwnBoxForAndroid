package libcore

import (
	"log"
	"sync"
	"sync/atomic"
	"time"
)

const (
	geoipDat       = "geoip.db"
	geositeDat     = "geosite.db"
	geoipVersion   = "geoip.version.txt"
	geositeVersion = "geosite.version.txt"

	yacdDstFolder = "yacd"
	yacdVersion   = "yacd.version.txt"
)

var apkAssetPrefixSingBox = "sing-box/"
var internalAssetsPath string
var externalAssetsPath string

// The geo databases are unpacked from the APK in a background goroutine of InitCore. On a fresh install the first
// start used to race that: rule-set conversion read a half-written geosite.db, wasted time and could cache an empty
// rule-set. A start now waits for the unpacking to finish.
var (
	assetsOnce    sync.Once
	assetsReady   = make(chan struct{})
	assetsPending atomic.Bool
)

// runExtractAssets unpacks the APK assets once per process. includeInternal also unpacks the dashboard (bg process).
func runExtractAssets(includeInternal bool) {
	assetsOnce.Do(func() {
		defer close(assetsReady)
		started := time.Now()
		extractAssets(includeInternal)
		log.Println("assets ready in", time.Since(started))
	})
}

func waitAssetsReady(timeout time.Duration) {
	if !assetsPending.Load() {
		return
	}
	select {
	case <-assetsReady:
	case <-time.After(timeout):
		log.Println("waiting for asset extraction timed out after", timeout)
	}
}
