package api

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/spacegoose02/banditbox/server/internal/auth"
	"github.com/spacegoose02/banditbox/server/internal/backup"
	"github.com/spacegoose02/banditbox/server/internal/pairing"
	"github.com/spacegoose02/banditbox/server/internal/store"
)

func init() { auth.FastHashingForTests() }

type env struct {
	t    *testing.T
	srv  *httptest.Server
	auth *auth.Auth
	c    *http.Client
}

func newEnv(t *testing.T) *env {
	t.Helper()
	ctx := context.Background()
	st, err := store.Open(ctx, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	if err := st.EnsureDefaultProfile(ctx); err != nil {
		t.Fatal(err)
	}
	au := auth.New(st)
	backups := &backup.Manager{Dir: filepath.Join(t.TempDir(), "backups"), Store: st, Version: "test"}
	srv := httptest.NewServer(NewHandler(Deps{Version: "test", Store: st, Auth: au, Pairing: pairing.New(st), Backups: backups, APKDir: filepath.Join(t.TempDir(), "apks")}))
	t.Cleanup(srv.Close)
	jar, _ := cookiejar.New(nil)
	return &env{t: t, srv: srv, auth: au, c: &http.Client{Jar: jar}}
}

// do sends a request and decodes a JSON reply into out (if not nil).
func (e *env) do(method, path string, body any, out any) int {
	e.t.Helper()
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, e.srv.URL+path, rd)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	res, err := e.c.Do(req)
	if err != nil {
		e.t.Fatal(err)
	}
	defer res.Body.Close()
	if out != nil {
		decode(e.t, res, out)
	}
	return res.StatusCode
}

func decode(t *testing.T, res *http.Response, out any) {
	t.Helper()
	if err := json.NewDecoder(res.Body).Decode(out); err != nil {
		t.Fatalf("%s %s: decode: %v", res.Request.Method, res.Request.URL.Path, err)
	}
}

func (e *env) signIn() {
	e.t.Helper()
	code, _ := e.auth.SetupCode(context.Background())
	if st := e.do("POST", "/api/v1/auth/setup", setupRequest{code, "admin", "correct horse"}, nil); st != http.StatusCreated {
		e.t.Fatalf("setup: %d", st)
	}
}

func TestHealth(t *testing.T) {
	e := newEnv(t)
	var info Info
	if st := e.do("GET", "/api/v1/health", nil, &info); st != 200 || info.Status != "ok" || info.Version != "test" {
		t.Fatalf("%d %+v", st, info)
	}
	if st := e.do("POST", "/api/v1/health", nil, nil); st < 400 {
		t.Fatalf("POST health = %d", st)
	}
}

func TestSetupFlow(t *testing.T) {
	e := newEnv(t)
	var st authStatus
	e.do("GET", "/api/v1/auth/status", nil, &st)
	if !st.SetupNeeded || st.Admin != nil {
		t.Fatalf("before setup: %+v", st)
	}
	if code := e.do("POST", "/api/v1/auth/setup", setupRequest{"WRNG-CODE", "admin", "correct horse"}, nil); code != 401 {
		t.Fatalf("wrong code = %d", code)
	}
	code, _ := e.auth.SetupCode(context.Background())
	if c := e.do("POST", "/api/v1/auth/setup", setupRequest{code, "admin", "short"}, nil); c != 400 {
		t.Fatalf("short password = %d", c)
	}
	// Codes are accepted without the dash and in lower case.
	loose := strings.ToLower(strings.ReplaceAll(code, "-", ""))
	if c := e.do("POST", "/api/v1/auth/setup", setupRequest{loose, "admin", "correct horse"}, nil); c != 201 {
		t.Fatalf("setup = %d", c)
	}
	e.do("GET", "/api/v1/auth/status", nil, &st)
	if st.SetupNeeded || st.Admin == nil || st.Admin.Username != "admin" {
		t.Fatalf("after setup: %+v", st)
	}
	if c := e.do("POST", "/api/v1/auth/setup", setupRequest{code, "other", "correct horse"}, nil); c != 409 {
		t.Fatalf("second setup = %d", c)
	}
}

func TestLoginLogoutAndLimit(t *testing.T) {
	e := newEnv(t)
	e.signIn()
	if c := e.do("POST", "/api/v1/auth/logout", nil, nil); c != 204 {
		t.Fatalf("logout = %d", c)
	}
	if c := e.do("GET", "/api/v1/profiles", nil, nil); c != 401 {
		t.Fatalf("after logout = %d", c)
	}
	if c := e.do("POST", "/api/v1/auth/login", loginRequest{"ADMIN", "correct horse"}, nil); c != 200 {
		t.Fatalf("login (any case) = %d", c)
	}
	if c := e.do("GET", "/api/v1/profiles", nil, nil); c != 200 {
		t.Fatalf("after login = %d", c)
	}
	for i := 0; i < 5; i++ {
		if c := e.do("POST", "/api/v1/auth/login", loginRequest{"admin", "wrong password"}, nil); c != 401 {
			t.Fatalf("bad login %d = %d", i, c)
		}
	}
	if c := e.do("POST", "/api/v1/auth/login", loginRequest{"admin", "correct horse"}, nil); c != 429 {
		t.Fatalf("after 5 failures = %d", c)
	}
}

func TestChangePasswordSignsOutOtherSessions(t *testing.T) {
	e := newEnv(t)
	e.signIn()
	other := &env{t: t, srv: e.srv, auth: e.auth}
	jar, _ := cookiejar.New(nil)
	other.c = &http.Client{Jar: jar}
	if c := other.do("POST", "/api/v1/auth/login", loginRequest{"admin", "correct horse"}, nil); c != 200 {
		t.Fatalf("second login = %d", c)
	}
	if c := e.do("POST", "/api/v1/auth/password", passwordRequest{"nope nope nope", "new password 1"}, nil); c != 403 {
		t.Fatalf("wrong current = %d", c)
	}
	if c := e.do("POST", "/api/v1/auth/password", passwordRequest{"correct horse", "new password 1"}, nil); c != 204 {
		t.Fatalf("change = %d", c)
	}
	if c := e.do("GET", "/api/v1/profiles", nil, nil); c != 200 {
		t.Fatalf("changer still signed in = %d", c)
	}
	if c := other.do("GET", "/api/v1/profiles", nil, nil); c != 401 {
		t.Fatalf("other session = %d", c)
	}
}

func TestCrossSiteRefused(t *testing.T) {
	e := newEnv(t)
	e.signIn()
	req, _ := http.NewRequest("DELETE", e.srv.URL+"/api/v1/profiles/p_default", nil)
	req.Header.Set("Origin", "http://evil.example")
	res, err := e.c.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 403 {
		t.Fatalf("cross-site delete = %d", res.StatusCode)
	}
}

func TestRequiresJSON(t *testing.T) {
	e := newEnv(t)
	res, err := e.c.Post(e.srv.URL+"/api/v1/auth/login", "application/x-www-form-urlencoded", strings.NewReader("username=a"))
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != http.StatusUnsupportedMediaType {
		t.Fatalf("form post = %d", res.StatusCode)
	}
}

func TestProfilesNeedSignIn(t *testing.T) {
	e := newEnv(t)
	for _, p := range []string{"/api/v1/profiles", "/api/v1/profiles/p_default/layout"} {
		if c := e.do("GET", p, nil, nil); c != 401 {
			t.Fatalf("%s = %d", p, c)
		}
	}
}

func TestProfileLifecycle(t *testing.T) {
	e := newEnv(t)
	e.signIn()

	var doc map[string]any
	if c := e.do("POST", "/api/v1/profiles", createProfileRequest{ID: "p_kids", Name: "Kids", CopyFrom: "p_default"}, &doc); c != 201 {
		t.Fatalf("create = %d", c)
	}
	if doc["profileId"] != "p_kids" || doc["version"] != float64(0) || len(doc["rows"].([]any)) != 1 {
		t.Fatalf("created %v", doc)
	}
	if c := e.do("POST", "/api/v1/profiles", createProfileRequest{ID: "p_kids", Name: "Kids"}, nil); c != 409 {
		t.Fatalf("duplicate = %d", c)
	}
	if c := e.do("POST", "/api/v1/profiles", createProfileRequest{ID: "bad id", Name: "X"}, nil); c != 400 {
		t.Fatalf("bad id = %d", c)
	}

	var list []store.ProfileSummary
	e.do("GET", "/api/v1/profiles", nil, &list)
	if len(list) != 2 {
		t.Fatalf("list = %+v", list)
	}

	// Edit: version 0 -> 1.
	doc["profile"].(map[string]any)["name"] = "Little ones"
	var saved map[string]any
	if c := e.do("PUT", "/api/v1/profiles/p_kids/layout", doc, &saved); c != 200 || saved["version"] != float64(1) {
		t.Fatalf("put = %d %v", c, saved)
	}
	// The same stale edit again conflicts and returns the current document.
	var conflict struct {
		Current map[string]any `json:"current"`
	}
	if c := e.do("PUT", "/api/v1/profiles/p_kids/layout", doc, &conflict); c != 409 || conflict.Current["version"] != float64(1) {
		t.Fatalf("stale put = %d %v", c, conflict)
	}
	// Invalid documents are refused with the reason.
	saved["rows"] = []any{map[string]any{"id": "r", "type": "nope"}}
	if c := e.do("PUT", "/api/v1/profiles/p_kids/layout", saved, nil); c != 422 {
		t.Fatalf("invalid put = %d", c)
	}

	var hist []map[string]any
	e.do("GET", "/api/v1/profiles/p_kids/history", nil, &hist)
	if len(hist) != 1 || hist[0]["version"] != float64(0) {
		t.Fatalf("history = %v", hist)
	}

	if c := e.do("DELETE", "/api/v1/profiles/p_kids", nil, nil); c != 204 {
		t.Fatalf("delete = %d", c)
	}
	if c := e.do("DELETE", "/api/v1/profiles/p_default", nil, nil); c != 409 {
		t.Fatalf("delete last = %d", c)
	}
	if c := e.do("GET", "/api/v1/profiles/p_kids/layout", nil, nil); c != 404 {
		t.Fatalf("get deleted = %d", c)
	}
}

func TestUnknownEndpoint(t *testing.T) {
	e := newEnv(t)
	if c := e.do("GET", "/api/v1/nope", nil, nil); c != 404 {
		t.Fatalf("%d", c)
	}
}
