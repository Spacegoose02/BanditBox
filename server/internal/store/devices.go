package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"
)

// Device is a paired TV.
type Device struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Model string `json:"model"`
	// Profiles the TV may sync. Empty means every profile.
	Profiles   []string  `json:"profiles"`
	Created    time.Time `json:"created"`
	LastSeen   time.Time `json:"lastSeen"`
	AppVersion string    `json:"appVersion"`
}

// AllowsProfile reports whether the device may sync profileID.
func (d Device) AllowsProfile(profileID string) bool {
	if len(d.Profiles) == 0 {
		return true
	}
	for _, p := range d.Profiles {
		if p == profileID {
			return true
		}
	}
	return false
}

// CreateDevice stores a newly paired TV with the hash of its token.
func (s *Store) CreateDevice(ctx context.Context, d Device, tokenHash []byte) error {
	profiles, _ := json.Marshal(nonNil(d.Profiles))
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO devices (id, name, model, token_hash, profiles, created_at) VALUES (?, ?, ?, ?, ?, ?)`,
		d.ID, d.Name, d.Model, tokenHash, string(profiles), time.Now().UnixMilli())
	return err
}

const deviceCols = `id, name, model, profiles, created_at, last_seen, app_version`

func scanDevice(sc interface{ Scan(...any) error }) (Device, error) {
	var d Device
	var profiles string
	var created, seen int64
	if err := sc.Scan(&d.ID, &d.Name, &d.Model, &profiles, &created, &seen, &d.AppVersion); err != nil {
		return Device{}, err
	}
	_ = json.Unmarshal([]byte(profiles), &d.Profiles)
	d.Profiles = nonNil(d.Profiles)
	d.Created = time.UnixMilli(created).UTC()
	if seen > 0 {
		d.LastSeen = time.UnixMilli(seen).UTC()
	}
	return d, nil
}

// ListDevices returns every paired TV, by name.
func (s *Store) ListDevices(ctx context.Context) ([]Device, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT `+deviceCols+` FROM devices ORDER BY name COLLATE NOCASE, id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Device{}
	for rows.Next() {
		d, err := scanDevice(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

// GetDevice returns one paired TV.
func (s *Store) GetDevice(ctx context.Context, id string) (Device, error) {
	d, err := scanDevice(s.db.QueryRowContext(ctx, `SELECT `+deviceCols+` FROM devices WHERE id = ?`, id))
	if errors.Is(err, sql.ErrNoRows) {
		return Device{}, ErrNotFound
	}
	return d, err
}

// DeviceByToken returns the TV a token belongs to.
func (s *Store) DeviceByToken(ctx context.Context, tokenHash []byte) (Device, error) {
	d, err := scanDevice(s.db.QueryRowContext(ctx, `SELECT `+deviceCols+` FROM devices WHERE token_hash = ?`, tokenHash))
	if errors.Is(err, sql.ErrNoRows) {
		return Device{}, ErrNotFound
	}
	return d, err
}

// UpdateDevice changes a TV's name and allowed profiles.
func (s *Store) UpdateDevice(ctx context.Context, id, name string, profiles []string) error {
	p, _ := json.Marshal(nonNil(profiles))
	res, err := s.db.ExecContext(ctx, `UPDATE devices SET name = ?, profiles = ? WHERE id = ?`, name, string(p), id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// TouchDevice records that a TV was just seen, with its app version.
func (s *Store) TouchDevice(ctx context.Context, id, appVersion string) error {
	_, err := s.db.ExecContext(ctx,
		`UPDATE devices SET last_seen = ?, app_version = CASE WHEN ? = '' THEN app_version ELSE ? END WHERE id = ?`,
		time.Now().UnixMilli(), appVersion, appVersion, id)
	return err
}

// DeleteDevice revokes a TV: its token stops working at once.
func (s *Store) DeleteDevice(ctx context.Context, id string) error {
	res, err := s.db.ExecContext(ctx, `DELETE FROM devices WHERE id = ?`, id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

func nonNil(s []string) []string {
	if s == nil {
		return []string{}
	}
	return s
}
