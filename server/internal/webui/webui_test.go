package webui

import (
	"io/fs"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func built() bool {
	_, err := fs.Stat(dist, "dist/index.html")
	return err == nil
}

func TestServesUIOrExplains(t *testing.T) {
	rec := httptest.NewRecorder()
	Handler().ServeHTTP(rec, httptest.NewRequest("GET", "/devices", nil))
	if built() {
		// Any app route falls back to index.html.
		if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), "<html") {
			t.Fatalf("status %d", rec.Code)
		}
	} else if rec.Code != http.StatusNotFound {
		t.Fatalf("status %d", rec.Code)
	}
}

func TestRejectsWrites(t *testing.T) {
	if !built() {
		t.Skip("no web build")
	}
	rec := httptest.NewRecorder()
	Handler().ServeHTTP(rec, httptest.NewRequest("POST", "/devices", nil))
	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status %d", rec.Code)
	}
}
