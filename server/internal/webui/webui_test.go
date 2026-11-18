package webui

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

// The test build has no web UI copied in, so the handler explains that.
func TestWithoutBuild(t *testing.T) {
	rec := httptest.NewRecorder()
	Handler().ServeHTTP(rec, httptest.NewRequest("GET", "/devices", nil))
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status %d", rec.Code)
	}
}
