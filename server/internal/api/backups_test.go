package api

import (
	"encoding/json"
	"io"
	"net/http"
	"testing"

	"github.com/spacegoose02/banditbox/server/internal/backup"
)

func TestBackupsAPI(t *testing.T) {
	e := newEnv(t)
	e.signIn()

	var made backup.Entry
	if c := e.do("POST", "/api/v1/backups", nil, &made); c != 201 || made.Profiles != 1 {
		t.Fatalf("create = %d %+v", c, made)
	}
	var list []backup.Entry
	if c := e.do("GET", "/api/v1/backups", nil, &list); c != 200 || len(list) != 1 {
		t.Fatalf("list = %d %+v", c, list)
	}

	res, err := e.c.Get(e.srv.URL + "/api/v1/backups/" + made.Name)
	if err != nil {
		t.Fatal(err)
	}
	file, _ := io.ReadAll(res.Body)
	res.Body.Close()
	if res.StatusCode != 200 || res.Header.Get("Content-Disposition") == "" {
		t.Fatalf("download = %d", res.StatusCode)
	}
	if c := e.do("GET", "/api/v1/backups/..%2Fbanditbox.db", nil, nil); c != 404 {
		t.Fatalf("download outside = %d", c)
	}

	// Rename the profile, then restore the uploaded file: the old name is back.
	var doc map[string]any
	e.do("GET", "/api/v1/profiles/p_default/layout", nil, &doc)
	doc["profile"] = map[string]any{"name": "Renamed"}
	e.do("PUT", "/api/v1/profiles/p_default/layout", doc, nil)
	var out struct{ Profiles, Changed int }
	if c := e.do("POST", "/api/v1/backups/restore", json.RawMessage(file), &out); c != 200 || out.Changed != 1 {
		t.Fatalf("restore upload = %d %+v", c, out)
	}
	e.do("GET", "/api/v1/profiles/p_default/layout", nil, &doc)
	if doc["profile"].(map[string]any)["name"] != "Home" {
		t.Fatalf("after restore = %v", doc["profile"])
	}
	if c := e.do("POST", "/api/v1/backups/"+made.Name+"/restore", nil, &out); c != 200 || out.Changed != 0 {
		t.Fatalf("restore saved = %d %+v", c, out)
	}
	if c := e.do("POST", "/api/v1/backups/restore", map[string]any{"format": "nope"}, nil); c != 422 {
		t.Fatalf("restore junk = %d", c)
	}

	if c := e.do("POST", "/api/v1/profiles/p_default/push", nil, nil); c != 204 {
		t.Fatalf("push = %d", c)
	}
	if c := e.do("POST", "/api/v1/profiles/p_nope/push", nil, nil); c != 404 {
		t.Fatalf("push missing = %d", c)
	}
	if c := (&env{t: t, srv: e.srv, c: &http.Client{}}).do("GET", "/api/v1/backups", nil, nil); c != 401 {
		t.Fatalf("anonymous = %d", c)
	}
}
