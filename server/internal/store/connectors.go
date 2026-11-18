package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"
)

// Connector is one configured integration that feeds cards to the TVs, such as a
// Weather Scout or StatScraps server. Its feeds are named "<id>.<feed>".
type Connector struct {
	ID       string            `json:"id"`
	Kind     string            `json:"kind"`
	Name     string            `json:"name"`
	Enabled  bool              `json:"enabled"`
	Settings map[string]string `json:"settings"`
	// Secret is an API key. It is never sent to the web UI.
	Secret  string    `json:"-"`
	Created time.Time `json:"created"`
}

const connectorCols = `id, kind, name, enabled, settings, secret, created_at`

func scanConnector(sc interface{ Scan(...any) error }) (Connector, error) {
	var c Connector
	var settings string
	var created int64
	if err := sc.Scan(&c.ID, &c.Kind, &c.Name, &c.Enabled, &settings, &c.Secret, &created); err != nil {
		return Connector{}, err
	}
	c.Created = time.UnixMilli(created)
	if err := json.Unmarshal([]byte(settings), &c.Settings); err != nil || c.Settings == nil {
		c.Settings = map[string]string{}
	}
	return c, nil
}

// ListConnectors returns every connector, oldest first.
func (s *Store) ListConnectors(ctx context.Context) ([]Connector, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT `+connectorCols+` FROM connectors ORDER BY created_at, id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Connector{}
	for rows.Next() {
		c, err := scanConnector(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// GetConnector returns one connector.
func (s *Store) GetConnector(ctx context.Context, id string) (Connector, error) {
	c, err := scanConnector(s.db.QueryRowContext(ctx, `SELECT `+connectorCols+` FROM connectors WHERE id = ?`, id))
	if errors.Is(err, sql.ErrNoRows) {
		return Connector{}, ErrNotFound
	}
	return c, err
}

// AddConnector stores a new connector.
func (s *Store) AddConnector(ctx context.Context, c Connector) (Connector, error) {
	settings, err := json.Marshal(nonNilMap(c.Settings))
	if err != nil {
		return Connector{}, err
	}
	res, err := s.db.ExecContext(ctx,
		`INSERT INTO connectors (`+connectorCols+`) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING`,
		c.ID, c.Kind, c.Name, c.Enabled, string(settings), c.Secret, time.Now().UnixMilli())
	if err != nil {
		return Connector{}, err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return Connector{}, ErrExists
	}
	return s.GetConnector(ctx, c.ID)
}

// UpdateConnector saves a connector's name, settings, secret and enabled flag.
func (s *Store) UpdateConnector(ctx context.Context, c Connector) error {
	settings, err := json.Marshal(nonNilMap(c.Settings))
	if err != nil {
		return err
	}
	res, err := s.db.ExecContext(ctx,
		`UPDATE connectors SET name = ?, enabled = ?, settings = ?, secret = ? WHERE id = ?`,
		c.Name, c.Enabled, string(settings), c.Secret, c.ID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// DeleteConnector removes a connector.
func (s *Store) DeleteConnector(ctx context.Context, id string) error {
	res, err := s.db.ExecContext(ctx, `DELETE FROM connectors WHERE id = ?`, id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

func nonNilMap(m map[string]string) map[string]string {
	if m == nil {
		return map[string]string{}
	}
	return m
}
