package api

import (
	"errors"
	"net/http"

	"github.com/spacegoose02/banditbox/server/internal/auth"
	"github.com/spacegoose02/banditbox/server/internal/store"
)

type adminHandler func(w http.ResponseWriter, r *http.Request, admin store.Admin)

// admin wraps a handler that needs a signed-in admin.
func (s *server) admin(h adminHandler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		c, err := r.Cookie(auth.CookieName)
		if err != nil {
			writeError(w, http.StatusUnauthorized, "sign in first")
			return
		}
		a, err := s.Auth.Session(r.Context(), c.Value)
		if errors.Is(err, store.ErrNotFound) {
			auth.ClearCookie(w, secure(r))
			writeError(w, http.StatusUnauthorized, "sign in first")
			return
		}
		if err != nil {
			s.internal(w, r, err)
			return
		}
		h(w, r, a)
	}
}

type authStatus struct {
	SetupNeeded bool         `json:"setupNeeded"`
	Admin       *store.Admin `json:"admin"`
}

func (s *server) authStatus(w http.ResponseWriter, r *http.Request) {
	need, err := s.Auth.SetupNeeded(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	st := authStatus{SetupNeeded: need}
	if c, err := r.Cookie(auth.CookieName); err == nil {
		if a, err := s.Auth.Session(r.Context(), c.Value); err == nil {
			st.Admin = &a
		}
	}
	writeJSON(w, http.StatusOK, st)
}

type setupRequest struct {
	Code     string `json:"code"`
	Username string `json:"username"`
	Password string `json:"password"`
}

func (s *server) setup(w http.ResponseWriter, r *http.Request) {
	var req setupRequest
	if !readJSON(w, r, &req) {
		return
	}
	token, a, err := s.Auth.Setup(r.Context(), clientKey(r), req.Code, req.Username, req.Password)
	if s.authError(w, r, err) {
		return
	}
	s.Log.Info("admin created", "username", a.Username)
	auth.SetCookie(w, token, secure(r))
	writeJSON(w, http.StatusCreated, authStatus{Admin: &a})
}

type loginRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

func (s *server) login(w http.ResponseWriter, r *http.Request) {
	var req loginRequest
	if !readJSON(w, r, &req) {
		return
	}
	token, a, err := s.Auth.Login(r.Context(), clientKey(r), req.Username, req.Password)
	if s.authError(w, r, err) {
		return
	}
	auth.SetCookie(w, token, secure(r))
	writeJSON(w, http.StatusOK, authStatus{Admin: &a})
}

func (s *server) logout(w http.ResponseWriter, r *http.Request) {
	if c, err := r.Cookie(auth.CookieName); err == nil {
		if err := s.Auth.Logout(r.Context(), c.Value); err != nil {
			s.internal(w, r, err)
			return
		}
	}
	auth.ClearCookie(w, secure(r))
	w.WriteHeader(http.StatusNoContent)
}

type passwordRequest struct {
	Current string `json:"current"`
	New     string `json:"new"`
}

func (s *server) changePassword(w http.ResponseWriter, r *http.Request, a store.Admin) {
	var req passwordRequest
	if !readJSON(w, r, &req) {
		return
	}
	token, err := s.Auth.ChangePassword(r.Context(), a, req.Current, req.New)
	if errors.Is(err, auth.ErrBadCredentials) {
		writeError(w, http.StatusForbidden, "current password is wrong")
		return
	}
	if s.authError(w, r, err) {
		return
	}
	auth.SetCookie(w, token, secure(r))
	w.WriteHeader(http.StatusNoContent)
}

// authError writes the response for an auth error and reports whether there was one.
func (s *server) authError(w http.ResponseWriter, r *http.Request, err error) bool {
	var ve auth.ValidationError
	switch {
	case err == nil:
		return false
	case errors.Is(err, auth.ErrBadCredentials), errors.Is(err, auth.ErrBadSetupCode):
		writeError(w, http.StatusUnauthorized, err.Error())
	case errors.Is(err, auth.ErrTooManyAttempts):
		w.Header().Set("Retry-After", "900")
		writeError(w, http.StatusTooManyRequests, err.Error())
	case errors.Is(err, auth.ErrSetupDone):
		writeError(w, http.StatusConflict, err.Error())
	case errors.As(err, &ve):
		writeError(w, http.StatusBadRequest, ve.Error())
	default:
		s.internal(w, r, err)
	}
	return true
}
