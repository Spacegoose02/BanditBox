package store

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

// Admin is a web UI user.
type Admin struct {
	ID       int64  `json:"id"`
	Username string `json:"username"`
	PwHash   string `json:"-"`
}

// ErrExists means the row being created already exists.
var ErrExists = errors.New("already exists")

// AdminCount returns how many admins exist. Zero means first-run setup is needed.
func (s *Store) AdminCount(ctx context.Context) (int, error) {
	var n int
	err := s.db.QueryRowContext(ctx, `SELECT count(*) FROM admins`).Scan(&n)
	return n, err
}

// CreateFirstAdmin creates an admin only if there are none yet, atomically,
// so two setup requests can't both succeed.
func (s *Store) CreateFirstAdmin(ctx context.Context, username, pwHash string) (Admin, error) {
	res, err := s.db.ExecContext(ctx,
		`INSERT INTO admins (username, pw_hash, created_at)
		 SELECT ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM admins)`,
		username, pwHash, time.Now().UnixMilli())
	if err != nil {
		return Admin{}, err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return Admin{}, ErrExists
	}
	id, _ := res.LastInsertId()
	return Admin{ID: id, Username: username, PwHash: pwHash}, nil
}

// AdminByUsername looks an admin up, ignoring case.
func (s *Store) AdminByUsername(ctx context.Context, username string) (Admin, error) {
	var a Admin
	err := s.db.QueryRowContext(ctx, `SELECT id, username, pw_hash FROM admins WHERE username = ?`, username).
		Scan(&a.ID, &a.Username, &a.PwHash)
	if errors.Is(err, sql.ErrNoRows) {
		return Admin{}, ErrNotFound
	}
	return a, err
}

// SetAdminPassword replaces an admin's password hash and signs out all their sessions.
func (s *Store) SetAdminPassword(ctx context.Context, adminID int64, pwHash string) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `UPDATE admins SET pw_hash = ? WHERE id = ?`, pwHash, adminID); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM sessions WHERE admin_id = ?`, adminID); err != nil {
		return err
	}
	return tx.Commit()
}

// CreateSession stores a session by the hash of its token.
func (s *Store) CreateSession(ctx context.Context, tokenHash []byte, adminID int64, expires time.Time) error {
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO sessions (token_hash, admin_id, created_at, expires_at) VALUES (?, ?, ?, ?)`,
		tokenHash, adminID, time.Now().UnixMilli(), expires.UnixMilli())
	return err
}

// SessionAdmin returns the admin for a live session and slides its expiry forward
// to extendTo when that is later than the current expiry.
func (s *Store) SessionAdmin(ctx context.Context, tokenHash []byte, extendTo time.Time) (Admin, error) {
	var a Admin
	var expires int64
	err := s.db.QueryRowContext(ctx,
		`SELECT a.id, a.username, a.pw_hash, s.expires_at FROM sessions s JOIN admins a ON a.id = s.admin_id
		 WHERE s.token_hash = ?`, tokenHash).Scan(&a.ID, &a.Username, &a.PwHash, &expires)
	if errors.Is(err, sql.ErrNoRows) {
		return Admin{}, ErrNotFound
	}
	if err != nil {
		return Admin{}, err
	}
	now := time.Now().UnixMilli()
	if expires <= now {
		_, _ = s.db.ExecContext(ctx, `DELETE FROM sessions WHERE token_hash = ?`, tokenHash)
		return Admin{}, ErrNotFound
	}
	// Only write when it moves by more than an hour, to avoid a write per request.
	if ext := extendTo.UnixMilli(); ext-expires > int64(time.Hour/time.Millisecond) {
		_, _ = s.db.ExecContext(ctx, `UPDATE sessions SET expires_at = ? WHERE token_hash = ?`, ext, tokenHash)
	}
	return a, nil
}

// DeleteSession signs a session out.
func (s *Store) DeleteSession(ctx context.Context, tokenHash []byte) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM sessions WHERE token_hash = ?`, tokenHash)
	return err
}

// DeleteExpiredSessions removes sessions past their expiry.
func (s *Store) DeleteExpiredSessions(ctx context.Context) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM sessions WHERE expires_at <= ?`, time.Now().UnixMilli())
	return err
}
