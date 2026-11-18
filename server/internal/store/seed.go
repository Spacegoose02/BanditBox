package store

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/spacegoose02/banditbox/server/internal/schema"
)

// ErrNotFresh means the server already has edited layouts, so a TV can't seed it.
var ErrNotFresh = errors.New("the server already has layouts")

// Fresh reports whether no profile has ever been edited (every one is at version 0).
func (s *Store) Fresh(ctx context.Context) (bool, error) {
	var n int
	err := s.db.QueryRowContext(ctx, `SELECT count(*) FROM profiles WHERE version > 0`).Scan(&n)
	return n == 0, err
}

// Seed replaces every profile with docs, the layouts a TV had before it was first
// paired, so moving to the server keeps the setup the TV already has. It only works
// on a fresh server. The seeded layouts start at version 1, so the server is no
// longer fresh afterwards.
func (s *Store) Seed(ctx context.Context, docs []json.RawMessage) ([]Layout, error) {
	if len(docs) == 0 {
		return nil, &ValidationError{Err: errors.New("no layouts")}
	}
	type prepared struct {
		id  string
		raw []byte
	}
	var ready []prepared
	seen := map[string]bool{}
	for i, doc := range docs {
		var m map[string]any
		if err := json.Unmarshal(doc, &m); err != nil {
			return nil, &ValidationError{Err: fmt.Errorf("layout %d: %w", i, err)}
		}
		id, _ := m["profileId"].(string)
		if id == "" || seen[id] {
			return nil, &ValidationError{Err: fmt.Errorf("layout %d: missing or repeated profileId", i)}
		}
		seen[id] = true
		m["version"] = 1
		raw, _ := json.Marshal(m)
		if err := schema.ValidateLayout(raw); err != nil {
			return nil, &ValidationError{Err: fmt.Errorf("layout %s: %w", id, err)}
		}
		ready = append(ready, prepared{id, raw})
	}

	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	var edited int
	if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM profiles WHERE version > 0`).Scan(&edited); err != nil {
		return nil, err
	}
	if edited > 0 {
		return nil, ErrNotFresh
	}
	for _, q := range []string{`DELETE FROM profiles`, `DELETE FROM layout_history`, `DELETE FROM layout_changes`, `DELETE FROM applied_ops`} {
		if _, err := tx.ExecContext(ctx, q); err != nil {
			return nil, err
		}
	}
	now := time.Now().UnixMilli()
	out := make([]Layout, 0, len(ready))
	for _, p := range ready {
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO profiles (id, version, doc, updated_at) VALUES (?, 1, ?, ?)`, p.id, string(p.raw), now); err != nil {
			return nil, err
		}
		out = append(out, Layout{ProfileID: p.id, Version: 1, Doc: p.raw})
	}
	return out, tx.Commit()
}
