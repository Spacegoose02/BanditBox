# banditbox-plugin-sdk

Put cards from your Android TV app on the [BanditBox](../../README.md) home screen: a now playing card, a Continue Watching row, a stat. BanditBox draws them itself in Compose, styled by its theme: no web views, no RemoteViews.

## Add it

From GitHub Packages (needs a GitHub token with `read:packages`):

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/Spacegoose02/BanditBox")
        credentials { username = "<github user>"; password = "<token>" }
    }
}
dependencies {
    implementation("com.spacegoose.banditbox:banditbox-plugin-sdk:0.1.0")
}
```

Or copy the two files in `src/main/java` into your app: they only use the Android framework.

## Publish a feed

```kotlin
class MyBanditBoxFeeds : BanditBoxFeedProvider() {
    override fun feeds() = listOf(
        Feed(
            id = "wavelength.now-playing",
            name = "Now playing",
            template = Feed.NOW_PLAYING,
            kidsSafe = true,
            cards = listOf(
                Card(
                    id = "current",
                    title = "Song title",
                    subtitle = "Artist",
                    image = "content://${authority(context!!)}/art/current.jpg",
                    imageAspect = 1f,
                    progress = 0.4f,
                    link = "wavelength://player",
                ),
            ),
        ),
    )
}
```

```xml
<provider
    android:name=".MyBanditBoxFeeds"
    android:authorities="${applicationId}.banditbox"
    android:exported="true">
    <intent-filter>
        <action android:name="com.spacegoose.banditbox.action.WIDGET_FEEDS" />
    </intent-filter>
</provider>
```

Call `BanditBoxFeedProvider.notifyChanged(context)` whenever the cards change. In BanditBox's web UI, add a widget row and type the feed id (`wavelength.now-playing`).

## Rules

- The authority must be your package name plus `.banditbox`. Feed ids start with your plugin name, like `wavelength.`.
- Card images are https URLs, `android.resource://` URIs, or `content://` URIs from **your own** provider (override `openFile` to serve them). Others are dropped.
- At most 20 feeds and 50 cards a feed; the whole reply must stay under 200 KB.
- `kidsSafe = false` (the default) hides a feed on kids profiles.
- The protocol: BanditBox calls `ContentResolver.call(content://<authority>, "getFeeds")` and reads `feeds`, a JSON string `{"feeds": [...]}` in the widget feed format (`schema/widget-feed.schema.json`).
