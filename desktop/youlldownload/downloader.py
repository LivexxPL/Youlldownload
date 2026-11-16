"""Downloads one link with yt-dlp. Mirrors the Android app's Engine.kt options."""
from __future__ import annotations

import os
import threading
from typing import Callable

from .formats import OutFormat, Resolution


class DownloadFailed(Exception):
    pass


class Downloader:
    def __init__(self, yt_dlp, ffmpeg: str | None, deno: str | None):
        self.yt_dlp = yt_dlp
        self.ffmpeg = ffmpeg
        self.deno = deno
        self._cancelled = threading.Event()

    def cancel(self):
        self._cancelled.set()

    def download(
        self,
        url: str,
        fmt: OutFormat,
        res: Resolution,
        for_compression: bool,
        work_dir: str,
        progress: Callable[[float | None, str], None],
    ) -> str:
        """Returns the path of the downloaded file inside `work_dir`.

        for_compression: the file is re-encoded afterwards, so skip remuxing and don't fetch
        more than 1080p (10 MB can't hold more anyway).
        """
        self._cancelled.clear()
        utils = self.yt_dlp.utils

        def hook(d):
            if self._cancelled.is_set():
                raise utils.DownloadCancelled()
            if d.get("status") == "downloading":
                total = d.get("total_bytes") or d.get("total_bytes_estimate")
                done = d.get("downloaded_bytes") or 0
                if total:
                    pct = done / total
                    progress(pct, f"Downloading… {int(pct * 100)}%")
                else:
                    progress(None, f"Downloading… {done / 1e6:.1f} MB")

        def pp_hook(d):
            if self._cancelled.is_set():
                raise utils.DownloadCancelled()
            if d.get("status") == "started":
                name = d.get("postprocessor", "")
                label = {
                    "Merger": "Merging video and audio…",
                    "ExtractAudio": "Extracting audio…",
                    "VideoConvertor": "Converting…",
                    "VideoRemuxer": "Converting…",
                }.get(name)
                if label:
                    progress(None, label)

        opts: dict = {
            "outtmpl": os.path.join(work_dir, "%(title).80B [%(id)s].%(ext)s"),
            "noplaylist": True,
            "updatetime": False,
            "windowsfilenames": True,
            "quiet": True,
            "no_warnings": True,
            "noprogress": True,
            "progress_hooks": [hook],
            "postprocessor_hooks": [pp_hook],
            "postprocessors": [],
        }
        if self.ffmpeg:
            opts["ffmpeg_location"] = self.ffmpeg
        if self.deno:
            opts["js_runtimes"] = {"deno": {"path": self.deno}}

        if fmt.is_audio:
            opts["format"] = "ba/b"
            opts["postprocessors"].append(
                {"key": "FFmpegExtractAudio", "preferredcodec": fmt.ext, "preferredquality": "0"}
            )
        elif fmt == OutFormat.GIF:
            # Video only; turned into a GIF afterwards. GIFs above 720p are huge, so don't fetch more.
            height = min(res.height or 720, 720)
            opts["format"] = "bv*/b"
            opts["format_sort"] = [f"res:{height}", "vcodec:h264"]
            opts["merge_output_format"] = "mkv"
        else:
            height = res.height
            if for_compression:
                height = min(height or 1080, 1080)
            res_key = f"res:{height}" if height else "res"
            opts["format"] = "bv*+ba/b"
            if fmt == OutFormat.WEBM:
                opts["format_sort"] = [res_key, "vcodec:vp9", "acodec:opus"]
            else:
                opts["format_sort"] = [res_key, "vcodec:h264", "acodec:m4a"]
            if for_compression:
                opts["merge_output_format"] = "mkv"  # intermediate file only
            elif fmt == OutFormat.WEBM:
                opts["merge_output_format"] = "webm/mkv"
                # only re-encodes when the site has no VP9
                opts["postprocessors"].append({"key": "FFmpegVideoConvertor", "preferedformat": "webm"})
            else:
                opts["merge_output_format"] = fmt.ext
                opts["postprocessors"].append({"key": "FFmpegVideoRemuxer", "preferedformat": fmt.ext})

        try:
            with self.yt_dlp.YoutubeDL(opts) as ydl:
                ydl.download([url.strip()])
        except utils.DownloadCancelled:
            raise
        except utils.DownloadError as e:
            msg = str(e).replace("ERROR: ", "", 1).split("; please report this issue")[0].strip()
            raise DownloadFailed(msg[:300] or "Download failed.") from e

        files = [
            os.path.join(work_dir, f) for f in os.listdir(work_dir)
            if os.path.isfile(os.path.join(work_dir, f)) and not f.endswith((".part", ".ytdl"))
        ]
        if not files:
            raise DownloadFailed("Download finished but no file was produced.")
        return max(files, key=os.path.getsize)
