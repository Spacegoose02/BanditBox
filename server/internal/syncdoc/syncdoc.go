// Package syncdoc applies sync ops to a layout document and finds which paths
// differ between two documents. Paths work like JSON Pointer, except that
// elements of id-keyed arrays (every element an object with a string "id") are
// addressed by id. See schema/README.md.
package syncdoc

import (
	"encoding/json"
	"errors"
	"fmt"
	"reflect"
	"sort"
	"strings"
)

// OrderKey is the pseudo path segment recorded when an id-keyed array gains,
// loses or reorders elements, for example /rows/@order.
const OrderKey = "@order"

// Op is one change. Its JSON form is schema/op.schema.json.
type Op struct {
	OpID     string          `json:"opId"`
	DeviceID string          `json:"deviceId,omitempty"`
	HLC      string          `json:"hlc"`
	Kind     string          `json:"kind"`
	Path     string          `json:"path"`
	Value    json.RawMessage `json:"value,omitempty"`
	After    *string         `json:"after,omitempty"`
}

// ErrBadPath means the path doesn't lead anywhere in the document.
var ErrBadPath = errors.New("path not found")

// Doc is a decoded layout document.
type Doc = map[string]any

// Split turns "/rows/r1/title" into ["rows","r1","title"], undoing ~1 and ~0.
func Split(path string) ([]string, error) {
	if !strings.HasPrefix(path, "/") || len(path) < 2 {
		return nil, fmt.Errorf("%w: %q", ErrBadPath, path)
	}
	parts := strings.Split(path[1:], "/")
	for i, p := range parts {
		if p == "" {
			return nil, fmt.Errorf("%w: empty segment in %q", ErrBadPath, path)
		}
		parts[i] = strings.ReplaceAll(strings.ReplaceAll(p, "~1", "/"), "~0", "~")
	}
	return parts, nil
}

func join(parts []string) string {
	var b strings.Builder
	for _, p := range parts {
		b.WriteByte('/')
		b.WriteString(strings.ReplaceAll(strings.ReplaceAll(p, "~", "~0"), "/", "~1"))
	}
	return b.String()
}

// Touches returns the paths an op changes, for conflict checks. Inserting,
// moving or deleting a keyed element also touches the array's @order.
func Touches(op Op) []string {
	parts, err := Split(op.Path)
	if err != nil {
		return nil
	}
	out := []string{op.Path}
	if op.Kind == "insert" || op.Kind == "move" || op.Kind == "delete" {
		if len(parts) >= 2 {
			out = append(out, join(append(append([]string{}, parts[:len(parts)-1]...), OrderKey)))
		}
	}
	return out
}

// Overlaps reports whether a and b are the same path or one contains the other.
func Overlaps(a, b string) bool {
	if a == b {
		return true
	}
	return strings.HasPrefix(a, b+"/") || strings.HasPrefix(b, a+"/")
}

// Apply changes doc in place. On error doc may be partly changed, so callers
// apply to a copy (see Clone).
func Apply(doc Doc, op Op) error {
	parts, err := Split(op.Path)
	if err != nil {
		return err
	}
	var value any
	if op.Kind == "set" || op.Kind == "insert" {
		if err := json.Unmarshal(op.Value, &value); err != nil {
			return fmt.Errorf("value: %w", err)
		}
	}
	parentParts, last := parts[:len(parts)-1], parts[len(parts)-1]
	if last == OrderKey {
		return fmt.Errorf("%w: %s is not writable", ErrBadPath, OrderKey)
	}

	// Walk to the parent container, remembering how to replace it (arrays change identity).
	var parent any = doc
	setParent := func(any) {}
	for _, seg := range parentParts {
		child, set, err := step(parent, seg, setParent)
		if err != nil {
			return fmt.Errorf("%w: %s", err, op.Path)
		}
		parent, setParent = child, set
	}

	switch p := parent.(type) {
	case map[string]any:
		switch op.Kind {
		case "set":
			p[last] = value
		case "delete":
			delete(p, last)
		default:
			return fmt.Errorf("%w: %s works only on rows and folders", ErrBadPath, op.Kind)
		}
		return nil
	case []any:
		if !keyed(p) {
			return fmt.Errorf("%w: %s is a plain list; set it whole", ErrBadPath, join(parentParts))
		}
		i := indexOf(p, last)
		switch op.Kind {
		case "set":
			if i < 0 {
				return fmt.Errorf("%w: no element %q (use insert)", ErrBadPath, last)
			}
			if !hasID(value, last) {
				return fmt.Errorf("value must be an object with id %q", last)
			}
			p[i] = value
		case "delete":
			if i >= 0 {
				setParent(append(p[:i:i], p[i+1:]...))
			}
		case "insert":
			if i >= 0 {
				return fmt.Errorf("element %q already exists", last)
			}
			if !hasID(value, last) {
				return fmt.Errorf("value must be an object with id %q", last)
			}
			return placeAfter(p, value, op.After, setParent)
		case "move":
			if i < 0 {
				return fmt.Errorf("%w: no element %q", ErrBadPath, last)
			}
			el := p[i]
			rest := append(p[:i:i], p[i+1:]...)
			return placeAfter(rest, el, op.After, setParent)
		}
		return nil
	default:
		return fmt.Errorf("%w: %s is not an object or list", ErrBadPath, join(parentParts))
	}
}

func step(cur any, seg string, setCur func(any)) (any, func(any), error) {
	switch c := cur.(type) {
	case map[string]any:
		child, ok := c[seg]
		if !ok {
			return nil, nil, ErrBadPath
		}
		return child, func(v any) { c[seg] = v }, nil
	case []any:
		if !keyed(c) {
			return nil, nil, ErrBadPath
		}
		i := indexOf(c, seg)
		if i < 0 {
			return nil, nil, ErrBadPath
		}
		return c[i], func(v any) { c[i] = v }, nil
	default:
		return nil, nil, ErrBadPath
	}
}

func placeAfter(arr []any, el any, after *string, set func(any)) error {
	pos := 0
	if after != nil {
		j := indexOf(arr, *after)
		if j < 0 {
			// The anchor was removed meanwhile: append rather than fail, so the change isn't lost.
			pos = len(arr)
		} else {
			pos = j + 1
		}
	}
	out := make([]any, 0, len(arr)+1)
	out = append(out, arr[:pos]...)
	out = append(out, el)
	out = append(out, arr[pos:]...)
	set(out)
	return nil
}

func keyed(arr []any) bool {
	for _, e := range arr {
		m, ok := e.(map[string]any)
		if !ok {
			return false
		}
		if _, ok := m["id"].(string); !ok {
			return false
		}
	}
	return true
}

func hasID(v any, id string) bool {
	m, ok := v.(map[string]any)
	return ok && m["id"] == id
}

func indexOf(arr []any, id string) int {
	for i, e := range arr {
		if m, ok := e.(map[string]any); ok && m["id"] == id {
			return i
		}
	}
	return -1
}

// Diff returns the paths whose values differ between a and b, at the finest
// level the path rules allow, sorted. Keyed arrays are compared element by id,
// with /@order added when elements were added, removed or reordered.
func Diff(a, b Doc) []string {
	var out []string
	diffValue(nil, a, b, &out)
	sort.Strings(out)
	return out
}

func diffValue(at []string, a, b any, out *[]string) {
	switch av := a.(type) {
	case map[string]any:
		if bv, ok := b.(map[string]any); ok {
			keys := map[string]bool{}
			for k := range av {
				keys[k] = true
			}
			for k := range bv {
				keys[k] = true
			}
			for k := range keys {
				x, inA := av[k]
				y, inB := bv[k]
				p := append(append([]string{}, at...), k)
				if inA != inB {
					*out = append(*out, join(p))
				} else {
					diffValue(p, x, y, out)
				}
			}
			return
		}
	case []any:
		if bv, ok := b.([]any); ok && keyed(av) && keyed(bv) && (len(av) > 0 || len(bv) > 0) {
			ids := func(arr []any) []string {
				s := make([]string, len(arr))
				for i, e := range arr {
					s[i] = e.(map[string]any)["id"].(string)
				}
				return s
			}
			if !reflect.DeepEqual(ids(av), ids(bv)) {
				*out = append(*out, join(append(append([]string{}, at...), OrderKey)))
			}
			for _, e := range av {
				id := e.(map[string]any)["id"].(string)
				p := append(append([]string{}, at...), id)
				if j := indexOf(bv, id); j < 0 {
					*out = append(*out, join(p))
				} else {
					diffValue(p, e, bv[j], out)
				}
			}
			for _, e := range bv {
				id := e.(map[string]any)["id"].(string)
				if indexOf(av, id) < 0 {
					*out = append(*out, join(append(append([]string{}, at...), id)))
				}
			}
			return
		}
	}
	if !reflect.DeepEqual(a, b) && len(at) > 0 {
		*out = append(*out, join(at))
	}
}

// Clone deep-copies a document.
func Clone(d Doc) Doc {
	raw, _ := json.Marshal(d)
	var out Doc
	_ = json.Unmarshal(raw, &out)
	return out
}
