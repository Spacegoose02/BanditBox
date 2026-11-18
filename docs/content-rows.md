# Content rows: Continue watching, app channels, and your own apps

Besides rows of app tiles, a profile's layout can hold rows of things to watch. Each profile has its own, set in **Settings > Edit rows** on the TV (and later in the web layout editor).

| Row `type` | What it shows | Where the cards come from |
| --- | --- | --- |
| `watchnext` | Continue watching: started shows, next episodes, optionally new episodes and watchlists | The Android TV **Watch Next** list in the system TvProvider |
| `channel` | One app's preview channel, such as YouTube's Recommended | The app's **preview channel** in the TvProvider |
| `widget` | A feed from a BanditBox plugin, such as `bingebandit.continue-watching` | Server connectors today (see [widgets.md](widgets.md)); apps on the TV in a later change |

Options are in `schema/layout.schema.json`: `kinds`, `apps`, `limit` and `size` on `watchnext`; `package`, `channel`, `limit` and `size` on `channel`.

## Permission

Reading other apps' rows needs `android.permission.READ_TV_LISTINGS`, a runtime permission. The first content row on the home screen shows a card that asks for it. If the TV doesn't show the prompt, grant it over ADB:

```sh
adb shell pm grant com.spacegoose.banditbox android.permission.READ_TV_LISTINGS
```

(Use `com.spacegoose.banditbox.debug` for a debug build.)

## What to expect on each TV

- Without the privileged `ACCESS_ALL_EPG_DATA` permission, which only system apps get, the TvProvider returns rows that apps marked *searchable*. That is the default, so most apps' rows show up, but an app that turns it off can't be read by any third-party launcher.
- On **Google TV (onn 4K)**, some apps publish Continue watching only through Google's Engage SDK, which goes to Google's own home screen and can't be read by other apps. Those apps won't appear in BanditBox's Continue watching. Preview channels from apps such as YouTube usually do appear.
- On the **Shield** (stock Android TV), Watch Next and channels from most apps appear.
- BanditBox reads the TvProvider only while the launcher is in front, and only the tables that a row on the open profile needs. It re-reads when an app changes what it publishes.
- Cards respect the profile: hidden and uninstalled apps are left out, and a kids profile shows only its approved apps.

## Plugging in BingeBandit and Wavelength

There are two routes, and both end up as the same cards on the home screen.

1. **Today, with no BanditBox changes:** the app publishes to the Android TV Watch Next list or a preview channel through `androidx.tvprovider`, with rows left searchable. A `watchnext` row with `"apps": ["<bingebandit package>"]` gives a BingeBandit-only Continue watching row, and a `channel` row shows its channel. This also feeds the stock Google TV home screen.
2. **Phase 3, the plugin host:** the app declares a `ContentProvider` with the authority `<package>.banditbox` (handoff section 8), and a `widget` row names its feed, for example `bingebandit.continue-watching`. The plugin host maps each feed card (title, subtitle, image, progress, deep link) to the same `TvProgram` card model in `android/core-content`, so `ContentRowView` draws it without changes. This route adds what the TvProvider can't carry, such as sports scores, badges and long-press actions, and doesn't need the permission.
