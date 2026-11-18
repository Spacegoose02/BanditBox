# Releasing

Pushing a tag like `v0.1.0` runs the **Release** workflow:

1. The **RAM budget** check runs the release build on a 2 GB Android TV emulator. Over 80 MB idle stops the release.
2. The app is built with R8, signed with the release key, and checked with `apksigner`.
3. A GitHub release is published with `banditbox-<version>.apk` and its SHA-256. Tags with a suffix (`v0.2.0-beta.1`) are marked as pre-releases.

The version comes from the tag: `v1.2.3` becomes versionName `1.2.3` and versionCode `10203`, so each release installs over the one before. Each part must be 0 to 99.

## One-time setup: the signing key

Android only installs an update signed with the **same key** as the installed app, and the server's self-update (Phase 2) uses this key too. Make it once, back it up somewhere safe (a password manager or an offline drive), and never commit it.

1. Make the key (valid for 50 years):

   ```sh
   keytool -genkeypair -v -keystore banditbox-release.jks -alias banditbox \
     -keyalg RSA -keysize 4096 -validity 18250
   ```

   It asks for a keystore password and a key password; they can be the same.

2. Turn it into text for GitHub:

   ```sh
   base64 -w0 banditbox-release.jks > banditbox-release.jks.b64   # macOS: base64 -i banditbox-release.jks -o banditbox-release.jks.b64
   ```

3. In the repository on GitHub, open **Settings → Secrets and variables → Actions** and add four repository secrets:

   | Secret | Value |
   | --- | --- |
   | `BANDITBOX_KEYSTORE_BASE64` | the contents of `banditbox-release.jks.b64` |
   | `BANDITBOX_KEYSTORE_PASSWORD` | the keystore password |
   | `BANDITBOX_KEY_ALIAS` | `banditbox` (or the alias you chose) |
   | `BANDITBOX_KEY_PASSWORD` | the key password |

4. Delete `banditbox-release.jks.b64`. Keep the `.jks` file and its passwords backed up.

## Making a release

```sh
git checkout main && git pull
git tag v0.1.0
git push origin v0.1.0
```

Watch it under **Actions → Release**. When it finishes, the APK is on the **Releases** page. Install it on a TV with `adb install -r banditbox-0.1.0.apk`, or with a downloader app pointed at the release asset's URL.

The release build installs as `com.spacegoose.banditbox`, next to any debug (`.debug`) or measuring (`.ramcheck`) builds, so its settings and profiles start fresh. Use Settings → Backup and restore to carry profiles over.

## Building a release by hand

Set the same variables the workflow uses, then build:

```sh
export BANDITBOX_VERSION=0.1.0
export BANDITBOX_KEYSTORE=/path/to/banditbox-release.jks
export BANDITBOX_KEYSTORE_PASSWORD=...
export BANDITBOX_KEY_ALIAS=banditbox
export BANDITBOX_KEY_PASSWORD=...
cd android && ./gradlew :app:assembleRelease
```

Without `BANDITBOX_KEYSTORE` the release APK is built unsigned, which is fine for checking R8 but won't install.
