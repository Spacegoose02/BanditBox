package auth

import (
	"testing"
	"time"
)

func TestLimiterWindow(t *testing.T) {
	now := time.Unix(0, 0)
	l := newLimiter(3, time.Minute)
	l.now = func() time.Time { return now }
	for i := 0; i < 3; i++ {
		if !l.allow("a") {
			t.Fatalf("blocked after %d failures", i)
		}
		l.fail("a")
	}
	if l.allow("a") {
		t.Fatal("not blocked after 3 failures")
	}
	if !l.allow("b") {
		t.Fatal("other client blocked")
	}
	now = now.Add(61 * time.Second)
	if !l.allow("a") {
		t.Fatal("still blocked after the window")
	}
	l.fail("a")
	l.reset("a")
	if len(l.fails) != 0 {
		t.Fatalf("reset left %v", l.fails)
	}
}

func TestSetupCodeFormat(t *testing.T) {
	c := randomCode()
	if len(c) != 9 || c[4] != '-' {
		t.Fatalf("code %q", c)
	}
	if normalizeCode(" "+c[:4]+c[5:]+" ") != c {
		t.Fatal("normalize")
	}
}
