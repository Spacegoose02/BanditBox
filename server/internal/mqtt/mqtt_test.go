package mqtt

import (
	"bufio"
	"context"
	"net"
	"testing"
	"time"
)

// fakeBroker accepts one connection, answers CONNECT with rc, and sends what it reads on got.
type fakeBroker struct {
	ln  net.Listener
	got chan [2]any // packet type, body
}

func newFakeBroker(t *testing.T, rc byte) *fakeBroker {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	b := &fakeBroker{ln: ln, got: make(chan [2]any, 16)}
	t.Cleanup(func() { ln.Close() })
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		r := bufio.NewReader(conn)
		for {
			typ, body, err := readPacket(r)
			if err != nil {
				close(b.got)
				return
			}
			b.got <- [2]any{typ, body}
			if typ == 0x10 {
				conn.Write([]byte{0x20, 0x02, 0x00, rc})
			}
		}
	}()
	return b
}

func (b *fakeBroker) url() string { return "mqtt://" + b.ln.Addr().String() }

func (b *fakeBroker) next(t *testing.T) (byte, []byte) {
	t.Helper()
	select {
	case p, ok := <-b.got:
		if !ok {
			t.Fatal("connection closed")
		}
		return p[0].(byte), p[1].([]byte)
	case <-time.After(2 * time.Second):
		t.Fatal("nothing received")
	}
	return 0, nil
}

func TestConnectAndPublish(t *testing.T) {
	b := newFakeBroker(t, 0)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	c, err := Dial(ctx, Options{
		URL: b.url(), Username: "ha", Password: "pw", ClientID: "banditbox",
		Will: &Message{Topic: "banditbox/status", Payload: []byte("offline"), Retain: true},
	})
	if err != nil {
		t.Fatal(err)
	}
	typ, body := b.next(t)
	if typ != 0x10 {
		t.Fatalf("first packet %x", typ)
	}
	// "MQTT", level 4, flags: user, password, will retain, will, clean session.
	if string(body[2:6]) != "MQTT" || body[6] != 4 || body[7] != 0xE6 {
		t.Fatalf("connect header % x", body[:10])
	}
	if err := c.Publish("banditbox/tv/state", []byte(`{"playing":true}`), true); err != nil {
		t.Fatal(err)
	}
	typ, body = b.next(t)
	if typ != 0x30 {
		t.Fatalf("publish type %x", typ)
	}
	if topic := string(body[2 : 2+int(body[1])]); topic != "banditbox/tv/state" {
		t.Fatalf("topic %q", topic)
	}
	if payload := string(body[2+int(body[1]):]); payload != `{"playing":true}` {
		t.Fatalf("payload %q", payload)
	}
	c.Close()
	if typ, _ := b.next(t); typ != 0xE0 {
		t.Fatalf("want disconnect, got %x", typ)
	}
	if err := c.Publish("x", nil, false); err != ErrClosed {
		t.Fatalf("publish after close: %v", err)
	}
}

func TestRefused(t *testing.T) {
	b := newFakeBroker(t, 4)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if _, err := Dial(ctx, Options{URL: b.url(), ClientID: "x"}); err == nil || err.Error() != "mqtt: wrong username or password" {
		t.Fatalf("err = %v", err)
	}
}

func TestParseURL(t *testing.T) {
	for in, want := range map[string]string{
		"mqtt://10.0.0.5":      "10.0.0.5:1883",
		"mqtt://ha.local:1884": "ha.local:1884",
		"mqtts://broker.lan":   "broker.lan:8883",
	} {
		got, _, err := ParseURL(in)
		if err != nil || got != want {
			t.Errorf("%s: %s %v", in, got, err)
		}
	}
	for _, bad := range []string{"", "10.0.0.5", "http://x"} {
		if _, _, err := ParseURL(bad); err == nil {
			t.Errorf("%q: want error", bad)
		}
	}
}

func TestLongPacketLength(t *testing.T) {
	p := packet(0x30, make([]byte, 300))
	if p[1] != 0xAC || p[2] != 0x02 {
		t.Fatalf("length bytes % x", p[1:3])
	}
}
