package api

import (
	"net/http"
	"testing"
	"time"

	"github.com/spacegoose02/banditbox/server/internal/pairing"
	"github.com/spacegoose02/banditbox/server/internal/store"
)

// tvGet calls a device endpoint with a bearer token.
func (e *env) tvGet(path, token string, out any) int {
	e.t.Helper()
	req, _ := http.NewRequest("GET", e.srv.URL+path, nil)
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("X-BanditBox-App", "0.2.0")
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

func TestPairingFlow(t *testing.T) {
	e := newEnv(t)
	e.signIn()
	e.do("POST", "/api/v1/profiles", createProfileRequest{ID: "p_kids", Name: "Kids"}, nil)

	// The TV is a different client with no cookies.
	tv := &env{t: t, srv: e.srv, auth: e.auth, c: &http.Client{}}
	var start pairStartResponse
	if c := tv.do("POST", "/api/v1/pair/start", pairStartRequest{Name: "Bedroom", Model: "SHIELD Android TV"}, &start); c != 201 {
		t.Fatalf("start = %d", c)
	}
	if start.PairID == "" || len(start.Code) != 6 || start.QRURL != e.srv.URL+"/devices?pair="+start.Code {
		t.Fatalf("start %+v", start)
	}

	// Only an admin can see or approve pairings.
	if c := tv.do("GET", "/api/v1/pair/pending", nil, nil); c != 401 {
		t.Fatalf("tv pending = %d", c)
	}
	var pending []pairing.Pending
	e.do("GET", "/api/v1/pair/pending", nil, &pending)
	if len(pending) != 1 || pending[0].Name != "Bedroom" {
		t.Fatalf("pending %+v", pending)
	}

	var res pairing.Result
	if c := tv.do("GET", "/api/v1/pair/"+start.PairID+"?wait=0", nil, &res); c != 200 || res.Status != "pending" {
		t.Fatalf("poll = %d %+v", c, res)
	}
	if c := e.do("POST", "/api/v1/pair/approve", pairApproveRequest{Code: start.Code, Profiles: []string{"p_nope"}}, nil); c != 400 {
		t.Fatalf("approve unknown profile = %d", c)
	}
	go func() {
		time.Sleep(50 * time.Millisecond)
		e.do("POST", "/api/v1/pair/approve", pairApproveRequest{Code: start.Code, Name: "Kids room", Profiles: []string{"p_kids"}}, nil)
	}()
	if c := tv.do("GET", "/api/v1/pair/"+start.PairID+"?wait=5", nil, &res); c != 200 || res.Status != "approved" || res.Token == "" {
		t.Fatalf("long poll = %d %+v", c, res)
	}

	var me struct {
		Device   store.Device
		Profiles []store.ProfileSummary
	}
	if c := e.tvGet("/api/v1/device/me", res.Token, &me); c != 200 {
		t.Fatalf("me = %d", c)
	}
	if me.Device.Name != "Kids room" || len(me.Profiles) != 1 || me.Profiles[0].ID != "p_kids" {
		t.Fatalf("me %+v", me)
	}

	var devices []store.Device
	e.do("GET", "/api/v1/devices", nil, &devices)
	if len(devices) != 1 || devices[0].AppVersion != "0.2.0" || devices[0].LastSeen.IsZero() {
		t.Fatalf("devices %+v", devices)
	}

	// Widen to all profiles, then revoke.
	all := []string{}
	if c := e.do("PATCH", "/api/v1/devices/"+me.Device.ID, updateDeviceRequest{Profiles: &all}, nil); c != 200 {
		t.Fatalf("patch = %d", c)
	}
	e.tvGet("/api/v1/device/me", res.Token, &me)
	if len(me.Profiles) != 2 {
		t.Fatalf("after widening %+v", me.Profiles)
	}
	if c := e.do("DELETE", "/api/v1/devices/"+me.Device.ID, nil, nil); c != 204 {
		t.Fatalf("revoke = %d", c)
	}
	if c := e.tvGet("/api/v1/device/me", res.Token, nil); c != 401 {
		t.Fatalf("after revoke = %d", c)
	}
}

func TestDeviceAuthNeedsToken(t *testing.T) {
	e := newEnv(t)
	if c := e.tvGet("/api/v1/device/me", "", nil); c != 401 {
		t.Fatalf("no token = %d", c)
	}
	if c := e.tvGet("/api/v1/device/me", "made-up", nil); c != 401 {
		t.Fatalf("bad token = %d", c)
	}
	if c := e.do("GET", "/api/v1/pair/unknown?wait=0", nil, nil); c != 404 {
		t.Fatalf("unknown pairing = %d", c)
	}
}
