package widgets

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/spacegoose02/banditbox/server/internal/schema"
	"github.com/spacegoose02/banditbox/server/internal/store"
)

// png is the smallest picture the fake servers hand out.
var png = []byte("\x89PNG\r\n\x1a\nfake")

func serve(t *testing.T, routes map[string]func(w http.ResponseWriter, r *http.Request)) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h, ok := routes[r.URL.Path]
		if !ok {
			http.NotFound(w, r)
			return
		}
		h(w, r)
	}))
	t.Cleanup(srv.Close)
	return srv
}

func jsonReply(body string) func(w http.ResponseWriter, r *http.Request) {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(body))
	}
}

func needKey(key string, h func(w http.ResponseWriter, r *http.Request)) func(w http.ResponseWriter, r *http.Request) {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer "+key {
			http.Error(w, `{"error":"no"}`, http.StatusUnauthorized)
			return
		}
		h(w, r)
	}
}

func valid(t *testing.T, feeds []Feed) {
	t.Helper()
	for _, f := range feeds {
		f.Schema = SchemaVersion
		raw, _ := json.Marshal(f)
		if err := schema.ValidateFeed(raw); err != nil {
			t.Errorf("feed %s doesn't validate: %v\n%s", f.ID, err, raw)
		}
	}
}

func TestWeatherScout(t *testing.T) {
	var query string
	srv := serve(t, map[string]func(http.ResponseWriter, *http.Request){
		"/api/connect/summary": needKey("wsk_1", func(w http.ResponseWriter, r *http.Request) {
			query = r.URL.RawQuery
			jsonReply(`{
				"location": {"name": "Richmond, VA"},
				"units": {"temp": "°F"},
				"current": {"temp": 52.4, "icon": "rain", "label": "Rain", "isDay": true},
				"today": {"high": 60, "low": 42, "precipChance": 80},
				"nowcast": {"short": "Rain in 15 min"},
				"alerts": [{"event": "Wind Advisory"}]
			}`)(w, r)
		}),
	})
	f := NewFetcher(t.TempDir())
	c := store.Connector{ID: "weatherscout", Kind: "weatherscout", Name: "Weather", Secret: "wsk_1",
		Settings: map[string]string{"url": srv.URL + "/", "lat": "37.5", "units": "imperial"}}
	res, err := weatherScout.Fetch(context.Background(), f, c)
	if err != nil {
		t.Fatal(err)
	}
	valid(t, res.Feeds)
	if len(res.Feeds) != 1 || res.Feeds[0].ID != "weatherscout.now" {
		t.Fatalf("feeds = %+v", res.Feeds)
	}
	card := res.Feeds[0].Cards[0]
	if card.Title != "Richmond, VA" || card.Subtitle != "Rain in 15 min" || card.Weather.Alert != "Wind Advisory" || *card.Weather.High != 60 {
		t.Errorf("card = %+v %+v", card, card.Weather)
	}
	if !strings.Contains(query, "lat=37.5") || strings.Contains(query, "lon") {
		t.Errorf("query = %q", query)
	}

	c.Secret = "wrong"
	if _, err := weatherScout.Fetch(context.Background(), f, c); err == nil || !strings.Contains(err.Error(), "key was refused") {
		t.Errorf("wrong key: err = %v", err)
	}
}

func TestStatScraps(t *testing.T) {
	var items string
	srv := serve(t, map[string]func(http.ResponseWriter, *http.Request){
		"/api/ha/scorecards": func(w http.ResponseWriter, r *http.Request) {
			items = r.URL.Query().Get("items")
			jsonReply(`{"updated": "x", "scorecards": {
				"cfb:259": {"state": "POST",
					"team": {"name": "Virginia Tech Hokies", "abbreviation": "VT", "score": "31", "homeAway": "home", "winner": true, "color": "#630031", "rank": null, "logo": "https://a.espncdn.com/vt.png"},
					"opponent": {"name": "Miami Hurricanes", "abbreviation": "MIA", "score": 17, "homeAway": "away", "rank": 8, "color": "nope"},
					"event": {"shortName": "MIA @ VT", "date": "2026-11-15T19:30Z", "shortDetail": "Final", "broadcast": "ESPN"}},
				"nhl:7": {"state": "IN",
					"team": {"name": "Washington Capitals", "abbreviation": "WSH", "score": 2, "homeAway": "away"},
					"opponent": {"name": "Pittsburgh Penguins", "abbreviation": "PIT", "score": 1, "homeAway": "home"},
					"event": {"name": "Capitals at Penguins", "date": "2026-11-18T00:00Z", "shortDetail": "2nd 12:01", "broadcast": null}},
				"nascar": {"state": "PRE", "team": null, "opponent": null, "event": {"name": "Cup Series Championship", "shortDetail": "Sun 3:00 PM"}},
				"nba:25": {"state": "NOT_FOUND", "event": null},
				"mlb:1": {"error": "Could not build scorecard"}
			}}`)(w, r)
		},
	})
	c := store.Connector{ID: "statscraps", Kind: "statscraps", Name: "Scores",
		Settings: map[string]string{"url": srv.URL, "items": " cfb:259, nhl:7,nascar , nba:25, mlb:1, cfb:259"}}
	res, err := statScraps.Fetch(context.Background(), NewFetcher(""), c)
	if err != nil {
		t.Fatal(err)
	}
	valid(t, res.Feeds)
	if items != "cfb:259,nhl:7,nascar,nba:25,mlb:1" {
		t.Errorf("items sent = %q", items)
	}
	cards := res.Feeds[0].Cards
	if len(cards) != 3 {
		t.Fatalf("cards = %+v", cards)
	}
	// Live first, then upcoming, then finals.
	if cards[0].ID != "nhl:7" || cards[0].Badge != "LIVE" || cards[1].ID != "nascar" || cards[2].ID != "cfb:259" {
		t.Errorf("order = %s %s %s", cards[0].ID, cards[1].ID, cards[2].ID)
	}
	vt := cards[2].Score
	if vt.Home.Abbr != "VT" || *vt.Home.Score != "31" || *vt.Away.Score != "17" || *vt.Away.Rank != 8 || vt.Away.Color != "" || vt.Home.Rank != nil {
		t.Errorf("final = %+v", vt)
	}
	if nhl := cards[0].Score; nhl.Home.Abbr != "PIT" || nhl.Away.Abbr != "WSH" {
		t.Errorf("home/away = %+v", nhl)
	}
	if res.Next != 30*time.Second {
		t.Errorf("next = %v, want 30s while a game is live", res.Next)
	}
	if cards[1].Score != nil || cards[1].Subtitle != "Sun 3:00 PM" {
		t.Errorf("event card = %+v", cards[1])
	}

	c.Settings["items"] = "cfb:259; drop table"
	if _, err := statScraps.Fetch(context.Background(), NewFetcher(""), c); err == nil {
		t.Error("bad items accepted")
	}
}

func TestBingeBandit(t *testing.T) {
	var picHits atomic.Int32
	var srvURL string
	srv := serve(t, map[string]func(http.ResponseWriter, *http.Request){
		"/api/rows": needKey("bb_1", func(w http.ResponseWriter, r *http.Request) {
			switch r.URL.Query().Get("key") {
			case "continue":
				jsonReply(`{"title": "Continue Watching", "items": [
					{"id": 42, "kind": "episode", "title": "Pilot", "showTitle": "The Long Drive", "seasonNumber": 2, "episodeNumber": 4,
					 "showBackdrop": "/api/items/7/backdrop", "watch": {"position": 1260, "duration": 3120}},
					{"id": 9, "kind": "movie", "title": "Heist", "year": 2024, "poster": "https://image.tmdb.org/t/p/w342/x.jpg", "watch": {"position": 0, "duration": 0}}
				]}`)(w, r)
			case "recent":
				jsonReply(`{"title": "Recently added", "items": [{"id": 5, "kind": "movie", "title": "New One", "poster": "`+srvURL+`/api/items/5/poster"}]}`)(w, r)
			}
		}),
		"/api/items/7/backdrop": needKey("bb_1", func(w http.ResponseWriter, r *http.Request) {
			picHits.Add(1)
			w.Header().Set("Content-Type", "image/png")
			_, _ = w.Write(png)
		}),
		"/api/items/5/poster": needKey("bb_1", func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "text/html")
			_, _ = w.Write([]byte("<html>"))
		}),
	})
	srvURL = srv.URL
	dir := t.TempDir()
	f := NewFetcher(dir)
	c := store.Connector{ID: "bingebandit", Kind: "bingebandit", Name: "BB", Secret: "bb_1", Settings: map[string]string{"url": srv.URL}}
	res, err := bingeBandit.Fetch(context.Background(), f, c)
	if err != nil {
		t.Fatal(err)
	}
	valid(t, res.Feeds)
	if len(res.Feeds) != 2 || res.Feeds[0].ID != "bingebandit.continue-watching" || res.Feeds[1].ID != "bingebandit.recent" {
		t.Fatalf("feeds = %+v", res.Feeds)
	}
	ep := res.Feeds[0].Cards[0]
	if ep.Title != "The Long Drive" || ep.Subtitle != "S2 E4 · Pilot · 31 min left" || ep.Link != "bingebandit://play/42?t=1260" {
		t.Errorf("episode = %+v", ep)
	}
	if ep.Progress == nil || *ep.Progress < 0.4 || *ep.Progress > 0.41 {
		t.Errorf("progress = %v", ep.Progress)
	}
	sha, ok := strings.CutPrefix(ep.Image, MediaPrefix)
	if !ok {
		t.Fatalf("image = %q, want a copy on the server", ep.Image)
	}
	if got, _ := os.ReadFile(filepath.Join(dir, sha)); string(got) != string(png) {
		t.Errorf("stored picture = %q", got)
	}
	movie := res.Feeds[0].Cards[1]
	if movie.Image != "https://image.tmdb.org/t/p/w342/x.jpg" || movie.ImageAspect != 0.67 || movie.Progress != nil || movie.Link != "bingebandit://play/9" {
		t.Errorf("movie = %+v", movie)
	}
	if res.Feeds[1].Cards[0].Image != "" {
		t.Errorf("a page that isn't a picture became %q", res.Feeds[1].Cards[0].Image)
	}
	// The second fetch reuses the stored picture.
	if _, err := bingeBandit.Fetch(context.Background(), f, c); err != nil {
		t.Fatal(err)
	}
	if picHits.Load() != 1 {
		t.Errorf("picture fetched %d times", picHits.Load())
	}
	f.Prune(-time.Second, nil)
	if entries, _ := os.ReadDir(dir); len(entries) != 0 {
		t.Errorf("prune left %d files", len(entries))
	}
}

func TestJSONFeed(t *testing.T) {
	example, err := os.ReadFile("../../../schema/examples/widget-feed/scores.json")
	if err != nil {
		t.Fatal(err)
	}
	srv := serve(t, map[string]func(http.ResponseWriter, *http.Request){
		"/one":  jsonReply(string(example)),
		"/many": jsonReply(`{"feeds": [` + string(example) + `, {"schema": 1, "id": "cinemask.upcoming", "name": "Upcoming", "template": "cardRow", "refreshSec": 600, "cards": []}]}`),
		"/bad":  jsonReply(`{"schema": 1, "id": "x.y", "name": "X", "template": "webview", "cards": []}`),
	})
	f := NewFetcher("")
	c := store.Connector{ID: "mine", Kind: "json", Name: "Mine", Settings: map[string]string{"url": srv.URL + "/one"}}
	res, err := jsonFeed.Fetch(context.Background(), f, c)
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Feeds) != 1 || res.Feeds[0].ID != "mine.scores" || res.Next != 30*time.Second {
		t.Errorf("one = %+v next %v", res.Feeds, res.Next)
	}
	c.Settings["url"] = srv.URL + "/many"
	res, err = jsonFeed.Fetch(context.Background(), f, c)
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Feeds) != 2 || res.Feeds[1].ID != "mine.upcoming" {
		t.Errorf("many = %+v", res.Feeds)
	}
	c.Settings["url"] = srv.URL + "/bad"
	if _, err := jsonFeed.Fetch(context.Background(), f, c); err == nil {
		t.Error("invalid feed accepted")
	}
}

type notes struct{ n atomic.Int32 }

func (n *notes) WidgetsChanged(int64) { n.n.Add(1) }

func TestManager(t *testing.T) {
	ctx := context.Background()
	st, err := store.OpenFile(ctx, ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	calls := 0
	fail := false
	kind := Kind{ID: "fake", Every: time.Hour, Fetch: func(ctx context.Context, f *Fetcher, c store.Connector) (Result, error) {
		calls++
		if fail {
			return Result{}, &HTTPError{Status: 500}
		}
		return Result{Feeds: []Feed{{ID: c.ID + ".a", Name: "A", Template: TemplateStat,
			Cards: []Card{{ID: "1", Title: "Visitors", Stat: &Stat{Value: "12"}}}}}}, nil
	}}
	n := &notes{}
	m := NewManager(st, "", n, nil)
	m.Kind = func(id string) (Kind, bool) { return kind, id == "fake" }
	if _, err := st.AddConnector(ctx, store.Connector{ID: "one", Kind: "fake", Name: "One", Enabled: true}); err != nil {
		t.Fatal(err)
	}
	if _, err := st.AddConnector(ctx, store.Connector{ID: "off", Kind: "fake", Name: "Off", Enabled: false}); err != nil {
		t.Fatal(err)
	}
	wait := m.tick(ctx)
	v1, feeds := m.Feeds()
	if len(feeds) != 1 || feeds[0].ID != "one.a" || feeds[0].Schema != 1 || feeds[0].UpdatedAt == "" || n.n.Load() != 1 || calls != 1 {
		t.Fatalf("after first tick: %+v notes=%d calls=%d", feeds, n.n.Load(), calls)
	}
	if wait < 59*time.Minute {
		t.Errorf("wait = %v", wait)
	}
	// Nothing due: no fetch.
	m.tick(ctx)
	if calls != 1 {
		t.Errorf("fetched again before due")
	}
	// A reload fetches at once; the same cards don't count as a change.
	m.Reload("one")
	m.tick(ctx)
	if v2, _ := m.Feeds(); calls != 2 || v2 != v1 || n.n.Load() != 1 {
		t.Errorf("unchanged refetch: calls=%d version %d->%d notes=%d", calls, v1, v2, n.n.Load())
	}
	// A failure keeps the cards, with the error, and retries sooner.
	fail = true
	m.Reload("one")
	wait = m.tick(ctx)
	_, feeds = m.Feeds()
	if len(feeds) != 1 || feeds[0].Error == "" || m.Status()["one"].LastError == "" || wait > retryEvery {
		t.Errorf("after failure: %+v status %+v wait %v", feeds, m.Status()["one"], wait)
	}
	// Turning it off drops its feeds.
	if err := st.UpdateConnector(ctx, store.Connector{ID: "one", Name: "One", Enabled: false}); err != nil {
		t.Fatal(err)
	}
	m.tick(ctx)
	if _, feeds = m.Feeds(); len(feeds) != 0 {
		t.Errorf("feeds of a connector turned off: %+v", feeds)
	}

	// Test runs a fetch without keeping it and rejects malformed feeds.
	kind.Fetch = func(ctx context.Context, f *Fetcher, c store.Connector) (Result, error) {
		return Result{Feeds: []Feed{{ID: "nodot", Name: "A", Template: TemplateStat}}}, nil
	}
	if _, err := m.Test(ctx, store.Connector{ID: "t", Kind: "fake"}); err == nil {
		t.Error("malformed feed passed Test")
	}
}
