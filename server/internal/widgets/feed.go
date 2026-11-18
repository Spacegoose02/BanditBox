// Package widgets runs the server connectors that feed cards to the TVs' home
// screens (Weather Scout, StatScraps, BingeBandit, or any JSON widget feed).
//
// Each connector is fetched on its own schedule. The results are widget feeds
// (schema/widget-feed.schema.json), held in memory and handed to TVs through
// GET /api/v1/widgets. Pictures that need the connector's key are copied to the
// server and served as media:sha256-<hex>, so keys never leave the server.
package widgets

import "unicode/utf8"

// SchemaVersion is the widget feed format version.
const SchemaVersion = 1

// Templates the TV knows how to draw.
const (
	TemplateCardRow    = "cardRow"
	TemplateStat       = "stat"
	TemplateWeather    = "weather"
	TemplateNowPlaying = "nowPlaying"
	TemplateScoreboard = "scoreboard"
)

// Feed is one widget feed. It mirrors schema/widget-feed.schema.json.
type Feed struct {
	Schema     int      `json:"schema"`
	ID         string   `json:"id"`
	Name       string   `json:"name"`
	Template   string   `json:"template"`
	Sizes      []string `json:"sizes,omitempty"`
	RefreshSec int      `json:"refreshSec,omitempty"`
	KidsSafe   bool     `json:"kidsSafe"`
	Package    string   `json:"package,omitempty"`
	UpdatedAt  string   `json:"updatedAt,omitempty"`
	Error      string   `json:"error,omitempty"`
	Cards      []Card   `json:"cards"`
}

// Card is one card in a feed.
type Card struct {
	ID          string   `json:"id"`
	Title       string   `json:"title"`
	Subtitle    string   `json:"subtitle,omitempty"`
	Image       string   `json:"image,omitempty"`
	ImageAspect float64  `json:"imageAspect,omitempty"`
	Progress    *float64 `json:"progress,omitempty"`
	Badge       string   `json:"badge,omitempty"`
	Link        string   `json:"link,omitempty"`
	Package     string   `json:"package,omitempty"`
	Actions     []Action `json:"actions,omitempty"`
	Stat        *Stat    `json:"stat,omitempty"`
	Weather     *Weather `json:"weather,omitempty"`
	Score       *Score   `json:"score,omitempty"`
}

// Action is a long-press action on a card.
type Action struct {
	Label string `json:"label"`
	Link  string `json:"link"`
}

// Stat is the big number of a stat card.
type Stat struct {
	Value string `json:"value"`
	Unit  string `json:"unit,omitempty"`
	Trend string `json:"trend,omitempty"`
}

// Weather is the conditions on a weather card.
type Weather struct {
	Temp         float64  `json:"temp"`
	Unit         string   `json:"unit"`
	Icon         string   `json:"icon"`
	IsDay        *bool    `json:"isDay,omitempty"`
	Label        string   `json:"label,omitempty"`
	High         *float64 `json:"high,omitempty"`
	Low          *float64 `json:"low,omitempty"`
	PrecipChance *int     `json:"precipChance,omitempty"`
	Alert        string   `json:"alert,omitempty"`
	Days         []Day    `json:"days,omitempty"`
}

// Day is one day of a forecast.
type Day struct {
	Day          string  `json:"day"`
	Icon         string  `json:"icon"`
	High         float64 `json:"high"`
	Low          float64 `json:"low"`
	PrecipChance *int    `json:"precipChance,omitempty"`
}

// Score is one game on a scoreboard card.
type Score struct {
	State     string `json:"state"` // pre, in, post
	Home      Team   `json:"home"`
	Away      Team   `json:"away"`
	Detail    string `json:"detail,omitempty"`
	StartsAt  string `json:"startsAt,omitempty"`
	Broadcast string `json:"broadcast,omitempty"`
}

// Team is one side of a game.
type Team struct {
	Name   string  `json:"name"`
	Abbr   string  `json:"abbr,omitempty"`
	Score  *string `json:"score,omitempty"`
	Logo   string  `json:"logo,omitempty"`
	Color  string  `json:"color,omitempty"`
	Rank   *int    `json:"rank,omitempty"`
	Record string  `json:"record,omitempty"`
	Winner bool    `json:"winner,omitempty"`
}

// weatherIcons are the icon names the schema allows.
var weatherIcons = map[string]bool{
	"clear": true, "partly-cloudy": true, "cloudy": true, "fog": true, "drizzle": true,
	"rain": true, "sleet": true, "snow": true, "thunder": true,
}

// weatherIcon maps a source's icon name to one the TV draws.
func weatherIcon(s string) string {
	if weatherIcons[s] {
		return s
	}
	return "cloudy"
}

// clip shortens s to at most n characters, so text from a source never breaks the schema's limits.
func clip(s string, n int) string {
	if utf8.RuneCountInString(s) <= n {
		return s
	}
	r := []rune(s)
	if n <= 1 {
		return string(r[:n])
	}
	return string(r[:n-1]) + "…"
}

func ptr[T any](v T) *T { return &v }
