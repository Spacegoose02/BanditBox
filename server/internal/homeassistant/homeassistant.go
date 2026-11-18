// Package homeassistant tells Home Assistant what each TV is doing: the app in front, whether
// something is playing, and whether the screen is on. It publishes over MQTT with Home
// Assistant's discovery, so each TV shows up as a device with sensors, and can also post each
// change to a webhook. Automations then do things like dim the lights when a movie starts.
package homeassistant

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/spacegoose02/banditbox/server/internal/mqtt"
	"github.com/spacegoose02/banditbox/server/internal/store"
)

// SettingKey is where the config is stored.
const SettingKey = "homeassistant"

// Config is what the admin sets in the web UI.
type Config struct {
	// Broker is mqtt://host:1883 or mqtts://host:8883; empty turns MQTT off.
	Broker   string `json:"broker"`
	Username string `json:"username"`
	// Password is never sent back to the web UI.
	Password string `json:"password,omitempty"`
	// Prefix starts BanditBox's own topics; Discovery is Home Assistant's discovery prefix.
	Prefix    string `json:"prefix"`
	Discovery string `json:"discovery"`
	// Webhook gets a POST for each change, for example a Home Assistant webhook trigger.
	Webhook string `json:"webhook"`
}

// Defaults fills in empty prefixes.
func (c Config) Defaults() Config {
	if c.Prefix == "" {
		c.Prefix = "banditbox"
	}
	if c.Discovery == "" {
		c.Discovery = "homeassistant"
	}
	return c
}

var topicPart = regexp.MustCompile(`^[A-Za-z0-9_\-/]{1,64}$`)

// Check says what is wrong with c, if anything.
func (c Config) Check() error {
	c = c.Defaults()
	if c.Broker != "" {
		if _, _, err := mqtt.ParseURL(c.Broker); err != nil {
			return err
		}
	}
	if !topicPart.MatchString(c.Prefix) || !topicPart.MatchString(c.Discovery) ||
		strings.Contains(c.Prefix, "//") || strings.HasPrefix(c.Prefix, "/") || strings.HasSuffix(c.Prefix, "/") ||
		strings.Contains(c.Discovery, "//") || strings.HasPrefix(c.Discovery, "/") || strings.HasSuffix(c.Discovery, "/") {
		return errors.New("topic prefixes may use letters, digits, - and _, separated by /")
	}
	if c.Webhook != "" {
		u, err := url.Parse(c.Webhook)
		if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
			return errors.New("the webhook should be an http:// or https:// address")
		}
	}
	if len(c.Username) > 200 || len(c.Password) > 200 || len(c.Broker) > 500 || len(c.Webhook) > 500 {
		return errors.New("a setting is too long")
	}
	return nil
}

// State is what a TV reports.
type State struct {
	// App is the package in front; AppLabel its name. The launcher itself counts as an app.
	App      string `json:"app"`
	AppLabel string `json:"appLabel"`
	Playing  bool   `json:"playing"`
	ScreenOn bool   `json:"screenOn"`
}

// TV is one TV's last report.
type TV struct {
	Device  store.Device `json:"device"`
	State   State        `json:"state"`
	Updated time.Time    `json:"updated"`
}

// Status is shown on the web UI.
type Status struct {
	Connected bool   `json:"connected"`
	Error     string `json:"error,omitempty"`
	TVs       []TV   `json:"tvs"`
}

// Bridge holds the TVs' states and keeps the MQTT connection.
type Bridge struct {
	Store *store.Store
	Log   *slog.Logger
	HTTP  *http.Client

	mu        sync.Mutex
	tvs       map[string]*TV
	dirty     map[string]bool
	connected bool
	lastErr   string
	wake      chan struct{}
}

// New returns a bridge; call Run to connect.
func New(st *store.Store, log *slog.Logger) *Bridge {
	if log == nil {
		log = slog.New(slog.DiscardHandler)
	}
	return &Bridge{
		Store: st, Log: log,
		HTTP: &http.Client{Timeout: 5 * time.Second},
		tvs:  map[string]*TV{}, dirty: map[string]bool{},
		wake: make(chan struct{}, 1),
	}
}

// Load reads the saved config.
func (b *Bridge) Load(ctx context.Context) (Config, error) {
	raw, err := b.Store.Setting(ctx, SettingKey)
	if err != nil || raw == "" {
		return Config{}.Defaults(), err
	}
	var c Config
	if err := json.Unmarshal([]byte(raw), &c); err != nil {
		return Config{}.Defaults(), nil
	}
	return c.Defaults(), nil
}

// Save stores c and reconnects with it.
func (b *Bridge) Save(ctx context.Context, c Config) error {
	if err := c.Check(); err != nil {
		return err
	}
	raw, _ := json.Marshal(c.Defaults())
	if err := b.Store.SetSetting(ctx, SettingKey, string(raw)); err != nil {
		return err
	}
	b.mu.Lock()
	b.lastErr = ""
	b.mu.Unlock()
	b.kick()
	return nil
}

// Status returns the connection state and every TV's last report.
func (b *Bridge) Status() Status {
	b.mu.Lock()
	defer b.mu.Unlock()
	s := Status{Connected: b.connected, Error: b.lastErr, TVs: []TV{}}
	for _, tv := range b.tvs {
		s.TVs = append(s.TVs, *tv)
	}
	return s
}

// Report records a TV's state, and on a change sends it to Home Assistant.
func (b *Bridge) Report(ctx context.Context, d store.Device, s State) {
	b.mu.Lock()
	old := b.tvs[d.ID]
	changed := old == nil || old.State != s || old.Device.Name != d.Name
	b.tvs[d.ID] = &TV{Device: d, State: s, Updated: time.Now()}
	if changed {
		b.dirty[d.ID] = true
	}
	b.mu.Unlock()
	if !changed {
		return
	}
	b.kick()
	var before *State
	if old != nil {
		before = &old.State
	}
	if events := Events(before, s); len(events) > 0 {
		c, err := b.Load(ctx)
		if err == nil && c.Webhook != "" {
			go b.postEvents(c.Webhook, d, s, events)
		}
	}
}

// Forget drops a TV, for example when it is unpaired.
func (b *Bridge) Forget(deviceID string) {
	b.mu.Lock()
	delete(b.tvs, deviceID)
	b.mu.Unlock()
}

func (b *Bridge) kick() {
	select {
	case b.wake <- struct{}{}:
	default:
	}
}

// Events names what changed between two reports, for webhooks.
func Events(before *State, now State) []string {
	var out []string
	if before == nil {
		before = &State{}
	}
	if now.App != before.App {
		out = append(out, "app_changed")
	}
	if now.Playing && !before.Playing {
		out = append(out, "playback_started")
	}
	if !now.Playing && before.Playing {
		out = append(out, "playback_stopped")
	}
	if now.ScreenOn && !before.ScreenOn {
		out = append(out, "screen_on")
	}
	if !now.ScreenOn && before.ScreenOn {
		out = append(out, "screen_off")
	}
	return out
}

func (b *Bridge) postEvents(webhook string, d store.Device, s State, events []string) {
	for _, e := range events {
		body, _ := json.Marshal(map[string]any{
			"event": e,
			"tv":    map[string]string{"id": d.ID, "name": d.Name},
			"state": statePayload(s),
		})
		if err := b.post(context.Background(), webhook, body); err != nil {
			b.Log.Warn("homeassistant: webhook", "err", err)
			return
		}
	}
}

func (b *Bridge) post(ctx context.Context, webhook string, body []byte) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, webhook, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := b.HTTP.Do(req)
	if err != nil {
		return err
	}
	resp.Body.Close()
	if resp.StatusCode >= 300 {
		return fmt.Errorf("the webhook answered %s", resp.Status)
	}
	return nil
}

// Test connects with c and sends a test message, without saving c.
func (b *Bridge) Test(ctx context.Context, c Config) error {
	c = c.Defaults()
	if err := c.Check(); err != nil {
		return err
	}
	if c.Broker == "" && c.Webhook == "" {
		return errors.New("add a broker or a webhook first")
	}
	if c.Broker != "" {
		dctx, cancel := context.WithTimeout(ctx, 5*time.Second)
		defer cancel()
		cl, err := mqtt.Dial(dctx, b.options(c, "banditbox-test-"+fmt.Sprint(time.Now().UnixNano()%1e6)))
		if err != nil {
			return err
		}
		err = cl.Publish(c.Prefix+"/test", []byte("BanditBox can reach this broker"), false)
		cl.Close()
		if err != nil {
			return err
		}
	}
	if c.Webhook != "" {
		body, _ := json.Marshal(map[string]any{"event": "test", "tv": map[string]string{"id": "", "name": "BanditBox"}})
		if err := b.post(ctx, c.Webhook, body); err != nil {
			return err
		}
	}
	return nil
}

func (b *Bridge) options(c Config, clientID string) mqtt.Options {
	return mqtt.Options{
		URL: c.Broker, Username: c.Username, Password: c.Password, ClientID: clientID,
		KeepAlive: time.Minute,
		Will:      &mqtt.Message{Topic: c.Prefix + "/status", Payload: []byte("offline"), Retain: true},
	}
}

// Run keeps the MQTT connection while there is a broker set, until ctx ends.
func (b *Bridge) Run(ctx context.Context) {
	backoff := 5 * time.Second
	for ctx.Err() == nil {
		c, err := b.Load(ctx)
		if err != nil || c.Broker == "" {
			b.setConnected(false, "")
			if !b.wait(ctx, nil, time.Hour) {
				return
			}
			continue
		}
		err = b.session(ctx, c)
		b.setConnected(false, errText(err))
		if err != nil && ctx.Err() == nil {
			b.Log.Warn("homeassistant: mqtt", "err", err)
			if !b.wait(ctx, nil, backoff) {
				return
			}
			backoff = min(backoff*2, 5*time.Minute)
			continue
		}
		backoff = 5 * time.Second
	}
}

func errText(err error) string {
	if err == nil || errors.Is(err, errReload) {
		return ""
	}
	return err.Error()
}

var errReload = errors.New("settings changed")

// session runs one connection: announce, publish states as they change, until the
// connection drops or the settings change.
func (b *Bridge) session(ctx context.Context, c Config) error {
	dctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	cl, err := mqtt.Dial(dctx, b.options(c, "banditbox-server"))
	cancel()
	if err != nil {
		return err
	}
	defer cl.Close()
	if err := cl.Publish(c.Prefix+"/status", []byte("online"), true); err != nil {
		return err
	}
	b.setConnected(true, "")
	announced := map[string]bool{}
	b.mu.Lock()
	for id := range b.tvs {
		b.dirty[id] = true
	}
	b.mu.Unlock()
	for {
		b.mu.Lock()
		var send []TV
		for id := range b.dirty {
			if tv := b.tvs[id]; tv != nil {
				send = append(send, *tv)
			}
		}
		b.dirty = map[string]bool{}
		b.mu.Unlock()
		for _, tv := range send {
			if !announced[tv.Device.ID] {
				for _, m := range Discovery(c, tv.Device) {
					if err := cl.Publish(m.Topic, m.Payload, true); err != nil {
						return err
					}
				}
				announced[tv.Device.ID] = true
			}
			payload, _ := json.Marshal(statePayload(tv.State))
			if err := cl.Publish(StateTopic(c, tv.Device.ID), payload, true); err != nil {
				return err
			}
		}
		now, err := b.Load(ctx)
		if err == nil && now != c {
			return errReload
		}
		if !b.wait(ctx, cl.Done(), time.Hour) {
			return ctx.Err()
		}
		select {
		case <-cl.Done():
			return cl.Err()
		default:
		}
	}
}

// wait sleeps until woken, d passes, done closes or ctx ends; false means ctx ended.
func (b *Bridge) wait(ctx context.Context, done <-chan struct{}, d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-b.wake:
	case <-done:
	case <-t.C:
	}
	return true
}

func (b *Bridge) setConnected(on bool, errText string) {
	b.mu.Lock()
	b.connected = on
	if errText != "" || on {
		b.lastErr = errText
	}
	b.mu.Unlock()
}

var nodeChars = regexp.MustCompile(`[^A-Za-z0-9_-]`)

func node(deviceID string) string { return "banditbox_" + nodeChars.ReplaceAllString(deviceID, "_") }

// StateTopic is where a TV's state is published.
func StateTopic(c Config, deviceID string) string {
	return c.Defaults().Prefix + "/" + nodeChars.ReplaceAllString(deviceID, "_") + "/state"
}

func statePayload(s State) map[string]any {
	return map[string]any{
		"app":       s.App,
		"app_label": s.AppLabel,
		"playing":   s.Playing,
		"screen_on": s.ScreenOn,
	}
}

// Discovery returns the retained config messages that make Home Assistant add a TV as a
// device with three entities: the app in front, playing, and screen on.
func Discovery(c Config, d store.Device) []mqtt.Message {
	c = c.Defaults()
	n := node(d.ID)
	name := d.Name
	if name == "" {
		name = "BanditBox TV"
	}
	device := map[string]any{
		"identifiers":  []string{n},
		"name":         name,
		"manufacturer": "BanditBox",
	}
	if d.Model != "" {
		device["model"] = d.Model
	}
	if d.AppVersion != "" {
		device["sw_version"] = d.AppVersion
	}
	state := StateTopic(c, d.ID)
	common := func(id, label string) map[string]any {
		return map[string]any{
			"name":               label,
			"unique_id":          n + "_" + id,
			"object_id":          n + "_" + id,
			"state_topic":        state,
			"availability_topic": c.Prefix + "/status",
			"device":             device,
		}
	}
	app := common("app", "App")
	app["value_template"] = "{{ value_json.app_label or value_json.app }}"
	app["json_attributes_topic"] = state
	app["icon"] = "mdi:television-play"
	playing := common("playing", "Playing")
	playing["value_template"] = "{{ 'ON' if value_json.playing else 'OFF' }}"
	playing["icon"] = "mdi:play-circle"
	screen := common("screen", "Screen")
	screen["value_template"] = "{{ 'ON' if value_json.screen_on else 'OFF' }}"
	screen["device_class"] = "power"
	msg := func(component, id string, v map[string]any) mqtt.Message {
		p, _ := json.Marshal(v)
		return mqtt.Message{Topic: c.Discovery + "/" + component + "/" + n + "/" + id + "/config", Payload: p, Retain: true}
	}
	return []mqtt.Message{
		msg("sensor", "app", app),
		msg("binary_sensor", "playing", playing),
		msg("binary_sensor", "screen", screen),
	}
}
