package widgets

import (
	"context"
	"errors"
	"net/url"
	"strings"
	"time"

	"github.com/spacegoose02/banditbox/server/internal/store"
)

// weatherScout reads Weather Scout's connected-apps summary
// (GET /api/connect/summary with a wsk_ key, see its docs/CONNECT.md).
var weatherScout = Kind{
	ID:          "weatherscout",
	Name:        "Weather Scout",
	Description: "Current weather and today's forecast next to the clock. Make a key in Weather Scout under Family & settings, Connected apps.",
	Fields: []Field{
		{Key: "url", Label: "Weather Scout address", Placeholder: "http://192.168.1.50:8080", Required: true},
		{Key: SecretKey, Label: "App key", Placeholder: "wsk_…", Required: true, Secret: true},
		{Key: "lat", Label: "Latitude", Help: "Leave empty for Weather Scout's home location."},
		{Key: "lon", Label: "Longitude"},
		{Key: "units", Label: "Units", Help: "imperial or metric. Empty uses Weather Scout's setting.", Placeholder: "imperial"},
	},
	Feeds: []FeedInfo{{Suffix: "now", Name: "Weather now", Template: TemplateWeather}},
	Every: 10 * time.Minute,
	Fetch: fetchWeatherScout,
}

type wsSummary struct {
	Location struct {
		Name string `json:"name"`
	} `json:"location"`
	Units struct {
		Temp string `json:"temp"`
	} `json:"units"`
	Current *struct {
		Temp     float64 `json:"temp"`
		Icon     string  `json:"icon"`
		Label    string  `json:"label"`
		KidLabel string  `json:"kidLabel"`
		IsDay    *bool   `json:"isDay"`
	} `json:"current"`
	Today *struct {
		High         *float64 `json:"high"`
		Low          *float64 `json:"low"`
		PrecipChance *int     `json:"precipChance"`
		KidLabel     string   `json:"kidLabel"`
	} `json:"today"`
	Nowcast *struct {
		Short string `json:"short"`
	} `json:"nowcast"`
	Alerts []struct {
		Event string `json:"event"`
	} `json:"alerts"`
}

func fetchWeatherScout(ctx context.Context, f *Fetcher, c store.Connector) (Result, error) {
	base, err := baseURL(c.Settings["url"])
	if err != nil {
		return Result{}, err
	}
	q := url.Values{}
	for _, k := range []string{"lat", "lon", "units"} {
		if v := strings.TrimSpace(c.Settings[k]); v != "" {
			q.Set(k, v)
		}
	}
	u := base + "/api/connect/summary"
	if len(q) > 0 {
		u += "?" + q.Encode()
	}
	var s wsSummary
	if err := f.GetJSON(ctx, u, c.Secret, &s); err != nil {
		return Result{}, err
	}
	if s.Current == nil {
		return Result{}, errors.New("Weather Scout sent no current conditions")
	}
	unit := "°F"
	if strings.Contains(strings.ToUpper(s.Units.Temp), "C") {
		unit = "°C"
	}
	w := &Weather{
		Temp:  s.Current.Temp,
		Unit:  unit,
		Icon:  weatherIcon(s.Current.Icon),
		IsDay: s.Current.IsDay,
		Label: clip(s.Current.Label, 40),
	}
	subtitle := ""
	if s.Today != nil {
		w.High, w.Low = s.Today.High, s.Today.Low
		if p := s.Today.PrecipChance; p != nil && *p >= 0 && *p <= 100 {
			w.PrecipChance = p
		}
	}
	if s.Nowcast != nil {
		subtitle = s.Nowcast.Short
	}
	if len(s.Alerts) > 0 {
		w.Alert = clip(s.Alerts[0].Event, 80)
	}
	title := s.Location.Name
	if title == "" {
		title = "Weather"
	}
	return Result{Feeds: []Feed{{
		ID:         c.ID + ".now",
		Name:       c.Name,
		Template:   TemplateWeather,
		Sizes:      []string{"1x1", "2x1", "4x2"},
		RefreshSec: 600,
		KidsSafe:   true,
		Cards:      []Card{{ID: "now", Title: clip(title, 120), Subtitle: clip(subtitle, 200), Weather: w}},
	}}}, nil
}
