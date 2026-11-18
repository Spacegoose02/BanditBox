package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"time"

	"github.com/spacegoose02/banditbox/server/internal/schema"
	"github.com/spacegoose02/banditbox/server/internal/syncdoc"
)

// changesKeep is how many versions of change records are kept per profile.
// A TV whose ops are based on an older version gets them all rejected as stale.
const changesKeep = 1000

// appliedOpsKeep is how long applied op ids are remembered, so a retried batch
// applies once.
const appliedOpsKeep = 30 * 24 * time.Hour

// Reasons an op is rejected.
const (
	RejectWebWins = "changed in the web UI"       // the web UI changed the same path since baseVersion
	RejectNewer   = "changed later on another TV" // another TV changed it with a later clock
	RejectStale   = "stale"                       // based on a version older than the last push
	RejectInvalid = "invalid"                     // would make the layout invalid, or the path is wrong
)

// Rejected is an op the server didn't apply.
type Rejected struct {
	OpID   string `json:"opId"`
	Reason string `json:"reason"`
	Detail string `json:"detail,omitempty"`
}

// OpsResult says what happened to a batch of ops.
type OpsResult struct {
	Version  int64      `json:"version"`
	Applied  []string   `json:"applied"`
	Rejected []Rejected `json:"rejected"`
}

// ErrFutureBase means a TV claims to have a version the server never made.
var ErrFutureBase = errors.New("baseVersion is newer than the server's layout")

type change struct {
	version  int64
	source   string
	deviceID string
	path     string
	hlc      string
}

// ApplyOps applies a TV's ops, made against baseVersion, following the conflict
// rules in handoff.md §6. All accepted ops become one new version.
func (s *Store) ApplyOps(ctx context.Context, profileID, deviceID string, baseVersion int64, ops []syncdoc.Op) (OpsResult, error) {
	res := OpsResult{Applied: []string{}, Rejected: []Rejected{}}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return res, err
	}
	defer tx.Rollback()

	var current, minBase int64
	var docText string
	err = tx.QueryRowContext(ctx, `SELECT version, doc, min_base FROM profiles WHERE id = ?`, profileID).Scan(&current, &docText, &minBase)
	if errors.Is(err, sql.ErrNoRows) {
		return res, ErrNotFound
	}
	if err != nil {
		return res, err
	}
	res.Version = current
	if baseVersion > current {
		return res, ErrFutureBase
	}
	stale := baseVersion < minBase || baseVersion <= current-changesKeep

	changes, err := changesSince(ctx, tx, profileID, baseVersion)
	if err != nil {
		return res, err
	}
	var doc syncdoc.Doc
	if err := json.Unmarshal([]byte(docText), &doc); err != nil {
		return res, err
	}

	// Apply in clock order; the TV's own order breaks ties.
	sorted := append([]syncdoc.Op(nil), ops...)
	sort.SliceStable(sorted, func(i, j int) bool { return sorted[i].HLC < sorted[j].HLC })

	var accepted []syncdoc.Op
	for _, op := range sorted {
		done, err := opApplied(ctx, tx, op.OpID)
		if err != nil {
			return res, err
		}
		if done {
			res.Applied = append(res.Applied, op.OpID)
			continue
		}
		if stale {
			res.Rejected = append(res.Rejected, Rejected{OpID: op.OpID, Reason: RejectStale})
			continue
		}
		if reason := conflict(op, deviceID, changes); reason != "" {
			res.Rejected = append(res.Rejected, Rejected{OpID: op.OpID, Reason: reason})
			continue
		}
		next := syncdoc.Clone(doc)
		if err := syncdoc.Apply(next, op); err != nil {
			res.Rejected = append(res.Rejected, Rejected{OpID: op.OpID, Reason: RejectInvalid, Detail: err.Error()})
			continue
		}
		next["profileId"], next["version"] = profileID, current+1
		raw, _ := json.Marshal(next)
		if err := schema.ValidateLayout(raw); err != nil {
			res.Rejected = append(res.Rejected, Rejected{OpID: op.OpID, Reason: RejectInvalid, Detail: err.Error()})
			continue
		}
		doc = next
		accepted = append(accepted, op)
		res.Applied = append(res.Applied, op.OpID)
	}

	if len(accepted) == 0 {
		return res, tx.Commit()
	}

	next := current + 1
	doc["profileId"], doc["version"] = profileID, next
	raw, err := json.Marshal(doc)
	if err != nil {
		return res, err
	}
	now := time.Now().UnixMilli()
	for _, op := range accepted {
		if err := recordChanges(ctx, tx, profileID, next, "device", deviceID, op.HLC, syncdoc.Touches(op)); err != nil {
			return res, err
		}
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO applied_ops (op_id, profile_id, device_id, version, applied_at) VALUES (?, ?, ?, ?, ?)`,
			op.OpID, profileID, deviceID, next, now); err != nil {
			return res, err
		}
	}
	if err := saveVersion(ctx, tx, profileID, next, raw, true, current, docText, now); err != nil {
		return res, err
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM applied_ops WHERE applied_at < ?`,
		time.Now().Add(-appliedOpsKeep).UnixMilli()); err != nil {
		return res, err
	}
	res.Version = next
	return res, tx.Commit()
}

// conflict returns why op loses to a change made since its base version, or "".
func conflict(op syncdoc.Op, deviceID string, changes []change) string {
	for _, path := range syncdoc.Touches(op) {
		for _, c := range changes {
			if !syncdoc.Overlaps(path, c.path) {
				continue
			}
			switch {
			case c.source == "admin":
				return RejectWebWins
			case c.deviceID != deviceID && c.hlc > op.HLC:
				return RejectNewer
			}
		}
	}
	return ""
}

func changesSince(ctx context.Context, tx *sql.Tx, profileID string, version int64) ([]change, error) {
	rows, err := tx.QueryContext(ctx,
		`SELECT version, source, device_id, path, hlc FROM layout_changes WHERE profile_id = ? AND version > ?`,
		profileID, version)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []change
	for rows.Next() {
		var c change
		if err := rows.Scan(&c.version, &c.source, &c.deviceID, &c.path, &c.hlc); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

func opApplied(ctx context.Context, tx *sql.Tx, opID string) (bool, error) {
	var one int
	err := tx.QueryRowContext(ctx, `SELECT 1 FROM applied_ops WHERE op_id = ?`, opID).Scan(&one)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	return err == nil, err
}

func recordChanges(ctx context.Context, tx *sql.Tx, profileID string, version int64, source, deviceID, hlc string, paths []string) error {
	for _, p := range paths {
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO layout_changes (profile_id, version, source, device_id, path, hlc) VALUES (?, ?, ?, ?, ?, ?)`,
			profileID, version, source, deviceID, p, hlc); err != nil {
			return fmt.Errorf("record change: %w", err)
		}
	}
	return nil
}

// changedPaths diffs two documents, ignoring the fields the server manages.
func changedPaths(a, b syncdoc.Doc) []string {
	var out []string
	for _, p := range syncdoc.Diff(a, b) {
		if p != "/version" && p != "/profileId" {
			out = append(out, p)
		}
	}
	return out
}
