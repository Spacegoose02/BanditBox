// Package hub tells connected TVs when a profile's layout has a new version.
//
// Each subscriber holds only the latest version per profile, so a slow TV can
// never make the server buffer an unbounded backlog: it just sees the newest one.
package hub

import "sync"

// Hub fans version changes out to subscribers.
type Hub struct {
	mu   sync.Mutex
	subs map[*Sub]struct{}
}

// New returns an empty Hub.
func New() *Hub { return &Hub{subs: map[*Sub]struct{}{}} }

// Sub is one subscriber, usually one TV's WebSocket.
type Sub struct {
	DeviceID string
	// Ready gets a value when there is something to Take.
	Ready chan struct{}

	mu      sync.Mutex
	pending map[string]int64
	kicked  bool
}

// Subscribe adds a subscriber for a device.
func (h *Hub) Subscribe(deviceID string) *Sub {
	s := &Sub{DeviceID: deviceID, Ready: make(chan struct{}, 1), pending: map[string]int64{}}
	h.mu.Lock()
	h.subs[s] = struct{}{}
	h.mu.Unlock()
	return s
}

// Unsubscribe removes a subscriber.
func (h *Hub) Unsubscribe(s *Sub) {
	h.mu.Lock()
	delete(h.subs, s)
	h.mu.Unlock()
}

// Publish announces a new version of a profile's layout.
func (h *Hub) Publish(profileID string, version int64) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for s := range h.subs {
		s.mu.Lock()
		if version > s.pending[profileID] {
			s.pending[profileID] = version
		}
		s.mu.Unlock()
		s.signal()
	}
}

// Kick tells every subscriber of a device to disconnect, for example after it is revoked.
func (h *Hub) Kick(deviceID string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for s := range h.subs {
		if s.DeviceID == deviceID {
			s.mu.Lock()
			s.kicked = true
			s.mu.Unlock()
			s.signal()
		}
	}
}

// Count returns the number of subscribers.
func (h *Hub) Count() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.subs)
}

// Take returns and clears the pending versions, and whether the device was kicked.
func (s *Sub) Take() (versions map[string]int64, kicked bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	v := s.pending
	s.pending = map[string]int64{}
	return v, s.kicked
}

func (s *Sub) signal() {
	select {
	case s.Ready <- struct{}{}:
	default:
	}
}
