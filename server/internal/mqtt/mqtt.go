// Package mqtt is a small MQTT 3.1.1 client: connect, publish at QoS 0 and keep the
// connection alive. It is all BanditBox needs to tell Home Assistant what the TVs are doing,
// and saves a dependency.
package mqtt

import (
	"bufio"
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"net/url"
	"sync"
	"time"
)

// Message is a topic and payload, used for the last will.
type Message struct {
	Topic   string
	Payload []byte
	Retain  bool
}

// Options say where and how to connect.
type Options struct {
	// URL is mqtt://host[:1883] or mqtts://host[:8883].
	URL       string
	Username  string
	Password  string
	ClientID  string
	KeepAlive time.Duration
	// Will is published by the broker if the connection drops.
	Will *Message
}

// Client is one connection. It is safe for concurrent use.
type Client struct {
	conn net.Conn
	mu   sync.Mutex // guards writes
	done chan struct{}
	once sync.Once
	err  error
}

// ErrClosed is returned when publishing on a closed connection.
var ErrClosed = errors.New("mqtt: connection closed")

// ParseURL checks a broker URL and returns the address to dial and whether to use TLS.
func ParseURL(raw string) (addr string, useTLS bool, err error) {
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		return "", false, fmt.Errorf("broker address should look like mqtt://192.168.1.10:1883")
	}
	port := u.Port()
	switch u.Scheme {
	case "mqtt", "tcp":
		if port == "" {
			port = "1883"
		}
	case "mqtts", "ssl", "tls":
		useTLS = true
		if port == "" {
			port = "8883"
		}
	default:
		return "", false, fmt.Errorf("broker address should start with mqtt:// or mqtts://")
	}
	return net.JoinHostPort(u.Hostname(), port), useTLS, nil
}

// Dial connects and waits for the broker to accept.
func Dial(ctx context.Context, o Options) (*Client, error) {
	addr, useTLS, err := ParseURL(o.URL)
	if err != nil {
		return nil, err
	}
	var d net.Dialer
	conn, err := d.DialContext(ctx, "tcp", addr)
	if err != nil {
		return nil, err
	}
	if useTLS {
		host, _, _ := net.SplitHostPort(addr)
		tc := tls.Client(conn, &tls.Config{ServerName: host, MinVersion: tls.VersionTLS12})
		if err := tc.HandshakeContext(ctx); err != nil {
			conn.Close()
			return nil, err
		}
		conn = tc
	}
	if dl, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(dl)
	}
	if o.KeepAlive <= 0 {
		o.KeepAlive = 60 * time.Second
	}
	if _, err := conn.Write(connectPacket(o)); err != nil {
		conn.Close()
		return nil, err
	}
	r := bufio.NewReader(conn)
	typ, body, err := readPacket(r)
	if err != nil {
		conn.Close()
		return nil, fmt.Errorf("mqtt: no answer from the broker: %w", err)
	}
	if typ != 0x20 || len(body) != 2 {
		conn.Close()
		return nil, errors.New("mqtt: unexpected answer from the broker")
	}
	if rc := body[1]; rc != 0 {
		conn.Close()
		return nil, connackError(rc)
	}
	_ = conn.SetDeadline(time.Time{})
	c := &Client{conn: conn, done: make(chan struct{})}
	go c.read(r)
	go c.ping(o.KeepAlive)
	return c, nil
}

func connackError(rc byte) error {
	switch rc {
	case 1:
		return errors.New("mqtt: the broker doesn't speak MQTT 3.1.1")
	case 2:
		return errors.New("mqtt: the broker refused the client id")
	case 3:
		return errors.New("mqtt: the broker is unavailable")
	case 4:
		return errors.New("mqtt: wrong username or password")
	case 5:
		return errors.New("mqtt: not authorized")
	}
	return fmt.Errorf("mqtt: the broker refused the connection (code %d)", rc)
}

// Publish sends a message at QoS 0.
func (c *Client) Publish(topic string, payload []byte, retain bool) error {
	var b []byte
	h := byte(0x30)
	if retain {
		h |= 0x01
	}
	b = appendString(b, topic)
	b = append(b, payload...)
	return c.write(packet(h, b))
}

// Done is closed when the connection ends.
func (c *Client) Done() <-chan struct{} { return c.done }

// Err says why the connection ended, once Done is closed.
func (c *Client) Err() error {
	<-c.done
	return c.err
}

// Close disconnects cleanly; the will is not published.
func (c *Client) Close() error {
	_ = c.write([]byte{0xE0, 0x00})
	c.stop(ErrClosed)
	return nil
}

func (c *Client) write(b []byte) error {
	select {
	case <-c.done:
		return ErrClosed
	default:
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	_ = c.conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
	if _, err := c.conn.Write(b); err != nil {
		c.stop(err)
		return err
	}
	return nil
}

func (c *Client) stop(err error) {
	c.once.Do(func() {
		c.err = err
		close(c.done)
		c.conn.Close()
	})
}

// read drains what the broker sends (ping answers) and notices when the connection drops.
func (c *Client) read(r *bufio.Reader) {
	for {
		if _, _, err := readPacket(r); err != nil {
			c.stop(err)
			return
		}
	}
}

func (c *Client) ping(every time.Duration) {
	t := time.NewTicker(every * 3 / 4)
	defer t.Stop()
	for {
		select {
		case <-c.done:
			return
		case <-t.C:
			if err := c.write([]byte{0xC0, 0x00}); err != nil {
				return
			}
		}
	}
}

func connectPacket(o Options) []byte {
	flags := byte(0x02) // clean session
	if o.Will != nil {
		flags |= 0x04
		if o.Will.Retain {
			flags |= 0x20
		}
	}
	if o.Username != "" {
		flags |= 0x80
		if o.Password != "" {
			flags |= 0x40
		}
	}
	keep := int(o.KeepAlive / time.Second)
	b := appendString(nil, "MQTT")
	b = append(b, 4, flags, byte(keep>>8), byte(keep))
	b = appendString(b, o.ClientID)
	if o.Will != nil {
		b = appendString(b, o.Will.Topic)
		b = appendBytes(b, o.Will.Payload)
	}
	if o.Username != "" {
		b = appendString(b, o.Username)
		if o.Password != "" {
			b = appendString(b, o.Password)
		}
	}
	return packet(0x10, b)
}

func packet(header byte, body []byte) []byte {
	out := []byte{header}
	n := len(body)
	for {
		d := byte(n % 128)
		n /= 128
		if n > 0 {
			d |= 0x80
		}
		out = append(out, d)
		if n == 0 {
			break
		}
	}
	return append(out, body...)
}

func appendString(b []byte, s string) []byte { return appendBytes(b, []byte(s)) }

func appendBytes(b, s []byte) []byte {
	b = append(b, byte(len(s)>>8), byte(len(s)))
	return append(b, s...)
}

// maxPacket caps what we read from a broker; we only expect tiny answers.
const maxPacket = 1 << 16

func readPacket(r *bufio.Reader) (byte, []byte, error) {
	h, err := r.ReadByte()
	if err != nil {
		return 0, nil, err
	}
	n, mul := 0, 1
	for i := 0; ; i++ {
		d, err := r.ReadByte()
		if err != nil {
			return 0, nil, err
		}
		n += int(d&0x7f) * mul
		if d&0x80 == 0 {
			break
		}
		mul *= 128
		if i == 3 {
			return 0, nil, errors.New("mqtt: bad packet length")
		}
	}
	if n > maxPacket {
		return 0, nil, errors.New("mqtt: packet too large")
	}
	body := make([]byte, n)
	if _, err := io.ReadFull(r, body); err != nil {
		return 0, nil, err
	}
	return h & 0xF0, body, nil
}
