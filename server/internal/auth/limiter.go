package auth

import (
	"sync"
	"time"
)

// limiter pauses a client after too many failures inside a window.
type limiter struct {
	mu     sync.Mutex
	max    int
	window time.Duration
	now    func() time.Time
	fails  map[string][]time.Time
}

func newLimiter(max int, window time.Duration) *limiter {
	return &limiter{max: max, window: window, now: time.Now, fails: map[string][]time.Time{}}
}

func (l *limiter) allow(client string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.prune(client)) < l.max
}

func (l *limiter) fail(client string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.fails[client] = append(l.prune(client), l.now())
	// Keep memory bounded if many clients fail once and never come back.
	if len(l.fails) > 10000 {
		for k := range l.fails {
			if len(l.prune(k)) == 0 {
				delete(l.fails, k)
			}
		}
	}
}

func (l *limiter) reset(client string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.fails, client)
}

// prune drops failures older than the window. Callers hold l.mu.
func (l *limiter) prune(client string) []time.Time {
	cutoff := l.now().Add(-l.window)
	kept := l.fails[client][:0]
	for _, t := range l.fails[client] {
		if t.After(cutoff) {
			kept = append(kept, t)
		}
	}
	if len(kept) == 0 {
		delete(l.fails, client)
		return nil
	}
	l.fails[client] = kept
	return kept
}
