package api

import (
	"net/http"
	"strings"

	"github.com/spacegoose02/banditbox/server/internal/homeassistant"
	"github.com/spacegoose02/banditbox/server/internal/store"
)

// haView is the config as the web UI sees it: the password is never sent back.
type haView struct {
	homeassistant.Config
	HasPassword bool                 `json:"hasPassword"`
	Status      homeassistant.Status `json:"status"`
}

func (s *server) haView(r *http.Request) (haView, error) {
	c, err := s.HomeAssistant.Load(r.Context())
	if err != nil {
		return haView{}, err
	}
	v := haView{Config: c, HasPassword: c.Password != "", Status: s.HomeAssistant.Status()}
	v.Password = ""
	return v, nil
}

func (s *server) getHomeAssistant(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	v, err := s.haView(r)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, v)
}

// haBody is what the web UI sends. An empty password keeps the saved one unless
// clearPassword is set.
type haBody struct {
	homeassistant.Config
	ClearPassword bool `json:"clearPassword"`
}

// withSaved fills in the saved password when the body leaves it empty.
func (s *server) withSaved(r *http.Request, b haBody) (homeassistant.Config, error) {
	c := b.Config
	c.Broker = strings.TrimSpace(c.Broker)
	c.Webhook = strings.TrimSpace(c.Webhook)
	c.Username = strings.TrimSpace(c.Username)
	c.Prefix = strings.TrimSpace(c.Prefix)
	c.Discovery = strings.TrimSpace(c.Discovery)
	if c.Password == "" && !b.ClearPassword {
		saved, err := s.HomeAssistant.Load(r.Context())
		if err != nil {
			return c, err
		}
		c.Password = saved.Password
	}
	return c, nil
}

func (s *server) putHomeAssistant(w http.ResponseWriter, r *http.Request, a store.Admin) {
	var b haBody
	if !readJSON(w, r, &b) {
		return
	}
	c, err := s.withSaved(r, b)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if err := c.Check(); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	if err := s.HomeAssistant.Save(r.Context(), c); err != nil {
		s.internal(w, r, err)
		return
	}
	s.Log.Info("home assistant settings saved", "by", a.Username)
	v, err := s.haView(r)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, v)
}

func (s *server) testHomeAssistant(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	var b haBody
	if !readJSON(w, r, &b) {
		return
	}
	c, err := s.withSaved(r, b)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if err := s.HomeAssistant.Test(r.Context(), c); err != nil {
		writeError(w, http.StatusBadGateway, err.Error())
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// deviceActivity takes a TV's report of what it is doing.
func (s *server) deviceActivity(w http.ResponseWriter, r *http.Request, d store.Device) {
	var st homeassistant.State
	if !readJSON(w, r, &st) {
		return
	}
	if len(st.App) > 255 || len(st.AppLabel) > 255 {
		writeError(w, http.StatusBadRequest, "app names are too long")
		return
	}
	s.HomeAssistant.Report(r.Context(), d, st)
	w.WriteHeader(http.StatusNoContent)
}
