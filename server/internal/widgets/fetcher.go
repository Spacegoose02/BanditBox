package widgets

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const (
	// maxJSON caps a source's reply.
	maxJSON = 4 << 20
	// maxImage caps one picture copied to the server.
	maxImage = 4 << 20
	// maxRemembered caps how many picture URLs the fetcher remembers.
	maxRemembered = 2000
	// MediaPrefix starts a picture stored on the server.
	MediaPrefix = "media:sha256-"
)

// Fetcher makes a connector's HTTP calls and copies pictures that need its key.
type Fetcher struct {
	Client   *http.Client
	MediaDir string

	mu   sync.Mutex
	seen map[string]string // picture URL -> sha256
}

// NewFetcher returns a Fetcher that keeps pictures in mediaDir.
func NewFetcher(mediaDir string) *Fetcher {
	return &Fetcher{Client: &http.Client{Timeout: 15 * time.Second}, MediaDir: mediaDir, seen: map[string]string{}}
}

// HTTPError is a reply with a status other than 200.
type HTTPError struct {
	Status int
	URL    string
}

func (e *HTTPError) Error() string {
	switch e.Status {
	case http.StatusUnauthorized, http.StatusForbidden:
		return fmt.Sprintf("the key was refused (HTTP %d)", e.Status)
	case http.StatusNotFound:
		return "not found (HTTP 404): check the address"
	}
	return fmt.Sprintf("HTTP %d", e.Status)
}

// GetJSON fetches u and decodes the JSON reply into out. A non-empty key is sent as a Bearer token.
func (f *Fetcher) GetJSON(ctx context.Context, u, key string, out any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "BanditBox")
	if key != "" {
		req.Header.Set("Authorization", "Bearer "+key)
	}
	res, err := f.Client.Do(req)
	if err != nil {
		return cleanNetError(err)
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return &HTTPError{Status: res.StatusCode, URL: u}
	}
	dec := json.NewDecoder(io.LimitReader(res.Body, maxJSON))
	if err := dec.Decode(out); err != nil {
		return fmt.Errorf("the reply isn't the JSON expected: %w", err)
	}
	return nil
}

// cleanNetError drops the URL from a client error, which can hold a key in its query.
func cleanNetError(err error) error {
	var ue *url.Error
	if errors.As(err, &ue) {
		if ue.Timeout() {
			return errors.New("timed out")
		}
		return ue.Err
	}
	return err
}

// Image copies the picture at u to the server, sending key when it is set, and
// returns its media:sha256-<hex> reference, or "" if it can't be fetched.
func (f *Fetcher) Image(ctx context.Context, u, key string) string {
	if f.MediaDir == "" || u == "" {
		return ""
	}
	f.mu.Lock()
	sha, ok := f.seen[u]
	f.mu.Unlock()
	if ok {
		if _, err := os.Stat(filepath.Join(f.MediaDir, sha)); err == nil {
			touch(filepath.Join(f.MediaDir, sha))
			return MediaPrefix + sha
		}
	}
	sha, err := f.download(ctx, u, key)
	if err != nil {
		return ""
	}
	f.mu.Lock()
	if len(f.seen) >= maxRemembered {
		f.seen = map[string]string{}
	}
	f.seen[u] = sha
	f.mu.Unlock()
	return MediaPrefix + sha
}

func (f *Fetcher) download(ctx context.Context, u, key string) (string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", "BanditBox")
	if key != "" {
		req.Header.Set("Authorization", "Bearer "+key)
	}
	res, err := f.Client.Do(req)
	if err != nil {
		return "", err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return "", &HTTPError{Status: res.StatusCode, URL: u}
	}
	if mt, _, _ := mime.ParseMediaType(res.Header.Get("Content-Type")); !strings.HasPrefix(mt, "image/") {
		return "", errors.New("not a picture")
	}
	if err := os.MkdirAll(f.MediaDir, 0o750); err != nil {
		return "", err
	}
	tmp, err := os.CreateTemp(f.MediaDir, ".download-*")
	if err != nil {
		return "", err
	}
	defer os.Remove(tmp.Name())
	h := sha256.New()
	n, err := io.Copy(io.MultiWriter(tmp, h), io.LimitReader(res.Body, maxImage+1))
	if cerr := tmp.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		return "", err
	}
	if n > maxImage {
		return "", errors.New("picture is too big")
	}
	sha := hex.EncodeToString(h.Sum(nil))
	if err := os.Rename(tmp.Name(), filepath.Join(f.MediaDir, sha)); err != nil {
		return "", err
	}
	return sha, nil
}

// touch marks a picture as still used, so Prune keeps it.
func touch(path string) {
	now := time.Now()
	_ = os.Chtimes(path, now, now)
}

// Prune deletes pictures that no feed has used for olderThan.
func (f *Fetcher) Prune(olderThan time.Duration, inUse map[string]bool) {
	if f.MediaDir == "" {
		return
	}
	entries, err := os.ReadDir(f.MediaDir)
	if err != nil {
		return
	}
	cutoff := time.Now().Add(-olderThan)
	for _, e := range entries {
		if inUse[e.Name()] {
			continue
		}
		info, err := e.Info()
		if err != nil || info.ModTime().After(cutoff) {
			continue
		}
		_ = os.Remove(filepath.Join(f.MediaDir, e.Name()))
	}
}

// resolve turns a path from a source into a full URL on base.
func resolve(base, ref string) (string, error) {
	b, err := url.Parse(base)
	if err != nil {
		return "", err
	}
	r, err := url.Parse(ref)
	if err != nil {
		return "", err
	}
	return b.ResolveReference(r).String(), nil
}

// sameHost reports whether u is on the same host as base.
func sameHost(base, u string) bool {
	b, err1 := url.Parse(base)
	x, err2 := url.Parse(u)
	return err1 == nil && err2 == nil && strings.EqualFold(b.Host, x.Host)
}

// baseURL checks and cleans a connector's server address.
func baseURL(raw string) (string, error) {
	raw = strings.TrimSpace(raw)
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return "", errors.New("the address must start with http:// or https://")
	}
	u.RawQuery, u.Fragment = "", ""
	u.Path = strings.TrimRight(u.Path, "/")
	return u.String(), nil
}

// picture returns a card image for a source's picture reference: pictures on the
// source's own server are copied (they may need the key), https pictures elsewhere
// are passed to the TV as they are.
func (f *Fetcher) picture(ctx context.Context, base, ref, key string) string {
	if ref == "" {
		return ""
	}
	u, err := resolve(base+"/", ref)
	if err != nil {
		return ""
	}
	if sameHost(base, u) {
		return f.Image(ctx, u, key)
	}
	if strings.HasPrefix(u, "https://") && len(u) <= 2048 {
		return u
	}
	return f.Image(ctx, u, "")
}
