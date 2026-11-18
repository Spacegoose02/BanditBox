package api

import (
	"encoding/json"
	"errors"
	"net/http"
	"regexp"
	"strconv"

	"github.com/spacegoose02/banditbox/server/internal/store"
)

var profileID = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

func (s *server) listProfiles(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	list, err := s.Store.ListProfiles(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, list)
}

type createProfileRequest struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	// CopyFrom is an existing profile whose layout the new one starts from.
	CopyFrom string `json:"copyFrom,omitempty"`
}

func (s *server) createProfile(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	var req createProfileRequest
	if !readJSON(w, r, &req) {
		return
	}
	if !profileID.MatchString(req.ID) {
		writeError(w, http.StatusBadRequest, "id must be 1 to 64 letters, digits, _ or -")
		return
	}
	doc := map[string]any{"schema": 1, "profile": map[string]any{}, "rows": []any{}}
	if req.CopyFrom != "" {
		src, err := s.Store.GetLayout(r.Context(), req.CopyFrom)
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusBadRequest, "copyFrom: no such profile")
			return
		}
		if err != nil {
			s.internal(w, r, err)
			return
		}
		if err := json.Unmarshal(src.Doc, &doc); err != nil {
			s.internal(w, r, err)
			return
		}
	}
	prof, _ := doc["profile"].(map[string]any)
	if prof == nil {
		prof = map[string]any{}
	}
	prof["name"] = req.Name
	// A copy starts unlocked and never signs in at boot on its own.
	delete(prof, "pin")
	delete(prof, "autoLogin")
	doc["profile"] = prof
	raw, _ := json.Marshal(doc)

	l, err := s.Store.PutLayout(r.Context(), req.ID, -1, raw)
	if s.layoutError(w, r, err) {
		return
	}
	writeLayout(w, http.StatusCreated, l)
}

func (s *server) getLayout(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	l, err := s.Store.GetLayout(r.Context(), r.PathValue("id"))
	if s.layoutError(w, r, err) {
		return
	}
	writeLayout(w, http.StatusOK, l)
}

// putLayout saves an edited layout. The document's own "version" is the version
// the editor started from; if the profile has moved on since, the reply is 409
// with the current document so the editor can merge.
func (s *server) putLayout(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	var doc json.RawMessage
	if !readJSON(w, r, &doc) {
		return
	}
	var head struct {
		Version *int64 `json:"version"`
	}
	if err := json.Unmarshal(doc, &head); err != nil || head.Version == nil || *head.Version < 0 {
		writeError(w, http.StatusBadRequest, "the document needs the version it was edited from")
		return
	}
	id := r.PathValue("id")
	l, err := s.Store.PutLayout(r.Context(), id, *head.Version, doc)
	if errors.Is(err, store.ErrVersionConflict) {
		cur, gerr := s.Store.GetLayout(r.Context(), id)
		if gerr != nil {
			s.internal(w, r, gerr)
			return
		}
		writeJSON(w, http.StatusConflict, struct {
			Error   string          `json:"error"`
			Current json.RawMessage `json:"current"`
		}{"the layout changed since you opened it", cur.Doc})
		return
	}
	if s.layoutError(w, r, err) {
		return
	}
	writeLayout(w, http.StatusOK, l)
}

func (s *server) layoutHistory(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	id := r.PathValue("id")
	if _, err := s.Store.GetLayout(r.Context(), id); s.layoutError(w, r, err) {
		return
	}
	hist, err := s.Store.LayoutHistory(r.Context(), id)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	docs := make([]json.RawMessage, len(hist))
	for i, h := range hist {
		docs[i] = h.Doc
	}
	writeJSON(w, http.StatusOK, docs)
}

func (s *server) deleteProfile(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	list, err := s.Store.ListProfiles(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if len(list) == 1 && list[0].ID == r.PathValue("id") {
		writeError(w, http.StatusConflict, "can't delete the last profile")
		return
	}
	if s.layoutError(w, r, s.Store.DeleteProfile(r.Context(), r.PathValue("id"))) {
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func writeLayout(w http.ResponseWriter, status int, l store.Layout) {
	w.Header().Set("ETag", strconv.Quote(strconv.FormatInt(l.Version, 10)))
	writeJSON(w, status, l.Doc)
}

func (s *server) layoutError(w http.ResponseWriter, r *http.Request, err error) bool {
	var ve *store.ValidationError
	switch {
	case err == nil:
		return false
	case errors.Is(err, store.ErrNotFound):
		writeError(w, http.StatusNotFound, "no such profile")
	case errors.Is(err, store.ErrVersionConflict):
		writeError(w, http.StatusConflict, "a profile with that id already exists")
	case errors.As(err, &ve):
		writeError(w, http.StatusUnprocessableEntity, ve.Error())
	default:
		s.internal(w, r, err)
	}
	return true
}
