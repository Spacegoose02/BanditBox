package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/spacegoose02/banditbox/server/internal/schema"
)

// historyKeep is how many earlier versions of each layout are kept.
const historyKeep = 50

// ErrVersionConflict means the layout changed since the caller read it.
var ErrVersionConflict = errors.New("layout changed since it was read")

// ProfileSummary is one row of the profile list.
type ProfileSummary struct {
	ID      string    `json:"id"`
	Name    string    `json:"name"`
	Version int64     `json:"version"`
	Updated time.Time `json:"updated"`
}

// Layout is a stored layout document with its version.
type Layout struct {
	ProfileID string
	Version   int64
	Doc       json.RawMessage
}

// ListProfiles returns every profile, ordered by id.
func (s *Store) ListProfiles(ctx context.Context) ([]ProfileSummary, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, version, json_extract(doc, '$.profile.name'), updated_at FROM profiles ORDER BY id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []ProfileSummary{}
	for rows.Next() {
		var p ProfileSummary
		var updated int64
		if err := rows.Scan(&p.ID, &p.Version, &p.Name, &updated); err != nil {
			return nil, err
		}
		p.Updated = time.UnixMilli(updated).UTC()
		out = append(out, p)
	}
	return out, rows.Err()
}

// GetLayout returns a profile's current layout document.
func (s *Store) GetLayout(ctx context.Context, profileID string) (Layout, error) {
	l := Layout{ProfileID: profileID}
	var doc string
	err := s.db.QueryRowContext(ctx, `SELECT version, doc FROM profiles WHERE id = ?`, profileID).Scan(&l.Version, &doc)
	if errors.Is(err, sql.ErrNoRows) {
		return Layout{}, ErrNotFound
	}
	l.Doc = json.RawMessage(doc)
	return l, err
}

// PutLayout validates doc and saves it as the profile's new layout.
//
// baseVersion is the version the caller edited: if the stored layout has moved on,
// ErrVersionConflict is returned and nothing changes. Use -1 to create a profile
// that must not exist yet. The saved document's version is baseVersion+1 and its
// profileId is forced to match. The previous document is kept in history.
func (s *Store) PutLayout(ctx context.Context, profileID string, baseVersion int64, doc json.RawMessage) (Layout, error) {
	var m map[string]any
	if err := json.Unmarshal(doc, &m); err != nil {
		return Layout{}, &ValidationError{Err: fmt.Errorf("not a JSON object: %w", err)}
	}
	next := baseVersion + 1
	m["profileId"] = profileID
	m["version"] = next
	raw, err := json.Marshal(m)
	if err != nil {
		return Layout{}, err
	}
	if err := schema.ValidateLayout(raw); err != nil {
		return Layout{}, &ValidationError{Err: err}
	}

	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return Layout{}, err
	}
	defer tx.Rollback()

	var current int64
	var prevDoc string
	err = tx.QueryRowContext(ctx, `SELECT version, doc FROM profiles WHERE id = ?`, profileID).Scan(&current, &prevDoc)
	exists := err == nil
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return Layout{}, err
	}
	switch {
	case baseVersion == -1 && exists:
		return Layout{}, ErrVersionConflict
	case baseVersion >= 0 && !exists:
		return Layout{}, ErrNotFound
	case exists && current != baseVersion:
		return Layout{}, ErrVersionConflict
	}

	now := time.Now().UnixMilli()
	if exists {
		if _, err := tx.ExecContext(ctx,
			`INSERT OR REPLACE INTO layout_history (profile_id, version, doc, saved_at) VALUES (?, ?, ?, ?)`,
			profileID, current, prevDoc, now); err != nil {
			return Layout{}, err
		}
		if _, err := tx.ExecContext(ctx,
			`DELETE FROM layout_history WHERE profile_id = ? AND version <= ?`, profileID, current-historyKeep); err != nil {
			return Layout{}, err
		}
	}
	if _, err := tx.ExecContext(ctx,
		`INSERT INTO profiles (id, version, doc, updated_at) VALUES (?, ?, ?, ?)
		 ON CONFLICT(id) DO UPDATE SET version = excluded.version, doc = excluded.doc, updated_at = excluded.updated_at`,
		profileID, next, string(raw), now); err != nil {
		return Layout{}, err
	}
	if err := tx.Commit(); err != nil {
		return Layout{}, err
	}
	return Layout{ProfileID: profileID, Version: next, Doc: raw}, nil
}

// DeleteProfile removes a profile and its history.
func (s *Store) DeleteProfile(ctx context.Context, profileID string) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	res, err := tx.ExecContext(ctx, `DELETE FROM profiles WHERE id = ?`, profileID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM layout_history WHERE profile_id = ?`, profileID); err != nil {
		return err
	}
	return tx.Commit()
}

// LayoutHistory returns the kept earlier versions of a profile's layout, newest first.
func (s *Store) LayoutHistory(ctx context.Context, profileID string) ([]Layout, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT version, doc FROM layout_history WHERE profile_id = ? ORDER BY version DESC`, profileID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Layout{}
	for rows.Next() {
		l := Layout{ProfileID: profileID}
		var doc string
		if err := rows.Scan(&l.Version, &doc); err != nil {
			return nil, err
		}
		l.Doc = json.RawMessage(doc)
		out = append(out, l)
	}
	return out, rows.Err()
}

// DefaultProfileID matches the TV app's first profile, so a TV's local profile and
// the server's line up when it first syncs.
const DefaultProfileID = "p_default"

// EnsureDefaultProfile creates the "Home" profile when the server has none.
func (s *Store) EnsureDefaultProfile(ctx context.Context) error {
	var n int
	if err := s.db.QueryRowContext(ctx, `SELECT count(*) FROM profiles`).Scan(&n); err != nil {
		return err
	}
	if n > 0 {
		return nil
	}
	doc := json.RawMessage(`{
		"schema": 1,
		"profileId": "p_default",
		"version": 0,
		"profile": { "name": "Home" },
		"rows": [ { "id": "favorites", "type": "apps", "title": "Favorites", "tile": { "size": "large" }, "items": [] } ],
		"topBar": ["clock", "profile", "inputs", "network", "settings"]
	}`)
	_, err := s.PutLayout(ctx, DefaultProfileID, -1, doc)
	return err
}

// ValidationError means a document doesn't match the schema.
type ValidationError struct{ Err error }

func (e *ValidationError) Error() string { return "invalid layout: " + e.Err.Error() }
func (e *ValidationError) Unwrap() error { return e.Err }
