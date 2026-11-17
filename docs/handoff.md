# BanditBox: Developer Handoff

This is the starting brief for a developer or coding agent picking up BanditBox. It covers what to build, how the pieces fit, the decisions already made, and the first tasks. The phase plan is in [roadmap.md](roadmap.md).

## 1. What BanditBox is

- An **Android TV launcher** that replaces Projectivy and the stock Google TV and Fire TV home screens. It shows no ads and no promoted rows.
- A **Docker server** (built for Unraid) is the **master sync**. Its web UI edits every profile, layout, theme and device, and each TV pulls from it.
- **Offline-first**: every TV keeps a complete local copy and keeps working when the server is down.
- **Customizable in every detail**: everything you see on the TV is data in one *layout document*. The web UI, backups, theme sharing and the Projectivy import all read and write that same format.
- **Lightweight**: under 80 MB of RAM at idle on a 2 GB TV device, and the server uses under 150 MB.
- Owner's own apps that plug in: **Wavelength, BingeBandit, StatScraps, Weather Scout, Cinemask**.

## 2. Architecture

```
 Browser / phone ──login──▶ ┌──────── Docker on Unraid ────────┐
                            │  Web UI (static SvelteKit)        │
                            │  API + sync (REST + WebSocket)    │◀──sync──▶ ┌──── TV: BanditBox app ────┐
                            │  SQLite + media in /config        │           │ Layout engine (Compose)   │
 Your web services ───────▶ │  Connectors (StatScraps, weather) │──cards──▶ │ Sync + local cache        │
                            └───────────────────────────────────┘           │ Guard (a11y service)      │
                                                                            │ Widget host               │◀── Your Android apps
                                                                            └───────────────────────────┘    (BingeBandit, Wavelength)
```

- The **server** owns the data. The **TV** draws only from its local cache and never waits on the network to show the home screen.
- **Integrations reach the TV by two routes**. Web services go through a server connector. On-device apps go through a content provider. Both routes produce the same *widget feed*.

## 3. Tech stack (decided unless marked open)

| Part | Choice | Notes |
| --- | --- | --- |
| TV app | Kotlin, Jetpack Compose for TV (`androidx.tv:tv-material`) | Single activity. minSdk 28 (Android 9) so older Fire TV devices work; to be confirmed |
| TV images | Coil with a capped memory cache | Banners are pre-sized by the server |
| TV storage | Room for the cache and the change queue; DataStore for small settings; EncryptedSharedPreferences for the device token | |
| TV networking | OkHttp (REST + WebSocket), kotlinx.serialization | No Retrofit, to keep the dependency count low |
| Server | **Go** (recommended; *open*: Kotlin/Ktor instead) | Go idles around 20 MB. Ktor shares code with the app but idles around 150 MB |
| Server DB | SQLite (`modernc.org/sqlite`, no CGO) | Single file in `/config` |
| Web UI | SvelteKit, `adapter-static`, served by the Go binary | No Node at runtime |
| Discovery | mDNS `_banditbox._tcp` | |
| Packaging | One Docker image (multi-stage build), Unraid CA template XML | Port 8686 suggested |
| Schemas | JSON Schema in `/schema`, the source of truth for both sides | Generate the Kotlin and Go types from it, or check them in CI |

## 4. Repo layout (monorepo, proposed)

```
BanditBox/
├── android/                 Gradle project
│   ├── app/                 Home activity, navigation, profile picker
│   ├── core-layout/         Layout document model + Compose renderer
│   ├── core-apps/           Installed-app index, icons, long-press actions
│   ├── core-sync/           Pairing, REST/WS client, Room cache, op queue
│   ├── feature-widgets/     Built-in widgets + plugin host
│   ├── guard/               Accessibility service: Home override, remap, kids
│   ├── updater/             Self-update + pushed APK installs
│   └── plugin-sdk/          Published library for third-party apps
├── server/                  Go module
│   ├── cmd/banditbox/       main
│   └── internal/{api,sync,store,media,pairing,connectors,mdns,auth}
├── web/                     SvelteKit app (static build embedded in the server)
├── schema/                  layout.schema.json, op.schema.json, widget-feed.schema.json
├── deploy/                  Dockerfile, docker-compose.yml, unraid/banditbox.xml
└── docs/                    roadmap.md, handoff.md, ADRs
```

## 5. The layout document

There is one per profile, plus optional per-device overrides. It is the single format behind the UI, backups, theme sharing and imports.

```json
{
  "schema": 1,
  "profileId": "p_kids",
  "version": 42,
  "profile": { "name": "Kids", "pin": "hash", "autoLogin": false, "kids": { "approvedApps": ["com.spacegoose.bingebandit"], "dailyLimitMin": 120, "bedtime": { "start": "20:00", "end": "07:00" } } },
  "theme": { "id": "neon", "accent": "#39FF14", "wallpaper": "media:sha256-…", "iconPack": "banditbox-uniform", "screensaver": { "type": "oled-drift" } },
  "rows": [
    { "id": "r1", "type": "apps", "title": "Favorites", "tile": { "size": "large", "style": "banner" }, "items": ["com.plexapp.android", "folder:f1"] },
    { "id": "r2", "type": "widget", "feed": "bingebandit.continue-watching" },
    { "id": "r3", "type": "watchnext" }
  ],
  "folders": [ { "id": "f1", "name": "Sports", "items": ["com.spacegoose.statscraps"] } ],
  "hiddenApps": ["com.amazon.tv.launcher"],
  "topBar": ["clock", "weatherscout.now", "inputs", "network", "settings"],
  "remap": [ { "key": "HOME", "press": "long", "action": { "type": "openInput", "input": "HDMI2" } } ]
}
```

Package names in this example are placeholders.

## 6. Sync protocol

### Pairing
1. The TV looks for `_banditbox._tcp` on the LAN. If nothing answers, the user types the server URL by hand.
2. The TV calls `POST /api/v1/pair/start` and gets back `{pairId, code, qrUrl, expiresAt}`, which expires in 10 minutes. It shows the QR code and the 6-digit code.
3. The admin opens the QR link or types the code into **Devices** in the web UI, names the TV, and assigns profiles: `POST /api/v1/pair/{pairId}/approve`.
4. The TV long-polls `GET /api/v1/pair/{pairId}` until it receives `{deviceId, token}`. It stores the token in encrypted storage. The token can be revoked from the web UI.

### Data flow
- Every change is an **op**: `{opId, deviceId, hlc, path, kind: set|insert|move|delete, value}`. The `hlc` field is a hybrid logical clock.
- The server keeps an op log and a **materialized document** for each profile, with a version number that only goes up.
- TV → server: `POST /api/v1/sync/ops` sends the queued ops along with the `baseVersion` they were made against.
- Server → TV: `GET /api/v1/sync/layout?since={version}` returns ops or a full snapshot. `WS /api/v1/sync/stream` pushes a "version N available" message while the launcher is in front.
- Media is addressed by content hash at `GET /api/v1/media/{sha256}`, so a theme change downloads only the new files.

### Conflict rules
| Situation | Winner | User sees |
| --- | --- | --- |
| Same field changed in web UI and on TV | Web UI | A short notice on the TV. The TV's value is kept in history so it can be restored |
| Different fields changed | Both (merge per field) | Nothing |
| Device-only data (last opened app, usage counts, local Watch Next) | TV | Never synced down |
| "Push to all TVs" from the dashboard | Web UI | Replaces each TV's copy. Device overrides are kept unless the push clears them |
| Stale ops older than the last push | Web UI | Dropped and listed in history |

### Rules for the TV
- Startup reads from Room only. The network is never on the critical path.
- The WebSocket is open only while the launcher is in the foreground.
- If the token is revoked, the TV keeps its last layout and shows a "not synced" state.

## 7. Home button override (Google TV / Fire TV)

1. Register as a normal `HOME` activity. That is enough on Shield, onn Android TV and most stock Android TV devices.
2. On Google TV (`com.google.android.apps.tv.launcherx`) and Fire TV (`com.amazon.tv.launcher`), an **optional accessibility service** in `guard/` watches for the stock launcher's window coming to the front and immediately starts BanditBox. This is the same approach Projectivy uses.
3. A setup screen walks the user through enabling the service.
4. The web UI shows ADB commands for power users who want to set the home activity directly or disable the stock launcher.
5. **Risk:** Amazon has blocked this in some Fire OS updates. Keep a test matrix for each Fire OS version.

The same service also handles remote **key remapping** and **kids enforcement**: if a kids profile opens an app that isn't approved, the service sends the user back to the launcher.

## 8. Plugin / widget API

- **Widget feed** (`schema/widget-feed.schema.json`):
  - The manifest holds the id, name, icon, supported sizes (`1x1`, `2x1`, `4x2`, `row`), refresh interval and a `kidsSafe` flag.
  - Each card has a title, subtitle, image, optional progress, optional badge, a deep link, and up to 3 long-press actions.
  - Templates: `cardRow`, `stat`, `weather`, `nowPlaying`, `scoreboard`. Themes style these templates, not the plugins.
- **On-device provider** (from `plugin-sdk`): the app declares a `ContentProvider` with authority `<pkg>.banditbox` and a meta-data tag. BanditBox finds these providers through `PackageManager`, then reads the manifest and cards. Use this route for BingeBandit (Continue Watching) and Wavelength.
- **Server connector** (`server/internal/connectors`): a Go interface `Fetch(ctx, cfg) ([]Card, error)` that runs on the server's schedule and pushes cards to the TVs. Use this route for StatScraps, Weather Scout and possibly Cinemask.
- **No WebViews and no RemoteViews.** The launcher draws every card in Compose.

## 9. Kids profiles

- Approved apps only. Wavelength and BingeBandit are approved by default.
- Long-press offers Open only. Settings and switching profiles are behind the parent PIN.
- A daily screen-time limit, measured with `UsageStatsManager` (this needs a usage access grant), with a 5-minute warning.
- A bedtime window that shows a lock screen. The parent PIN unlocks it for a set time.
- This is a **soft lock**. A hard lock would need device-owner mode, which means a factory reset (open decision).

## 10. First tasks (Phase 1, in order)

1. **Scaffold the monorepo** using the layout in section 4. Add a root README, a LICENSE once that decision is made, and `.editorconfig`.
2. **Write `schema/layout.schema.json`** (v1, from section 5) and generate or check the Kotlin types.
3. **Set up the Android project**: Gradle version catalog, Compose for TV, a debug build that installs as a HOME app.
4. **Build the app index** (`core-apps`): installed leanback and launcher apps, banners with icon fallback, and the long-press menu (Open, App Info, App Settings, Force Close via App Info, Uninstall, Launch on Boot).
5. **Build the layout renderer** (`core-layout`): rows, adjustable tiles, folders, hidden apps, and a top bar with clock, inputs, network and settings.
6. **Add profiles**: a picker, PIN lock, auto-login at boot (a `BOOT_COMPLETED` receiver).
7. **Write the guard service**: Home override for Google TV and Fire TV, plus key remapping.
8. **Add local backup and restore** of all layout documents as one JSON file.
9. **Set a RAM budget check**: measure with `dumpsys meminfo` on a 2 GB device in CI or by hand, and fail the release if idle use goes over 80 MB.
10. **Set up a release pipeline**: a signed APK on GitHub Releases. The same key is later used for self-update from the server.

Phase 2 starts with `server/` (pairing + sync endpoints), then `web/` (Devices and the layout editor), then `deploy/` (Dockerfile and Unraid XML).

## 11. Conventions

- Kotlin: ktlint and detekt. Go: `gofmt` and `golangci-lint`. Web: Prettier and ESLint.
- API under `/api/v1`, JSON only. Breaking changes require a schema version bump.
- Every change to the layout format goes through `schema/` first.
- Keep dependencies lean: every new library on the TV needs a reason that justifies its RAM.

## 12. Open decisions (owner to answer)

- [ ] **Server language**: Go (recommended) or Kotlin/Ktor?
- [ ] **Distribution**: sideload and self-update only, or also Google Play / Amazon Appstore? Play may reject the accessibility service.
- [ ] **Open source or private**: an Unraid Community Apps listing needs a public image and template.
- [ ] **Kids lock strength**: soft lock only, or offer device-owner mode?
- [ ] **Minimum devices** for Phase 1 (Chromecast with Google TV, Fire TV Stick, Shield, onn): this sets the minSdk and the test matrix.
- [ ] **Remote access**: LAN only, or support managing TVs through Tailscale or a reverse proxy?
- [ ] **Cinemask**: what it should show on the TV.
- [ ] **Monorepo or separate repos**: this handoff assumes a monorepo.

## 13. Risks

- Fire OS updates may break the Home override.
- Reading other apps' Watch Next and preview channels needs an ADB permission grant on many devices.
- Play policy on accessibility services.
- A low-RAM budget with animated themes: keep animation opt-in, with static fallbacks below 2 GB.
