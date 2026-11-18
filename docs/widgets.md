# Widgets and integrations

Widgets put live cards from other apps on the home screen: the weather next to the clock, your teams' scores, BingeBandit's Continue Watching. Every widget is a **feed** in one format, [`schema/widget-feed.schema.json`](../schema/widget-feed.schema.json), and a layout names a feed by its id:

- a row: `{ "id": "r5", "type": "widget", "feed": "bingebandit.continue-watching" }`
- the top bar: `"topBar": ["clock", "weatherscout.now", "inputs", "settings"]`

Feeds reach the TV by two routes, and both produce the same format.

## Route 1: server connectors

The BanditBox server fetches web services on a schedule and hands the feeds to every paired TV (`GET /api/v1/widgets`, see [api.md](api.md)). Set them up in the web UI under **Integrations**. Keys stay on the server: pictures that need a key are copied to the server and the TV fetches them from there.

| Connector | Feeds | What it reads | Key |
| --- | --- | --- | --- |
| Weather Scout | `weatherscout.now` (weather) | `GET /api/connect/summary` | An app key (`wsk_…`) from Weather Scout's Family & settings, Connected apps |
| StatScraps | `statscraps.scores` (scoreboard) | `GET /api/ha/scorecards?items=cfb:259,nhl:7,nascar` | None |
| BingeBandit | `bingebandit.continue-watching`, `bingebandit.recent` (card rows) | `GET /api/rows?key=continue` and `key=recent` | An API key (`bb_…`) from BingeBandit's Account, API keys. The rows show that account's titles, so they are hidden on kids profiles |
| Widget feed (JSON) | `<id>.<feed>` for each feed it serves | Any address serving widget feed JSON: one feed, or `{"feeds": [...]}` | Optional Bearer key |

On the TV, feeds are cached on disk, so widget rows draw at once after a restart and while the server is down. The TV fetches them after each sync and whenever the server announces a change while the launcher is in front. Widget rows whose feed has no cards (or that the server doesn't have) are skipped, like empty app rows. On a kids profile only `kidsSafe` feeds show, and only cards for approved apps. Selecting a card opens its `link` in the feed's app, or the app itself; long-press shows the card's `actions`.

Fetch times: weather every 10 minutes, scores every 15 minutes (every minute in the hour before a game, every 30 seconds while one is live), BingeBandit every 2 minutes, JSON feeds as their `refreshSec` says (5 minutes if they don't). A failed fetch keeps the last good cards and tries again within 2 minutes. A second connector of the same kind gets the id `weatherscout2`, so its feed is `weatherscout2.now`.

The JSON connector is the way in for anything else, Cinemask included: serve a feed in the format below and add its address.

## Route 2: apps on the TV

An Android app on the TV can publish feeds itself through a content provider, with no server involved. This is for apps whose data lives on the TV, such as Wavelength's now playing. 

How it works:

- The app declares an exported content provider with the authority `<package>.banditbox` and an intent filter for the action `com.spacegoose.banditbox.action.WIDGET_FEEDS`.
- BanditBox finds these providers while it is in front, calls `getFeeds` on each one, and reads the Bundle string `feeds`, which holds `{"feeds":[...]}` in the feed format below.
- The app calls `notifyChange` on `content://<authority>/feeds` when its cards change, and BanditBox reads them again.
- Every link opens in the app that published the feed. Pictures must be https, or the app's own `content://` or `android.resource://` URIs.
- At most 20 feeds and 50 cards per feed are kept. When the server and an app publish the same feed id, the server's feed wins.

The [`banditbox-plugin-sdk`](../android/plugin-sdk/README.md) library does all of this for you. It is published to GitHub Packages when a tag like `sdk-v0.1.0` is pushed.

## The feed format

```json
{
  "schema": 1,
  "id": "statscraps.scores",
  "name": "StatScraps",
  "template": "scoreboard",
  "sizes": ["row", "2x1"],
  "refreshSec": 30,
  "kidsSafe": true,
  "cards": [
    {
      "id": "cfb:259",
      "title": "VT at MIA",
      "badge": "LIVE",
      "score": {
        "state": "in",
        "away": { "name": "Virginia Tech Hokies", "abbr": "VT", "score": "21", "color": "#630031" },
        "home": { "name": "Miami Hurricanes", "abbr": "MIA", "score": "17", "rank": 8 },
        "detail": "Q3 4:12"
      }
    }
  ]
}
```

- `template` is how the TV draws the cards: `cardRow` (posters or stills), `stat` (a big number), `weather`, `nowPlaying` or `scoreboard`. The theme styles the template; a feed carries only data. There are no web views.
- Every card has an `id` and a `title`. Optional: `subtitle`, `image` (https, `content://`, `android.resource://` or `media:sha256-<hex>`), `imageAspect`, `progress` (0 to 1), `badge`, `link` (an intent URI or any URI an app handles), `package` (the app to open when there's no link), up to 3 long-press `actions`, and one of `stat`, `weather` or `score` for those templates.
- `kidsSafe: false` hides the feed on kids profiles.
- [`schema/examples/widget-feed`](../schema/examples/widget-feed) has a valid feed for each template.
