package store

import (
	"context"
	"encoding/json"
	"errors"
	"testing"

	"github.com/spacegoose02/banditbox/server/internal/syncdoc"
)

func setOp(id, hlc, path, value string) syncdoc.Op {
	return syncdoc.Op{OpID: id, HLC: hlc, Kind: "set", Path: path, Value: json.RawMessage(value)}
}

const hlc1, hlc2, hlc3 = "1763430000000-00000", "1763430000001-00000", "1763430000002-00000"

func syncFixture(t *testing.T) *Store {
	s := open(t)
	if err := s.EnsureDefaultProfile(context.Background()); err != nil {
		t.Fatal(err)
	}
	return s
}

func layoutField(t *testing.T, s *Store, path ...string) any {
	t.Helper()
	l, err := s.GetLayout(context.Background(), DefaultProfileID)
	if err != nil {
		t.Fatal(err)
	}
	var cur any
	json.Unmarshal(l.Doc, &cur)
	for _, p := range path {
		switch c := cur.(type) {
		case map[string]any:
			cur = c[p]
		case []any:
			for _, e := range c {
				if e.(map[string]any)["id"] == p {
					cur = e
				}
			}
		}
	}
	return cur
}

func TestApplyOpsMergesAndIsIdempotent(t *testing.T) {
	ctx := context.Background()
	s := syncFixture(t)
	ops := []syncdoc.Op{
		setOp("op_000001", hlc1, "/rows/favorites/title", `"Mine"`),
		setOp("op_000002", hlc2, "/topBar", `["clock"]`),
	}
	res, err := s.ApplyOps(ctx, DefaultProfileID, "d_tv1", 0, ops)
	if err != nil {
		t.Fatal(err)
	}
	if res.Version != 1 || len(res.Applied) != 2 || len(res.Rejected) != 0 {
		t.Fatalf("res %+v", res)
	}
	if layoutField(t, s, "rows", "favorites", "title") != "Mine" {
		t.Fatal("title not applied")
	}
	// Retrying the same batch changes nothing.
	res, err = s.ApplyOps(ctx, DefaultProfileID, "d_tv1", 0, ops)
	if err != nil || res.Version != 1 || len(res.Applied) != 2 {
		t.Fatalf("retry %+v %v", res, err)
	}
}

func TestWebUIWinsOnSamePath(t *testing.T) {
	ctx := context.Background()
	s := syncFixture(t)
	// The web UI renames the row (version 0 -> 1).
	l, _ := s.GetLayout(ctx, DefaultProfileID)
	var doc map[string]any
	json.Unmarshal(l.Doc, &doc)
	doc["rows"].([]any)[0].(map[string]any)["title"] = "From web"
	raw, _ := json.Marshal(doc)
	if _, err := s.PutLayout(ctx, DefaultProfileID, 0, raw); err != nil {
		t.Fatal(err)
	}
	// The TV, still on version 0, renamed the same row and changed the top bar.
	res, err := s.ApplyOps(ctx, DefaultProfileID, "d_tv1", 0, []syncdoc.Op{
		setOp("op_000001", hlc1, "/rows/favorites/title", `"From TV"`),
		setOp("op_000002", hlc2, "/topBar", `["clock"]`),
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Rejected) != 1 || res.Rejected[0].OpID != "op_000001" || res.Rejected[0].Reason != RejectWebWins {
		t.Fatalf("res %+v", res)
	}
	if layoutField(t, s, "rows", "favorites", "title") != "From web" {
		t.Fatal("web value lost")
	}
	if tb := layoutField(t, s, "topBar").([]any); len(tb) != 1 {
		t.Fatal("different path not merged")
	}
	// The TV's version of the row is still in history.
	hist, _ := s.LayoutHistory(ctx, DefaultProfileID)
	if len(hist) != 2 {
		t.Fatalf("history %d", len(hist))
	}
}

func TestWebDeleteBeatsTVEditInside(t *testing.T) {
	ctx := context.Background()
	s := syncFixture(t)
	l, _ := s.GetLayout(ctx, DefaultProfileID)
	var doc map[string]any
	json.Unmarshal(l.Doc, &doc)
	doc["rows"] = []any{}
	raw, _ := json.Marshal(doc)
	s.PutLayout(ctx, DefaultProfileID, 0, raw)

	res, _ := s.ApplyOps(ctx, DefaultProfileID, "d_tv1", 0, []syncdoc.Op{setOp("op_000001", hlc1, "/rows/favorites/title", `"x"`)})
	if len(res.Rejected) != 1 || res.Rejected[0].Reason != RejectWebWins {
		t.Fatalf("res %+v", res)
	}
}

func TestLaterClockWinsBetweenTVs(t *testing.T) {
	ctx := context.Background()
	s := syncFixture(t)
	// TV 2 changed the title at hlc3 and synced first.
	if _, err := s.ApplyOps(ctx, DefaultProfileID, "d_tv2", 0, []syncdoc.Op{setOp("op_tv2_001", hlc3, "/rows/favorites/title", `"TV2"`)}); err != nil {
		t.Fatal(err)
	}
	// TV 1 changed it earlier (hlc1) but synced later: it loses.
	res, _ := s.ApplyOps(ctx, DefaultProfileID, "d_tv1", 0, []syncdoc.Op{setOp("op_tv1_001", hlc1, "/rows/favorites/title", `"TV1"`)})
	if len(res.Rejected) != 1 || res.Rejected[0].Reason != RejectNewer {
		t.Fatalf("res %+v", res)
	}
	// A later change from TV 1 wins.
	res, _ = s.ApplyOps(ctx, DefaultProfileID, "d_tv1", 0, []syncdoc.Op{setOp("op_tv1_002", "1763430000009-00000", "/rows/favorites/title", `"TV1 later"`)})
	if len(res.Applied) != 1 || layoutField(t, s, "rows", "favorites", "title") != "TV1 later" {
		t.Fatalf("res %+v", res)
	}
}

func TestInvalidOpsRejectedIndividually(t *testing.T) {
	ctx := context.Background()
	s := syncFixture(t)
	res, err := s.ApplyOps(ctx, DefaultProfileID, "d_tv1", 0, []syncdoc.Op{
		setOp("op_000001", hlc1, "/rows/favorites/type", `"nope"`), // breaks the schema
		setOp("op_000002", hlc2, "/nowhere/x", `1`),                // bad path
		setOp("op_000003", hlc3, "/profile/name", `"Den"`),
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Applied) != 1 || len(res.Rejected) != 2 || res.Rejected[0].Reason != RejectInvalid {
		t.Fatalf("res %+v", res)
	}
}

func TestStaleAndFutureBase(t *testing.T) {
	ctx := context.Background()
	s := syncFixture(t)
	if _, err := s.ApplyOps(ctx, DefaultProfileID, "d", 5, nil); !errors.Is(err, ErrFutureBase) {
		t.Fatalf("future err = %v", err)
	}
	s.db.Exec(`UPDATE profiles SET min_base = 1, version = 1`)
	res, _ := s.ApplyOps(ctx, DefaultProfileID, "d", 0, []syncdoc.Op{setOp("op_000001", hlc1, "/profile/name", `"x"`)})
	if len(res.Rejected) != 1 || res.Rejected[0].Reason != RejectStale {
		t.Fatalf("res %+v", res)
	}
	if _, err := s.ApplyOps(ctx, "p_none", "d", 0, nil); !errors.Is(err, ErrNotFound) {
		t.Fatalf("missing profile err = %v", err)
	}
}
