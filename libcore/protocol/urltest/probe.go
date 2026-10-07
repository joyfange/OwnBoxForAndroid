package urltest

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/sagernet/sing-box/adapter"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	"golang.org/x/net/http2"
)

const (
	DefaultFallbackURL = "https://www.gstatic.com/generate_204"
	DefaultCFURL       = "https://cp.cloudflare.com/generate_204"
	BrowserUserAgent   = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)

func GetFallbackLink(primaryLink string) string {
	if strings.Contains(primaryLink, "cloudflare.com") {
		return DefaultFallbackURL
	}
	return DefaultCFURL
}

// ProbeOutbound 测的是“真实新连接”的代价（与 Exclave 一致）：
// 每次都新建连接、只发一次请求，计时包含 经节点拨号 + 节点协议握手 + 到测速站的 TLS + 一次往返。
// 旧实现先预热再测“复用连接后的第二次往返”，结果偏乐观，握手慢的节点被排得太靠前，
// 而真实流量每条新连接都要付完整握手的时间。
// 主地址不可达时以备用地址重试，避免 CDN 兼容性造成误报超时。
func ProbeOutbound(ctx context.Context, detour adapter.Outbound, link string, timeout time.Duration) (uint16, error) {
	if detour == nil {
		return 0, E.New("nil detour")
	}
	if link == "" {
		link = DefaultCFURL
	}
	if timeout <= 0 {
		timeout = 3500 * time.Millisecond
	}

	primaryTimeout := timeout
	fallbackTimeout := 2000 * time.Millisecond
	if timeout > 3500*time.Millisecond {
		primaryTimeout = timeout - 1500*time.Millisecond
	}

	delay, err := probeSingleURL(ctx, detour, link, primaryTimeout)
	if err == nil && delay > 0 {
		return delay, nil
	}

	// 备选地址重试
	fbLink := GetFallbackLink(link)
	fbDelay, fbErr := probeSingleURL(ctx, detour, fbLink, fallbackTimeout)
	if fbErr == nil && fbDelay > 0 {
		return fbDelay, nil
	}

	if err != nil {
		return 0, err
	}
	return 0, fbErr
}

func probeSingleURL(parentCtx context.Context, detour adapter.Outbound, link string, timeout time.Duration) (uint16, error) {
	linkURL, err := url.Parse(link)
	if err != nil {
		return 0, E.Cause(err, "parse test link")
	}
	hostname := linkURL.Hostname()

	ctx, cancel := context.WithTimeout(parentCtx, timeout)
	defer cancel()

	transport := &http.Transport{
		DialContext: func(dialCtx context.Context, network, addr string) (net.Conn, error) {
			return detour.DialContext(dialCtx, network, M.ParseSocksaddr(addr))
		},
		TLSClientConfig: &tls.Config{
			ServerName:         hostname,
			InsecureSkipVerify: true,
			NextProtos:         []string{"h2", "http/1.1"},
		},
		ForceAttemptHTTP2: true,
		// 每次测速都是全新连接，测完即关
		DisableKeepAlives: true,
	}
	_ = http2.ConfigureTransport(transport)
	defer transport.CloseIdleConnections()

	client := &http.Client{
		Transport: transport,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, link, nil)
	if err != nil {
		return 0, err
	}
	req.Header.Set("User-Agent", BrowserUserAgent)

	start := time.Now()
	resp, err := client.Do(req)
	if err != nil {
		return 0, err
	}
	// 计时到响应头为止（含新连接的全部握手），不计响应体下载
	elapsed := time.Since(start)
	_, _ = io.CopyN(io.Discard, resp.Body, 8192)
	_ = resp.Body.Close()
	if resp.StatusCode >= 500 {
		return 0, fmt.Errorf("HTTP error %d", resp.StatusCode)
	}
	lat := elapsed.Milliseconds()
	if lat <= 0 {
		lat = 1
	}
	if lat > 65535 {
		lat = 65535
	}
	return uint16(lat), nil
}
