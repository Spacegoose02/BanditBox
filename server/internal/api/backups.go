package api

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"

	"github.com/spacegoose02/banditbox/server/internal/backup"
	"github.com/spacegoose02/banditbox/server/internal/store"
)

// pushProfile makes the server's copy of a profile win over changes TVs haven't sent yet.
func (s *server) pushProfile(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	if err := s.Store.PushProfile(r.Context(), r.PathValue("id")); errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusNotFound, "no such profile")
		return
	} else if err != nil {
		s.internal(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *server) listBackups(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	list, err := s.Backups.List()
	if err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, list)
}

func (s *server) createBackup(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	e, err := s.Backups.Create(r.Context(), "manual")
	if err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, e)
}

func (s *server) downloadBackup(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	name := r.PathValue("name")
	p, err := s.Backups.Path(name)
	if err != nil {
		writeError(w, http.StatusNotFound, "no such backup")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Content-Disposition", "attachment; filename="+strconv.Quote(name))
	http.ServeFile(w, r, p)
}

func (s *server) restoreSaved(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	f, err := s.Backups.Read(r.PathValue("name"))
	if errors.Is(err, backup.ErrNotFound) {
		writeError(w, http.StatusNotFound, "no such backup")
		return
	}
	s.restore(w, r, f, err)
}

// restoreUpload restores a backup file sent in the body: one made here or on a TV.
func (s *server) restoreUpload(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	var raw json.RawMessage
	if !readJSON(w, r, &raw) {
		return
	}
	f, err := backup.Parse(raw)
	s.restore(w, r, f, err)
}

func (s *server) restore(w http.ResponseWriter, r *http.Request, f backup.File, err error) {
	var invalid *backup.InvalidError
	if errors.As(err, &invalid) {
		writeError(w, http.StatusUnprocessableEntity, invalid.Reason)
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	changed, listChanged, err := s.Backups.Restore(r.Context(), f)
	// Tell TVs about whatever was restored, even if a later step failed.
	for id, v := range changed {
		s.Hub.Publish(id, v)
	}
	if listChanged {
		s.Hub.ProfilesChanged()
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, struct {
		Profiles int `json:"profiles"`
		Changed  int `json:"changed"`
	}{len(f.Layouts), len(changed)})
}
