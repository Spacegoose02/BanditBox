package widgets

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/spacegoose02/banditbox/server/internal/schema"
	"github.com/spacegoose02/banditbox/server/internal/store"
)

const (
	// minEvery is the shortest time between two fetches of one connector.
	minEvery = 30 * time.Second
	// retryEvery is how soon a failed fetch is tried again.
	retryEvery = 2 * time.Minute
	// fetchTimeout caps one connector's fetch, pictures included.
	fetchTimeout = 45 * time.Second
	// keepPictures is how long a picture no feed uses stays on disk.
	keepPictures = 24 * time.Hour
)

// Notifier is told when the feeds change. The sync hub implements it.
type Notifier interface {
	WidgetsChanged(version int64)
}

// Status is how a connector's last fetch went, for the web UI.
type Status struct {
	LastRun   time.Time `json:"lastRun,omitzero"`
	LastOK    time.Time `json:"lastOk,omitzero"`
	LastError string    `json:"lastError,omitempty"`
	NextRun   time.Time `json:"nextRun,omitzero"`
	Feeds     []string  `json:"feeds"`
}

type entry struct {
	feeds  []Feed
	status Status
	sig    string // what the feeds held last time, to tell if they changed
}

// Manager fetches every enabled connector on its schedule and holds the feeds.
type Manager struct {
	Store   *store.Store
	Fetcher *Fetcher
	Notify  Notifier
	Log     *slog.Logger
	// Kind looks up a connector kind; tests replace it.
	Kind func(id string) (Kind, bool)

	mu      sync.Mutex
	byConn  map[string]*entry
	version int64
	wake    chan struct{}
}

// NewManager returns a Manager that keeps pictures in mediaDir.
func NewManager(st *store.Store, mediaDir string, notify Notifier, log *slog.Logger) *Manager {
	if log == nil {
		log = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	return &Manager{
		Store: st, Fetcher: NewFetcher(mediaDir), Notify: notify, Log: log, Kind: KindByID,
		byConn: map[string]*entry{}, version: time.Now().UnixMilli(), wake: make(chan struct{}, 1),
	}
}

// Feeds returns every feed and the version they are at. The version changes
// whenever any feed does, so a TV can tell if it is up to date.
func (m *Manager) Feeds() (int64, []Feed) {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := []Feed{}
	for _, e := range m.byConn {
		out = append(out, e.feeds...)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return m.version, out
}

// Status returns how each connector's fetches are going, by connector id.
func (m *Manager) Status() map[string]Status {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make(map[string]Status, len(m.byConn))
	for id, e := range m.byConn {
		out[id] = e.status
	}
	return out
}

// Reload makes the manager read the connectors again and fetch the changed ones now.
func (m *Manager) Reload(id string) {
	m.mu.Lock()
	if e, ok := m.byConn[id]; ok {
		e.status.NextRun = time.Time{}
	}
	m.mu.Unlock()
	select {
	case m.wake <- struct{}{}:
	default:
	}
}

// Test fetches a connector once without keeping the result.
func (m *Manager) Test(ctx context.Context, c store.Connector) ([]Feed, error) {
	k, ok := m.Kind(c.Kind)
	if !ok {
		return nil, fmt.Errorf("unknown connector kind %q", c.Kind)
	}
	ctx, cancel := context.WithTimeout(ctx, fetchTimeout)
	defer cancel()
	res, err := k.Fetch(ctx, m.Fetcher, c)
	if err != nil {
		return nil, err
	}
	if err := checkFeeds(res.Feeds); err != nil {
		return nil, err
	}
	return res.Feeds, nil
}

// Run fetches connectors until ctx ends.
func (m *Manager) Run(ctx context.Context) {
	prune := time.Now()
	for {
		wait := m.tick(ctx)
		if time.Since(prune) > time.Hour {
			m.prune()
			prune = time.Now()
		}
		t := time.NewTimer(wait)
		select {
		case <-ctx.Done():
			t.Stop()
			return
		case <-m.wake:
			t.Stop()
		case <-t.C:
		}
	}
}

// tick fetches every connector that is due and returns how long to sleep.
func (m *Manager) tick(ctx context.Context) time.Duration {
	conns, err := m.Store.ListConnectors(ctx)
	if err != nil {
		if ctx.Err() == nil {
			m.Log.Warn("widgets: list connectors", "err", err)
		}
		return time.Minute
	}
	m.forgetRemoved(conns)
	wait := time.Hour
	for _, c := range conns {
		if !c.Enabled {
			continue
		}
		k, ok := m.Kind(c.Kind)
		if !ok {
			continue
		}
		m.mu.Lock()
		e := m.byConn[c.ID]
		if e == nil {
			e = &entry{}
			m.byConn[c.ID] = e
		}
		next := e.status.NextRun
		m.mu.Unlock()
		if now := time.Now(); next.After(now) {
			wait = min(wait, next.Sub(now))
			continue
		}
		every := m.fetch(ctx, k, c)
		wait = min(wait, every)
	}
	return max(wait, time.Second)
}

// forgetRemoved drops the feeds of connectors that were deleted or turned off.
func (m *Manager) forgetRemoved(conns []store.Connector) {
	live := map[string]bool{}
	for _, c := range conns {
		if c.Enabled {
			live[c.ID] = true
		}
	}
	m.mu.Lock()
	changed := false
	for id := range m.byConn {
		if !live[id] {
			delete(m.byConn, id)
			changed = true
		}
	}
	v := m.bump(changed)
	m.mu.Unlock()
	if changed && m.Notify != nil {
		m.Notify.WidgetsChanged(v)
	}
}

// bump moves the version on if changed. Callers hold m.mu.
func (m *Manager) bump(changed bool) int64 {
	if changed {
		m.version = max(m.version+1, time.Now().UnixMilli())
	}
	return m.version
}

func (m *Manager) fetch(ctx context.Context, k Kind, c store.Connector) time.Duration {
	fctx, cancel := context.WithTimeout(ctx, fetchTimeout)
	res, err := k.Fetch(fctx, m.Fetcher, c)
	cancel()
	if err == nil {
		err = checkFeeds(res.Feeds)
	}
	now := time.Now()
	every := k.Every
	if res.Next > 0 {
		every = res.Next
	}
	every = max(every, minEvery)

	m.mu.Lock()
	e := m.byConn[c.ID]
	if e == nil {
		// Deleted while we fetched.
		m.mu.Unlock()
		return every
	}
	e.status.LastRun = now
	if err != nil {
		if ctx.Err() != nil {
			m.mu.Unlock()
			return every
		}
		m.Log.Warn("widgets: fetch failed", "connector", c.ID, "err", err)
		e.status.LastError = clip(err.Error(), 200)
		every = min(every, retryEvery)
		// Keep the last good cards, marked with the error.
		for i := range e.feeds {
			e.feeds[i].Error = e.status.LastError
		}
	} else {
		e.status.LastOK, e.status.LastError = now, ""
		stamp := now.UTC().Format(time.RFC3339)
		for i := range res.Feeds {
			res.Feeds[i].Schema = SchemaVersion
			res.Feeds[i].UpdatedAt = stamp
			if res.Feeds[i].Cards == nil {
				res.Feeds[i].Cards = []Card{}
			}
		}
		e.feeds = res.Feeds
	}
	e.status.NextRun = now.Add(every)
	e.status.Feeds = e.status.Feeds[:0]
	for _, f := range e.feeds {
		e.status.Feeds = append(e.status.Feeds, f.ID)
	}
	sig := signature(e.feeds)
	changed := sig != e.sig
	e.sig = sig
	v := m.bump(changed)
	m.mu.Unlock()
	if changed && m.Notify != nil {
		m.Notify.WidgetsChanged(v)
	}
	return every
}

// signature is the feeds' content without the fetch time, so a refetch that
// changes nothing doesn't wake every TV.
func signature(feeds []Feed) string {
	cp := make([]Feed, len(feeds))
	copy(cp, feeds)
	for i := range cp {
		cp[i].UpdatedAt = ""
	}
	b, _ := json.Marshal(cp)
	return string(b)
}

// checkFeeds validates feeds against the widget feed schema, so a source can
// never hand a TV something it can't read.
func checkFeeds(feeds []Feed) error {
	seen := map[string]bool{}
	for _, f := range feeds {
		f.Schema = SchemaVersion
		if f.Cards == nil {
			f.Cards = []Card{}
		}
		raw, err := json.Marshal(f)
		if err != nil {
			return err
		}
		if err := schema.ValidateFeed(raw); err != nil {
			return fmt.Errorf("feed %s is malformed: %v", f.ID, err)
		}
		if seen[f.ID] {
			return errors.New("two feeds named " + f.ID)
		}
		seen[f.ID] = true
	}
	return nil
}

// prune deletes pictures no feed uses any more.
func (m *Manager) prune() {
	m.mu.Lock()
	inUse := map[string]bool{}
	for _, e := range m.byConn {
		for _, f := range e.feeds {
			for _, c := range f.Cards {
				if sha, ok := strings.CutPrefix(c.Image, MediaPrefix); ok {
					inUse[sha] = true
				}
			}
		}
	}
	m.mu.Unlock()
	m.Fetcher.Prune(keepPictures, inUse)
}
