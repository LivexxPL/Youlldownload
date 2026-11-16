from __future__ import annotations

from enum import Enum

SIZE_LIMIT_BYTES = 10_000_000  # "under 10 MB"; also fits 10 MiB limits


class OutFormat(Enum):
    MP4 = ("MP4 (video)", "mp4", False)
    WEBM = ("WEBM (video)", "webm", False)
    MKV = ("MKV (video)", "mkv", False)
    GIF = ("GIF (animated, no sound)", "gif", False)
    MP3 = ("MP3 (audio only)", "mp3", True)
    M4A = ("M4A (audio only)", "m4a", True)

    def __init__(self, label: str, ext: str, is_audio: bool):
        self.label = label
        self.ext = ext
        self.is_audio = is_audio


class Resolution(Enum):
    """Max height ("up to"); sites without that exact height get the closest lower one."""
    BEST = ("Best available", None)
    P2160 = ("2160p (4K)", 2160)
    P1440 = ("1440p", 1440)
    P1080 = ("1080p", 1080)
    P720 = ("720p", 720)
    P480 = ("480p", 480)
    P360 = ("360p", 360)

    def __init__(self, label: str, height: int | None):
        self.label = label
        self.height = height
