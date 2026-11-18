package widgets

import (
	"context"
	"encoding/json"
	"errors"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/spacegoose02/banditbox/server/internal/store"
)

// statScraps reads StatScraps' scorecards (GET /api/ha/scorecards, the same
// endpoint its Home Assistant integration polls). It needs no key.
var statScraps = Kind{
	ID:          "statscraps",
	Name:        "StatScraps",
	Description: "Scores for the teams you follow: live while a game is on, then the final and the next game.",
	Fields: []Field{
		{Key: "url", Label: "StatScraps address", Placeholder: "http://192.168.1.50:3000", Required: true},
		{Key: "items", Label: "Teams", Help: "sport:team pairs, or a sport for NASCAR and UFC, comma-separated (up to 20). Find team ids in StatScraps' team page addresses.", Placeholder: "cfb:259, nfl:28, nascar", Required: true},
	},
	Feeds: []FeedInfo{{Suffix: "scores", Name: "Scores", Template: TemplateScoreboard}},
	Every: 15 * time.Minute,
	Fetch: fetchStatScraps,
}

var itemRe = regexp.MustCompile(`^[a-z0-9]+(:[A-Za-z0-9-]+)?$`)

// scoreItems cleans the comma-separated list of sport:team items.
func scoreItems(raw string) ([]string, error) {
	var out []string
	seen := map[string]bool{}
	for _, it := range strings.Split(raw, ",") {
		it = strings.TrimSpace(it)
		if it == "" || seen[it] {
			continue
		}
		if !itemRe.MatchString(it) {
			return nil, errors.New("teams must look like cfb:259 or nascar: " + it)
		}
		seen[it] = true
		out = append(out, it)
	}
	if len(out) == 0 {
		return nil, errors.New("add at least one team")
	}
	if len(out) > 20 {
		out = out[:20]
	}
	return out, nil
}

type ssSide struct {
	Name         string          `json:"name"`
	ShortName    string          `json:"shortName"`
	Abbreviation string          `json:"abbreviation"`
	Logo         string          `json:"logo"`
	Color        string          `json:"color"`
	Record       string          `json:"record"`
	Rank         json.RawMessage `json:"rank"`
	Score        json.RawMessage `json:"score"`
	HomeAway     string          `json:"homeAway"`
	Winner       bool            `json:"winner"`
}

type ssCard struct {
	Error    string  `json:"error"`
	State    string  `json:"state"`
	Team     *ssSide `json:"team"`
	Opponent *ssSide `json:"opponent"`
	Event    *struct {
		Name        string          `json:"name"`
		ShortName   string          `json:"shortName"`
		Date        string          `json:"date"`
		Broadcast   json.RawMessage `json:"broadcast"`
		ShortDetail string          `json:"shortDetail"`
		Detail      string          `json:"detail"`
	} `json:"event"`
}

var hexColor = regexp.MustCompile(`^#[0-9A-Fa-f]{6}$`)

// rawString reads a JSON string or number as text.
func rawString(raw json.RawMessage) string {
	var s string
	if json.Unmarshal(raw, &s) == nil {
		return s
	}
	var n json.Number
	if json.Unmarshal(raw, &n) == nil {
		return n.String()
	}
	return ""
}

func ssTeam(s *ssSide) Team {
	t := Team{
		Name:   clip(firstOf(s.Name, s.ShortName, s.Abbreviation, "?"), 60),
		Abbr:   clip(s.Abbreviation, 8),
		Record: clip(s.Record, 16),
		Winner: s.Winner,
	}
	if sc := rawString(s.Score); sc != "" {
		t.Score = ptr(clip(sc, 8))
	}
	if r, err := strconv.Atoi(rawString(s.Rank)); err == nil && r >= 1 && r <= 99 {
		t.Rank = ptr(r)
	}
	if hexColor.MatchString(s.Color) {
		t.Color = s.Color
	}
	if strings.HasPrefix(s.Logo, "https://") && len(s.Logo) <= 2048 {
		t.Logo = s.Logo
	}
	return t
}

func firstOf(s ...string) string {
	for _, v := range s {
		if v != "" {
			return v
		}
	}
	return ""
}

func fetchStatScraps(ctx context.Context, f *Fetcher, c store.Connector) (Result, error) {
	base, err := baseURL(c.Settings["url"])
	if err != nil {
		return Result{}, err
	}
	items, err := scoreItems(c.Settings["items"])
	if err != nil {
		return Result{}, err
	}
	var reply struct {
		Scorecards map[string]ssCard `json:"scorecards"`
	}
	if err := f.GetJSON(ctx, base+"/api/ha/scorecards?items="+url.QueryEscape(strings.Join(items, ",")), "", &reply); err != nil {
		return Result{}, err
	}
	next := 15 * time.Minute
	now := time.Now()
	cards := []Card{}
	for _, item := range items {
		sc, ok := reply.Scorecards[item]
		if !ok || sc.Error != "" || sc.Event == nil || sc.State == "NOT_FOUND" {
			continue
		}
		state := strings.ToLower(sc.State)
		if state != "pre" && state != "in" && state != "post" {
			continue
		}
		card := Card{
			ID:    clip(item, 128),
			Title: clip(firstOf(sc.Event.ShortName, sc.Event.Name), 120),
		}
		detail := clip(firstOf(sc.Event.ShortDetail, sc.Event.Detail), 40)
		start, _ := time.Parse(time.RFC3339, sc.Event.Date)
		if start.IsZero() {
			start, _ = time.Parse("2006-01-02T15:04Z", sc.Event.Date)
		}
		switch {
		case state == "in":
			card.Badge = "LIVE"
			next = min(next, 30*time.Second)
		case state == "pre" && !start.IsZero() && start.Sub(now) < time.Hour:
			next = min(next, time.Minute)
		}
		if sc.Team != nil && sc.Opponent != nil {
			team, opp := ssTeam(sc.Team), ssTeam(sc.Opponent)
			home, away := team, opp
			if sc.Team.HomeAway == "away" || sc.Opponent.HomeAway == "home" {
				home, away = opp, team
			}
			s := &Score{State: state, Home: home, Away: away, Detail: detail, Broadcast: clip(rawString(sc.Event.Broadcast), 30)}
			if !start.IsZero() {
				s.StartsAt = start.UTC().Format(time.RFC3339)
			}
			card.Score = s
		} else {
			// NASCAR and UFC: an event, not two teams.
			card.Subtitle = detail
		}
		cards = append(cards, card)
	}
	// Live games first, then the ones about to start, then finals.
	rank := map[string]int{"in": 0, "pre": 1, "post": 2}
	sort.SliceStable(cards, func(i, j int) bool {
		return rank[stateOf(cards[i])] < rank[stateOf(cards[j])]
	})
	return Result{Feeds: []Feed{{
		ID:         c.ID + ".scores",
		Name:       c.Name,
		Template:   TemplateScoreboard,
		Sizes:      []string{"row", "2x1"},
		RefreshSec: max(30, int(next.Seconds())),
		KidsSafe:   true,
		Cards:      cards,
	}}, Next: next}, nil
}

func stateOf(c Card) string {
	if c.Score != nil {
		return c.Score.State
	}
	if c.Badge == "LIVE" {
		return "in"
	}
	return "pre"
}
