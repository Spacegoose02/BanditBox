# BanditBox

The ultimate Android TV launcher: fast, ad-free, and fully customizable, with a self-hosted Docker server (Unraid-ready) as the master sync.

See [docs/roadmap.md](docs/roadmap.md) and [docs/handoff.md](docs/handoff.md). The server API is in [docs/api.md](docs/api.md).

## Repo layout

| Folder | What it is |
| --- | --- |
| `android/` | The TV launcher: Kotlin, Compose for TV, minSdk 30 (onn 4K, Nvidia Shield) |
| `server/` | The master sync server: Go, single static binary, runs in Docker on Unraid |
| `schema/` | JSON Schemas shared by both sides, the source of truth for the layout format |
| `web/` | SvelteKit web UI, embedded in the server (Phase 2) |
| `deploy/` | Dockerfile, compose file, Unraid template (Phase 2) |
| `docs/` | Roadmap, handoff, decision records |
| `tools/` | Scripts, such as the RAM budget check |

## Building

### TV app

Needs JDK 17+ and the Android SDK (platform 35).

```sh
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build installs as `com.spacegoose.banditbox.debug` and registers as a Home app. On a Shield, press Home and pick BanditBox. On Google TV (onn 4K) the stock launcher keeps Home: in BanditBox open Settings, then Home button, and turn on the BanditBox Home button service.

Every pull request that touches `android/` builds a debug APK in CI, which you can download from the run's artifacts.

#### RAM budget

BanditBox must stay under 80 MB of memory (PSS) while idle on a 2 GB TV. The **RAM budget** workflow checks this on every push to `main`: it builds the minified release build, runs it on a 2 GB Android TV emulator, and fails if the median idle PSS is over budget. Its `ram-report` artifact holds the samples and the full `dumpsys meminfo`.

To measure a real TV by hand (the onn 4K is the reference device):

```sh
cd android
./gradlew :app:assembleRamcheck      # release build, signed with the debug key
adb connect <tv-ip>
adb install -r app/build/outputs/apk/ramcheck/app-ramcheck.apk
../tools/ram-check.sh com.spacegoose.banditbox.ramcheck 80
```

Measure the release or ramcheck build: a debug build is much bigger and isn't representative.

#### Releases

Pushing a tag like `v0.1.0` publishes a signed APK on GitHub Releases, after the RAM budget check passes. The one-time key setup and the steps are in [docs/releasing.md](docs/releasing.md).

### Server

Needs Go 1.24+.

```sh
cd server
go run ./cmd/banditbox          # listens on :8686
curl localhost:8686/api/v1/health
```

| Variable | Default | Meaning |
| --- | --- | --- |
| `BANDITBOX_ADDR` | `:8686` | Listen address |
| `BANDITBOX_DATA` | `/config` | Data folder: `banditbox.db` (SQLite) and media |

On first start the server creates the database and a **Home** profile (`p_default`, the same id the TV starts with). Layout documents are checked against `schema/layout.schema.json`, which is embedded in the binary: after changing a schema, run `go generate ./...` in `server/` (CI fails if the copy is out of date).

**First sign-in:** while no admin exists, the server prints a one-time **setup code** in its log (`docker logs banditbox` or the Unraid container log). The web UI asks for it with the admin's username and password, so only someone who can read the log can claim the server. Sessions last 30 days from last use; five wrong passwords in 15 minutes pause sign-in from that address.
