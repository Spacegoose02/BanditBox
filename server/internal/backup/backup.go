// Package backup keeps snapshots of every profile's layout on the server, in the same file
// format as a backup made on the TV (schema/backup.schema.json), so either can restore the other.
package backup

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"time"

	"github.com/spacegoose02/banditbox/server/internal/schema"
	"github.com/spacegoose02/banditbox/server/internal/store"
)

const (
	Format  = "banditbox-backup"
	Version = 1
	// Keep is how many automatic backups are kept; backups made by hand and before a restore count too.
	Keep = 30
)

// File is a backup file's contents.
type File struct {
	Format        string            `json:"format"`
	BackupVersion int               `json:"backupVersion"`
	CreatedAt     string            `json:"createdAt"`
	App           string            `json:"app,omitempty"`
	Layouts       []json.RawMessage `json:"layouts"`
}

// Entry describes a saved backup.
type Entry struct {
	Name     string    `json:"name"`
	Size     int64     `json:"size"`
	Created  time.Time `json:"created"`
	Profiles int       `json:"profiles"`
}

// ErrNotFound means there is no backup with that name.
var ErrNotFound = errors.New("no such backup")

// InvalidError says why a file can't be restored.
type InvalidError struct{ Reason string }

func (e *InvalidError) Error() string { return e.Reason }

var nameRE = regexp.MustCompile(`^banditbox-(auto|manual|before-restore)-\d{8}-\d{6}\.json$`)

// Manager makes, lists and restores backups in Dir.
type Manager struct {
	Dir     string
	Store   *store.Store
	Version string
	Log     *slog.Logger
	// Now is the clock; tests replace it.
	Now func() time.Time
}

func (m *Manager) now() time.Time {
	if m.Now != nil {
		return m.Now()
	}
	return time.Now()
}

// Snapshot builds a backup of every profile as it is now.
func (m *Manager) Snapshot(ctx context.Context) (File, error) {
	list, err := m.Store.ListProfiles(ctx)
	if err != nil {
		return File{}, err
	}
	f := File{Format: Format, BackupVersion: Version, CreatedAt: m.now().UTC().Format(time.RFC3339), App: "server " + m.Version}
	for _, p := range list {
		l, err := m.Store.GetLayout(ctx, p.ID)
		if errors.Is(err, store.ErrNotFound) {
			continue // deleted meanwhile
		}
		if err != nil {
			return File{}, err
		}
		f.Layouts = append(f.Layouts, l.Doc)
	}
	return f, nil
}

// Create saves a backup now. kind is auto, manual or before-restore. An automatic backup
// is skipped (and nil returned) when nothing changed since the newest backup.
func (m *Manager) Create(ctx context.Context, kind string) (*Entry, error) {
	f, err := m.Snapshot(ctx)
	if err != nil {
		return nil, err
	}
	data, err := json.MarshalIndent(f, "", "  ")
	if err != nil {
		return nil, err
	}
	if kind == "auto" {
		if same, err := m.sameAsNewest(f); err != nil {
			return nil, err
		} else if same {
			return nil, nil
		}
	}
	if err := os.MkdirAll(m.Dir, 0o750); err != nil {
		return nil, err
	}
	t := m.now().UTC()
	name := fmt.Sprintf("banditbox-%s-%s.json", kind, t.Format("20060102-150405"))
	tmp := filepath.Join(m.Dir, "."+name+".tmp")
	if err := os.WriteFile(tmp, append(data, '\n'), 0o640); err != nil {
		return nil, err
	}
	if err := os.Rename(tmp, filepath.Join(m.Dir, name)); err != nil {
		os.Remove(tmp)
		return nil, err
	}
	m.prune()
	return &Entry{Name: name, Size: int64(len(data) + 1), Created: t, Profiles: len(f.Layouts)}, nil
}

// sameAsNewest reports whether the newest backup holds exactly these layouts.
func (m *Manager) sameAsNewest(f File) (bool, error) {
	list, err := m.List()
	if err != nil || len(list) == 0 {
		return false, err
	}
	old, err := m.Read(list[0].Name)
	if err != nil {
		return false, nil
	}
	return layoutsHash(old.Layouts) == layoutsHash(f.Layouts), nil
}

func layoutsHash(layouts []json.RawMessage) [32]byte {
	h := sha256.New()
	for _, l := range layouts {
		var buf bytes.Buffer
		if json.Compact(&buf, l) == nil {
			h.Write(buf.Bytes())
		}
		h.Write([]byte{0})
	}
	var out [32]byte
	copy(out[:], h.Sum(nil))
	return out
}

// List returns saved backups, newest first.
func (m *Manager) List() ([]Entry, error) {
	des, err := os.ReadDir(m.Dir)
	if errors.Is(err, os.ErrNotExist) {
		return []Entry{}, nil
	}
	if err != nil {
		return nil, err
	}
	out := []Entry{}
	for _, de := range des {
		if !nameRE.MatchString(de.Name()) {
			continue
		}
		info, err := de.Info()
		if err != nil {
			continue
		}
		name := de.Name()
		e := Entry{Name: name, Size: info.Size(), Created: info.ModTime().UTC()}
		// The name ends in YYYYMMDD-HHMMSS.json.
		if t, err := time.Parse("20060102-150405", name[len(name)-20:len(name)-5]); err == nil {
			e.Created = t.UTC()
		}
		if data, err := os.ReadFile(filepath.Join(m.Dir, name)); err == nil {
			var f struct {
				Layouts []json.RawMessage `json:"layouts"`
			}
			if json.Unmarshal(data, &f) == nil {
				e.Profiles = len(f.Layouts)
			}
		}
		out = append(out, e)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Created.After(out[j].Created) })
	return out, nil
}

// Path returns where a backup is stored, checking the name first.
func (m *Manager) Path(name string) (string, error) {
	if !nameRE.MatchString(name) {
		return "", ErrNotFound
	}
	p := filepath.Join(m.Dir, name)
	if _, err := os.Stat(p); err != nil {
		return "", ErrNotFound
	}
	return p, nil
}

// Read loads and checks a saved backup.
func (m *Manager) Read(name string) (File, error) {
	p, err := m.Path(name)
	if err != nil {
		return File{}, err
	}
	data, err := os.ReadFile(p)
	if err != nil {
		return File{}, err
	}
	return Parse(data)
}

// Parse reads and checks a backup file, from this server or a TV.
func Parse(data []byte) (File, error) {
	var f File
	if err := json.Unmarshal(data, &f); err != nil || f.Format != Format {
		return File{}, &InvalidError{"not a BanditBox backup"}
	}
	if f.BackupVersion != Version {
		return File{}, &InvalidError{fmt.Sprintf("made by a newer BanditBox (backup version %d)", f.BackupVersion)}
	}
	if len(f.Layouts) == 0 {
		return File{}, &InvalidError{"the backup has no profiles"}
	}
	seen := map[string]bool{}
	for _, l := range f.Layouts {
		var head struct {
			ProfileID string `json:"profileId"`
		}
		_ = json.Unmarshal(l, &head)
		if err := schema.ValidateLayout(l); err != nil {
			return File{}, &InvalidError{fmt.Sprintf("profile %q isn't valid: %v", head.ProfileID, err)}
		}
		if seen[head.ProfileID] {
			return File{}, &InvalidError{"the backup has the same profile twice: " + head.ProfileID}
		}
		seen[head.ProfileID] = true
	}
	return f, nil
}

// Restore replaces every profile with the backup's, after saving a backup of how things are
// now. Restored profiles win on every TV, as with a push. It returns the profiles that changed
// and whether profiles were added or removed.
func (m *Manager) Restore(ctx context.Context, f File) (changed map[string]int64, listChanged bool, err error) {
	if _, err := m.Create(ctx, "before-restore"); err != nil {
		return nil, false, fmt.Errorf("couldn't save a backup first: %w", err)
	}
	changed = map[string]int64{}
	keep := map[string]bool{}
	for _, raw := range f.Layouts {
		var head struct {
			ProfileID string `json:"profileId"`
		}
		_ = json.Unmarshal(raw, &head)
		id := head.ProfileID
		keep[id] = true
		base := int64(-1)
		if cur, err := m.Store.GetLayout(ctx, id); err == nil {
			if sameJSON(cur.Doc, raw) {
				continue
			}
			base = cur.Version
		} else if !errors.Is(err, store.ErrNotFound) {
			return changed, listChanged, err
		} else {
			listChanged = true
		}
		l, err := m.Store.PutLayout(ctx, id, base, raw)
		if err != nil {
			return changed, listChanged, fmt.Errorf("restoring %s: %w", id, err)
		}
		if err := m.Store.PushProfile(ctx, id); err != nil {
			return changed, listChanged, err
		}
		changed[id] = l.Version
	}
	list, err := m.Store.ListProfiles(ctx)
	if err != nil {
		return changed, listChanged, err
	}
	for _, p := range list {
		if !keep[p.ID] {
			if err := m.Store.DeleteProfile(ctx, p.ID); err != nil && !errors.Is(err, store.ErrNotFound) {
				return changed, listChanged, err
			}
			listChanged = true
		}
	}
	return changed, listChanged, nil
}

// sameJSON compares two layouts, ignoring their version numbers.
func sameJSON(a, b json.RawMessage) bool {
	var x, y map[string]any
	if json.Unmarshal(a, &x) != nil || json.Unmarshal(b, &y) != nil {
		return false
	}
	delete(x, "version")
	delete(y, "version")
	xa, _ := json.Marshal(x)
	ya, _ := json.Marshal(y)
	return bytes.Equal(xa, ya)
}

// prune removes the oldest backups beyond Keep.
func (m *Manager) prune() {
	list, err := m.List()
	if err != nil {
		return
	}
	for _, e := range list[min(len(list), Keep):] {
		if err := os.Remove(filepath.Join(m.Dir, e.Name)); err != nil && m.Log != nil {
			m.Log.Warn("remove old backup", "name", e.Name, "err", err)
		}
	}
}

// Run makes an automatic backup at start and then once a day, until ctx ends.
func (m *Manager) Run(ctx context.Context) {
	for {
		if e, err := m.Create(ctx, "auto"); err != nil && ctx.Err() == nil {
			if m.Log != nil {
				m.Log.Error("automatic backup failed", "err", err)
			}
		} else if e != nil && m.Log != nil {
			m.Log.Info("backup saved", "name", e.Name, "profiles", e.Profiles)
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(24 * time.Hour):
		}
	}
}
