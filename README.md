# Youlldownload

A simple Android and desktop app: paste a link from YouTube, TikTok, Instagram, X/Twitter, Reddit, Facebook, Vimeo and
[many more sites](https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md), tap **Download**, and the
video is saved to `Downloads/Youlldownload`.

- **Format:** MP4, WEBM, MKV, GIF (animated, no sound), or audio only (MP3 / M4A)
- **Resolution:** Best, 4K, 1440p, 1080p, 720p, 480p, 360p (picks the closest available, never higher)
- **Compress to under 10 MB:** two-pass encoding that uses the whole 10 MB budget, so quality is as high as
  that size allows. Resolution and frame rate are only reduced when the clip is too long for them.
  Files already under 10 MB are left untouched. For GIFs it steps size and frame rate down until the GIF fits.
- **GIF:** any video link becomes a looping GIF (up to 480p / 15 fps by default, with a palette tuned to the clip).
  Direct `.gif` links are saved as-is.
- **Convert files:** on the *Convert file* tab pick any video/audio file on your phone (e.g. `.ts`, `.mov`, `.avi`,
  `.flv`, `.wav`) and convert it to MP4, WEBM, MKV, GIF, MP3 or M4A. Streams the new format can hold are copied
  untouched (e.g. `.ts` → `.mp4` takes seconds with zero quality loss); the rest is re-encoded at high quality.
  The resolution and *Compress to under 10 MB* options work here too. You can also share a file to the app.
- **Share to app:** in YouTube/TikTok/Instagram tap *Share → Youlldownload* and the link is filled in.

## Install

### Desktop (Windows, macOS, Linux)

Download from the **Releases** page and unzip:

- **Windows:** `Youlldownload-windows.zip` → open the folder → run `Youlldownload.exe`.
  If SmartScreen warns ("unknown publisher"), click *More info → Run anyway*.
- **macOS (Apple Silicon):** `Youlldownload-macos.zip` → move `Youlldownload.app` to Applications.
  First launch: right-click the app → *Open* → *Open* (it isn't signed by Apple).
- **Linux:** `Youlldownload-linux.tar.gz` → extract → run `Youlldownload/Youlldownload`.

Same features as the phone app: *Download link* and *Convert file* tabs, format, resolution,
*Compress to under 10 MB*, GIF. Files go to `Downloads/Youlldownload` (changeable).
*Help → Update yt-dlp* fetches the newest yt-dlp if a site stops working.

To run from source instead: `pip install -r desktop/requirements.txt` then `python desktop/run.py`
(needs Python 3.10+ with Tkinter).

### Android

Grab `Youlldownload.apk` from the **Releases** page (or the latest run in the **Actions** tab), open it on your
phone and allow "install unknown apps". Android 8.0+, ARM phones.

## Numbers Converter (separate app)

A second, standalone APK in this repo (`numbers/`): converts Apple Numbers spreadsheets (`.numbers`) to
**PDF** and/or **Word (.docx)** right on the phone — no upload, no internet needed.

- Grab `NumbersConverter.apk` from the **Releases** page (or the **Actions** tab), install it like the main app.
- Tap *Choose a .numbers file* (or *Share / Open with → Numbers Converter* from Files, Drive, email…),
  tick PDF and/or Word, tap **Convert**. Files go to `Downloads/NumbersConverter`.
- Every sheet and table is converted, using the values Numbers last calculated (formulas show their results),
  with number / currency / percent / date / duration / checkbox formats. Header rows are repeated on each page.
- Wide tables switch to landscape, wrap long text, and if still too wide are split into column groups.
- Not carried over: charts, images, text boxes, cell colours/fonts and merged cells (merged text shows in
  the first cell). Files from Numbers '09 or older aren't supported — re-save them in a newer Numbers.

## How it works

- [yt-dlp](https://github.com/yt-dlp/yt-dlp) + ffmpeg via [youtubedl-android](https://github.com/yausername/youtubedl-android)
- yt-dlp updates itself once a day (or via ⋮ → *Update yt-dlp*) so sites keep working.
- UI: Jetpack Compose, Material 3.

## Notes

- Some Instagram/Facebook posts that are private or need login won't download.
- Compression runs on the phone's CPU: a 1–3 minute clip usually takes a minute or two. Keep the app open.
- Only download content you have the right to download.

## Build (Android)

```
./gradlew assembleRelease
```
Requires JDK 17 and the Android SDK (API 35). The APKs are at `app/build/outputs/apk/release/app-release.apk`
(Youlldownload) and `numbers/build/outputs/apk/release/numbers-release.apk` (Numbers Converter).
Build just one with `./gradlew :app:assembleRelease` or `./gradlew :numbers:assembleRelease`.
