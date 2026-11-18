# Schemas

JSON Schemas (draft 2020-12) shared by the TV app and the server. They are the source of truth: change a schema here first, then the Kotlin and Go types.

| File | What it describes |
| --- | --- |
| `layout.schema.json` | The layout document: one per profile, holding everything the TV shows |
| `backup.schema.json` | A backup file: every profile's layout document, as made by Settings > Backup on the TV |
| `themes.schema.json` | The theme catalog format |
| `themes.json` | The built-in theme presets and wallpaper packs. The TV's `ThemeCatalog` must match it (a `core-layout` test checks) |

`examples/` holds documents that must validate, and `examples/invalid/` holds documents that must be rejected. CI checks both, and the TV app's `core-layout` tests decode the same files.

## Layout document notes (v1)

- Row items are package names or `folder:<id>`.
- `wallpaper` is content-addressed media on the server: `media:sha256-<hex>`.
- `pin` is a hash, never the PIN itself.
- `theme` is optional and every field in it is too: a missing field takes the preset's value. `theme.id` names a preset from `themes.json` (one or more per category: nuclear, laser, dark, kids-dark, neon, cinematic, futuristic). `wallpaper` is `media:sha256-<hex>` (an uploaded picture), `pack:<pack>/<wallpaper>` (built in, drawn as vector art), `none`, or missing for the preset's own. `adaptiveAccent` takes the accent from the wallpaper. `iconPack` is `system`, `banditbox-uniform`, `banditbox-mono` or `pkg:<package>` for an installed ADW/Nova icon pack. The `oled-` screensavers are drawn by the launcher on black and never hold a pixel in place.
- `remap[].key` is an Android key name without `KEYCODE_` (for example `HOME`, `PROG_RED`), and `press` is `short`, `long` or `double`.
- The TV reads leniently (unknown optional fields are ignored) so an older TV keeps working with a newer server. Anything that would break an older reader bumps `schema`.

Validate locally with `pipx run check-jsonschema --schemafile layout.schema.json examples/*.json`.

`backup.schema.json` refers to `layout.schema.json` by a relative `$ref`, so point the base URI at this folder to resolve it without the network:

```
check-jsonschema --base-uri "file://$PWD/backup.schema.json" --schemafile backup.schema.json examples/backup/*.json
```
