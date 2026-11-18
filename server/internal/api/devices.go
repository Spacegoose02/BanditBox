package api

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/spacegoose02/banditbox/server/internal/pairing"
	"github.com/spacegoose02/banditbox/server/internal/store"
)

// maxPairWait caps a pairing long-poll.
const maxPairWait = 30 * time.Second

type deviceHandler func(w http.ResponseWriter, r *http.Request, d store.Device)

// device wraps a handler called by a paired TV with "Authorization: Bearer <token>".
// A TV may send its app version in X-BanditBox-App so the dashboard can show it.
func (s *server) device(h deviceHandler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		if !ok || token == "" {
			writeError(w, http.StatusUnauthorized, "device token needed")
			return
		}
		d, err := s.Store.DeviceByToken(r.Context(), pairing.HashToken(token))
		if errors.Is(err, store.ErrNotFound) {
			// Revoked or never paired: the TV keeps its layout and shows "not synced".
			writeError(w, http.StatusUnauthorized, "this TV is no longer paired")
			return
		}
		if err != nil {
			s.internal(w, r, err)
			return
		}
		app := r.Header.Get("X-BanditBox-App")
		if len(app) > 40 {
			app = app[:40]
		}
		if time.Since(d.LastSeen) > time.Minute || (app != "" && app != d.AppVersion) {
			if err := s.Store.TouchDevice(r.Context(), d.ID, app); err != nil {
				s.Log.Warn("touch device", "device", d.ID, "err", err)
			}
		}
		h(w, r, d)
	}
}

type pairStartRequest struct {
	Name  string `json:"name"`
	Model string `json:"model"`
}

type pairStartResponse struct {
	PairID    string    `json:"pairId"`
	Code      string    `json:"code"`
	QRURL     string    `json:"qrUrl"`
	ExpiresAt time.Time `json:"expiresAt"`
}

func (s *server) pairStart(w http.ResponseWriter, r *http.Request) {
	var req pairStartRequest
	if !readJSON(w, r, &req) {
		return
	}
	id, p, err := s.Pairing.Start(clientKey(r), req.Name, req.Model)
	if errors.Is(err, pairing.ErrTooMany) {
		writeError(w, http.StatusTooManyRequests, err.Error())
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	s.Log.Info("pairing started", "name", p.Name, "model", p.Model, "client", clientKey(r))
	writeJSON(w, http.StatusCreated, pairStartResponse{
		PairID:    id,
		Code:      p.Code,
		QRURL:     baseURL(r) + "/devices?pair=" + p.Code,
		ExpiresAt: p.Expires,
	})
}

// pairWait is long-polled by the TV: ?wait=<seconds> (default and max 30).
func (s *server) pairWait(w http.ResponseWriter, r *http.Request) {
	wait := maxPairWait
	if v := r.URL.Query().Get("wait"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 0 {
			writeError(w, http.StatusBadRequest, "wait must be a number of seconds")
			return
		}
		wait = min(time.Duration(n)*time.Second, maxPairWait)
	}
	res, err := s.Pairing.Wait(r.Context(), r.PathValue("pairId"), wait)
	switch {
	case errors.Is(err, pairing.ErrNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	case errors.Is(err, context.Canceled):
		// The TV went away.
	case err != nil:
		s.internal(w, r, err)
	default:
		writeJSON(w, http.StatusOK, res)
	}
}

func (s *server) pairPending(w http.ResponseWriter, _ *http.Request, _ store.Admin) {
	writeJSON(w, http.StatusOK, s.Pairing.List())
}

type pairApproveRequest struct {
	Code     string   `json:"code"`
	Name     string   `json:"name"`
	Profiles []string `json:"profiles"`
}

func (s *server) pairApprove(w http.ResponseWriter, r *http.Request, a store.Admin) {
	var req pairApproveRequest
	if !readJSON(w, r, &req) {
		return
	}
	if !s.checkProfiles(w, r, req.Profiles) {
		return
	}
	d, err := s.Pairing.Approve(r.Context(), req.Code, req.Name, req.Profiles)
	if errors.Is(err, pairing.ErrNotFound) {
		writeError(w, http.StatusNotFound, "no TV is showing that code, or it expired")
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	s.Log.Info("device paired", "device", d.ID, "name", d.Name, "by", a.Username)
	writeJSON(w, http.StatusCreated, d)
}

type pairDenyRequest struct {
	Code string `json:"code"`
}

func (s *server) pairDeny(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	var req pairDenyRequest
	if !readJSON(w, r, &req) {
		return
	}
	if err := s.Pairing.Deny(req.Code); err != nil {
		writeError(w, http.StatusNotFound, "no TV is showing that code, or it expired")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *server) listDevices(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	list, err := s.Store.ListDevices(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, list)
}

type updateDeviceRequest struct {
	Name     *string   `json:"name"`
	Profiles *[]string `json:"profiles"`
}

func (s *server) updateDevice(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	var req updateDeviceRequest
	if !readJSON(w, r, &req) {
		return
	}
	d, err := s.Store.GetDevice(r.Context(), r.PathValue("id"))
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusNotFound, "no such device")
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if req.Name != nil {
		n := strings.TrimSpace(*req.Name)
		if n == "" || len([]rune(n)) > 60 {
			writeError(w, http.StatusBadRequest, "name must be 1 to 60 characters")
			return
		}
		d.Name = n
	}
	if req.Profiles != nil {
		if !s.checkProfiles(w, r, *req.Profiles) {
			return
		}
		d.Profiles = *req.Profiles
	}
	if err := s.Store.UpdateDevice(r.Context(), d.ID, d.Name, d.Profiles); err != nil {
		s.internal(w, r, err)
		return
	}
	d, err = s.Store.GetDevice(r.Context(), d.ID)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if req.Profiles != nil {
		s.Hub.ProfilesChanged()
	}
	writeJSON(w, http.StatusOK, d)
}

func (s *server) revokeDevice(w http.ResponseWriter, r *http.Request, a store.Admin) {
	err := s.Store.DeleteDevice(r.Context(), r.PathValue("id"))
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusNotFound, "no such device")
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	s.Hub.Kick(r.PathValue("id"))
	s.Log.Info("device revoked", "device", r.PathValue("id"), "by", a.Username)
	w.WriteHeader(http.StatusNoContent)
}

// deviceMe tells a TV who it is and which profiles it may sync.
func (s *server) deviceMe(w http.ResponseWriter, r *http.Request, d store.Device) {
	list, err := s.Store.ListProfiles(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	allowed := []store.ProfileSummary{}
	for _, p := range list {
		if d.AllowsProfile(p.ID) {
			allowed = append(allowed, p)
		}
	}
	fresh, err := s.Store.Fresh(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, struct {
		Device   store.Device           `json:"device"`
		Profiles []store.ProfileSummary `json:"profiles"`
		Server   Info                   `json:"server"`
		// Fresh is true while no layout has been edited: the TV may seed the server.
		Fresh bool `json:"fresh"`
	}{d, allowed, Info{Status: "ok", Version: s.Version}, fresh})
}

// checkProfiles makes sure every id names an existing profile.
func (s *server) checkProfiles(w http.ResponseWriter, r *http.Request, ids []string) bool {
	for _, id := range ids {
		if _, err := s.Store.GetLayout(r.Context(), id); errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusBadRequest, "no such profile: "+id)
			return false
		} else if err != nil {
			s.internal(w, r, err)
			return false
		}
	}
	return true
}

// baseURL is how the caller reached this server, for links shown on the TV.
func baseURL(r *http.Request) string {
	scheme := "http"
	if secure(r) {
		scheme = "https"
	}
	return scheme + "://" + r.Host
}
