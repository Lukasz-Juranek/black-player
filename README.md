# Black Player

A minimal black Android music player. You pick a folder and it plays everything inside it, including subfolders.

- Play/pause, previous/next, seek, shuffle, repeat (off / all / one)
- Background playback with notification, lock-screen and headset/Bluetooth controls
- Pauses when headphones are unplugged or a call comes in
- Remembers the folder, track, position, shuffle and repeat
- Skips files that can't be played
- Needs no storage permission, because the folder is picked through the system picker

Built with Kotlin, Jetpack Compose and Media3 (ExoPlayer). It needs Android 8.0 or newer.

## Building

The Android SDK is not needed locally. Every push to `main` builds a signed APK on GitHub Actions and
publishes it on the **Releases** page. Open that page on your phone and install `BlackPlayer.apk`
(allow "install unknown apps" for your browser).

The signing key is stored in the `KEYSTORE_B64` / `KEYSTORE_PASSWORD` repo secrets. Because of that,
new builds install as updates over old ones. Keep a backup of `keystore/release.p12`. It is gitignored.

### Optional local build (Docker)

```sh
docker run --rm -v "$PWD":/project -w /project mingc/android-build-box gradle assembleDebug
```
