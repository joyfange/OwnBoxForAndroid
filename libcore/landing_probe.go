package libcore

// 落地 IP 专用探测入口（仅 :bg 进程，仅 127.0.0.1）。
//
// 旧做法：UI 进程经 mixed 端口（127.0.0.1:mixedPort）查询落地 IP，问题有四：
//  1. 要过分流规则和策略组：轮询/随机时 5 个查询源从不同节点出去，IP 和显示的节点对不上；
//  2. 查询自己的连接会让策略组切换“当前节点”，又触发新一轮查询，IP 来回跳；
//  3. mixed 端口连不上时 TrySocks5 静默直连，可能显示真实 IP；
//  4. 设置了 mixed 用户名、或关闭 mixed 入站时，查询必定失败（一直“点击重试”）。
//
// 现在：独立监听 127.0.0.1 随机端口，随机令牌认证，只接受 CONNECT。
// 每个连接直接用「当前具体节点」（沿嵌套策略组解析到叶子）的 outbound 拨号，
// 不经过路由规则、不经过策略组的选择逻辑（不会推进轮询、不会改变当前节点），失败就失败，绝不直连。
// 用户名格式 "<token>:<session>"：同一 session 内的所有连接固定走第一次解析出的同一个节点，
// 保证一次查询里多个查询源看到的是同一个出口。

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"io"
	"log"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/sing/protocol/socks/socks5"
)

const landingProbeFileName = "landing_probe"

type landingProbeServer struct {
	listener net.Listener
	token    string
	file     string

	access   sync.Mutex
	sessions map[string]landingProbeSession
}

type landingProbeSession struct {
	tag     string
	created time.Time
}

var (
	landingProbeAccess sync.Mutex
	landingProbe       *landingProbeServer
)

func landingProbeFilePath() string {
	return filepath.Join(internalAssetsPath, landingProbeFileName)
}

func goServeLandingProbe(start bool) {
	landingProbeAccess.Lock()
	defer landingProbeAccess.Unlock()
	if landingProbe != nil {
		landingProbe.close()
		landingProbe = nil
	}
	if !start {
		return
	}
	server, err := newLandingProbeServer(landingProbeFilePath())
	if err != nil {
		log.Println("landing probe: start failed:", err)
		return
	}
	landingProbe = server
}

func newLandingProbeServer(file string) (*landingProbeServer, error) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	raw := make([]byte, 24)
	if _, err = rand.Read(raw); err != nil {
		listener.Close()
		return nil, err
	}
	s := &landingProbeServer{
		listener: listener,
		token:    hex.EncodeToString(raw),
		file:     file,
		sessions: make(map[string]landingProbeSession),
	}
	port := listener.Addr().(*net.TCPAddr).Port
	// 私有目录 + 0600 + 先写临时文件再改名：只有本应用（两个进程同 uid）能读到端口与令牌
	tmp := file + ".tmp"
	if err = os.WriteFile(tmp, []byte(strconv.Itoa(port)+"\n"+s.token+"\n"), 0o600); err == nil {
		err = os.Rename(tmp, file)
	}
	if err != nil {
		listener.Close()
		return nil, err
	}
	go s.loop()
	return s, nil
}

func (s *landingProbeServer) close() {
	_ = s.listener.Close()
	_ = os.Remove(s.file)
}

func (s *landingProbeServer) loop() {
	for {
		conn, err := s.listener.Accept()
		if err != nil {
			return
		}
		go s.handle(conn)
	}
}

// pinnedTag 返回该 session 固定使用的节点 tag；第一次调用时解析“当前具体节点”。
func (s *landingProbeServer) pinnedTag(session string) (string, error) {
	s.access.Lock()
	defer s.access.Unlock()
	now := time.Now()
	for k, v := range s.sessions {
		if now.Sub(v.created) > 30*time.Second {
			delete(s.sessions, k)
		}
	}
	if session != "" {
		if hit, ok := s.sessions[session]; ok {
			return hit.tag, nil
		}
	}
	b := mainInstance
	if b == nil || b.Box == nil {
		return "", errors.New("box not running")
	}
	tag := b.GetActiveOutboundTag("")
	if tag == "" {
		return "", errors.New("no active node")
	}
	if session != "" {
		s.sessions[session] = landingProbeSession{tag: tag, created: now}
	}
	return tag, nil
}

func (s *landingProbeServer) handle(conn net.Conn) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(10 * time.Second))
	reader := bufio.NewReader(conn)

	auth, err := socks5.ReadAuthRequest(reader)
	if err != nil {
		return
	}
	hasUserPass := false
	for _, m := range auth.Methods {
		if m == socks5.AuthTypeUsernamePassword {
			hasUserPass = true
		}
	}
	if !hasUserPass {
		_ = socks5.WriteAuthResponse(conn, socks5.AuthResponse{Method: socks5.AuthTypeNoAcceptedMethods})
		return
	}
	if err = socks5.WriteAuthResponse(conn, socks5.AuthResponse{Method: socks5.AuthTypeUsernamePassword}); err != nil {
		return
	}
	up, err := socks5.ReadUsernamePasswordAuthRequest(reader)
	if err != nil {
		return
	}
	token, session, _ := strings.Cut(up.Username, ":")
	if subtle.ConstantTimeCompare([]byte(token), []byte(s.token)) != 1 ||
		subtle.ConstantTimeCompare([]byte(up.Password), []byte(s.token)) != 1 {
		_ = socks5.WriteUsernamePasswordAuthResponse(conn, socks5.UsernamePasswordAuthResponse{Status: 1})
		return
	}
	if err = socks5.WriteUsernamePasswordAuthResponse(conn, socks5.UsernamePasswordAuthResponse{Status: 0}); err != nil {
		return
	}
	request, err := socks5.ReadRequest(reader)
	if err != nil {
		return
	}
	if request.Command != socks5.CommandConnect {
		_ = socks5.WriteResponse(conn, socks5.Response{ReplyCode: socks5.ReplyCodeUnsupported})
		return
	}

	tag, err := s.pinnedTag(session)
	if err != nil {
		_ = socks5.WriteResponse(conn, socks5.Response{ReplyCode: socks5.ReplyCodeNetworkUnreachable})
		return
	}
	b := mainInstance
	if b == nil || b.Box == nil {
		_ = socks5.WriteResponse(conn, socks5.Response{ReplyCode: socks5.ReplyCodeNetworkUnreachable})
		return
	}
	outbound, ok := b.Outbound().Outbound(tag)
	if !ok || outbound == nil {
		_ = socks5.WriteResponse(conn, socks5.Response{ReplyCode: socks5.ReplyCodeNetworkUnreachable})
		return
	}
	ctx, cancel := context.WithTimeout(b.ctx, 8*time.Second)
	remote, err := outbound.DialContext(ctx, "tcp", request.Destination)
	cancel()
	if err != nil {
		_ = socks5.WriteResponse(conn, socks5.Response{ReplyCode: socks5.ReplyCodeForError(err)})
		return
	}
	defer remote.Close()
	if err = socks5.WriteResponse(conn, socks5.Response{ReplyCode: socks5.ReplyCodeSuccess}); err != nil {
		return
	}
	// 查询请求很小，整体限时即可
	_ = conn.SetDeadline(time.Now().Add(15 * time.Second))
	_ = remote.SetDeadline(time.Now().Add(15 * time.Second))
	done := make(chan struct{}, 2)
	go func() { _, _ = io.Copy(remote, reader); done <- struct{}{} }()
	go func() { _, _ = io.Copy(conn, remote); done <- struct{}{} }()
	<-done
}
