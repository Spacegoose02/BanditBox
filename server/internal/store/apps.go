package store

import (
	"context"
	"encoding/json"
	"regexp"
	"sort"
	"strings"
	"unicode/utf8"
)

// App is an app installed on a TV, as the TV reports it.
type App struct {
	PackageName string `json:"packageName"`
	Label       string `json:"label"`
	// TV is true for apps with a TV launcher entry (Leanback).
	TV bool `json:"tv"`
}

// KnownApp is an app installed on at least one TV.
type KnownApp struct {
	App
	// Devices that have it installed.
	Devices []string `json:"devices"`
}

// MaxApps caps how many apps one TV may report.
const MaxApps = 2000

var packageName = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$`)

// CleanApps checks a TV's app list and tidies it: labels are trimmed and shortened,
// duplicates dropped. It returns nil and false if anything is not a valid package name.
func CleanApps(apps []App) ([]App, bool) {
	if len(apps) > MaxApps {
		return nil, false
	}
	seen := map[string]bool{}
	out := make([]App, 0, len(apps))
	for _, a := range apps {
		if len(a.PackageName) > 255 || !packageName.MatchString(a.PackageName) {
			return nil, false
		}
		if seen[a.PackageName] {
			continue
		}
		seen[a.PackageName] = true
		a.Label = strings.TrimSpace(a.Label)
		if utf8.RuneCountInString(a.Label) > 100 {
			a.Label = string([]rune(a.Label)[:100])
		}
		if a.Label == "" {
			a.Label = a.PackageName
		}
		out = append(out, a)
	}
	return out, true
}

// SetDeviceApps replaces the app list a TV reported.
func (s *Store) SetDeviceApps(ctx context.Context, deviceID string, apps []App) error {
	b, err := json.Marshal(apps)
	if err != nil {
		return err
	}
	res, err := s.db.ExecContext(ctx, `UPDATE devices SET apps = ? WHERE id = ?`, string(b), deviceID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// KnownApps lists every app installed on any paired TV, by label. When TVs disagree on a
// label, the first TV's (by id) wins; an app is a TV app if any TV says so.
func (s *Store) KnownApps(ctx context.Context) ([]KnownApp, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT id, apps FROM devices ORDER BY id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	byPkg := map[string]*KnownApp{}
	for rows.Next() {
		var id, text string
		if err := rows.Scan(&id, &text); err != nil {
			return nil, err
		}
		var apps []App
		if json.Unmarshal([]byte(text), &apps) != nil {
			continue
		}
		for _, a := range apps {
			k := byPkg[a.PackageName]
			if k == nil {
				k = &KnownApp{App: a, Devices: []string{}}
				byPkg[a.PackageName] = k
			}
			k.TV = k.TV || a.TV
			k.Devices = append(k.Devices, id)
		}
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	out := make([]KnownApp, 0, len(byPkg))
	for _, k := range byPkg {
		out = append(out, *k)
	}
	sort.Slice(out, func(i, j int) bool {
		a, b := strings.ToLower(out[i].Label), strings.ToLower(out[j].Label)
		if a != b {
			return a < b
		}
		return out[i].PackageName < out[j].PackageName
	})
	return out, nil
}
