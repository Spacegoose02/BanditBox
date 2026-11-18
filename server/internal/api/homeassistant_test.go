package api

import (
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
)

func TestHomeAssistant(t *testing.T) {
	var posts atomic.Int32
	hook := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { posts.Add(1) }))
	defer hook.Close()

	e := newEnv(t)
	if c := e.do("GET", "/api/v1/homeassistant", nil, nil); c != 401 {
		t.Fatalf("signed out = %d", c)
	}
	e.signIn()
	var v haView
	if c := e.do("GET", "/api/v1/homeassistant", nil, &v); c != 200 || v.Prefix != "banditbox" || v.Discovery != "homeassistant" {
		t.Fatalf("get = %d %+v", c, v)
	}
	if c := e.do("PUT", "/api/v1/homeassistant", map[string]any{"broker": "10.0.0.2"}, nil); c != 400 {
		t.Fatalf("bad broker = %d", c)
	}
	body := map[string]any{"webhook": hook.URL, "username": "ha", "password": "secret"}
	if c := e.do("PUT", "/api/v1/homeassistant", body, &v); c != 200 || !v.HasPassword || v.Password != "" || v.Webhook != hook.URL {
		t.Fatalf("put = %d %+v", c, v)
	}
	// Saving again without the password keeps it.
	if c := e.do("PUT", "/api/v1/homeassistant", map[string]any{"webhook": hook.URL, "username": "ha"}, &v); c != 200 || !v.HasPassword {
		t.Fatalf("keep password = %d %+v", c, v)
	}
	if c := e.do("POST", "/api/v1/homeassistant/test", map[string]any{"webhook": hook.URL}, nil); c != 204 || posts.Load() != 1 {
		t.Fatalf("test = %d, %d posts", c, posts.Load())
	}

	token, _ := e.pairTV(nil)
	if c := e.tvPost("/api/v1/device/activity", token, map[string]any{"app": "com.plexapp.android", "appLabel": "Plex", "playing": true, "screenOn": true}, nil); c != 204 {
		t.Fatalf("activity = %d", c)
	}
	if c := e.do("GET", "/api/v1/homeassistant", nil, &v); c != 200 || len(v.Status.TVs) != 1 || !v.Status.TVs[0].State.Playing {
		t.Fatalf("status = %d %+v", c, v.Status)
	}
	if c := e.tvPost("/api/v1/device/activity", "bad", map[string]any{"app": "x"}, nil); c != 401 {
		t.Fatalf("activity with a bad token = %d", c)
	}
}
