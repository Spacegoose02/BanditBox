// Package schema validates documents against the JSON Schemas in /schema.
//
// The schema files are copied here so they can be embedded in the binary.
// Run `go generate ./...` after changing /schema; CI fails if the copies drift.
package schema

//go:generate cp ../../../schema/layout.schema.json layout.schema.json

import (
	"bytes"
	_ "embed"
	"encoding/json"
	"fmt"
	"sync"

	"github.com/santhosh-tekuri/jsonschema/v6"
)

//go:embed layout.schema.json
var layoutSchema []byte

const layoutURL = "https://banditbox.dev/schema/layout.schema.json"

var (
	compileOnce sync.Once
	layout      *jsonschema.Schema
	compileErr  error
)

func compiled() (*jsonschema.Schema, error) {
	compileOnce.Do(func() {
		doc, err := jsonschema.UnmarshalJSON(bytes.NewReader(layoutSchema))
		if err != nil {
			compileErr = err
			return
		}
		c := jsonschema.NewCompiler()
		if err := c.AddResource(layoutURL, doc); err != nil {
			compileErr = err
			return
		}
		layout, compileErr = c.Compile(layoutURL)
	})
	return layout, compileErr
}

// ValidateLayout reports whether raw is a valid layout document.
// The error message says which part is wrong.
func ValidateLayout(raw []byte) error {
	s, err := compiled()
	if err != nil {
		return fmt.Errorf("layout schema: %w", err)
	}
	inst, err := jsonschema.UnmarshalJSON(bytes.NewReader(raw))
	if err != nil {
		return fmt.Errorf("not JSON: %w", err)
	}
	if err := s.Validate(inst); err != nil {
		return err
	}
	return nil
}

// ValidateLayoutValue validates an already decoded document (for example a map from json.Unmarshal).
func ValidateLayoutValue(v any) error {
	raw, err := json.Marshal(v)
	if err != nil {
		return err
	}
	return ValidateLayout(raw)
}
