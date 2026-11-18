package widgets

import (
	"context"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/spacegoose02/banditbox/server/internal/store"
)

// bingeBanditPackage is the BingeBandit Android TV app, which opens bingebandit://play/<id>?t=<seconds>.
const bingeBanditPackage = "com.bingebandit.tv"

// bingeBandit reads Continue Watching and Recently added from a BingeBandit server
// (GET /api/rows, see its docs/API.md) with a bb_ API key.
var bingeBandit = Kind{
	ID:          "bingebandit",
	Name:        "BingeBandit",
	Description: "Continue Watching and Recently added from your BingeBandit server. Make an API key in BingeBandit under Account, API keys; the rows show what that account can see, so they are hidden on kids profiles.",
	Fields: []Field{
		{Key: "url", Label: "BingeBandit address", Placeholder: "http://192.168.1.50:8484", Required: true},
		{Key: SecretKey, Label: "API key", Placeholder: "bb_…", Required: true, Secret: true},
		{Key: "limit", Label: "Cards per row", Placeholder: "20"},
	},
	Feeds: []FeedInfo{
		{Suffix: "continue-watching", Name: "Continue Watching", Template: TemplateCardRow},
		{Suffix: "recent", Name: "Recently added", Template: TemplateCardRow},
	},
	Every: 2 * time.Minute,
	Fetch: fetchBingeBandit,
}

type bbItem struct {
	ID            int64  `json:"id"`
	Kind          string `json:"kind"`
	Title         string `json:"title"`
	Year          int    `json:"year"`
	Poster        string `json:"poster"`
	Backdrop      string `json:"backdrop"`
	ShowTitle     string `json:"showTitle"`
	ShowPoster    string `json:"showPoster"`
	ShowBackdrop  string `json:"showBackdrop"`
	SeasonNumber  *int   `json:"seasonNumber"`
	EpisodeNumber *int   `json:"episodeNumber"`
	Watch         struct {
		Position float64 `json:"position"`
		Duration float64 `json:"duration"`
	} `json:"watch"`
}

func fetchBingeBandit(ctx context.Context, f *Fetcher, c store.Connector) (Result, error) {
	base, err := baseURL(c.Settings["url"])
	if err != nil {
		return Result{}, err
	}
	limit := 20
	if n, err := strconv.Atoi(strings.TrimSpace(c.Settings["limit"])); err == nil && n >= 1 {
		limit = min(n, 50)
	}
	var feeds []Feed
	for _, row := range []struct{ key, suffix, name string }{
		{"continue", "continue-watching", "Continue Watching"},
		{"recent", "recent", "Recently added"},
	} {
		var reply struct {
			Items []bbItem `json:"items"`
		}
		if err := f.GetJSON(ctx, fmt.Sprintf("%s/api/rows?key=%s&limit=%d", base, row.key, limit), c.Secret, &reply); err != nil {
			return Result{}, err
		}
		cards := make([]Card, 0, len(reply.Items))
		for _, it := range reply.Items {
			if len(cards) == limit {
				break
			}
			cards = append(cards, bbCard(ctx, f, base, c.Secret, it, row.key == "continue"))
		}
		feeds = append(feeds, Feed{
			ID:         c.ID + "." + row.suffix,
			Name:       row.name,
			Template:   TemplateCardRow,
			Sizes:      []string{"row"},
			RefreshSec: 120,
			KidsSafe:   false,
			Package:    bingeBanditPackage,
			Cards:      cards,
		})
	}
	return Result{Feeds: feeds}, nil
}

func bbCard(ctx context.Context, f *Fetcher, base, key string, it bbItem, resume bool) Card {
	id := strconv.FormatInt(it.ID, 10)
	card := Card{ID: id, Title: clip(it.Title, 120), Package: bingeBanditPackage}
	if it.Kind == "episode" && it.ShowTitle != "" {
		card.Title = clip(it.ShowTitle, 120)
		ep := it.Title
		if it.SeasonNumber != nil && it.EpisodeNumber != nil {
			ep = fmt.Sprintf("S%d E%d · %s", *it.SeasonNumber, *it.EpisodeNumber, it.Title)
		}
		card.Subtitle = clip(ep, 200)
	} else if it.Year > 0 {
		card.Subtitle = strconv.Itoa(it.Year)
	}
	// Wide stills for the row; posters only when there's nothing wider.
	img, aspect := firstOf(it.Backdrop, it.ShowBackdrop), 1.78
	if img == "" {
		img, aspect = firstOf(it.Poster, it.ShowPoster), 0.67
	}
	if pic := f.picture(ctx, base, img, key); pic != "" {
		card.Image, card.ImageAspect = pic, aspect
	}
	link := "bingebandit://play/" + id
	if resume && it.Watch.Duration > 0 && it.Watch.Position > 0 {
		p := it.Watch.Position / it.Watch.Duration
		card.Progress = ptr(min(1, max(0, p)))
		left := int((it.Watch.Duration - it.Watch.Position) / 60)
		if left > 0 && card.Subtitle != "" {
			card.Subtitle = clip(fmt.Sprintf("%s · %d min left", card.Subtitle, left), 200)
		} else if left > 0 {
			card.Subtitle = fmt.Sprintf("%d min left", left)
		}
		link += "?t=" + strconv.Itoa(int(it.Watch.Position))
	}
	card.Link = link
	return card
}
