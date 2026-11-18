package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"testing"

	"github.com/spacegoose02/banditbox/server/internal/store"
)

func (e *env) tvPut(path, token string, body any) int {
	e.t.Helper()
	b, _ := json.Marshal(body)
	req, _ := http.NewRequest("PUT", e.srv.URL+path, bytes.NewReader(b))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		e.t.Fatal(err)
	}
	res.Body.Close()
	return res.StatusCode
}

func TestTVsReportApps(t *testing.T) {
	e := newEnv(t)
	e.signIn()
	den, denID := e.pairTV(nil)
	living, livingID := e.pairTV(nil)

	put := func(token string, apps ...store.App) int {
		return e.tvPut("/api/v1/device/apps", token, map[string]any{"apps": apps})
	}
	if c := put(den, store.App{PackageName: "com.plexapp.android", Label: " Plex ", TV: true},
		store.App{PackageName: "com.android.chrome", Label: "Chrome"}); c != 204 {
		t.Fatalf("den put = %d", c)
	}
	if c := put(living, store.App{PackageName: "com.plexapp.android", Label: "Plex", TV: true}); c != 204 {
		t.Fatalf("living put = %d", c)
	}
	if c := put(den, store.App{PackageName: "not a package"}); c != 400 {
		t.Fatalf("bad package = %d, want 400", c)
	}

	var apps []store.KnownApp
	if c := e.do("GET", "/api/v1/apps", nil, &apps); c != 200 {
		t.Fatalf("list = %d", c)
	}
	if len(apps) != 2 || apps[0].Label != "Chrome" || apps[1].Label != "Plex" || !apps[1].TV || len(apps[1].Devices) != 2 {
		t.Fatalf("apps = %+v", apps)
	}

	// A TV's new list replaces its old one; revoking a TV drops its apps.
	put(den, store.App{PackageName: "com.plexapp.android", Label: "Plex", TV: true})
	e.do("DELETE", "/api/v1/devices/"+livingID, nil, nil)
	e.do("GET", "/api/v1/apps", nil, &apps)
	if len(apps) != 1 || len(apps[0].Devices) != 1 || apps[0].Devices[0] != denID {
		t.Fatalf("after changes = %+v", apps)
	}

	// Only TVs may report, only admins may list.
	if c := (&env{t: t, srv: e.srv, c: &http.Client{}}).do("GET", "/api/v1/apps", nil, nil); c != 401 {
		t.Fatalf("anonymous list = %d", c)
	}
}
