package urltest

import (
	"context"
	"errors"
	"net"
	"sync/atomic"
	"testing"
	"time"
)

func pipeConn() net.Conn { a, b := net.Pipe(); go b.Close(); return a }

func TestRaceDialPrefersFastFirst(t *testing.T) {
	var launched atomic.Int32
	conn, idx, err := RaceDial(context.Background(), 3, 200*time.Millisecond, func(ctx context.Context, i int) (net.Conn, error) {
		launched.Add(1)
		return pipeConn(), nil
	}, nil)
	if err != nil || idx != 0 || conn == nil {
		t.Fatalf("idx=%d err=%v", idx, err)
	}
	time.Sleep(50 * time.Millisecond)
	if launched.Load() != 1 {
		t.Fatalf("expected only the first candidate to be dialed, got %d", launched.Load())
	}
}

func TestRaceDialStaggersPastSlowFirst(t *testing.T) {
	start := time.Now()
	_, idx, err := RaceDial(context.Background(), 3, 100*time.Millisecond, func(ctx context.Context, i int) (net.Conn, error) {
		if i == 0 {
			<-ctx.Done() // 死节点：一直不返回，直到被取消
			return nil, ctx.Err()
		}
		return pipeConn(), nil
	}, nil)
	if err != nil || idx != 1 {
		t.Fatalf("idx=%d err=%v", idx, err)
	}
	if d := time.Since(start); d > 400*time.Millisecond {
		t.Fatalf("took %s, stagger not applied", d)
	}
}

func TestRaceDialFailureStartsNextImmediately(t *testing.T) {
	var fails atomic.Int32
	start := time.Now()
	_, idx, err := RaceDial(context.Background(), 3, time.Second, func(ctx context.Context, i int) (net.Conn, error) {
		if i < 2 {
			return nil, errors.New("refused")
		}
		return pipeConn(), nil
	}, func(i int, err error) { fails.Add(1) })
	if err != nil || idx != 2 || fails.Load() != 2 {
		t.Fatalf("idx=%d err=%v fails=%d", idx, err, fails.Load())
	}
	if time.Since(start) > 300*time.Millisecond {
		t.Fatal("failures should not wait for the stagger")
	}
}

func TestRaceDialAllFail(t *testing.T) {
	_, idx, err := RaceDial(context.Background(), 2, 50*time.Millisecond, func(ctx context.Context, i int) (net.Conn, error) {
		return nil, errors.New("down")
	}, nil)
	if err == nil || idx != -1 {
		t.Fatal("expected failure")
	}
}
