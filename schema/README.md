# Schemas

JSON Schemas (draft 2020-12) shared by the TV app and the server. They are the source of truth: change a schema here first, then the Kotlin and Go types.

| File | What it describes |
| --- | --- |
| `layout.schema.json` | The layout document: one per profile, holding everything the TV shows |
| `backup.schema.json` | A backup file: every profile's layout document, as made by Settings > Backup on the TV |

`examples/` holds documents that must validate, and `examples/invalid/` holds documents that must be rejected. CI checks both, and the TV app's `core-layout` tests decode the same files.

## Layout document notes (v1)

- Row items are package names or `folder:<id>`.
- `watchnext` and `channel` rows show what other apps publish to the Android TV home screen. See [docs/content-rows.md](../docs/content-rows.md).
- `wallpaper` is content-addressed media on the server: `media:sha256-<hex>`.
- `pin` is a hash, never the PIN itself.
- `remap[].key` is an Android key name without `KEYCODE_` (for example `HOME`, `PROG_RED`), and `press` is `short`, `long` or `double`.
- The TV reads leniently (unknown optional fields are ignored) so an older TV keeps working with a newer server. Anything that would break an older reader bumps `schema`.

Validate locally with `pipx run check-jsonschema --schemafile layout.schema.json examples/*.json`.

`backup.schema.json` refers to `layout.schema.json` by a relative `$ref`, so point the base URI at this folder to resolve it without the network:

```
check-jsonschema --base-uri "file://$PWD/backup.schema.json" --schemafile backup.schema.json examples/backup/*.json
```
