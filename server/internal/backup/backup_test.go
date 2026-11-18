package backup

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/spacegoose02/banditbox/server/internal/store"
	"github.com/spacegoose02/banditbox/server/internal/syncdoc"
)

func setup(t *testing.T) (*Manager, *store.Store) {
	t.Helper()
	ctx := context.Background()
	st, err := store.Open(ctx, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	if err := st.EnsureDefaultProfile(ctx); err != nil {
		t.Fatal(err)
	}
	clock := time.Date(2026, 11, 18, 3, 0, 0, 0, time.UTC)
	m := &Manager{Dir: filepath.Join(t.TempDir(), "backups"), Store: st, Version: "test", Now: func() time.Time {
		clock = clock.Add(time.Second)
		return clock
	}}
	return m, st
}

func layout(id, name string) json.RawMessage {
	b, _ := json.Marshal(map[string]any{"schema": 1, "profileId": id, "version": 0, "profile": map[string]any{"name": name}, "rows": []any{}})
	return b
}

func TestAutomaticBackupSkipsWhenNothingChanged(t *testing.T) {
	m, st := setup(t)
	ctx := context.Background()
	e, err := m.Create(ctx, "auto")
	if err != nil || e == nil || e.Profiles != 1 {
		t.Fatalf("first = %+v, %v", e, err)
	}
	if e, err := m.Create(ctx, "auto"); err != nil || e != nil {
		t.Fatalf("unchanged = %+v, %v; want skipped", e, err)
	}
	if _, err := st.PutLayout(ctx, "p_den", -1, layout("p_den", "Den")); err != nil {
		t.Fatal(err)
	}
	if e, err := m.Create(ctx, "auto"); err != nil || e == nil || e.Profiles != 2 {
		t.Fatalf("after change = %+v, %v", e, err)
	}
	list, _ := m.List()
	if len(list) != 2 || list[0].Profiles != 2 || !list[0].Created.After(list[1].Created) {
		t.Fatalf("list = %+v", list)
	}
}

func TestKeepsOnlyTheNewest(t *testing.T) {
	m, _ := setup(t)
	for range Keep + 3 {
		if _, err := m.Create(context.Background(), "manual"); err != nil {
			t.Fatal(err)
		}
	}
	list, _ := m.List()
	if len(list) != Keep {
		t.Fatalf("kept %d, want %d", len(list), Keep)
	}
}

func TestRestoreReplacesProfiles(t *testing.T) {
	m, st := setup(t)
	ctx := context.Background()
	if _, err := st.PutLayout(ctx, "p_den", -1, layout("p_den", "Den")); err != nil {
		t.Fatal(err)
	}
	saved, _ := m.Create(ctx, "manual")

	// Change things: rename Home, delete Den, add Kids.
	home, _ := st.GetLayout(ctx, "p_default")
	if _, err := st.PutLayout(ctx, "p_default", home.Version, layout("p_default", "Renamed")); err != nil {
		t.Fatal(err)
	}
	st.DeleteProfile(ctx, "p_den")
	st.PutLayout(ctx, "p_kids", -1, layout("p_kids", "Kids"))

	f, err := m.Read(saved.Name)
	if err != nil {
		t.Fatal(err)
	}
	changed, listChanged, err := m.Restore(ctx, f)
	if err != nil || !listChanged || len(changed) != 2 {
		t.Fatalf("restore = %v, %v, %v", changed, listChanged, err)
	}
	list, _ := st.ListProfiles(ctx)
	names := map[string]string{}
	for _, p := range list {
		names[p.ID] = p.Name
	}
	if len(names) != 2 || names["p_default"] != "Home" || names["p_den"] != "Den" {
		t.Fatalf("profiles = %v", names)
	}
	// A copy of how things were before is kept.
	if all, _ := m.List(); len(all) != 2 {
		t.Fatalf("backups = %+v", all)
	}
	// A TV change made before the restore is refused as stale.
	h, _ := st.GetLayout(ctx, "p_default")
	op := syncdoc.Op{OpID: "op_1", HLC: "0000000000001-00000", Kind: "set", Path: "/profile/name", Value: json.RawMessage(`"TV"`)}
	res, err := st.ApplyOps(ctx, "p_default", "d1", h.Version-1, []syncdoc.Op{op})
	if err != nil || len(res.Rejected) != 1 || res.Rejected[0].Reason != store.RejectStale {
		t.Fatalf("old TV change = %+v, %v", res, err)
	}
}

func TestParseRejectsBadFiles(t *testing.T) {
	for _, tc := range []string{
		`{}`,
		`{"format":"banditbox-backup","backupVersion":2,"createdAt":"x","layouts":[]}`,
		`{"format":"banditbox-backup","backupVersion":1,"createdAt":"x","layouts":[]}`,
		`{"format":"banditbox-backup","backupVersion":1,"createdAt":"x","layouts":[{"schema":1}]}`,
	} {
		var invalid *InvalidError
		if _, err := Parse([]byte(tc)); !errors.As(err, &invalid) {
			t.Errorf("Parse(%s) = %v, want InvalidError", tc, err)
		}
	}
	twice := `{"format":"banditbox-backup","backupVersion":1,"createdAt":"x","layouts":[` + string(layout("p_a", "A")) + `,` + string(layout("p_a", "A")) + `]}`
	if _, err := Parse([]byte(twice)); err == nil {
		t.Error("same profile twice was accepted")
	}
}

func TestTVBackupExampleParses(t *testing.T) {
	data, err := os.ReadFile("../../../schema/examples/backup/two-profiles.json")
	if err != nil {
		t.Fatal(err)
	}
	if f, err := Parse(data); err != nil || len(f.Layouts) != 2 {
		t.Fatalf("Parse = %d layouts, %v", len(f.Layouts), err)
	}
}

func TestPathRejectsOtherNames(t *testing.T) {
	m, _ := setup(t)
	for _, name := range []string{"../banditbox.db", "banditbox.db", "banditbox-auto-1.json"} {
		if _, err := m.Path(name); !errors.Is(err, ErrNotFound) {
			t.Errorf("Path(%q) = %v", name, err)
		}
	}
}
