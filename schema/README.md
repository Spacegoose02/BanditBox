# Schemas

JSON Schemas (draft 2020-12) shared by the TV app and the server. They are the source of truth: change a schema here first, then the Kotlin and Go types.

| File | What it describes |
| --- | --- |
| `layout.schema.json` | The layout document: one per profile, holding everything the TV shows |
| `backup.schema.json` | A backup file: every profile's layout document, as made by Settings > Backup on the TV |
| `themes.schema.json` | The theme catalog format |
| `themes.json` | The built-in theme presets and wallpaper packs. The TV's `ThemeCatalog` must match it (a `core-layout` test checks) |
| `op.schema.json` | One sync op: a change a TV made to a layout, sent to the server |

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

## Sync ops (v1)

A TV records each change as an op and sends a batch with `POST /api/v1/sync/ops`. Paths address the layout document like JSON Pointer, with one difference: elements of **id-keyed arrays** (arrays whose elements are all objects with an `id`, which today means `rows` and `folders`) are addressed by id, not position. So `/rows/favorites/title` stays correct when someone else inserts a row above it.

| kind | path | needs | does |
| --- | --- | --- | --- |
| `set` | an object field, or a keyed element | `value` | Replaces the value (creates an object field if missing). |
| `insert` | `/rows/<new id>` | `value` (with that `id`), `after` | Adds the element after `after`, or first when `after` is null. |
| `move` | `/rows/<id>` | `after` | Moves the element after `after`, or first. |
| `delete` | an object field, or a keyed element | | Removes it. Deleting something already gone is fine. |

Other arrays (`items`, `hiddenApps`, `topBar`, `remap`, `approvedApps`) are single values: change them with `set` on the whole array.

`hlc` is a hybrid logical clock, `<13-digit Unix ms>-<5-digit counter>`, so later changes sort later as plain strings. It decides between two TVs that changed the same field.

The server's conflict rules (handoff.md §6): when the web UI and a TV change the same path (or one changes a parent of the other), the web UI wins and the TV's op comes back as rejected; ops on different paths merge.
