package homeassistant

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/spacegoose02/banditbox/server/internal/store"
)

func openStore(t *testing.T) *store.Store {
	t.Helper()
	s, err := store.Open(context.Background(), t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

func TestEvents(t *testing.T) {
	got := Events(nil, State{App: "com.plexapp.android", Playing: true, ScreenOn: true})
	want := "app_changed playback_started screen_on"
	if strings.Join(got, " ") != want {
		t.Fatalf("got %v", got)
	}
	before := State{App: "com.plexapp.android", Playing: true, ScreenOn: true}
	if got := Events(&before, State{App: "com.plexapp.android", ScreenOn: false}); strings.Join(got, " ") != "playback_stopped screen_off" {
		t.Fatalf("got %v", got)
	}
	if got := Events(&before, before); len(got) != 0 {
		t.Fatalf("got %v", got)
	}
}

func TestCheck(t *testing.T) {
	ok := []Config{{}, {Broker: "mqtt://10.0.0.2"}, {Webhook: "http://ha.local:8123/api/webhook/abc"}, {Prefix: "home/tv"}}
	for _, c := range ok {
		if err := c.Check(); err != nil {
			t.Errorf("%+v: %v", c, err)
		}
	}
	bad := []Config{{Broker: "10.0.0.2"}, {Webhook: "ftp://x"}, {Prefix: "a/#"}, {Prefix: "/a"}, {Discovery: "a//b"}}
	for _, c := range bad {
		if err := c.Check(); err == nil {
			t.Errorf("%+v: want error", c)
		}
	}
}

func TestDiscovery(t *testing.T) {
	msgs := Discovery(Config{}, store.Device{ID: "d_1", Name: "Living room", Model: "onn 4K", AppVersion: "0.3.0"})
	if len(msgs) != 3 {
		t.Fatalf("%d messages", len(msgs))
	}
	if msgs[0].Topic != "homeassistant/sensor/banditbox_d_1/app/config" || !msgs[0].Retain {
		t.Fatalf("topic %s", msgs[0].Topic)
	}
	var cfg map[string]any
	if err := json.Unmarshal(msgs[1].Payload, &cfg); err != nil {
		t.Fatal(err)
	}
	if cfg["state_topic"] != "banditbox/d_1/state" || cfg["availability_topic"] != "banditbox/status" {
		t.Fatalf("config %v", cfg)
	}
	dev := cfg["device"].(map[string]any)
	if dev["name"] != "Living room" || dev["model"] != "onn 4K" {
		t.Fatalf("device %v", dev)
	}
}

func TestWebhookGetsEvents(t *testing.T) {
	var mu sync.Mutex
	var got []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Event string                `json:"event"`
			TV    struct{ Name string } `json:"tv"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		mu.Lock()
		got = append(got, body.Event+"@"+body.TV.Name)
		mu.Unlock()
	}))
	defer srv.Close()
	b := New(openStore(t), nil)
	ctx := context.Background()
	if err := b.Save(ctx, Config{Webhook: srv.URL}); err != nil {
		t.Fatal(err)
	}
	d := store.Device{ID: "d_1", Name: "Den"}
	b.Report(ctx, d, State{App: "launcher", ScreenOn: true})
	time.Sleep(100 * time.Millisecond)
	b.Report(ctx, d, State{App: "launcher", ScreenOn: true}) // no change, no post
	b.Report(ctx, d, State{App: "plex", Playing: true, ScreenOn: true})
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		mu.Lock()
		n := len(got)
		mu.Unlock()
		if n >= 4 {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	mu.Lock()
	defer mu.Unlock()
	want := "app_changed@Den screen_on@Den app_changed@Den playback_started@Den"
	if strings.Join(got, " ") != want {
		t.Fatalf("got %v", got)
	}
	if s := b.Status(); len(s.TVs) != 1 || !s.TVs[0].State.Playing {
		t.Fatalf("status %+v", s)
	}
}

// TestPublishesOverMQTT runs the bridge against a fake broker and checks the messages.
func TestPublishesOverMQTT(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	topics := make(chan string, 32)
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		r := bufio.NewReader(conn)
		for {
			h, err := r.ReadByte()
			if err != nil {
				return
			}
			n, mul := 0, 1
			for {
				d, _ := r.ReadByte()
				n += int(d&0x7f) * mul
				if d&0x80 == 0 {
					break
				}
				mul *= 128
			}
			body := make([]byte, n)
			if _, err := io.ReadFull(r, body); err != nil {
				return
			}
			switch h & 0xF0 {
			case 0x10:
				conn.Write([]byte{0x20, 0x02, 0, 0})
			case 0x30:
				l := int(body[0])<<8 | int(body[1])
				topics <- string(body[2:2+l]) + " " + string(body[2+l:])
			}
		}
	}()

	b := New(openStore(t), nil)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if err := b.Save(ctx, Config{Broker: "mqtt://" + ln.Addr().String()}); err != nil {
		t.Fatal(err)
	}
	b.Report(ctx, store.Device{ID: "d_1", Name: "Den"}, State{App: "plex", AppLabel: "Plex", Playing: true, ScreenOn: true})
	go b.Run(ctx)

	var seen []string
	timeout := time.After(3 * time.Second)
	for len(seen) < 5 {
		select {
		case m := <-topics:
			seen = append(seen, m)
		case <-timeout:
			t.Fatalf("got only %v", seen)
		}
	}
	if seen[0] != "banditbox/status online" {
		t.Fatalf("first %q", seen[0])
	}
	if !strings.HasPrefix(seen[1], "homeassistant/sensor/banditbox_d_1/app/config ") {
		t.Fatalf("second %q", seen[1])
	}
	last := seen[4]
	if !strings.HasPrefix(last, "banditbox/d_1/state ") || !strings.Contains(last, `"playing":true`) || !strings.Contains(last, `"app_label":"Plex"`) {
		t.Fatalf("state %q", last)
	}
	deadline := time.Now().Add(time.Second)
	for !b.Status().Connected && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if !b.Status().Connected {
		t.Fatal("not connected")
	}
}
