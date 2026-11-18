package widgets

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"testing"

	"github.com/spacegoose02/banditbox/server/internal/store"
)

func needHeader(name, value string, h func(w http.ResponseWriter, r *http.Request)) func(w http.ResponseWriter, r *http.Request) {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get(name) != value {
			http.Error(w, `{"error":"no"}`, http.StatusUnauthorized)
			return
		}
		h(w, r)
	}
}

func picture(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "image/jpeg")
	_, _ = w.Write(png)
}

func TestImmichAlbum(t *testing.T) {
	srv := serve(t, map[string]func(http.ResponseWriter, *http.Request){
		"/api/albums/al-1": needHeader("X-Api-Key", "k1", jsonReply(`{"albumName": "Beach 2024", "assets": [
			{"id": "a1", "type": "IMAGE", "fileCreatedAt": "2024-03-02T10:00:00.000Z",
			 "exifInfo": {"city": "Nags Head", "country": "United States", "exifImageWidth": 4000, "exifImageHeight": 3000, "orientation": "6"}},
			{"id": "v1", "type": "VIDEO"},
			{"id": "a2", "type": "IMAGE", "fileCreatedAt": "2023-07-04T10:00:00Z"}
		]}`)),
		"/api/assets/a1/thumbnail": needHeader("X-Api-Key", "k1", picture),
		"/api/assets/a2/thumbnail": needHeader("X-Api-Key", "k1", picture),
	})
	c := store.Connector{ID: "immich", Kind: "immich", Secret: "k1", Settings: map[string]string{"url": srv.URL, "album": "al-1", "kidsSafe": "Yes"}}
	res, err := immich.Fetch(context.Background(), NewFetcher(t.TempDir()), c)
	if err != nil {
		t.Fatal(err)
	}
	valid(t, res.Feeds)
	f := res.Feeds[0]
	if f.ID != "immich.photos" || f.Name != "Beach 2024" || !f.KidsSafe || len(f.Cards) != 2 {
		t.Fatalf("feed = %+v", f)
	}
	for _, card := range f.Cards {
		if !strings.HasPrefix(card.Image, MediaPrefix) {
			t.Errorf("image %q", card.Image)
		}
		if card.ID == "a1" && (card.Title != "Nags Head, United States" || card.Subtitle != "March 2024" || card.ImageAspect != 0.75) {
			t.Errorf("a1 = %+v", card)
		}
	}
}

func TestImmichRandom(t *testing.T) {
	var asked map[string]any
	srv := serve(t, map[string]func(http.ResponseWriter, *http.Request){
		"/api/search/random": needHeader("X-Api-Key", "k1", func(w http.ResponseWriter, r *http.Request) {
			if r.Method != http.MethodPost {
				http.Error(w, "post", http.StatusMethodNotAllowed)
				return
			}
			_ = json.NewDecoder(r.Body).Decode(&asked)
			jsonReply(`[{"id": "a1", "type": "IMAGE"}]`)(w, r)
		}),
		"/api/assets/a1/thumbnail": needHeader("X-Api-Key", "k1", picture),
	})
	c := store.Connector{ID: "immich", Kind: "immich", Secret: "k1", Settings: map[string]string{"url": srv.URL}}
	res, err := immich.Fetch(context.Background(), NewFetcher(t.TempDir()), c)
	if err != nil {
		t.Fatal(err)
	}
	valid(t, res.Feeds)
	if asked["type"] != "IMAGE" || len(res.Feeds[0].Cards) != 1 || res.Feeds[0].KidsSafe {
		t.Fatalf("asked %v, feed %+v", asked, res.Feeds[0])
	}
	c.Secret = "wrong"
	if _, err := immich.Fetch(context.Background(), NewFetcher(t.TempDir()), c); err == nil || !strings.Contains(err.Error(), "refused") {
		t.Fatalf("wrong key: %v", err)
	}
}

func TestPlexPhotos(t *testing.T) {
	srv := serve(t, map[string]func(http.ResponseWriter, *http.Request){
		"/library/sections/3/all": needHeader("X-Plex-Token", "tok", func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Query().Get("type") != "13" {
				http.Error(w, "type", http.StatusBadRequest)
				return
			}
			jsonReply(`{"MediaContainer": {"title1": "Family Photos", "Metadata": [
				{"ratingKey": "101", "type": "photo", "title": "IMG_1", "parentTitle": "Christmas", "originallyAvailableAt": "2022-12-25",
				 "Media": [{"width": 1920, "height": 1080, "Part": [{"key": "/library/parts/9/file.jpg"}]}]},
				{"ratingKey": "102", "type": "photo", "Media": []}
			]}}`)(w, r)
		}),
		"/photo/:/transcode": needHeader("X-Plex-Token", "tok", func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Query().Get("url") != "/library/parts/9/file.jpg" {
				http.NotFound(w, r)
				return
			}
			picture(w, r)
		}),
	})
	c := store.Connector{ID: "plexphotos", Kind: "plexphotos", Secret: "tok", Settings: map[string]string{"url": srv.URL, "section": "3"}}
	res, err := plexPhotos.Fetch(context.Background(), NewFetcher(t.TempDir()), c)
	if err != nil {
		t.Fatal(err)
	}
	valid(t, res.Feeds)
	f := res.Feeds[0]
	if f.Name != "Family Photos" || len(f.Cards) != 1 || f.Cards[0].Title != "Christmas" || f.Cards[0].Subtitle != "December 2022" {
		t.Fatalf("feed = %+v", f)
	}
	c.Settings["section"] = "photos"
	if _, err := plexPhotos.Fetch(context.Background(), NewFetcher(t.TempDir()), c); err == nil {
		t.Fatal("want an error for a section that isn't a number")
	}
}
