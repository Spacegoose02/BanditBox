package widgets

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"time"

	"github.com/spacegoose02/banditbox/server/internal/schema"
	"github.com/spacegoose02/banditbox/server/internal/store"
)

// jsonFeed reads widget feeds that a web service already serves in BanditBox's own
// format (schema/widget-feed.schema.json): one feed, or {"feeds": [...]}.
var jsonFeed = Kind{
	ID:          "json",
	Name:        "Widget feed (JSON)",
	Description: "Any web address that serves BanditBox widget feed JSON, such as a feed from Cinemask or your own script. See docs/widgets.md for the format.",
	Fields: []Field{
		{Key: "url", Label: "Feed address", Placeholder: "http://192.168.1.50:9000/banditbox/feed.json", Required: true},
		{Key: SecretKey, Label: "Bearer key", Help: "Optional. Sent as Authorization: Bearer.", Secret: true},
	},
	Feeds: []FeedInfo{{Suffix: "*", Name: "The feeds it serves", Template: ""}},
	Every: 5 * time.Minute,
	Fetch: fetchJSONFeed,
}

var feedSuffix = regexp.MustCompile(`^[a-z0-9_-]+$`)

func fetchJSONFeed(ctx context.Context, f *Fetcher, c store.Connector) (Result, error) {
	u, err := baseURL(c.Settings["url"])
	if err != nil {
		return Result{}, err
	}
	// baseURL drops the query; feed addresses may need theirs.
	if i := strings.IndexByte(c.Settings["url"], '?'); i >= 0 {
		u += strings.TrimSpace(c.Settings["url"][i:])
	}
	var raw json.RawMessage
	if err := f.GetJSON(ctx, u, c.Secret, &raw); err != nil {
		return Result{}, err
	}
	var list []json.RawMessage
	var wrapped struct {
		Feeds []json.RawMessage `json:"feeds"`
	}
	if bytes.HasPrefix(bytes.TrimSpace(raw), []byte("{")) && json.Unmarshal(raw, &wrapped) == nil && wrapped.Feeds != nil {
		list = wrapped.Feeds
	} else {
		list = []json.RawMessage{raw}
	}
	if len(list) > 20 {
		return Result{}, errors.New("more than 20 feeds")
	}
	base := u
	var feeds []Feed
	every := time.Duration(0)
	for i, one := range list {
		if err := schema.ValidateFeed(one); err != nil {
			return Result{}, fmt.Errorf("feed %d isn't a valid widget feed: %v", i+1, err)
		}
		var fd Feed
		if err := json.Unmarshal(one, &fd); err != nil {
			return Result{}, err
		}
		// Feeds are named after the connector, so two sources can't collide.
		suffix := fd.ID[strings.LastIndexByte(fd.ID, '.')+1:]
		if !feedSuffix.MatchString(suffix) {
			return Result{}, fmt.Errorf("feed %d has a bad id", i+1)
		}
		fd.ID = c.ID + "." + suffix
		fd.Error, fd.UpdatedAt = "", ""
		for j := range fd.Cards {
			card := &fd.Cards[j]
			if strings.HasPrefix(card.Image, "http") && sameHost(base, card.Image) && c.Secret != "" {
				card.Image = f.Image(ctx, card.Image, c.Secret)
				if card.Image == "" {
					card.ImageAspect = 0
				}
			}
		}
		if fd.RefreshSec > 0 && (every == 0 || time.Duration(fd.RefreshSec)*time.Second < every) {
			every = time.Duration(fd.RefreshSec) * time.Second
		}
		feeds = append(feeds, fd)
	}
	return Result{Feeds: feeds, Next: every}, nil
}
