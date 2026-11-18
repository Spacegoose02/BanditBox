package widgets

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/spacegoose02/banditbox/server/internal/store"
)

// Field is one setting of a connector kind, described so the web UI can draw the form.
type Field struct {
	Key         string `json:"key"`
	Label       string `json:"label"`
	Help        string `json:"help,omitempty"`
	Placeholder string `json:"placeholder,omitempty"`
	Required    bool   `json:"required,omitempty"`
	// Secret fields are stored as the connector's secret and never sent back.
	Secret bool `json:"secret,omitempty"`
}

// FeedInfo names a feed a connector kind makes, for the web UI.
type FeedInfo struct {
	Suffix   string `json:"suffix"`
	Name     string `json:"name"`
	Template string `json:"template"`
}

// Result is what one fetch produced.
type Result struct {
	Feeds []Feed
	// Next is when to fetch again; zero means the kind's default.
	Next time.Duration
}

// Kind is a type of connector.
type Kind struct {
	ID          string     `json:"id"`
	Name        string     `json:"name"`
	Description string     `json:"description"`
	Fields      []Field    `json:"fields"`
	Feeds       []FeedInfo `json:"feeds"`
	// Every is how often to fetch when the fetch doesn't say.
	Every time.Duration                                                            `json:"-"`
	Fetch func(ctx context.Context, f *Fetcher, c store.Connector) (Result, error) `json:"-"`
}

// SecretKey is the settings key that holds a kind's secret field in requests from the web UI.
const SecretKey = "key"

// Kinds lists the connector kinds, in the order the web UI shows them.
func Kinds() []Kind {
	return []Kind{weatherScout, statScraps, bingeBandit, immich, plexPhotos, jsonFeed}
}

// KindByID returns a kind, or false.
func KindByID(id string) (Kind, bool) {
	for _, k := range Kinds() {
		if k.ID == id {
			return k, true
		}
	}
	return Kind{}, false
}

// maxSetting caps one setting's length.
const maxSetting = 500

// CleanSettings checks settings for kind k and splits out the secret. hasSecret
// says a secret is already stored, so leaving the key empty keeps it.
func CleanSettings(k Kind, in map[string]string, hasSecret bool) (settings map[string]string, secret string, err error) {
	settings = map[string]string{}
	known := map[string]Field{}
	for _, f := range k.Fields {
		known[f.Key] = f
	}
	for key, v := range in {
		f, ok := known[key]
		if !ok {
			return nil, "", fmt.Errorf("%s has no setting %q", k.Name, key)
		}
		v = strings.TrimSpace(v)
		if len(v) > maxSetting {
			return nil, "", fmt.Errorf("%s is too long", f.Label)
		}
		if f.Secret {
			secret = v
		} else if v != "" {
			settings[key] = v
		}
	}
	for _, f := range k.Fields {
		if !f.Required {
			continue
		}
		if f.Secret && secret == "" && !hasSecret {
			return nil, "", errors.New(f.Label + " is needed")
		}
		if !f.Secret && settings[f.Key] == "" {
			return nil, "", errors.New(f.Label + " is needed")
		}
	}
	if u, ok := settings["url"]; ok {
		if _, err := baseURL(u); err != nil {
			return nil, "", err
		}
	}
	return settings, secret, nil
}
