// Package pairing links a TV to the server.
//
// The TV starts a pairing and shows a 6-digit code (and a QR code holding it).
// An admin types the code into the web UI and approves it. Meanwhile the TV
// long-polls with its secret pairing id and receives a device token once.
// Pairings live in memory for 10 minutes; a restart simply cancels them.
package pairing

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"math/big"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/spacegoose02/banditbox/server/internal/store"
)

const (
	// TTL is how long a pairing code stays valid.
	TTL = 10 * time.Minute
	// maxPerClient caps open pairings from one address.
	maxPerClient = 5
	// maxPending caps open pairings in total.
	maxPending = 100
)

// Status of a pairing as the TV sees it.
const (
	StatusPending  = "pending"
	StatusApproved = "approved"
	StatusDenied   = "denied"
	StatusExpired  = "expired"
)

var (
	// ErrNotFound means there is no open pairing with that code or id.
	ErrNotFound = errors.New("no such pairing, or it expired")
	// ErrTooMany means too many pairings are open.
	ErrTooMany = errors.New("too many pairings in progress, try again in a few minutes")
)

// Pending is an open pairing as the admin sees it.
type Pending struct {
	Code    string    `json:"code"`
	Name    string    `json:"name"`
	Model   string    `json:"model"`
	Created time.Time `json:"created"`
	Expires time.Time `json:"expires"`
}

// Result is what the TV receives once the pairing ends.
type Result struct {
	Status   string `json:"status"`
	DeviceID string `json:"deviceId,omitempty"`
	Token    string `json:"token,omitempty"`
}

type pairing struct {
	Pending
	id     string
	client string
	result Result
	done   chan struct{} // closed when approved or denied
}

// Manager holds the open pairings.
type Manager struct {
	store *store.Store
	now   func() time.Time

	mu     sync.Mutex
	byID   map[string]*pairing
	byCode map[string]*pairing
}

// New returns a Manager that saves approved TVs in st.
func New(st *store.Store) *Manager {
	return &Manager{store: st, now: time.Now, byID: map[string]*pairing{}, byCode: map[string]*pairing{}}
}

// Start opens a pairing for a TV. It returns the secret id the TV polls with,
// and the code to show on screen.
func (m *Manager) Start(client, name, model string) (id string, p Pending, err error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.sweep()
	if len(m.byID) >= maxPending {
		return "", Pending{}, ErrTooMany
	}
	n := 0
	for _, x := range m.byID {
		if x.client == client {
			n++
		}
	}
	if n >= maxPerClient {
		return "", Pending{}, ErrTooMany
	}
	code := m.newCode()
	now := m.now()
	x := &pairing{
		Pending: Pending{Code: code, Name: clean(name, "TV"), Model: clean(model, ""), Created: now, Expires: now.Add(TTL)},
		id:      randomID(),
		client:  client,
		done:    make(chan struct{}),
	}
	m.byID[x.id] = x
	m.byCode[code] = x
	return x.id, x.Pending, nil
}

// List returns the open pairings, oldest first.
func (m *Manager) List() []Pending {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.sweep()
	out := make([]Pending, 0, len(m.byCode))
	for _, x := range m.byCode {
		if x.result.Status == "" {
			out = append(out, x.Pending)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Created.Before(out[j].Created) })
	return out
}

// Approve pairs the TV showing code. The device is saved with the given name
// (or the name the TV sent) and allowed profiles (empty means all).
func (m *Manager) Approve(ctx context.Context, code, name string, profiles []string) (store.Device, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.sweep()
	x, ok := m.byCode[normalizeCode(code)]
	if !ok || x.result.Status != "" {
		return store.Device{}, ErrNotFound
	}
	d := store.Device{ID: "d_" + randomID()[:12], Name: clean(name, x.Name), Model: x.Model, Profiles: profiles}
	token := randomID() + randomID()
	if err := m.store.CreateDevice(ctx, d, HashToken(token)); err != nil {
		return store.Device{}, err
	}
	x.result = Result{Status: StatusApproved, DeviceID: d.ID, Token: token}
	delete(m.byCode, x.Code)
	close(x.done)
	return m.store.GetDevice(ctx, d.ID)
}

// Deny refuses the TV showing code.
func (m *Manager) Deny(code string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	x, ok := m.byCode[normalizeCode(code)]
	if !ok || x.result.Status != "" {
		return ErrNotFound
	}
	x.result = Result{Status: StatusDenied}
	delete(m.byCode, x.Code)
	close(x.done)
	return nil
}

// Wait blocks until the pairing ends or wait passes, then returns its status.
// An approved result (with the token) is handed out once and then forgotten.
func (m *Manager) Wait(ctx context.Context, id string, wait time.Duration) (Result, error) {
	m.mu.Lock()
	x, ok := m.byID[id]
	m.mu.Unlock()
	if !ok {
		return Result{}, ErrNotFound
	}
	timer := time.NewTimer(min(wait, time.Until(x.Expires)))
	defer timer.Stop()
	select {
	case <-x.done:
	case <-timer.C:
	case <-ctx.Done():
		return Result{}, ctx.Err()
	}

	m.mu.Lock()
	defer m.mu.Unlock()
	if x.result.Status != "" {
		delete(m.byID, x.id)
		delete(m.byCode, x.Code)
		return x.result, nil
	}
	if !m.now().Before(x.Expires) {
		delete(m.byID, x.id)
		delete(m.byCode, x.Code)
		return Result{Status: StatusExpired}, nil
	}
	return Result{Status: StatusPending}, nil
}

// sweep forgets expired pairings. Callers hold m.mu.
func (m *Manager) sweep() {
	now := m.now()
	for id, x := range m.byID {
		// Keep ended pairings a little past expiry so the TV can still collect its result.
		if now.After(x.Expires.Add(time.Minute)) {
			delete(m.byID, id)
			delete(m.byCode, x.Code)
		} else if !now.Before(x.Expires) && x.result.Status == "" {
			delete(m.byCode, x.Code)
		}
	}
}

// newCode returns a 6-digit code not in use. Callers hold m.mu.
func (m *Manager) newCode() string {
	for {
		n, err := rand.Int(rand.Reader, big.NewInt(1_000_000))
		if err != nil {
			panic(err)
		}
		c := fmt.Sprintf("%06d", n.Int64())
		if _, used := m.byCode[c]; !used {
			return c
		}
	}
}

// HashToken is how device tokens are stored: never in the clear.
func HashToken(token string) []byte {
	h := sha256.Sum256([]byte(token))
	return h[:]
}

func randomID() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return base64.RawURLEncoding.EncodeToString(b)
}

func normalizeCode(s string) string {
	return strings.Map(func(r rune) rune {
		if r >= '0' && r <= '9' {
			return r
		}
		return -1
	}, s)
}

func clean(s, fallback string) string {
	s = strings.TrimSpace(s)
	if r := []rune(s); len(r) > 60 {
		s = strings.TrimSpace(string(r[:60]))
	}
	if s == "" {
		return fallback
	}
	return s
}
