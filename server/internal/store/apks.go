package store

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

// APK is an app file uploaded to the server to install on TVs.
type APK struct {
	SHA256      string    `json:"sha256"`
	Package     string    `json:"package"`
	VersionCode int64     `json:"versionCode"`
	VersionName string    `json:"versionName"`
	FileName    string    `json:"fileName"`
	Size        int64     `json:"size"`
	Uploaded    time.Time `json:"uploaded"`
	Installs    []Install `json:"installs"`
}

// Install is one APK sent to one TV.
type Install struct {
	DeviceID string    `json:"deviceId"`
	SHA256   string    `json:"sha256"`
	Status   string    `json:"status"`
	Detail   string    `json:"detail"`
	Updated  time.Time `json:"updated"`
}

// Install statuses. The TV reports all but pending.
const (
	InstallPending   = "pending"   // sent; the TV hasn't picked it up yet
	InstallWaiting   = "waiting"   // the TV is waiting for someone to confirm on screen
	InstallInstalled = "installed" // done
	InstallSkipped   = "skipped"   // the TV already has this version or newer
	InstallFailed    = "failed"    // see Detail
)

// ValidReportedStatus reports whether a TV may set this status.
func ValidReportedStatus(s string) bool {
	return s == InstallWaiting || s == InstallInstalled || s == InstallSkipped || s == InstallFailed
}

// AddAPK records an uploaded APK. Uploading the same file again keeps the first record.
func (s *Store) AddAPK(ctx context.Context, a APK) (APK, error) {
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO apks (sha256, package, version_code, version_name, file_name, size, uploaded_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (sha256) DO NOTHING`,
		a.SHA256, a.Package, a.VersionCode, a.VersionName, a.FileName, a.Size, time.Now().UnixMilli())
	if err != nil {
		return APK{}, err
	}
	return s.GetAPK(ctx, a.SHA256)
}

const apkCols = `sha256, package, version_code, version_name, file_name, size, uploaded_at`

func scanAPK(sc interface{ Scan(...any) error }) (APK, error) {
	var a APK
	var up int64
	if err := sc.Scan(&a.SHA256, &a.Package, &a.VersionCode, &a.VersionName, &a.FileName, &a.Size, &up); err != nil {
		return APK{}, err
	}
	a.Uploaded = time.UnixMilli(up).UTC()
	a.Installs = []Install{}
	return a, nil
}

// GetAPK returns one APK with where it was sent.
func (s *Store) GetAPK(ctx context.Context, sha string) (APK, error) {
	a, err := scanAPK(s.db.QueryRowContext(ctx, `SELECT `+apkCols+` FROM apks WHERE sha256 = ?`, sha))
	if errors.Is(err, sql.ErrNoRows) {
		return APK{}, ErrNotFound
	}
	if err != nil {
		return APK{}, err
	}
	a.Installs, err = s.installs(ctx, `WHERE sha256 = ?`, sha)
	return a, err
}

// ListAPKs returns every uploaded APK, newest first, with where each was sent.
func (s *Store) ListAPKs(ctx context.Context) ([]APK, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT `+apkCols+` FROM apks ORDER BY uploaded_at DESC`)
	if err != nil {
		return nil, err
	}
	out := []APK{}
	for rows.Next() {
		a, err := scanAPK(rows)
		if err != nil {
			rows.Close()
			return nil, err
		}
		out = append(out, a)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	all, err := s.installs(ctx, ``)
	if err != nil {
		return nil, err
	}
	for i := range out {
		for _, in := range all {
			if in.SHA256 == out[i].SHA256 {
				out[i].Installs = append(out[i].Installs, in)
			}
		}
	}
	return out, nil
}

func (s *Store) installs(ctx context.Context, where string, args ...any) ([]Install, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT device_id, sha256, status, detail, updated_at FROM installs `+where+` ORDER BY device_id`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Install{}
	for rows.Next() {
		var in Install
		var up int64
		if err := rows.Scan(&in.DeviceID, &in.SHA256, &in.Status, &in.Detail, &up); err != nil {
			return nil, err
		}
		in.Updated = time.UnixMilli(up).UTC()
		out = append(out, in)
	}
	return out, rows.Err()
}

// DeleteAPK forgets an APK and every install of it that hasn't happened yet.
func (s *Store) DeleteAPK(ctx context.Context, sha string) error {
	res, err := s.db.ExecContext(ctx, `DELETE FROM apks WHERE sha256 = ?`, sha)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	_, err = s.db.ExecContext(ctx, `DELETE FROM installs WHERE sha256 = ?`, sha)
	return err
}

// QueueInstall sends an APK to TVs: each gets it (again) the next time it checks.
func (s *Store) QueueInstall(ctx context.Context, sha string, deviceIDs []string) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	now := time.Now().UnixMilli()
	for _, id := range deviceIDs {
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO installs (device_id, sha256, status, detail, updated_at) VALUES (?, ?, ?, '', ?)
			 ON CONFLICT (device_id, sha256) DO UPDATE SET status = excluded.status, detail = '', updated_at = excluded.updated_at`,
			id, sha, InstallPending, now); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// PendingInstalls lists the APKs a TV should install, oldest first.
func (s *Store) PendingInstalls(ctx context.Context, deviceID string) ([]APK, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT a.sha256, a.package, a.version_code, a.version_name, a.file_name, a.size, a.uploaded_at
		 FROM installs i JOIN apks a ON a.sha256 = i.sha256
		 WHERE i.device_id = ? AND i.status IN (?, ?) ORDER BY i.updated_at`, deviceID, InstallPending, InstallWaiting)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []APK{}
	for rows.Next() {
		a, err := scanAPK(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

// ReportInstall records what a TV did with an APK it was sent.
func (s *Store) ReportInstall(ctx context.Context, deviceID, sha, status, detail string) error {
	if len(detail) > 500 {
		detail = detail[:500]
	}
	res, err := s.db.ExecContext(ctx,
		`UPDATE installs SET status = ?, detail = ?, updated_at = ? WHERE device_id = ? AND sha256 = ?`,
		status, detail, time.Now().UnixMilli(), deviceID, sha)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}
