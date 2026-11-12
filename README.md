# Youlldownload

A simple Android app: paste a link from YouTube, TikTok, Instagram, X/Twitter, Reddit, Facebook, Vimeo and
[many more sites](https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md), tap **Download**, and the
video is saved to `Downloads/Youlldownload`.

- **Format:** MP4, WEBM, MKV, or audio only (MP3 / M4A)
- **Resolution:** Best, 4K, 1440p, 1080p, 720p, 480p, 360p (picks the closest available, never higher)
- **Compress to under 10 MB:** two-pass encoding that uses the whole 10 MB budget, so quality is as high as
  that size allows. Resolution and frame rate are only reduced when the clip is too long for them.
  Files already under 10 MB are left untouched.
- **Share to app:** in YouTube/TikTok/Instagram tap *Share → Youlldownload* and the link is filled in.

## Install

Grab `Youlldownload.apk` from the **Releases** page (or the latest run in the **Actions** tab), open it on your
phone and allow "install unknown apps". Android 8.0+, ARM phones.

## How it works

- [yt-dlp](https://github.com/yt-dlp/yt-dlp) + ffmpeg via [youtubedl-android](https://github.com/yausername/youtubedl-android)
- yt-dlp updates itself once a day (or via ⋮ → *Update yt-dlp*) so sites keep working.
- UI: Jetpack Compose, Material 3.

## Notes

- Some Instagram/Facebook posts that are private or need login won't download.
- Compression runs on the phone's CPU: a 1–3 minute clip usually takes a minute or two. Keep the app open.
- Only download content you have the right to download.

## Build

```
./gradlew assembleRelease
```
Requires JDK 17 and the Android SDK (API 35). The APK is at `app/build/outputs/apk/release/app-release.apk`.
