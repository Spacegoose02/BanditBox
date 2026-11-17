# BanditBox

The ultimate Android TV launcher: fast, ad-free, and fully customizable, with a self-hosted Docker server (Unraid-ready) as the master sync.

See [docs/roadmap.md](docs/roadmap.md) and [docs/handoff.md](docs/handoff.md).

## Repo layout

| Folder | What it is |
| --- | --- |
| `android/` | The TV launcher: Kotlin, Compose for TV, minSdk 30 (onn 4K, Nvidia Shield) |
| `server/` | The master sync server: Go, single static binary, runs in Docker on Unraid |
| `schema/` | JSON Schemas shared by both sides (coming next) |
| `web/` | SvelteKit web UI, embedded in the server (Phase 2) |
| `deploy/` | Dockerfile, compose file, Unraid template (Phase 2) |
| `docs/` | Roadmap, handoff, decision records |

## Building

### TV app

Needs JDK 17+ and the Android SDK (platform 35).

```sh
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build installs as `com.spacegoose.banditbox.debug` and registers as a Home app. On a Shield, press Home and pick BanditBox. On Google TV (onn 4K) the stock launcher keeps Home until the guard service lands; open BanditBox from the app list for now.

Every pull request that touches `android/` builds a debug APK in CI, which you can download from the run's artifacts.

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
| `BANDITBOX_DATA` | `/config` | Data folder (SQLite and media) |
