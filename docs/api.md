# Server API (v1)

Everything is JSON under `/api/v1`. Errors look like `{"error": "..."}`.

## Who can call what

| Caller | How it authenticates |
| --- | --- |
| Web UI (admin) | `bb_session` cookie from `/auth/setup` or `/auth/login` |
| TV | `Authorization: Bearer <device token>` from pairing. Optional `X-BanditBox-App: <app version>` |
| Anyone | `/health`, `/auth/status`, `/pair/start`, `/pair/{pairId}` |

## Admin

| Method and path | Body | Reply |
| --- | --- | --- |
| `GET /auth/status` | | `{setupNeeded, admin}` |
| `POST /auth/setup` | `{code, username, password}` (code from the server log) | 201, sets cookie |
| `POST /auth/login` | `{username, password}` | 200, sets cookie. 429 after 5 failures in 15 min |
| `POST /auth/logout` | | 204 |
| `POST /auth/password` | `{current, new}` | 204. Other sessions are signed out |
| `GET /profiles` | | `[{id, name, version, updated}]` |
| `POST /profiles` | `{id, name, copyFrom?}` | 201, the new layout |
| `GET /profiles/{id}/layout` | | the layout document |
| `PUT /profiles/{id}/layout` | the edited document; its `version` is the one it was edited from | 200 new document, or 409 `{error, current}` |
| `GET /profiles/{id}/history` | | earlier documents, newest first (up to 50) |
| `DELETE /profiles/{id}` | | 204. The last profile can't be deleted |
| `POST /profiles/{id}/push` | | 204. Push to all TVs: changes TVs made before now and haven't sent yet are refused as stale, so every TV ends up with the server's copy |
| `GET /backups` | | Saved backups, newest first: `[{name, size, created, profiles}]`. The server makes one a day when something changed and keeps 30 |
| `POST /backups` | | 201, a new backup |
| `GET /backups/{name}` | | The backup file (same format as a TV backup, `schema/backup.schema.json`) |
| `POST /backups/{name}/restore` | | `{profiles, changed}`. Replaces every profile with the backup's (profiles not in it are deleted) after saving a backup of the current state. Restored profiles are pushed to all TVs |
| `POST /backups/restore` | a backup file, from the server or a TV | Same as above; 422 if the file isn't a valid backup |
| `GET /pair/pending` | | TVs waiting: `[{code, name, model, created, expires}]` |
| `POST /pair/approve` | `{code, name?, profiles}` (`[]` = all profiles) | 201, the device |
| `POST /pair/deny` | `{code}` | 204 |
| `GET /devices` | | `[{id, name, model, profiles, created, lastSeen, appVersion}]` |
| `PATCH /devices/{id}` | `{name?, profiles?}` | the device |
| `DELETE /devices/{id}` | | 204. The token stops working and its stream closes |
| `GET /apps` | | Apps installed on any paired TV, for the layout editor: `[{packageName, label, tv, devices}]`, by label. `tv` is true for apps with a TV launcher entry |
| `GET /apks` | | Uploaded APKs, newest first: `[{sha256, package, versionCode, versionName, fileName, size, uploaded, installs: [{deviceId, status, detail, updated}]}]` |
| `POST /apks` | the APK file, `Content-Type: application/vnd.android.package-archive`, optional `X-File-Name` | 201, the APK (package and version read from its manifest). 422 if it isn't an APK. At most 512 MB |
| `POST /apks/{sha256}/install` | `{devices}` (`[]` = every TV) | the APK with its installs. TVs in front hear about it at once |
| `DELETE /apks/{sha256}` | | 204. Installs not done yet are dropped |
| `GET /connectors/kinds` | | The connector kinds (Weather Scout, StatScraps, BingeBandit, JSON feed) with the settings each needs: `[{id, name, description, fields: [{key, label, help, placeholder, required, secret}], feeds: [{suffix, name, template}]}]` |
| `GET /connectors` | | `[{id, kind, name, enabled, settings, created, hasKey, status: {lastRun, lastOk, lastError, nextRun, feeds}}]`. Keys are never sent back |
| `POST /connectors` | `{kind, name?, id?, enabled?, settings}` | 201, the connector. The key goes in `settings.key`. `id` defaults to the kind (then `kind2`…), and its feeds are `<id>.<feed>`, for example `weatherscout.now` |
| `PUT /connectors/{id}` | `{name?, enabled?, settings?}` | The connector. An empty `settings.key` keeps the stored key |
| `DELETE /connectors/{id}` | | 204 |
| `POST /connectors/test` | `{kind, settings}` or `{id, settings}` | `{feeds}` from one fetch, nothing saved. 422 `{error}` says what went wrong (address, key, reply) |
| `POST /connectors/{id}/refresh` | | 202. Fetches now |
| `GET /connectors/feeds` | | `{version, feeds}`: what the TVs get |

## TV

### Pairing

1. `POST /pair/start {name, model}` → `{pairId, code, qrUrl, expiresAt}`. Show `code` and a QR code of `qrUrl`. Keep `pairId` secret.
2. Poll `GET /pair/{pairId}?wait=30` until `status` is not `pending`:
   - `approved` with `{deviceId, token}`. This is sent **once**; store the token encrypted.
   - `denied` or `expired`: start again. 404 means the server forgot it (for example after a restart).

### Sync

- `PUT /device/apps {apps: [{packageName, label, tv}]}` → 204. The apps installed on the TV (at most 2000), replacing what it sent before. Send it after pairing and whenever the list changes.
- `GET /device/installs` → `[apk]`: APKs to install, oldest first. Download each from `GET /device/apks/{sha256}`, check the hash, skip it if the same package is already at `versionCode` or newer, and report with `POST /device/installs/{sha256} {status, detail?}`: `waiting` (asking the user to confirm), `installed`, `skipped` or `failed`.
- `GET /device/me` → `{device, profiles: [{id, name, version, updated}], server}`. 401 means the TV was revoked: keep the last layout and show "not synced".
- `GET /sync/layout?profile=ID&since=V` → `{profileId, version, doc}`, or 204 if `V` is current.
- `POST /sync/ops {profileId, baseVersion, ops}` → `{version, applied: [opId], rejected: [{opId, reason, detail?}], doc}`.
  - Ops follow `schema/op.schema.json`. Up to 500 per batch. Retrying a batch is safe: applied op ids are remembered for 30 days.
  - `reason` is `changed in the web UI`, `changed later on another TV`, `stale`, or `invalid`. Show a short notice for the first two.
  - `doc` is the layout after the batch: replace the local copy with it, then re-apply any ops queued since.
  - 409 means `baseVersion` is newer than the server's (the server was restored from a backup): fetch the layout and drop the queue.
- `POST /sync/seed {layouts: [doc]}` → `[{profileId, version, doc}]`. Only while `/device/me` says `fresh` (no layout ever edited) and only for a TV allowed every profile: replaces the server's profiles with the TV's own, so the first TV keeps its setup. 409 once the server isn't fresh.
- `POST /sync/profiles` with a layout document → 201 `{profileId, version, doc}`: a profile made on the TV. A TV limited to some profiles gets it added to its list. 409 if the id exists.
- `DELETE /sync/profiles/{id}` → 204: a profile deleted on the TV.
- `GET /widgets` → `{version, feeds}`: every widget feed from the server's connectors (`schema/widget-feed.schema.json`). Cache them; the TV draws from its copy. A feed with `error` holds the last good cards. Hide feeds whose `kidsSafe` is false on kids profiles.
- `GET /media/{sha256}` → a picture a feed refers to as `media:sha256-<hex>`. Never changes, so cache it.
- `GET /sync/stream` (WebSocket, open only while the launcher is in front). On connect the server sends `{"type":"version","profileId","version"}` for each allowed profile, then one whenever a profile changes. `{"type":"profiles"}` means profiles were added or removed, or this TV's allowed profiles changed: call `/device/me` again. `{"type":"installs"}` means there are APKs to install: call `/device/installs`. `{"type":"widgets","version"}` comes on connect and whenever a widget feed changes: call `/widgets` if your copy is older. Fetch the layout when the version is newer than yours. The server pings every 30 s. Close code 4401 means the TV was revoked.
