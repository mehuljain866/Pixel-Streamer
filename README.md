# Pixel Streamer

Android TV player (`com.pixelstreamer.tv`, ~2.1 MB release APK, `minSdk 21`, `targetSdk 35`) for streaming movies, TV shows, and photos from public or shared Google Photos album links without signing into a Google account.

## How it works

1. Open the app on Android TV and scan the on-screen QR code with a phone on the same Wi-Fi network, or open `http://<TV-IP>:8765` in a browser.
2. Paste one or more Google Photos shared album links (`https://photos.app.goo.gl/...` or `https://photos.google.com/share/...`). A DNS-over-HTTPS resolver (`8.8.8.8/resolve`) handles `photos.app.goo.gl` redirects when local ISP DNS blocks shortlinks.
3. For videos, the scraper reads `Content-Disposition` filenames via `HEAD =dv`, cleans release tags, groups episodes chronologically by season and episode number (`S01E01`, `1x01`), and fetches posters, transparent PNG title logos, ratings, and descriptions from IMDb, Cinemeta/Metahub, TVmaze, iTunes, Wikipedia, or an optional TMDB key.

## Library views and companion app

The **Home** screen combines the featured backdrop/title-logo hero with horizontal shelves for **Continue Watching**, **TV Shows**, **Movies**, and **Photos**. The **Movies** and **Shows** tabs switch to dedicated grids sorted A-Z or by album order, and the top-bar search button filters across titles, filenames, episodes, genres, and cast.

Episodes detected in a folder (or albums marked as Shows) are grouped by series and season so playback picks up from the next unfinished episode. The **Photos** tab opens a full-screen gallery with D-pad navigation and an adjustable slideshow timer.

To manage albums from a phone, open the built-in companion web page on port `8765`. From there you can add or remove shared links, hide files, search the library, manually re-match title artwork, and set the default quality.

## Player controls

Built on AndroidX Media3 (`ExoPlayer` + `OkHttpDataSource`):

- `Up`: Show the timeline bar without pausing.
- `Down`: Hide the timeline bar immediately while playing.
- `Left` / `Right`: Scrub forward or back (`10s` per tap, accelerating to `30s` and `60s` while held).
- `Center` / `Play/Pause`: Toggle playback from either full screen or the timeline bar. Pausing displays the title logo and synopsis.
- `Settings` gear (or `Menu`): Open quality, playback speed (`0.75x` to `2x`), and screen fit (`Fit` / `Fill`) options. Quality choices are filtered to each video's resolution (`720p` files omit `1080p`).

When quality is set to `Auto`, the player probes the `=dv` original stream for HTTP `206` range-seek support first so source `1080p` and `720p` uploads play directly, falling back to signed `video-downloads.googleusercontent.com` URLs or `=m37`/`=m22`/`=m18` variants when needed.

## Install on Android TV

### Wi-Fi download

Run the local APK server from the repo root on the same network as the TV:

```powershell
python serve_apk.py
```

Open the printed URL (for example `http://<pc-ip>:8088/PixelStreamer-tv-v1.0.0.apk`) in **Downloader** or a TV browser and confirm the install prompt.

### ADB

```powershell
adb connect <TV_IP>:5555
adb install -r dist\PixelStreamer-tv-v1.0.0.apk
```

## Build from source

Requires JDK 17 and Android SDK Platform 35 (`build-tools 35.0.0`). Set `sdk.dir` in `local.properties` or export `ANDROID_HOME` and `JAVA_HOME`:

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Android\jdk-17"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat testDebugUnitTest assembleRelease
```

The signed release APK is written to `app\build\outputs\apk\release\app-release.apk`. Copy it to `dist\PixelStreamer-tv-v1.0.0.apk` if serving via `serve_apk.py`.
