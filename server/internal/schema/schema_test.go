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

func TestEmbeddedCopyMatchesSource(t *testing.T) {
	src, err := os.ReadFile("../../../schema/layout.schema.json")
	if err != nil {
		t.Fatal(err)
	}
	if string(src) != string(layoutSchema) {
		t.Fatal("internal/schema/layout.schema.json is out of date: run go generate ./... in server/")
	}
}
