// Package api serves the BanditBox HTTP API under /api/v1.
package api

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"strings"

	"github.com/spacegoose02/banditbox/server/internal/auth"
	"github.com/spacegoose02/banditbox/server/internal/hub"
	"github.com/spacegoose02/banditbox/server/internal/pairing"
	"github.com/spacegoose02/banditbox/server/internal/store"
	"github.com/spacegoose02/banditbox/server/internal/webui"
)

// maxBody caps request bodies. Layout documents are a few KB.
const maxBody = 1 << 20

// Info describes the running server. It is returned by the health endpoint.
type Info struct {
	Status  string `json:"status"`
	Version string `json:"version"`
}

// Deps are what the API needs to run.
type Deps struct {
	Version string
	Store   *store.Store
	Auth    *auth.Auth
	Pairing *pairing.Manager
	Hub     *hub.Hub
	Log     *slog.Logger
}

type server struct {
	Deps
}

// NewHandler returns the root HTTP handler for the API.
func NewHandler(d Deps) http.Handler {
	if d.Log == nil {
		d.Log = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	if d.Hub == nil {
		d.Hub = hub.New()
	}
	s := &server{d}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/health", s.health)

	mux.HandleFunc("GET /api/v1/auth/status", s.authStatus)
	mux.HandleFunc("POST /api/v1/auth/setup", s.setup)
	mux.HandleFunc("POST /api/v1/auth/login", s.login)
	mux.HandleFunc("POST /api/v1/auth/logout", s.logout)
	mux.HandleFunc("POST /api/v1/auth/password", s.admin(s.changePassword))

	mux.HandleFunc("GET /api/v1/profiles", s.admin(s.listProfiles))
	mux.HandleFunc("POST /api/v1/profiles", s.admin(s.createProfile))
	mux.HandleFunc("GET /api/v1/profiles/{id}/layout", s.admin(s.getLayout))
	mux.HandleFunc("PUT /api/v1/profiles/{id}/layout", s.admin(s.putLayout))
	mux.HandleFunc("GET /api/v1/profiles/{id}/history", s.admin(s.layoutHistory))
	mux.HandleFunc("DELETE /api/v1/profiles/{id}", s.admin(s.deleteProfile))

	mux.HandleFunc("POST /api/v1/pair/start", s.pairStart)
	mux.HandleFunc("GET /api/v1/pair/{pairId}", s.pairWait)
	mux.HandleFunc("GET /api/v1/pair/pending", s.admin(s.pairPending))
	mux.HandleFunc("POST /api/v1/pair/approve", s.admin(s.pairApprove))
	mux.HandleFunc("POST /api/v1/pair/deny", s.admin(s.pairDeny))

	mux.HandleFunc("GET /api/v1/devices", s.admin(s.listDevices))
	mux.HandleFunc("PATCH /api/v1/devices/{id}", s.admin(s.updateDevice))
	mux.HandleFunc("DELETE /api/v1/devices/{id}", s.admin(s.revokeDevice))
	mux.HandleFunc("GET /api/v1/apps", s.admin(s.listApps))

	mux.HandleFunc("GET /api/v1/device/me", s.device(s.deviceMe))
	mux.HandleFunc("PUT /api/v1/device/apps", s.device(s.deviceApps))
	mux.HandleFunc("GET /api/v1/sync/layout", s.device(s.syncLayout))
	mux.HandleFunc("POST /api/v1/sync/ops", s.device(s.syncOps))
	mux.HandleFunc("GET /api/v1/sync/stream", s.device(s.syncStream))
	mux.HandleFunc("POST /api/v1/sync/seed", s.device(s.syncSeed))
	mux.HandleFunc("POST /api/v1/sync/profiles", s.device(s.syncCreateProfile))
	mux.HandleFunc("DELETE /api/v1/sync/profiles/{id}", s.device(s.syncDeleteProfile))

	// Everything outside /api/ is the web UI.
	ui := webui.Handler()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/") {
			writeError(w, http.StatusNotFound, "no such endpoint")
			return
		}
		ui.ServeHTTP(w, r)
	})
	return sameOrigin(mux)
}

func (s *server) health(w http.ResponseWriter, r *http.Request) {
	if err := s.Store.Ping(r.Context()); err != nil {
		writeJSON(w, http.StatusServiceUnavailable, Info{Status: "database unavailable", Version: s.Version})
		return
	}
	writeJSON(w, http.StatusOK, Info{Status: "ok", Version: s.Version})
}

// sameOrigin refuses state-changing requests sent by another site's page. Together
// with SameSite=Strict cookies this blocks cross-site request forgery.
func sameOrigin(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodGet, http.MethodHead, http.MethodOptions:
		default:
			if origin := r.Header.Get("Origin"); origin != "" {
				u, err := url.Parse(origin)
				if err != nil || !strings.EqualFold(u.Host, r.Host) {
					writeError(w, http.StatusForbidden, "cross-site request refused")
					return
				}
			}
		}
		next.ServeHTTP(w, r)
	})
}

// clientKey identifies a client for the sign-in limit.
func clientKey(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

// secure reports whether the browser reached us over HTTPS.
func secure(r *http.Request) bool {
	return r.TLS != nil || strings.EqualFold(r.Header.Get("X-Forwarded-Proto"), "https")
}

// readJSON decodes a JSON request body into v, rejecting unknown fields.
func readJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	if ct := r.Header.Get("Content-Type"); !strings.HasPrefix(ct, "application/json") {
		writeError(w, http.StatusUnsupportedMediaType, "send JSON with Content-Type: application/json")
		return false
	}
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxBody))
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			writeError(w, http.StatusRequestEntityTooLarge, "request body is too large")
		} else {
			writeError(w, http.StatusBadRequest, "bad JSON: "+err.Error())
		}
		return false
	}
	return true
}

type apiError struct {
	Error string `json:"error"`
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, apiError{Error: msg})
}

func (s *server) internal(w http.ResponseWriter, r *http.Request, err error) {
	s.Log.Error("request failed", "method", r.Method, "path", r.URL.Path, "err", err)
	writeError(w, http.StatusInternalServerError, "internal error")
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
