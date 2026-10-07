package libcore

import (
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/protocol/socks"
	"github.com/sagernet/sing/protocol/socks/socks5"
)

func TestLandingProbeAuthAndNoDirectFallback(t *testing.T) {
	file := filepath.Join(t.TempDir(), "landing_probe")
	s, err := newLandingProbeServer(file)
	if err != nil {
		t.Fatal(err)
	}
	defer s.close()
	info, err := os.Stat(file)
	if err != nil || info.Mode().Perm() != 0o600 {
		t.Fatalf("probe file must exist with 0600, got %v %v", info, err)
	}
	data, _ := os.ReadFile(file)
	lines := strings.Fields(string(data))
	addr := "127.0.0.1:" + lines[0]
	dest := metadata.ParseSocksaddr("example.com:443")

	// 错误令牌：认证失败
	conn, _ := net.Dial("tcp", addr)
	if _, err = socks.ClientHandshake5(conn, socks5.CommandConnect, dest, "bad:1", "bad"); err == nil {
		t.Fatal("wrong token must be rejected")
	}
	conn.Close()

	// 正确令牌但内核未运行：必须失败，绝不直连
	mainInstance = nil
	conn, _ = net.Dial("tcp", addr)
	if _, err = socks.ClientHandshake5(conn, socks5.CommandConnect, dest, lines[1]+":s1", lines[1]); err == nil {
		t.Fatal("must not connect without a running node")
	}
	conn.Close()
}
