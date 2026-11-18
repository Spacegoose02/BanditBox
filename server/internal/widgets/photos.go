package widgets

import (
	"context"
	"fmt"
	"math/rand/v2"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/spacegoose02/banditbox/server/internal/store"
)

// Photo feeds feed the TV's photo screensaver (ambient mode), and work as a widget row too.
// A new random set is picked every hour.

const (
	photoCount = 30
	photoEvery = time.Hour
)

var immich = Kind{
	ID:          "immich",
	Name:        "Immich photos",
	Description: "Photos from your Immich server for the TV's photo screensaver. Make an API key in Immich under Account settings, API keys. Pick an album, or leave it empty for random photos from the whole library. Hidden on kids profiles unless the album is marked kids-safe below.",
	Fields: []Field{
		{Key: "url", Label: "Immich address", Placeholder: "http://192.168.1.50:2283", Required: true},
		{Key: SecretKey, Label: "API key", Required: true, Secret: true},
		{Key: "album", Label: "Album ID", Help: "The long id at the end of the album's address in Immich. Empty means random photos from the whole library.", Placeholder: "e.g. 5c9f1d2e-…"},
		{Key: "kidsSafe", Label: "Show on kids profiles", Help: "Type yes to allow these photos on kids profiles.", Placeholder: "no"},
	},
	Feeds: []FeedInfo{{Suffix: "photos", Name: "Photos", Template: TemplateCardRow}},
	Every: photoEvery,
	Fetch: fetchImmich,
}

type immichAsset struct {
	ID               string `json:"id"`
	Type             string `json:"type"`
	OriginalFileName string `json:"originalFileName"`
	FileCreatedAt    string `json:"fileCreatedAt"`
	ExifInfo         *struct {
		City             string `json:"city"`
		Country          string `json:"country"`
		DateTimeOriginal string `json:"dateTimeOriginal"`
		ExifImageWidth   int    `json:"exifImageWidth"`
		ExifImageHeight  int    `json:"exifImageHeight"`
		Orientation      string `json:"orientation"`
	} `json:"exifInfo"`
}

func fetchImmich(ctx context.Context, f *Fetcher, c store.Connector) (Result, error) {
	base, err := baseURL(c.Settings["url"])
	if err != nil {
		return Result{}, err
	}
	hdr := http.Header{"X-Api-Key": {c.Secret}}
	var assets []immichAsset
	name := "Photos"
	if album := strings.TrimSpace(c.Settings["album"]); album != "" {
		var reply struct {
			AlbumName string        `json:"albumName"`
			Assets    []immichAsset `json:"assets"`
		}
		u := base + "/api/albums/" + url.PathEscape(album) + "?withoutAssets=false"
		if err := f.DoJSON(ctx, http.MethodGet, u, hdr, nil, &reply); err != nil {
			return Result{}, err
		}
		assets = reply.Assets
		if reply.AlbumName != "" {
			name = reply.AlbumName
		}
	} else {
		body := map[string]any{"size": photoCount * 2, "type": "IMAGE", "withExif": true}
		if err := f.DoJSON(ctx, http.MethodPost, base+"/api/search/random", hdr, body, &assets); err != nil {
			return Result{}, err
		}
	}
	images := assets[:0]
	for _, a := range assets {
		if a.Type == "IMAGE" && a.ID != "" {
			images = append(images, a)
		}
	}
	rand.Shuffle(len(images), func(i, j int) { images[i], images[j] = images[j], images[i] })
	cards := make([]Card, 0, photoCount)
	for _, a := range images {
		if len(cards) == photoCount {
			break
		}
		pic := f.ImageWith(ctx, base+"/api/assets/"+url.PathEscape(a.ID)+"/thumbnail?size=preview", hdr)
		if pic == "" {
			continue
		}
		card := Card{ID: clip(a.ID, 64), Image: pic, ImageAspect: 1.78}
		when := a.FileCreatedAt
		if a.ExifInfo != nil {
			if a.ExifInfo.DateTimeOriginal != "" {
				when = a.ExifInfo.DateTimeOriginal
			}
			card.Title = clip(strings.Trim(strings.Join(nonEmpty(a.ExifInfo.City, a.ExifInfo.Country), ", "), ", "), 120)
			if w, h := a.ExifInfo.ExifImageWidth, a.ExifInfo.ExifImageHeight; w > 0 && h > 0 {
				// Orientations 5 to 8 are turned a quarter.
				if o, _ := strconv.Atoi(a.ExifInfo.Orientation); o >= 5 && o <= 8 {
					w, h = h, w
				}
				card.ImageAspect = aspect(w, h)
			}
		}
		card.Subtitle = photoDate(when)
		cards = append(cards, card)
	}
	if len(images) > 0 && len(cards) == 0 {
		return Result{}, fmt.Errorf("couldn't copy any photos from Immich")
	}
	return Result{Feeds: []Feed{photoFeed(c, name, cards)}}, nil
}

var plexPhotos = Kind{
	ID:          "plexphotos",
	Name:        "Plex photos",
	Description: "Photos from a Plex photo library for the TV's photo screensaver. You need your Plex token (Plex's support article \"Finding an authentication token\" shows where) and the library's section number, the number after /library/sections/ when you open the library in Plex Web.",
	Fields: []Field{
		{Key: "url", Label: "Plex address", Placeholder: "http://192.168.1.50:32400", Required: true},
		{Key: SecretKey, Label: "Plex token", Required: true, Secret: true},
		{Key: "section", Label: "Photo library section number", Placeholder: "3", Required: true},
		{Key: "kidsSafe", Label: "Show on kids profiles", Help: "Type yes to allow these photos on kids profiles.", Placeholder: "no"},
	},
	Feeds: []FeedInfo{{Suffix: "photos", Name: "Photos", Template: TemplateCardRow}},
	Every: photoEvery,
	Fetch: fetchPlexPhotos,
}

type plexPhoto struct {
	RatingKey             string `json:"ratingKey"`
	Title                 string `json:"title"`
	Type                  string `json:"type"`
	Thumb                 string `json:"thumb"`
	OriginallyAvailableAt string `json:"originallyAvailableAt"`
	ParentTitle           string `json:"parentTitle"`
	Media                 []struct {
		Width  int `json:"width"`
		Height int `json:"height"`
		Part   []struct {
			Key string `json:"key"`
		} `json:"Part"`
	} `json:"Media"`
}

func fetchPlexPhotos(ctx context.Context, f *Fetcher, c store.Connector) (Result, error) {
	base, err := baseURL(c.Settings["url"])
	if err != nil {
		return Result{}, err
	}
	section := strings.TrimSpace(c.Settings["section"])
	if _, err := strconv.Atoi(section); err != nil {
		return Result{}, fmt.Errorf("the section should be a number, like 3")
	}
	hdr := http.Header{"X-Plex-Token": {c.Secret}}
	var reply struct {
		MediaContainer struct {
			Title1   string      `json:"title1"`
			Metadata []plexPhoto `json:"Metadata"`
		} `json:"MediaContainer"`
	}
	// type=13 lists every photo in the library, whatever album it is in.
	u := base + "/library/sections/" + section + "/all?type=13"
	if err := f.DoJSON(ctx, http.MethodGet, u, hdr, nil, &reply); err != nil {
		return Result{}, err
	}
	photos := reply.MediaContainer.Metadata
	rand.Shuffle(len(photos), func(i, j int) { photos[i], photos[j] = photos[j], photos[i] })
	cards := make([]Card, 0, photoCount)
	for _, p := range photos {
		if len(cards) == photoCount {
			break
		}
		if p.Type != "photo" || len(p.Media) == 0 || len(p.Media[0].Part) == 0 {
			continue
		}
		// Plex scales the original down to TV size.
		q := url.Values{"width": {"1920"}, "height": {"1080"}, "minSize": {"1"}, "upscale": {"0"}, "url": {p.Media[0].Part[0].Key}}
		pic := f.ImageWith(ctx, base+"/photo/:/transcode?"+q.Encode(), hdr)
		if pic == "" {
			continue
		}
		card := Card{ID: clip(p.RatingKey, 64), Image: pic, ImageAspect: 1.78, Title: clip(p.ParentTitle, 120), Subtitle: photoDate(p.OriginallyAvailableAt)}
		if m := p.Media[0]; m.Width > 0 && m.Height > 0 {
			card.ImageAspect = aspect(m.Width, m.Height)
		}
		cards = append(cards, card)
	}
	if len(photos) > 0 && len(cards) == 0 {
		return Result{}, fmt.Errorf("couldn't copy any photos from Plex")
	}
	name := "Photos"
	if reply.MediaContainer.Title1 != "" {
		name = reply.MediaContainer.Title1
	}
	return Result{Feeds: []Feed{photoFeed(c, name, cards)}}, nil
}

func photoFeed(c store.Connector, name string, cards []Card) Feed {
	return Feed{
		ID:         c.ID + ".photos",
		Name:       clip(name, 80),
		Template:   TemplateCardRow,
		Sizes:      []string{"row"},
		RefreshSec: int(photoEvery / time.Second),
		KidsSafe:   strings.EqualFold(strings.TrimSpace(c.Settings["kidsSafe"]), "yes"),
		Cards:      cards,
	}
}

// aspect is w/h within what the feed format allows.
func aspect(w, h int) float64 { return min(4, max(0.25, float64(w)/float64(h))) }

// photoDate turns an ISO date or time into "March 2024", or "" if it can't.
func photoDate(s string) string {
	for _, layout := range []string{time.RFC3339Nano, time.RFC3339, "2006-01-02T15:04:05", "2006-01-02"} {
		if t, err := time.Parse(layout, s); err == nil {
			return t.Format("January 2006")
		}
	}
	return ""
}

func nonEmpty(s ...string) []string {
	out := s[:0:0]
	for _, x := range s {
		if strings.TrimSpace(x) != "" {
			out = append(out, x)
		}
	}
	return out
}
