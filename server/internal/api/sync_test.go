package api

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"

	"github.com/spacegoose02/banditbox/server/internal/pairing"
	"github.com/spacegoose02/banditbox/server/internal/store"
)

// pairTV signs in as admin and pairs a TV allowed the given profiles, returning its token.
func (e *env) pairTV(profiles []string) (token, deviceID string) {
	e.t.Helper()
	tv := &env{t: e.t, srv: e.srv, auth: e.auth, c: &http.Client{}}
	var start pairStartResponse
	tv.do("POST", "/api/v1/pair/start", pairStartRequest{Name: "TV"}, &start)
	var d store.Device
	if c := e.do("POST", "/api/v1/pair/approve", pairApproveRequest{Code: start.Code, Profiles: profiles}, &d); c != 201 {
		e.t.Fatalf("approve = %d", c)
	}
	var res pairing.Result
	tv.do("GET", "/api/v1/pair/"+start.PairID+"?wait=0", nil, &res)
	return res.Token, d.ID
}

func (e *env) tvPost(path, token string, body, out any) int {
	e.t.Helper()
	b, _ := json.Marshal(body)
	req, _ := http.NewRequest("POST", e.srv.URL+path, bytes.NewReader(b))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		e.t.Fatal(err)
	}
	defer res.Body.Close()
	if out != nil {
		decode(e.t, res, out)
	}
	return res.StatusCode
}

func raw(s string) json.RawMessage { return json.RawMessage(s) }

func TestSyncLayoutAndOps(t *testing.T) {
	e := newEnv(t)
	e.signIn()
	e.do("POST", "/api/v1/profiles", createProfileRequest{ID: "p_kids", Name: "Kids"}, nil)
	token, _ := e.pairTV([]string{"p_default"})

	var l layoutResponse
	if c := e.tvGet("/api/v1/sync/layout?profile=p_default", token, &l); c != 200 || l.Version != 0 || len(l.Doc) == 0 {
		t.Fatalf("layout = %d %+v", c, l)
	}
	if c := e.tvGet("/api/v1/sync/layout?profile=p_default&since=0", token, nil); c != 204 {
		t.Fatalf("up to date = %d", c)
	}
	if c := e.tvGet("/api/v1/sync/layout?profile=p_kids", token, nil); c != 403 {
		t.Fatalf("other profile = %d", c)
	}

	var res opsResponse
	c := e.tvPost("/api/v1/sync/ops", token, opsRequest{ProfileID: "p_default", BaseVersion: 0, Ops: []json.RawMessage{
		raw(`{"opId":"op_tv_0001","hlc":"1763430000000-00000","kind":"set","path":"/profile/name","value":"Den"}`),
		raw(`{"opId":"op_tv_0002","hlc":"nope","kind":"set","path":"/profile/name","value":"x"}`),
		raw(`{"hlc":"1763430000000-00000"}`),
	}}, &res)
	if c != 200 || res.Version != 1 || len(res.Applied) != 1 || len(res.Rejected) != 2 {
		t.Fatalf("ops = %d %+v", c, res)
	}
	if !strings.Contains(string(res.Doc), `"Den"`) {
		t.Fatalf("doc %s", res.Doc)
	}
	if c := e.tvPost("/api/v1/sync/ops", token, opsRequest{ProfileID: "p_default", BaseVersion: 9}, nil); c != 409 {
		t.Fatalf("future base = %d", c)
	}
	if c := e.tvPost("/api/v1/sync/ops", token, opsRequest{ProfileID: "p_kids"}, nil); c != 403 {
		t.Fatalf("ops on other profile = %d", c)
	}
}

func TestSyncStream(t *testing.T) {
	e := newEnv(t)
	e.signIn()
	token, deviceID := e.pairTV(nil)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	url := "ws" + strings.TrimPrefix(e.srv.URL, "http") + "/api/v1/sync/stream"
	if _, _, err := websocket.Dial(ctx, url, nil); err == nil {
		t.Fatal("stream without token accepted")
	}
	conn, _, err := websocket.Dial(ctx, url, &websocket.DialOptions{HTTPHeader: http.Header{"Authorization": {"Bearer " + token}}})
	if err != nil {
		t.Fatal(err)
	}
	defer conn.CloseNow()

	var m streamMessage
	if err := wsjson.Read(ctx, conn, &m); err != nil || m.ProfileID != "p_default" || m.Version != 0 {
		t.Fatalf("hello %+v %v", m, err)
	}

	// An edit in the web UI is announced.
	var doc map[string]any
	e.do("GET", "/api/v1/profiles/p_default/layout", nil, &doc)
	doc["topBar"] = []any{"clock"}
	if c := e.do("PUT", "/api/v1/profiles/p_default/layout", doc, nil); c != 200 {
		t.Fatalf("put = %d", c)
	}
	if err := wsjson.Read(ctx, conn, &m); err != nil || m.Type != "version" || m.Version != 1 {
		t.Fatalf("after edit %+v %v", m, err)
	}

	// Revoking the TV closes its stream.
	e.do("DELETE", "/api/v1/devices/"+deviceID, nil, nil)
	_, _, err = conn.Read(ctx)
	if websocket.CloseStatus(err) != 4401 {
		t.Fatalf("after revoke err = %v", err)
	}
}

func (e *env) tvDelete(path, token string) int {
	e.t.Helper()
	req, _ := http.NewRequest("DELETE", e.srv.URL+path, nil)
	req.Header.Set("Authorization", "Bearer "+token)
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		e.t.Fatal(err)
	}
	res.Body.Close()
	return res.StatusCode
}

func TestTVSeedsAndManagesProfiles(t *testing.T) {
	e := newEnv(t)
	e.signIn()
	token, _ := e.pairTV(nil)

	var me struct{ Fresh bool }
	e.tvGet("/api/v1/device/me", token, &me)
	if !me.Fresh {
		t.Fatal("new server not fresh")
	}
	seed := seedRequest{Layouts: []json.RawMessage{
		raw(`{"schema":1,"profileId":"p_default","version":4,"profile":{"name":"Den"},"rows":[]}`),
		raw(`{"schema":1,"profileId":"p_kids","version":2,"profile":{"name":"Kids"},"rows":[]}`),
	}}
	var seeded []layoutResponse
	if c := e.tvPost("/api/v1/sync/seed", token, seed, &seeded); c != 200 || len(seeded) != 2 {
		t.Fatalf("seed = %d %+v", c, seeded)
	}
	if c := e.tvPost("/api/v1/sync/seed", token, seed, nil); c != 409 {
		t.Fatalf("second seed = %d", c)
	}

	var created layoutResponse
	c := e.tvPost("/api/v1/sync/profiles", token, raw(`{"schema":1,"profileId":"p_guest","version":0,"profile":{"name":"Guest"},"rows":[]}`), &created)
	if c != 201 || created.Version != 0 {
		t.Fatalf("create = %d %+v", c, created)
	}
	if c := e.tvDelete("/api/v1/sync/profiles/p_guest", token); c != 204 {
		t.Fatalf("delete = %d", c)
	}

	// A TV limited to some profiles can't seed, and its new profiles join its list.
	limited, id := e.pairTV([]string{"p_kids"})
	if c := e.tvPost("/api/v1/sync/seed", limited, seed, nil); c != 403 {
		t.Fatalf("limited seed = %d", c)
	}
	e.tvPost("/api/v1/sync/profiles", limited, raw(`{"schema":1,"profileId":"p_den2","version":0,"profile":{"name":"Den 2"},"rows":[]}`), nil)
	var d store.Device
	e.do("GET", "/api/v1/devices", nil, nil)
	var devices []store.Device
	e.do("GET", "/api/v1/devices", nil, &devices)
	for _, x := range devices {
		if x.ID == id {
			d = x
		}
	}
	if len(d.Profiles) != 2 {
		t.Fatalf("limited TV profiles %v", d.Profiles)
	}
	if c := e.tvDelete("/api/v1/sync/profiles/p_default", limited); c != 403 {
		t.Fatalf("delete not allowed = %d", c)
	}
}
