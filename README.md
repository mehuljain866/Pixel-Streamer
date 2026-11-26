# Pixel Streamer

Android TV (`com.pixelstreamer.tv`) and Android Tablet (`com.pixelstreamer.tablet`) app (`minSdk 21`, `targetSdk 35`) for streaming movies, TV shows, and photos from public or shared Google Photos album links without signing into a Google account.

## How it works

1. Open the app on Android TV or Tablet and scan the on-screen QR code with a phone on the same Wi-Fi network, or open `http://<DEVICE-IP>:8765` in a browser and enter the 4-digit pairing PIN shown on screen.
2. Paste one or more Google Photos shared album links (`https://photos.app.goo.gl/...` or `https://photos.google.com/share/...`). A DNS-over-HTTPS resolver (`8.8.8.8/resolve`) handles `photos.app.goo.gl` redirects when local ISP DNS blocks shortlinks.
3. Parsed video and photo items appear right away, while posters, transparent PNG title logos, ratings, and descriptions load in the background. For videos, the scraper reads `Content-Disposition` filenames via `HEAD =dv`, cleans release tags, groups episodes chronologically by season and episode number (`S01E01`, `1x01`), and queries IMDb, Cinemeta/Metahub, TVmaze, iTunes, Wikipedia, or an optional TMDB key.

## Library views and companion app

The Home screen shows the featured backdrop/title-logo hero above shelves for Continue Watching, TV Shows, Movies, and Photos. The Movies and Shows tabs open grids sorted A-Z or by album order, and top-bar search filters across titles, filenames, episodes, genres, and cast.

Episodes detected in an album (or albums marked as Shows) group under one series card so playback resumes from the next unfinished episode. The Photos tab opens a full-screen viewer with D-pad navigation, touch swiping on Tablet, and an adjustable slideshow timer.

From the companion web page on port `8765` (protected by the session PIN and `HttpOnly` cookie), you can:

- Add, sync, enable, or remove shared album links.
- Hide or unhide individual items (or bulk show/hide inside the device Manage Albums screen).
- Search the library, pick a default quality, or set a custom title on a movie or TV series so artwork updates across all matching episodes.

## Player controls

Built on AndroidX Media3 (`ExoPlayer` + `OkHttpDataSource`):

- `Up` / `Down`: Show or hide the timeline bar while playing.
- `Left` / `Right`: Seek forward or back (`10s` per tap, accelerating to `30s` and `60s` while held).
- `Center` or `Play/Pause`: Toggle playback from full screen or the timeline bar. Pausing displays the title logo and synopsis.
- `Settings` gear (or `Menu`): Change quality, playback speed (`0.75x` to `2x`), and screen fit (`Fit` / `Fill`). Quality choices are filtered per file so `720p` streams omit `1080p`.
- Tablet touch gestures: Single-tap the video to show or hide the overlay and toggle playback, double-tap left or right to jump `10s`, swipe sideways in the photo viewer, and use the on-screen back buttons.

On `Auto` quality, playback checks the `=dv` original file for HTTP `206` range-seek support first so source `1080p` and `720p` uploads play directly, falling back to `video-downloads.googleusercontent.com` URLs or `=m37`, `=m22`, and `=m18` variants when needed.

## Install

Download `PixelStreamer-tv-v1.0.4.apk` (Android TV) or `PixelStreamer-tablet-v1.0.4.apk` (Android Tablet) from the [GitHub Releases](https://github.com/mehuljain866/Pixel-Streamer/releases) page, or sideload local builds over Wi-Fi or ADB.

### Wi-Fi download from local builds

Place built APKs in `dist/` (ignored by git) and run the helper HTTP server on the same network:

```powershell
python serve_apk.py
```

Open the printed URL (for example `http://<pc-ip>:8088/PixelStreamer-tv-v1.0.4.apk`) in Downloader or a browser on the device and confirm the install prompt.

### ADB

```powershell
adb connect <DEVICE_IP>:5555
adb install -r dist\PixelStreamer-tv-v1.0.4.apk
```

For tablets, substitute `PixelStreamer-tablet-v1.0.4.apk`.

## Build from source

Requires JDK 17 and Android SDK Platform 35 (`build-tools 35.0.0`). Set `sdk.dir` in `local.properties` or set `ANDROID_HOME` and `JAVA_HOME`:

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Android\jdk-17"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat testTvDebugUnitTest testTabletDebugUnitTest assembleTvRelease assembleTabletRelease
```

Signed release APKs are written to `app\build\outputs\apk\tv\release\app-tv-release.apk` and `app\build\outputs\apk\tablet\release\app-tablet-release.apk`.
