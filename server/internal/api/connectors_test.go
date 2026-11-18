package api

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/spacegoose02/banditbox/server/internal/widgets"
)

func TestConnectors(t *testing.T) {
	ws := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer wsk_good" {
			http.Error(w, "no", http.StatusUnauthorized)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"location":{"name":"Home"},"units":{"temp":"°C"},"current":{"temp":4,"icon":"snow","label":"Snow"}}`))
	}))
	defer ws.Close()

	e := newEnv(t)
	if c := e.do("GET", "/api/v1/connectors", nil, nil); c != 401 {
		t.Fatalf("list signed out = %d", c)
	}
	e.signIn()

	var kinds []widgets.Kind
	if c := e.do("GET", "/api/v1/connectors/kinds", nil, &kinds); c != 200 || len(kinds) < 4 || kinds[0].ID != "weatherscout" {
		t.Fatalf("kinds = %d %+v", c, kinds)
	}

	// Test before saving: a wrong key says so, the right one shows the feed.
	body := map[string]any{"kind": "weatherscout", "settings": map[string]string{"url": ws.URL, "key": "wsk_bad"}}
	var fail apiError
	if c := e.do("POST", "/api/v1/connectors/test", body, &fail); c != 422 || fail.Error == "" {
		t.Fatalf("test with a bad key = %d %+v", c, fail)
	}
	body["settings"] = map[string]string{"url": ws.URL, "key": "wsk_good"}
	var tested struct{ Feeds []widgets.Feed }
	if c := e.do("POST", "/api/v1/connectors/test", body, &tested); c != 200 || len(tested.Feeds) != 1 || tested.Feeds[0].Cards[0].Weather.Unit != "°C" {
		t.Fatalf("test = %d %+v", c, tested)
	}

	if c := e.do("POST", "/api/v1/connectors", map[string]any{"kind": "weatherscout", "settings": map[string]string{"url": ws.URL}}, nil); c != 400 {
		t.Fatalf("create without key = %d", c)
	}
	if c := e.do("POST", "/api/v1/connectors", map[string]any{"kind": "weatherscout", "settings": map[string]string{"url": "ftp://x", "key": "k"}}, nil); c != 400 {
		t.Fatalf("create with a bad address = %d", c)
	}
	if c := e.do("POST", "/api/v1/connectors", map[string]any{"kind": "weatherscout", "settings": map[string]string{"url": ws.URL, "key": "k", "evil": "x"}}, nil); c != 400 {
		t.Fatalf("create with an unknown setting = %d", c)
	}
	var made connectorView
	if c := e.do("POST", "/api/v1/connectors", body, &made); c != 201 || made.ID != "weatherscout" || !made.HasKey || made.Name != "Weather Scout" {
		t.Fatalf("create = %d %+v", c, made)
	}
	var second connectorView
	if c := e.do("POST", "/api/v1/connectors", body, &second); c != 201 || second.ID != "weatherscout2" {
		t.Fatalf("second = %d %+v", c, second)
	}

	// The key never comes back, and an empty key keeps it.
	var list []map[string]any
	e.do("GET", "/api/v1/connectors", nil, &list)
	if len(list) != 2 || list[0]["hasKey"] != true {
		t.Fatalf("list = %+v", list)
	}
	if _, ok := list[0]["secret"]; ok {
		t.Fatal("secret sent to the web UI")
	}
	if s := list[0]["settings"].(map[string]any); s["key"] != nil {
		t.Fatal("key kept in settings")
	}
	off := false
	upd := map[string]any{"name": "Rocky", "enabled": off, "settings": map[string]string{"url": ws.URL, "key": ""}}
	var updated connectorView
	if c := e.do("PUT", "/api/v1/connectors/weatherscout", upd, &updated); c != 200 || !updated.HasKey || updated.Enabled || updated.Name != "Rocky" {
		t.Fatalf("update = %d %+v", c, updated)
	}
	// Test with the saved key.
	if c := e.do("POST", "/api/v1/connectors/test", map[string]any{"id": "weatherscout", "settings": map[string]string{"url": ws.URL}}, nil); c != 200 {
		t.Fatalf("test with the stored key = %d", c)
	}
	if c := e.do("DELETE", "/api/v1/connectors/weatherscout2", nil, nil); c != 204 {
		t.Fatalf("delete = %d", c)
	}
	if c := e.do("DELETE", "/api/v1/connectors/weatherscout2", nil, nil); c != 404 {
		t.Fatalf("delete again = %d", c)
	}

	// TVs read the feeds, and only with a token.
	token, _ := e.pairTV(nil)
	var feeds widgetsResponse
	if c := e.tvRequest("GET", "/api/v1/widgets", token, nil, &feeds); c != 200 || feeds.Version == 0 || feeds.Feeds == nil {
		t.Fatalf("widgets = %d %+v", c, feeds)
	}
	if c := e.tvRequest("GET", "/api/v1/widgets", "nope", nil, nil); c != 401 {
		t.Fatalf("widgets without a real token = %d", c)
	}
	if c := e.tvRequest("GET", "/api/v1/media/"+"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", token, nil, nil); c != 404 {
		t.Fatalf("missing picture = %d", c)
	}
	if c := e.tvRequest("GET", "/api/v1/media/..%2F..%2Fbanditbox.db", token, nil, nil); c != 404 {
		t.Fatalf("path trick = %d", c)
	}
}
