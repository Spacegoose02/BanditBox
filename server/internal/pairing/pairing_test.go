package pairing

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/spacegoose02/banditbox/server/internal/store"
)

func newManager(t *testing.T) (*Manager, *store.Store) {
	st, err := store.Open(context.Background(), t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	return New(st), st
}

func TestApproveDeliversTokenOnce(t *testing.T) {
	ctx := context.Background()
	m, st := newManager(t)
	id, p, err := m.Start("1.2.3.4", "Living room", "onn 4K")
	if err != nil {
		t.Fatal(err)
	}
	if len(p.Code) != 6 {
		t.Fatalf("code %q", p.Code)
	}
	if got := m.List(); len(got) != 1 || got[0].Name != "Living room" {
		t.Fatalf("list %+v", got)
	}

	done := make(chan Result)
	go func() {
		r, err := m.Wait(ctx, id, 5*time.Second)
		if err != nil {
			t.Error(err)
		}
		done <- r
	}()
	time.Sleep(20 * time.Millisecond)
	d, err := m.Approve(ctx, p.Code[:3]+" "+p.Code[3:], "", nil)
	if err != nil {
		t.Fatal(err)
	}
	if d.Name != "Living room" || d.Model != "onn 4K" {
		t.Fatalf("device %+v", d)
	}
	r := <-done
	if r.Status != StatusApproved || r.Token == "" || r.DeviceID != d.ID {
		t.Fatalf("result %+v", r)
	}
	got, err := st.DeviceByToken(ctx, HashToken(r.Token))
	if err != nil || got.ID != d.ID {
		t.Fatalf("token lookup %+v %v", got, err)
	}
	if _, err := m.Wait(ctx, id, 0); !errors.Is(err, ErrNotFound) {
		t.Fatalf("second wait err = %v", err)
	}
	if len(m.List()) != 0 {
		t.Fatal("approved pairing still listed")
	}
}

func TestDenyAndUnknownCode(t *testing.T) {
	ctx := context.Background()
	m, _ := newManager(t)
	id, p, _ := m.Start("c", "", "")
	if _, err := m.Approve(ctx, "000000x", "", nil); !errors.Is(err, ErrNotFound) && p.Code != "000000" {
		t.Fatalf("unknown code err = %v", err)
	}
	if err := m.Deny(p.Code); err != nil {
		t.Fatal(err)
	}
	r, _ := m.Wait(ctx, id, 0)
	if r.Status != StatusDenied {
		t.Fatalf("result %+v", r)
	}
}

func TestExpiry(t *testing.T) {
	ctx := context.Background()
	m, _ := newManager(t)
	now := time.Now()
	m.now = func() time.Time { return now }
	id, p, _ := m.Start("c", "", "")
	r, _ := m.Wait(ctx, id, 0)
	if r.Status != StatusPending {
		t.Fatalf("fresh = %+v", r)
	}
	now = now.Add(TTL + time.Second)
	if len(m.List()) != 0 {
		t.Fatal("expired pairing listed")
	}
	if _, err := m.Approve(ctx, p.Code, "", nil); !errors.Is(err, ErrNotFound) {
		t.Fatalf("approve expired err = %v", err)
	}
	r, _ = m.Wait(ctx, id, time.Second)
	if r.Status != StatusExpired {
		t.Fatalf("expired = %+v", r)
	}
}

func TestLimits(t *testing.T) {
	m, _ := newManager(t)
	for i := 0; i < maxPerClient; i++ {
		if _, _, err := m.Start("same", "", ""); err != nil {
			t.Fatal(err)
		}
	}
	if _, _, err := m.Start("same", "", ""); !errors.Is(err, ErrTooMany) {
		t.Fatalf("err = %v", err)
	}
	if _, _, err := m.Start("other", "", ""); err != nil {
		t.Fatal(err)
	}
}
