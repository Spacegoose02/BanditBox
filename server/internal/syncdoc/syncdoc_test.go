package syncdoc

import (
	"encoding/json"
	"reflect"
	"testing"
)

func doc(t *testing.T, s string) Doc {
	t.Helper()
	var d Doc
	if err := json.Unmarshal([]byte(s), &d); err != nil {
		t.Fatal(err)
	}
	return d
}

func str(s string) *string { return &s }

const base = `{
	"profile": {"name": "Home"},
	"rows": [
		{"id": "a", "type": "apps", "title": "A", "items": ["x.y"]},
		{"id": "b", "type": "apps", "title": "B"},
		{"id": "c", "type": "watchnext"}
	],
	"topBar": ["clock"]
}`

func rowIDs(d Doc) []string {
	var ids []string
	for _, r := range d["rows"].([]any) {
		ids = append(ids, r.(map[string]any)["id"].(string))
	}
	return ids
}

func TestApply(t *testing.T) {
	cases := []struct {
		name  string
		op    Op
		check func(Doc) bool
	}{
		{"set object field", Op{Kind: "set", Path: "/profile/name", Value: json.RawMessage(`"Kids"`)},
			func(d Doc) bool { return d["profile"].(map[string]any)["name"] == "Kids" }},
		{"set new field", Op{Kind: "set", Path: "/theme", Value: json.RawMessage(`{"accent":"#FF0000"}`)},
			func(d Doc) bool { return d["theme"] != nil }},
		{"set field inside keyed element", Op{Kind: "set", Path: "/rows/b/title", Value: json.RawMessage(`"Bee"`)},
			func(d Doc) bool { return d["rows"].([]any)[1].(map[string]any)["title"] == "Bee" }},
		{"set plain list whole", Op{Kind: "set", Path: "/rows/a/items", Value: json.RawMessage(`["p.q","r.s"]`)},
			func(d Doc) bool { return len(d["rows"].([]any)[0].(map[string]any)["items"].([]any)) == 2 }},
		{"delete field", Op{Kind: "delete", Path: "/rows/a/title"},
			func(d Doc) bool { _, ok := d["rows"].([]any)[0].(map[string]any)["title"]; return !ok }},
		{"delete keyed element", Op{Kind: "delete", Path: "/rows/b"},
			func(d Doc) bool { return reflect.DeepEqual(rowIDs(d), []string{"a", "c"}) }},
		{"delete missing element is fine", Op{Kind: "delete", Path: "/rows/zz"},
			func(d Doc) bool { return len(rowIDs(d)) == 3 }},
		{"insert after", Op{Kind: "insert", Path: "/rows/n", After: str("a"), Value: json.RawMessage(`{"id":"n","type":"watchnext"}`)},
			func(d Doc) bool { return reflect.DeepEqual(rowIDs(d), []string{"a", "n", "b", "c"}) }},
		{"insert first", Op{Kind: "insert", Path: "/rows/n", Value: json.RawMessage(`{"id":"n","type":"watchnext"}`)},
			func(d Doc) bool { return reflect.DeepEqual(rowIDs(d), []string{"n", "a", "b", "c"}) }},
		{"insert after missing anchor appends", Op{Kind: "insert", Path: "/rows/n", After: str("gone"), Value: json.RawMessage(`{"id":"n","type":"watchnext"}`)},
			func(d Doc) bool { return reflect.DeepEqual(rowIDs(d), []string{"a", "b", "c", "n"}) }},
		{"move first", Op{Kind: "move", Path: "/rows/c"},
			func(d Doc) bool { return reflect.DeepEqual(rowIDs(d), []string{"c", "a", "b"}) }},
		{"move after", Op{Kind: "move", Path: "/rows/a", After: str("c")},
			func(d Doc) bool { return reflect.DeepEqual(rowIDs(d), []string{"b", "c", "a"}) }},
		{"escaped path", Op{Kind: "set", Path: "/profile/a~1b", Value: json.RawMessage(`1`)},
			func(d Doc) bool { return d["profile"].(map[string]any)["a/b"] == float64(1) }},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			d := doc(t, base)
			if err := Apply(d, c.op); err != nil {
				t.Fatal(err)
			}
			if !c.check(d) {
				b, _ := json.Marshal(d)
				t.Fatalf("unexpected result: %s", b)
			}
		})
	}
}

func TestApplyErrors(t *testing.T) {
	cases := map[string]Op{
		"missing parent":        {Kind: "set", Path: "/theme/accent", Value: json.RawMessage(`"#000000"`)},
		"set missing element":   {Kind: "set", Path: "/rows/zz", Value: json.RawMessage(`{"id":"zz"}`)},
		"set element wrong id":  {Kind: "set", Path: "/rows/a", Value: json.RawMessage(`{"id":"b"}`)},
		"insert existing":       {Kind: "insert", Path: "/rows/a", Value: json.RawMessage(`{"id":"a"}`)},
		"insert into object":    {Kind: "insert", Path: "/profile/x", Value: json.RawMessage(`1`)},
		"move missing":          {Kind: "move", Path: "/rows/zz"},
		"index into plain list": {Kind: "set", Path: "/topBar/0", Value: json.RawMessage(`"x"`)},
		"write order":           {Kind: "set", Path: "/rows/@order", Value: json.RawMessage(`[]`)},
		"through a scalar":      {Kind: "set", Path: "/profile/name/x", Value: json.RawMessage(`1`)},
		"bad path":              {Kind: "set", Path: "rows", Value: json.RawMessage(`1`)},
		"bad value":             {Kind: "set", Path: "/profile/name", Value: json.RawMessage(`{`)},
	}
	for name, op := range cases {
		t.Run(name, func(t *testing.T) {
			if err := Apply(doc(t, base), op); err == nil {
				t.Fatal("expected an error")
			}
		})
	}
}

func TestDiff(t *testing.T) {
	a := doc(t, base)
	b := Clone(a)
	if got := Diff(a, b); len(got) != 0 {
		t.Fatalf("identical docs differ: %v", got)
	}
	Apply(b, Op{Kind: "set", Path: "/rows/b/title", Value: json.RawMessage(`"Bee"`)})
	Apply(b, Op{Kind: "move", Path: "/rows/c"})
	Apply(b, Op{Kind: "delete", Path: "/rows/a"})
	Apply(b, Op{Kind: "insert", Path: "/rows/n", After: str("b"), Value: json.RawMessage(`{"id":"n","type":"watchnext"}`)})
	Apply(b, Op{Kind: "set", Path: "/topBar", Value: json.RawMessage(`["clock","settings"]`)})
	Apply(b, Op{Kind: "set", Path: "/hiddenApps", Value: json.RawMessage(`[]`)})
	want := []string{"/hiddenApps", "/rows/@order", "/rows/a", "/rows/b/title", "/rows/n", "/topBar"}
	if got := Diff(a, b); !reflect.DeepEqual(got, want) {
		t.Fatalf("diff = %v, want %v", got, want)
	}
}

func TestTouchesAndOverlaps(t *testing.T) {
	got := Touches(Op{Kind: "move", Path: "/rows/a"})
	if !reflect.DeepEqual(got, []string{"/rows/a", "/rows/@order"}) {
		t.Fatalf("touches = %v", got)
	}
	if !Overlaps("/rows/a", "/rows/a/title") || !Overlaps("/rows/a/title", "/rows/a") || !Overlaps("/x", "/x") {
		t.Fatal("expected overlap")
	}
	if Overlaps("/rows/a", "/rows/ab") || Overlaps("/rows/a/title", "/rows/b/title") {
		t.Fatal("unexpected overlap")
	}
}
