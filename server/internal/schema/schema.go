// Package schema validates documents against the JSON Schemas in /schema.
//
// The schema files are copied here so they can be embedded in the binary.
// Run `go generate ./...` after changing /schema; CI fails if the copies drift.
package schema

//go:generate cp ../../../schema/layout.schema.json layout.schema.json
//go:generate cp ../../../schema/op.schema.json op.schema.json

import (
	"bytes"
	_ "embed"
	"fmt"
	"sync"

	"github.com/santhosh-tekuri/jsonschema/v6"
)

var (
	//go:embed layout.schema.json
	layoutSchema []byte
	//go:embed op.schema.json
	opSchema []byte
)

type compiled struct {
	url  string
	src  []byte
	once sync.Once
	s    *jsonschema.Schema
	err  error
}

var (
	layout = &compiled{url: "https://banditbox.dev/schema/layout.schema.json", src: layoutSchema}
	op     = &compiled{url: "https://banditbox.dev/schema/op.schema.json", src: opSchema}
)

func (c *compiled) get() (*jsonschema.Schema, error) {
	c.once.Do(func() {
		doc, err := jsonschema.UnmarshalJSON(bytes.NewReader(c.src))
		if err != nil {
			c.err = err
			return
		}
		comp := jsonschema.NewCompiler()
		if err := comp.AddResource(c.url, doc); err != nil {
			c.err = err
			return
		}
		c.s, c.err = comp.Compile(c.url)
	})
	return c.s, c.err
}

func (c *compiled) validate(raw []byte) error {
	s, err := c.get()
	if err != nil {
		return fmt.Errorf("schema %s: %w", c.url, err)
	}
	inst, err := jsonschema.UnmarshalJSON(bytes.NewReader(raw))
	if err != nil {
		return fmt.Errorf("not JSON: %w", err)
	}
	return s.Validate(inst)
}

// ValidateLayout reports whether raw is a valid layout document.
// The error message says which part is wrong.
func ValidateLayout(raw []byte) error { return layout.validate(raw) }

// ValidateOp reports whether raw is a valid sync op.
func ValidateOp(raw []byte) error { return op.validate(raw) }
