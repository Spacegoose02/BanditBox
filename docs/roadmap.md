# BanditBox Roadmap

BanditBox is an Android TV launcher built to replace Projectivy and the stock Google TV / Fire TV home screens. A Docker server on Unraid is the master sync, and the launcher can still be customized in every detail. The work is split into five phases. Phase 1 on its own is enough to replace Projectivy, and each later phase builds on the one before it.

Each phase ends at a **gate**: one check you can do yourself. The next phase starts once that check is true. No dates are set yet.

| Phase | Name | Gate (exit check) |
| --- | --- | --- |
| 1 | Daily driver | BanditBox replaces Projectivy on every TV in the house |
| 2 | Master sync | A change in the web UI shows up on the TV, and the TV still works with the server off |
| 3 | Integrations | Your apps (BingeBandit, Wavelength, StatScraps, Weather Scout) feed the home screen |
| 4 | Looks and kids | A kids profile runs for a full week with no workarounds needed |
| 5 | Extras | Ongoing, no gate |

---

## Phase 1: Daily driver (TV app only, no server)

The aim is a fast, ad-free launcher you'd actually use every day.

- [ ] Kotlin + Compose for TV app shell: the BanditBox name, the raccoon icon and banner
- [ ] Lightweight: under 80 MB of RAM at idle on a 2 GB device, no WebView
- [ ] Zero ads, no promoted rows, no autoplay trailers
- [ ] Home button override: an accessibility service for Google TV (onn 4K), plus ADB instructions
- [ ] Target devices: onn 4K and Nvidia Shield (minSdk 30); RAM target measured on the onn 4K
- [ ] Rows of apps with adjustable tiles (size, shape, banner or icon)
- [ ] Folders, categories and hidden apps, set per profile
- [ ] Menu button that opens All Apps
- [ ] Long-press on an app: Open, App Settings, App Info, Force Close, Uninstall, Launch on Boot
- [ ] Profiles, profiles locked with a PIN, and a profile that signs in automatically at boot
- [ ] Settings buttons for Android settings, launcher settings, editing channels, installing third-party apps, and Network & Internet
- [ ] Clock/date widget and HDMI/AV input shortcuts
- [ ] Remote button remapping: long-press Home, double-press, and spare keys
- [ ] Local backup and restore to a file (the layout document as JSON)
- [ ] Basic wallpaper (a single image)

**Gate 1:** BanditBox replaces Projectivy on every TV in the house (onn 4K and Shield).

## Phase 2: Master sync (Docker server + web UI)

- [x] One Docker container: Go API, static SvelteKit web UI, SQLite in `/config`
- [x] Web UI login: a local admin
- [ ] Optional OIDC sign-in (Authentik or Authelia)
- [x] Unraid template (`deploy/unraid/banditbox.xml`)
- [ ] Listed in Community Apps
- [x] Discovery on the LAN through mDNS (`_banditbox._tcp`)
- [x] Pairing with a 6-digit code; each TV gets its own token, which can be revoked (a QR code on the TV is still to do)
- [x] "Sign in to sync" in the launcher's settings
- [x] Offline-first sync: a local cache, a queue of offline changes, and conflict rules (see handoff.md)
- [x] Web layout editor for each profile
- [ ] A live preview in the editor
- [x] Push a profile to all TVs, and choose which profiles each TV gets
- Per-device overrides: deferred (decided 2026-11-18), since choosing profiles per TV covers it for now
- [x] Server-side backups: daily snapshots and restore
- [x] Self-update: the launcher installs new versions from the server
- [x] Push an APK from the browser to chosen TVs
- [ ] Import from Projectivy

**Gate 2:** A change in the web UI shows up on the TV, and the TV still works with the server off.

## Phase 3: Integrations

- [x] Plugin/widget API: an on-device provider contract, server connectors, and a JSON schema for the widget feed
- [x] `banditbox-plugin-sdk` Android library (published on `sdk-v*` tags)
- [x] BingeBandit: Continue Watching row, plus sports and show cards
- [ ] Wavelength widget and row
- [x] StatScraps scores and scoreboard tile (server connector)
- [x] Weather Scout weather widget next to the clock (server connector)
- [ ] Cinemask (scope to be decided)
- [ ] Watch Next and preview channels from other apps (Plex, YouTube and others), with guided ADB permission setup

**Gate 3:** Your apps feed the home screen.

## Phase 4: Looks and kids

- [ ] Theme categories: nuclear, laser, dark, kids, neon, cinematic, futuristic
- [ ] Theme editor in the web UI, with a live preview on a chosen TV
- [ ] Theme files to import, export and share
- [ ] Icon packs (uniform icons for BingeBandit, Wavelength, Plex, Jellyfin, YouTube, Twitch and others)
- [ ] Wallpaper packs, plus easy upload from a file or URL
- [ ] Accent colors that adapt to the wallpaper
- [ ] OLED-safe animated screensavers that prevent burn-in
- [ ] Kids profiles: approved apps only, with Wavelength and BingeBandit as the defaults
- [ ] Approve apps from a long-press on the TV (PIN needed) or from the web UI
- [ ] Parental locks, daily screen-time limits, bedtime lock

**Gate 4:** A kids profile runs for a full week with no workarounds needed.

## Phase 5: Extras

- [x] Home Assistant/MQTT hooks (for example, dim the lights when a movie starts): MQTT discovery and webhooks, see [homeassistant.md](homeassistant.md). Commands from Home Assistant to the TV are still to do
- [x] One search across apps and integrations (apps, Continue watching and channel cards, widget cards, then an app's own search or the TV's)
- [x] Sleep timer (screen-off still to be checked on the onn 4K and the Shield)
- [ ] Ambient mode with photos from Immich or Plex

---

## Risks

- **Google TV updates may change the Home override** on the onn 4K. Retest after each update, and keep remote remapping as a fallback. Fire TV support comes later and is riskier, since Amazon has blocked overrides before.
- **Rows from other apps need an ADB permission grant** on many devices. Your own apps don't need it.
- **The kids lock is a soft lock** unless the TV runs in device-owner mode.
- **Google Play policy** may reject an accessibility service that isn't used for accessibility. Distribute through your own server and GitHub releases first.
