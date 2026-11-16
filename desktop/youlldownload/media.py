"""ffmpeg work: probing, plain conversion, compression to under 10 MB, and GIF making.

Same logic as the Android app (app/src/main/java/com/youlldownload/app/Compressor.kt).
"""
from __future__ import annotations

import os
import re
import subprocess
import tempfile
import threading
from dataclasses import dataclass
from typing import Callable

from .formats import SIZE_LIMIT_BYTES, OutFormat

Progress = Callable[[float, str], None]


class Cancelled(Exception):
    pass


class MediaError(Exception):
    pass


@dataclass
class Info:
    duration: float
    width: int
    height: int
    fps: float
    has_video: bool
    has_audio: bool
    bitrate_kbps: int
    video_codec: str
    audio_codec: str


_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)  # no console flashes on Windows


class Media:
    def __init__(self, ffmpeg: str):
        self.ffmpeg = ffmpeg
        self._proc: subprocess.Popen | None = None
        self._cancelled = threading.Event()

    # ---- control ---------------------------------------------------------------------

    def reset(self):
        self._cancelled.clear()

    def cancel(self):
        self._cancelled.set()
        p = self._proc
        if p and p.poll() is None:
            p.kill()

    def _check(self):
        if self._cancelled.is_set():
            raise Cancelled()

    # ---- probe -----------------------------------------------------------------------

    def probe(self, path: str) -> Info:
        """Reads stream info from `ffmpeg -i` (no ffprobe needed)."""
        r = subprocess.run(
            [self.ffmpeg, "-hide_banner", "-i", path],
            capture_output=True, text=True, errors="replace", creationflags=_NO_WINDOW,
        )
        text = r.stderr
        duration = 0.0
        m = re.search(r"Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)", text)
        if m:
            duration = int(m[1]) * 3600 + int(m[2]) * 60 + float(m[3])
        m = re.search(r"Duration:.*?bitrate:\s*(\d+)\s*kb/s", text)
        bitrate = int(m[1]) if m else 0

        w = h = 0
        fps = 30.0
        vcodec = acodec = ""
        for line in text.splitlines():
            line = line.strip()
            if not line.startswith("Stream #"):
                continue
            mv = re.search(r": Video: (\w+)", line)
            if mv and not vcodec and "attached pic" not in line:
                vcodec = mv[1]
                ms = re.search(r", (\d{2,5})x(\d{2,5})", line)
                if ms:
                    w, h = int(ms[1]), int(ms[2])
                mf = re.search(r"([\d.]+) fps", line) or re.search(r"([\d.]+) tbr", line)
                if mf:
                    try:
                        fps = float(mf[1]) or 30.0
                    except ValueError:
                        pass
            ma = re.search(r": Audio: (\w+)", line)
            if ma and not acodec:
                acodec = ma[1]
        if not vcodec and not acodec:
            raise MediaError("This file isn't a video or audio file that can be read.")
        return Info(duration, w, h, fps, bool(vcodec), bool(acodec), bitrate, vcodec, acodec)

    # ---- plain conversion ------------------------------------------------------------

    def convert(self, src: str, fmt: OutFormat, max_height: int | None, out_dir: str, progress: Progress) -> str:
        """Copies streams the target container supports untouched; re-encodes only the rest."""
        info = self.probe(src)
        out = _out_path(src, out_dir, fmt.ext)
        if fmt.is_audio and not info.has_audio:
            raise MediaError(f"This file has no sound to turn into {fmt.ext.upper()}.")
        if not fmt.is_audio and not info.has_video:
            raise MediaError("This file has no video. Pick MP3 or M4A instead.")

        short = min(info.width, info.height)
        scale = max_height is not None and short > max_height
        vc, ac = info.video_codec, info.audio_codec
        copy_v = not scale and {
            OutFormat.MP4: vc in {"h264", "hevc", "av1", "mpeg4"},
            OutFormat.WEBM: vc in {"vp8", "vp9", "av1"},
            OutFormat.MKV: True,
        }.get(fmt, False)
        copy_a = {
            OutFormat.MP4: ac in {"aac", "mp3"},
            OutFormat.WEBM: ac in {"opus", "vorbis"},
            OutFormat.MKV: True,
            OutFormat.MP3: ac == "mp3",
            OutFormat.M4A: ac in {"aac", "alac"},
        }.get(fmt, False)

        def command(cv: bool, ca: bool) -> list[str]:
            cmd = [self.ffmpeg, "-y", "-hide_banner", "-nostats", "-progress", "pipe:1", "-i", src]
            if fmt.is_audio:
                cmd += ["-vn", "-map", "0:a:0"]
            else:
                cmd += ["-map", "0:v:0", "-map", "0:a:0?"]
                if cv:
                    cmd += ["-c:v", "copy"] + (["-tag:v", "hvc1"] if fmt == OutFormat.MP4 and vc == "hevc" else [])
                elif fmt == OutFormat.WEBM:
                    cmd += ["-c:v", "libvpx-vp9", "-crf", "31", "-b:v", "0", "-row-mt", "1",
                            "-deadline", "good", "-cpu-used", "4", "-pix_fmt", "yuv420p"]
                else:
                    cmd += ["-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p"]
                if not cv and scale:
                    cmd += ["-vf", _scale(info, max_height)]
            if info.has_audio:
                if ca:
                    cmd += ["-c:a", "copy"]
                elif fmt == OutFormat.MP3:
                    cmd += ["-c:a", "libmp3lame", "-q:a", "0"]
                elif fmt == OutFormat.WEBM:
                    cmd += ["-c:a", "libopus", "-b:a", "160k"]
                else:
                    cmd += ["-c:a", "aac", "-b:a", "192k"]
            if fmt in (OutFormat.MP4, OutFormat.M4A):
                cmd += ["-movflags", "+faststart"]
            return cmd + [out]

        lossless = copy_a if fmt.is_audio else (copy_v and (copy_a or not info.has_audio))
        label = "Converting (no quality loss)" if lossless else f"Converting to {fmt.ext.upper()} (re-encoding)"
        try:
            self._run_ffmpeg(command(copy_v, copy_a), info.duration, lambda f: progress(f, label))
        except MediaError:
            if not copy_v and not copy_a:
                raise
            _rm(out)  # copying a stream failed (odd timestamps etc.): re-encode everything
            again = f"Converting to {fmt.ext.upper()} (re-encoding)"
            self._run_ffmpeg(command(False, False), info.duration, lambda f: progress(f, again))
        return out

    # ---- compress to under 10 MB -----------------------------------------------------

    def compress(self, src: str, fmt: OutFormat, max_height: int | None, out_dir: str, progress: Progress) -> str:
        info = self.probe(src)
        if info.duration <= 0:
            raise MediaError("Couldn't read the video length.")
        out = _out_path(src, out_dir, fmt.ext)

        if os.path.getsize(src) < SIZE_LIMIT_BYTES:
            if os.path.splitext(src)[1].lower() == "." + fmt.ext:
                return src
            progress(0.0, "Already under 10 MB, converting container…")
            if self._try_remux(src, out, fmt):
                return out

        if fmt.is_audio or not info.has_video:
            return self._compress_audio(src, out, fmt, info, progress)
        return self._compress_video(src, out, fmt, info, max_height, progress)

    def _compress_video(self, src, out, fmt, info: Info, max_height, progress: Progress) -> str:
        total = SIZE_LIMIT_BYTES * 8 * 0.96 / info.duration / 1000  # kbps, ~4% headroom
        if not info.has_audio:
            akbps = 0
        elif total >= 1200:
            akbps = 128
        elif total >= 600:
            akbps = 96
        elif total >= 300:
            akbps = 64
        else:
            akbps = 48
        vkbps = round(total - akbps)
        if info.bitrate_kbps > 0:
            vkbps = min(vkbps, info.bitrate_kbps)  # never inflate
        if vkbps < 60:
            max_min = round(SIZE_LIMIT_BYTES * 8 * 0.96 / (60 + akbps) / 1000 / 60)
            raise MediaError(
                "This video is too long to fit under 10 MB with watchable quality "
                f"(about {max_min} min is the max). Try MP3/M4A for audio only."
            )

        tmp = os.path.join(os.path.dirname(out), "tmp_" + os.path.basename(out))
        for attempt in range(3):
            target = _target_short_side(vkbps)
            short = min(info.width, info.height) or target
            side = min(target, short, max_height or 10**9)
            side -= side % 2
            filters = []
            if side < short:
                filters.append(_scale(info, side))
            if info.fps > 31 and vkbps < 2500:
                filters.append("fps=30")
            label = f"Compressing to {side}p" + (f" (retry {attempt})" if attempt else "")
            self._two_pass(src, tmp, fmt, info, vkbps, akbps, filters,
                           lambda f, p: progress(f, f"{label} · pass {p} of 2"))
            size = os.path.getsize(tmp)
            if 0 < size < SIZE_LIMIT_BYTES:
                os.replace(tmp, out)
                return out
            vkbps = round(vkbps * SIZE_LIMIT_BYTES * 0.95 / size)  # overshot: scale down and retry
            if vkbps < 60:
                break
        _rm(tmp)
        raise MediaError("Couldn't get the file under 10 MB.")

    def _two_pass(self, src, out, fmt, info: Info, vkbps, akbps, filters, progress):
        work = os.path.dirname(out)
        log = os.path.join(work, "passlog")
        vf = ["-vf", ",".join(filters)] if filters else []
        vp9 = fmt == OutFormat.WEBM
        if vp9:
            vcodec = ["-c:v", "libvpx-vp9", "-b:v", f"{vkbps}k", "-row-mt", "1", "-deadline", "good", "-pix_fmt", "yuv420p"]
        else:
            vcodec = ["-c:v", "libx264", "-preset", "medium", "-b:v", f"{vkbps}k", "-pix_fmt", "yuv420p", "-profile:v", "high"]
        # Same frame timing in both passes: otherwise the MP4 muxer may duplicate a frame in pass 2,
        # x264 then gets more frames than its pass-1 stats and errors out, crashes or hangs.
        common = [self.ffmpeg, "-y", "-hide_banner", "-nostats", "-progress", "pipe:1", "-i", src,
                  "-fps_mode", "passthrough"]

        pass1 = common + ["-map", "0:v:0"] + vf + vcodec + (["-cpu-used", "4"] if vp9 else []) + \
            ["-pass", "1", "-passlogfile", log, "-an", "-f", "null", os.devnull]
        self._run_ffmpeg(pass1, info.duration, lambda f: progress(f * 0.4, 1))

        if info.has_audio:
            audio = ["-c:a", "libopus", "-b:a", f"{akbps}k"] if vp9 else ["-c:a", "aac", "-b:a", f"{akbps}k", "-ac", "2"]
        else:
            audio = ["-an"]
        pass2 = common + ["-map", "0:v:0", "-map", "0:a:0?"] + vf + vcodec + (["-cpu-used", "2"] if vp9 else []) + \
            ["-pass", "2", "-passlogfile", log] + audio + \
            (["-movflags", "+faststart"] if fmt == OutFormat.MP4 else []) + [out]
        self._run_ffmpeg(pass2, info.duration, lambda f: progress(0.4 + f * 0.6, 2))

        for name in os.listdir(work):
            if name.startswith("passlog"):
                _rm(os.path.join(work, name))

    def _compress_audio(self, src, out, fmt, info: Info, progress: Progress) -> str:
        kbps = min(320, int(SIZE_LIMIT_BYTES * 8 * 0.95 / info.duration / 1000))
        if kbps < 32:
            raise MediaError("This audio is too long to fit under 10 MB.")
        codec = ["-c:a", "libmp3lame"] if fmt == OutFormat.MP3 else ["-c:a", "aac"]
        tmp = os.path.join(os.path.dirname(out), "tmp_" + os.path.basename(out))
        cmd = [self.ffmpeg, "-y", "-hide_banner", "-nostats", "-progress", "pipe:1", "-i", src,
               "-vn", "-map", "0:a:0"] + codec + ["-b:a", f"{kbps}k", tmp]
        self._run_ffmpeg(cmd, info.duration, lambda f: progress(f, f"Compressing audio to {kbps} kbps"))
        os.replace(tmp, out)
        return out

    def _try_remux(self, src, out, fmt: OutFormat) -> bool:
        maps = ["-map", "0:a:0"] if fmt.is_audio else ["-map", "0:v:0?", "-map", "0:a:0?"]
        cmd = [self.ffmpeg, "-y", "-hide_banner", "-i", src] + maps + ["-c", "copy"] + \
            (["-movflags", "+faststart"] if fmt == OutFormat.MP4 else []) + [out]
        try:
            self._run_ffmpeg(cmd, 0, lambda f: None)
            return 0 < os.path.getsize(out) < SIZE_LIMIT_BYTES
        except MediaError:
            _rm(out)
            return False

    # ---- GIF -------------------------------------------------------------------------

    def make_gif(self, src: str, max_side: int | None, limit: bool, out_dir: str, progress: Progress) -> str:
        """Video → looping GIF with a palette made for the clip. With `limit`, shrinks until < 10 MB."""
        if src.lower().endswith(".gif") and (not limit or os.path.getsize(src) < SIZE_LIMIT_BYTES):
            return src
        info = self.probe(src)
        if not info.has_video:
            raise MediaError("This has no video to turn into a GIF.")
        short = min(info.width, info.height) or 480
        start = min(short, max_side or 480)
        src_fps = max(1, round(info.fps))
        steps = [(1.0, 15), (0.75, 12), (0.6, 10), (0.5, 10), (0.4, 8), (0.3, 8)] if limit else [(1.0, 15)]
        palette = os.path.join(out_dir, "palette.png")
        out = _out_path(src, out_dir, "gif")

        for i, (factor, max_fps) in enumerate(steps):
            side = max(64, int(start * factor))
            side -= side % 2
            fps = min(max_fps, src_fps)
            base = f"fps={fps},{_scale(info, side)}:flags=lanczos"
            label = f"Making GIF {side}p, {fps} fps" + (f" (smaller, try {i + 1})" if i else "")
            self._run_ffmpeg(
                [self.ffmpeg, "-y", "-hide_banner", "-nostats", "-progress", "pipe:1", "-i", src, "-an",
                 "-vf", f"{base},palettegen=stats_mode=diff", "-update", "1", palette],
                info.duration, lambda f: progress(f * 0.3, label))
            self._run_ffmpeg(
                [self.ffmpeg, "-y", "-hide_banner", "-nostats", "-progress", "pipe:1", "-i", src, "-i", palette, "-an",
                 "-lavfi", f"{base}[x];[x][1:v]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle",
                 "-loop", "0", out],
                info.duration, lambda f: progress(0.3 + f * 0.7, label))
            if not limit or 0 < os.path.getsize(out) < SIZE_LIMIT_BYTES:
                _rm(palette)
                return out
        _rm(palette)
        _rm(out)
        raise MediaError("This clip is too long to fit as a GIF under 10 MB. Try MP4 with compression instead (much smaller).")

    # ---- process helper --------------------------------------------------------------

    def _run_ffmpeg(self, cmd: list[str], duration: float, on_fraction: Callable[[float], None]):
        self._check()
        with tempfile.TemporaryFile() as err:
            self._proc = p = subprocess.Popen(
                cmd, stdout=subprocess.PIPE, stderr=err, stdin=subprocess.DEVNULL,
                text=True, errors="replace", creationflags=_NO_WINDOW,
            )
            total_us = duration * 1_000_000
            for line in p.stdout:
                if total_us > 0 and (line.startswith("out_time_us=") or line.startswith("out_time_ms=")):
                    try:
                        us = int(line.split("=", 1)[1])
                    except ValueError:
                        continue
                    on_fraction(max(0.0, min(1.0, us / total_us)))
            code = p.wait()
            self._proc = None
            self._check()
            if code != 0:
                err.seek(0)
                lines = [l for l in err.read().decode(errors="replace").splitlines() if l.strip()]
                raise MediaError("ffmpeg failed: " + (lines[-1] if lines else f"exit code {code}"))


def _target_short_side(vkbps: int) -> int:
    if vkbps >= 2200:
        return 1080
    if vkbps >= 1100:
        return 720
    if vkbps >= 650:
        return 540
    if vkbps >= 450:
        return 480
    if vkbps >= 250:
        return 360
    return 240


def _scale(info: Info, side: int) -> str:
    """Scales so the *short* side is `side` (works for landscape and vertical videos)."""
    return f"scale=-2:{side}" if info.width >= info.height else f"scale={side}:-2"


def _out_path(src: str, out_dir: str, ext: str) -> str:
    return os.path.join(out_dir, os.path.splitext(os.path.basename(src))[0] + "." + ext)


def _rm(path: str):
    try:
        os.remove(path)
    except OSError:
        pass
