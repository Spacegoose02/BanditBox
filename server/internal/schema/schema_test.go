package schema

import (
	"os"
	"path/filepath"
	"testing"
)

func TestExamplesValidate(t *testing.T) {
	files, _ := filepath.Glob("../../../schema/examples/*.json")
	if len(files) == 0 {
		t.Fatal("no examples found")
	}
	for _, f := range files {
		raw, err := os.ReadFile(f)
		if err != nil {
			t.Fatal(err)
		}
		if err := ValidateLayout(raw); err != nil {
			t.Errorf("%s: %v", f, err)
		}
	}
}

func TestInvalidExamplesFail(t *testing.T) {
	files, _ := filepath.Glob("../../../schema/examples/invalid/*.json")
	if len(files) == 0 {
		t.Fatal("no invalid examples found")
	}
	for _, f := range files {
		raw, err := os.ReadFile(f)
		if err != nil {
			t.Fatal(err)
		}
		if ValidateLayout(raw) == nil {
			t.Errorf("%s: expected a validation error", f)
		}
	}
}

func TestOpExamples(t *testing.T) {
	check := func(glob string, wantValid bool) {
		files, _ := filepath.Glob(glob)
		if len(files) == 0 {
			t.Fatalf("no files for %s", glob)
		}
		for _, f := range files {
			raw, err := os.ReadFile(f)
			if err != nil {
				t.Fatal(err)
			}
			if err := ValidateOp(raw); (err == nil) != wantValid {
				t.Errorf("%s: valid=%v, err=%v", f, wantValid, err)
			}
		}
	}
	check("../../../schema/examples/ops/*.json", true)
	check("../../../schema/examples/ops/invalid/*.json", false)
}

func TestFeedExamples(t *testing.T) {
	check := func(glob string, wantValid bool) {
		files, _ := filepath.Glob(glob)
		if len(files) == 0 {
			t.Fatalf("no files for %s", glob)
		}
		for _, f := range files {
			raw, err := os.ReadFile(f)
			if err != nil {
				t.Fatal(err)
			}
			if err := ValidateFeed(raw); (err == nil) != wantValid {
				t.Errorf("%s: valid=%v, err=%v", f, wantValid, err)
			}
		}
	}
	check("../../../schema/examples/widget-feed/*.json", true)
	check("../../../schema/examples/widget-feed/invalid/*.json", false)
}

func TestEmbeddedCopiesMatchSource(t *testing.T) {
	for name, embedded := range map[string][]byte{"layout.schema.json": layoutSchema, "op.schema.json": opSchema, "widget-feed.schema.json": feedSchema} {
		src, err := os.ReadFile("../../../schema/" + name)
		if err != nil {
			t.Fatal(err)
		}
		if string(src) != string(embedded) {
			t.Errorf("internal/schema/%s is out of date: run go generate ./... in server/", name)
		}
	}
}
