// Package store keeps the server's data in one SQLite file in the data folder.
package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"

	_ "modernc.org/sqlite" // pure-Go SQLite driver, no CGO
)

// FileName is the database file inside the data folder.
const FileName = "banditbox.db"

// Store wraps the SQLite database.
type Store struct {
	db *sql.DB
}

// ErrNotFound is returned when a row doesn't exist.
var ErrNotFound = errors.New("not found")

// Open opens (or creates) the database in dataDir and applies any pending migrations.
func Open(ctx context.Context, dataDir string) (*Store, error) {
	if err := os.MkdirAll(dataDir, 0o750); err != nil {
		return nil, fmt.Errorf("create data folder: %w", err)
	}
	return OpenFile(ctx, filepath.Join(dataDir, FileName))
}

// OpenFile opens the database at path. Use ":memory:" in tests.
func OpenFile(ctx context.Context, path string) (*Store, error) {
	dsn := "file:" + path + "?_pragma=busy_timeout(5000)&_pragma=foreign_keys(1)&_pragma=journal_mode(WAL)&_pragma=synchronous(NORMAL)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	// One connection: SQLite allows one writer anyway, this keeps memory low and
	// makes ":memory:" databases behave in tests.
	db.SetMaxOpenConns(1)
	s := &Store{db: db}
	if err := s.migrate(ctx); err != nil {
		db.Close()
		return nil, fmt.Errorf("migrate: %w", err)
	}
	return s, nil
}

// Close closes the database.
func (s *Store) Close() error { return s.db.Close() }

// Ping checks the database is reachable.
func (s *Store) Ping(ctx context.Context) error { return s.db.PingContext(ctx) }

// migrations run in order; the database's user_version records how many have run.
// Never edit a released migration: add a new one.
var migrations = []string{
	`CREATE TABLE profiles (
		id         TEXT PRIMARY KEY,
		version    INTEGER NOT NULL,
		doc        TEXT NOT NULL,
		updated_at INTEGER NOT NULL
	) STRICT;
	CREATE TABLE layout_history (
		profile_id TEXT NOT NULL,
		version    INTEGER NOT NULL,
		doc        TEXT NOT NULL,
		saved_at   INTEGER NOT NULL,
		PRIMARY KEY (profile_id, version)
	) STRICT;`,
	`CREATE TABLE admins (
		id         INTEGER PRIMARY KEY,
		username   TEXT NOT NULL UNIQUE COLLATE NOCASE,
		pw_hash    TEXT NOT NULL,
		created_at INTEGER NOT NULL
	) STRICT;
	CREATE TABLE sessions (
		token_hash BLOB PRIMARY KEY,
		admin_id   INTEGER NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
		created_at INTEGER NOT NULL,
		expires_at INTEGER NOT NULL
	) STRICT;`,
	`CREATE TABLE devices (
		id         TEXT PRIMARY KEY,
		name       TEXT NOT NULL,
		model      TEXT NOT NULL DEFAULT '',
		token_hash BLOB NOT NULL UNIQUE,
		profiles   TEXT NOT NULL DEFAULT '[]',
		created_at INTEGER NOT NULL,
		last_seen  INTEGER NOT NULL DEFAULT 0,
		app_version TEXT NOT NULL DEFAULT ''
	) STRICT;`,
	`CREATE TABLE layout_changes (
		profile_id TEXT NOT NULL,
		version    INTEGER NOT NULL,
		source     TEXT NOT NULL,
		device_id  TEXT NOT NULL DEFAULT '',
		path       TEXT NOT NULL,
		hlc        TEXT NOT NULL DEFAULT ''
	) STRICT;
	CREATE INDEX layout_changes_by_version ON layout_changes (profile_id, version);
	CREATE TABLE applied_ops (
		op_id      TEXT PRIMARY KEY,
		profile_id TEXT NOT NULL,
		device_id  TEXT NOT NULL,
		version    INTEGER NOT NULL,
		applied_at INTEGER NOT NULL
	) STRICT;
	ALTER TABLE profiles ADD COLUMN min_base INTEGER NOT NULL DEFAULT 0;`,
	`ALTER TABLE devices ADD COLUMN apps TEXT NOT NULL DEFAULT '[]';`,
	`CREATE TABLE apks (
		sha256       TEXT PRIMARY KEY,
		package      TEXT NOT NULL,
		version_code INTEGER NOT NULL,
		version_name TEXT NOT NULL DEFAULT '',
		file_name    TEXT NOT NULL DEFAULT '',
		size         INTEGER NOT NULL,
		uploaded_at  INTEGER NOT NULL
	) STRICT;
	CREATE TABLE installs (
		device_id  TEXT NOT NULL,
		sha256     TEXT NOT NULL,
		status     TEXT NOT NULL,
		detail     TEXT NOT NULL DEFAULT '',
		updated_at INTEGER NOT NULL,
		PRIMARY KEY (device_id, sha256)
	) STRICT;`,
}

func (s *Store) migrate(ctx context.Context) error {
	var current int
	if err := s.db.QueryRowContext(ctx, "PRAGMA user_version").Scan(&current); err != nil {
		return err
	}
	if current > len(migrations) {
		return fmt.Errorf("database is from a newer BanditBox (schema %d, this build knows %d)", current, len(migrations))
	}
	for i := current; i < len(migrations); i++ {
		tx, err := s.db.BeginTx(ctx, nil)
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, migrations[i]); err != nil {
			tx.Rollback()
			return fmt.Errorf("migration %d: %w", i+1, err)
		}
		if _, err := tx.ExecContext(ctx, fmt.Sprintf("PRAGMA user_version = %d", i+1)); err != nil {
			tx.Rollback()
			return err
		}
		if err := tx.Commit(); err != nil {
			return err
		}
	}
	return nil
}
