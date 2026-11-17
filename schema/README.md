# Schemas

JSON Schemas (draft 2020-12) shared by the TV app and the server. They are the source of truth: change a schema here first, then the Kotlin and Go types.

| File | What it describes |
| --- | --- |
| `layout.schema.json` | The layout document: one per profile, holding everything the TV shows |

`examples/` holds documents that must validate, and `examples/invalid/` holds documents that must be rejected. CI checks both, and the TV app's `core-layout` tests decode the same files.

## Layout document notes (v1)

- Row items are package names or `folder:<id>`.
- `wallpaper` is content-addressed media on the server: `media:sha256-<hex>`.
- `pin` is a hash, never the PIN itself.
- `remap[].key` is an Android key name without `KEYCODE_` (for example `HOME`, `PROG_RED`), and `press` is `short`, `long` or `double`.
- The TV reads leniently (unknown optional fields are ignored) so an older TV keeps working with a newer server. Anything that would break an older reader bumps `schema`.

Validate locally with `pipx run check-jsonschema --schemafile layout.schema.json examples/*.json`.
