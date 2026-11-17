// Package api serves the BanditBox HTTP API under /api/v1.
package api

import (
	"encoding/json"
	"net/http"
)

// Info describes the running server. It is returned by the health endpoint.
type Info struct {
	Status  string `json:"status"`
	Version string `json:"version"`
}

// NewHandler returns the root HTTP handler for the API.
func NewHandler(version string) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/health", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, Info{Status: "ok", Version: version})
	})
	return mux
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
