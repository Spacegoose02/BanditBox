package api

import (
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"github.com/spacegoose02/banditbox/server/internal/store"
	"github.com/spacegoose02/banditbox/server/internal/widgets"
)

var connectorID = regexp.MustCompile(`^[a-z0-9_-]{1,32}$`)

// connectorView is a connector as the web UI sees it: no secret, plus how it is doing.
type connectorView struct {
	store.Connector
	HasKey bool            `json:"hasKey"`
	Status *widgets.Status `json:"status,omitempty"`
}

type connectorRequest struct {
	ID       string            `json:"id"`
	Kind     string            `json:"kind"`
	Name     string            `json:"name"`
	Enabled  *bool             `json:"enabled"`
	Settings map[string]string `json:"settings"`
}

func (s *server) connectorKinds(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	writeJSON(w, http.StatusOK, widgets.Kinds())
}

func (s *server) listConnectors(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	list, err := s.Store.ListConnectors(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	status := s.Widgets.Status()
	out := make([]connectorView, len(list))
	for i, c := range list {
		out[i] = connectorView{Connector: c, HasKey: c.Secret != ""}
		if st, ok := status[c.ID]; ok {
			out[i].Status = &st
		}
	}
	writeJSON(w, http.StatusOK, out)
}

// createConnector adds a connector: POST /api/v1/connectors. The id defaults to the
// kind, so the first Weather Scout's feed is weatherscout.now.
func (s *server) createConnector(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	var req connectorRequest
	if !readJSON(w, r, &req) {
		return
	}
	k, ok := widgets.KindByID(req.Kind)
	if !ok {
		writeError(w, http.StatusBadRequest, "unknown kind")
		return
	}
	settings, secret, err := widgets.CleanSettings(k, req.Settings, false)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	c := store.Connector{Kind: k.ID, Name: cleanName(req.Name, k.Name), Enabled: req.Enabled == nil || *req.Enabled, Settings: settings, Secret: secret}
	ids := []string{req.ID}
	if req.ID == "" {
		ids = []string{k.ID}
		for i := 2; i <= 9; i++ {
			ids = append(ids, fmt.Sprintf("%s%d", k.ID, i))
		}
	}
	for _, id := range ids {
		if !connectorID.MatchString(id) {
			writeError(w, http.StatusBadRequest, "the id may use only a-z, 0-9, - and _ (up to 32)")
			return
		}
		c.ID = id
		saved, err := s.Store.AddConnector(r.Context(), c)
		if errors.Is(err, store.ErrExists) {
			continue
		}
		if err != nil {
			s.internal(w, r, err)
			return
		}
		s.Widgets.Reload(saved.ID)
		writeJSON(w, http.StatusCreated, connectorView{Connector: saved, HasKey: saved.Secret != ""})
		return
	}
	writeError(w, http.StatusConflict, "that id is taken")
}

// updateConnector changes a connector: PUT /api/v1/connectors/{id}. An empty key keeps the stored one.
func (s *server) updateConnector(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	c, err := s.Store.GetConnector(r.Context(), r.PathValue("id"))
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusNotFound, "no such connector")
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	var req connectorRequest
	if !readJSON(w, r, &req) {
		return
	}
	k, ok := widgets.KindByID(c.Kind)
	if !ok {
		writeError(w, http.StatusBadRequest, "unknown kind")
		return
	}
	if req.Settings != nil {
		settings, secret, err := widgets.CleanSettings(k, req.Settings, c.Secret != "")
		if err != nil {
			writeError(w, http.StatusBadRequest, err.Error())
			return
		}
		c.Settings = settings
		if secret != "" {
			c.Secret = secret
		}
	}
	if req.Name != "" {
		c.Name = cleanName(req.Name, k.Name)
	}
	if req.Enabled != nil {
		c.Enabled = *req.Enabled
	}
	if err := s.Store.UpdateConnector(r.Context(), c); err != nil {
		s.internal(w, r, err)
		return
	}
	s.Widgets.Reload(c.ID)
	writeJSON(w, http.StatusOK, connectorView{Connector: c, HasKey: c.Secret != ""})
}

func (s *server) deleteConnector(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	id := r.PathValue("id")
	err := s.Store.DeleteConnector(r.Context(), id)
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusNotFound, "no such connector")
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	s.Widgets.Reload(id)
	w.WriteHeader(http.StatusNoContent)
}

func (s *server) refreshConnector(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	if _, err := s.Store.GetConnector(r.Context(), r.PathValue("id")); err != nil {
		writeError(w, http.StatusNotFound, "no such connector")
		return
	}
	s.Widgets.Reload(r.PathValue("id"))
	w.WriteHeader(http.StatusAccepted)
}

// testConnector fetches once with the given settings and returns the feeds:
// POST /api/v1/connectors/test. With an id and no key, the stored key is used.
func (s *server) testConnector(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	var req connectorRequest
	if !readJSON(w, r, &req) {
		return
	}
	var stored store.Connector
	if req.ID != "" {
		c, err := s.Store.GetConnector(r.Context(), req.ID)
		if err == nil {
			stored = c
			req.Kind = c.Kind
		}
	}
	k, ok := widgets.KindByID(req.Kind)
	if !ok {
		writeError(w, http.StatusBadRequest, "unknown kind")
		return
	}
	settings, secret, err := widgets.CleanSettings(k, req.Settings, stored.Secret != "")
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	if secret == "" {
		secret = stored.Secret
	}
	id := req.ID
	if id == "" || !connectorID.MatchString(id) {
		id = k.ID
	}
	feeds, err := s.Widgets.Test(r.Context(), store.Connector{ID: id, Kind: k.ID, Name: cleanName(req.Name, k.Name), Settings: settings, Secret: secret})
	if err != nil {
		writeError(w, http.StatusUnprocessableEntity, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"feeds": feeds})
}

func cleanName(name, fallback string) string {
	name = strings.TrimSpace(name)
	if name == "" {
		return fallback
	}
	if r := []rune(name); len(r) > 60 {
		name = string(r[:60])
	}
	return name
}

type widgetsResponse struct {
	Version int64          `json:"version"`
	Feeds   []widgets.Feed `json:"feeds"`
}

// deviceWidgets hands a TV every widget feed: GET /api/v1/widgets.
func (s *server) deviceWidgets(w http.ResponseWriter, r *http.Request, _ store.Device) {
	v, feeds := s.Widgets.Feeds()
	writeJSON(w, http.StatusOK, widgetsResponse{Version: v, Feeds: feeds})
}

// adminWidgets is the same for the web UI's layout editor.
func (s *server) adminWidgets(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	v, feeds := s.Widgets.Feeds()
	writeJSON(w, http.StatusOK, widgetsResponse{Version: v, Feeds: feeds})
}

var shaHex = regexp.MustCompile(`^[0-9a-f]{64}$`)

// deviceMedia serves a picture stored on the server: GET /api/v1/media/{sha256}.
func (s *server) deviceMedia(w http.ResponseWriter, r *http.Request, _ store.Device) {
	sha := r.PathValue("sha")
	if !shaHex.MatchString(sha) || s.MediaDir == "" {
		writeError(w, http.StatusNotFound, "no such picture")
		return
	}
	f, err := os.Open(filepath.Join(s.MediaDir, sha))
	if err != nil {
		writeError(w, http.StatusNotFound, "no such picture")
		return
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		s.internal(w, r, err)
		return
	}
	// Named by its content, so it never changes.
	w.Header().Set("Cache-Control", "private, max-age=31536000, immutable")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	http.ServeContent(w, r, "", info.ModTime(), f)
}
