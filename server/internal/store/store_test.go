package store

import (
	"context"
	"encoding/json"
	"errors"
	"path/filepath"
	"testing"
)

func open(t *testing.T) *Store {
	t.Helper()
	s, err := Open(context.Background(), t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

const kids = `{"schema":1,"profileId":"ignored","version":99,"profile":{"name":"Kids"},"rows":[]}`

func TestDefaultProfileCreatedOnce(t *testing.T) {
	ctx := context.Background()
	s := open(t)
	for i := 0; i < 2; i++ {
		if err := s.EnsureDefaultProfile(ctx); err != nil {
			t.Fatal(err)
		}
	}
	list, err := s.ListProfiles(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(list) != 1 || list[0].ID != DefaultProfileID || list[0].Name != "Home" || list[0].Version != 0 {
		t.Fatalf("got %+v", list)
	}
}

func TestPutLayoutVersions(t *testing.T) {
	ctx := context.Background()
	s := open(t)

	l, err := s.PutLayout(ctx, "p_kids", -1, json.RawMessage(kids))
	if err != nil {
		t.Fatal(err)
	}
	if l.Version != 0 {
		t.Fatalf("version = %d", l.Version)
	}
	var doc map[string]any
	json.Unmarshal(l.Doc, &doc)
	if doc["profileId"] != "p_kids" || doc["version"] != float64(0) {
		t.Fatalf("doc not normalised: %v", doc)
	}

	if _, err := s.PutLayout(ctx, "p_kids", -1, json.RawMessage(kids)); !errors.Is(err, ErrVersionConflict) {
		t.Fatalf("create twice: err = %v", err)
	}
	if _, err := s.PutLayout(ctx, "p_kids", 0, json.RawMessage(kids)); err != nil {
		t.Fatal(err)
	}
	if _, err := s.PutLayout(ctx, "p_kids", 0, json.RawMessage(kids)); !errors.Is(err, ErrVersionConflict) {
		t.Fatalf("stale write: err = %v", err)
	}
	if _, err := s.PutLayout(ctx, "p_none", 3, json.RawMessage(kids)); !errors.Is(err, ErrNotFound) {
		t.Fatalf("missing profile: err = %v", err)
	}

	got, err := s.GetLayout(ctx, "p_kids")
	if err != nil || got.Version != 1 {
		t.Fatalf("get: %+v %v", got, err)
	}
	hist, err := s.LayoutHistory(ctx, "p_kids")
	if err != nil || len(hist) != 1 || hist[0].Version != 0 {
		t.Fatalf("history: %+v %v", hist, err)
	}
}

func TestPutLayoutRejectsInvalid(t *testing.T) {
	s := open(t)
	bad := `{"schema":1,"profile":{"name":"X"},"rows":[{"id":"r","type":"nope"}]}`
	_, err := s.PutLayout(context.Background(), "p_x", -1, json.RawMessage(bad))
	var ve *ValidationError
	if !errors.As(err, &ve) {
		t.Fatalf("err = %v, want ValidationError", err)
	}
}

func TestHistoryIsCapped(t *testing.T) {
	ctx := context.Background()
	s := open(t)
	v := int64(-1)
	for i := 0; i < historyKeep+10; i++ {
		l, err := s.PutLayout(ctx, "p_a", v, json.RawMessage(kids))
		if err != nil {
			t.Fatal(err)
		}
		v = l.Version
	}
	hist, _ := s.LayoutHistory(ctx, "p_a")
	if len(hist) != historyKeep {
		t.Fatalf("history len = %d, want %d", len(hist), historyKeep)
	}
}

func TestDeleteProfile(t *testing.T) {
	ctx := context.Background()
	s := open(t)
	s.PutLayout(ctx, "p_a", -1, json.RawMessage(kids))
	if err := s.DeleteProfile(ctx, "p_a"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.GetLayout(ctx, "p_a"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("err = %v", err)
	}
	if err := s.DeleteProfile(ctx, "p_a"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("second delete err = %v", err)
	}
}

func TestReopenKeepsData(t *testing.T) {
	ctx := context.Background()
	dir := t.TempDir()
	s, err := Open(ctx, dir)
	if err != nil {
		t.Fatal(err)
	}
	s.EnsureDefaultProfile(ctx)
	s.Close()

	s, err = OpenFile(ctx, filepath.Join(dir, FileName))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if _, err := s.GetLayout(ctx, DefaultProfileID); err != nil {
		t.Fatal(err)
	}
}

func TestRefusesNewerDatabase(t *testing.T) {
	ctx := context.Background()
	dir := t.TempDir()
	s, _ := Open(ctx, dir)
	s.db.Exec("PRAGMA user_version = 999")
	s.Close()
	if _, err := Open(ctx, dir); err == nil {
		t.Fatal("expected an error for a newer database")
	}
}
