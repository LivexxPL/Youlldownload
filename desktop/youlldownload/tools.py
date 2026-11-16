"""Finds the bundled ffmpeg / deno binaries, the app's data folder, and keeps yt-dlp up to date."""
from __future__ import annotations

import json
import os
import shutil
import sys
import urllib.request
import zipfile

APP_NAME = "Youlldownload"


def data_dir() -> str:
    if sys.platform == "win32":
        base = os.environ.get("LOCALAPPDATA") or os.path.expanduser("~\\AppData\\Local")
    elif sys.platform == "darwin":
        base = os.path.expanduser("~/Library/Application Support")
    else:
        base = os.environ.get("XDG_DATA_HOME") or os.path.expanduser("~/.local/share")
    path = os.path.join(base, APP_NAME)
    os.makedirs(path, exist_ok=True)
    return path


def default_save_dir() -> str:
    return os.path.join(os.path.expanduser("~"), "Downloads", APP_NAME)


def _bundled_bin_dir() -> str | None:
    base = getattr(sys, "_MEIPASS", None)  # set when running from the PyInstaller build
    if base:
        return os.path.join(base, "bin")
    return None


def _exe(name: str) -> str:
    return name + (".exe" if sys.platform == "win32" else "")


def find_ffmpeg() -> str | None:
    d = _bundled_bin_dir()
    if d and os.path.exists(os.path.join(d, _exe("ffmpeg"))):
        return os.path.join(d, _exe("ffmpeg"))
    try:
        import imageio_ffmpeg  # dev installs
        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        return shutil.which("ffmpeg")


def find_deno() -> str | None:
    """yt-dlp needs a JavaScript runtime (deno) for full YouTube support."""
    d = _bundled_bin_dir()
    if d and os.path.exists(os.path.join(d, _exe("deno"))):
        return os.path.join(d, _exe("deno"))
    try:
        import deno  # dev installs (pip package "deno")
        return deno.find_deno_bin()
    except Exception:
        return shutil.which("deno")


# ---- yt-dlp loading / updating -----------------------------------------------------------
#
# The app ships with a yt-dlp version baked in. Sites change often, so it can download the
# official "yt-dlp" zipimport release into the data folder; on the next start that newer copy
# is imported instead (falling back to the bundled one if it can't be loaded).

_UPDATED_ZIP = "yt-dlp.zip"


def load_ytdlp():
    zpath = os.path.join(data_dir(), _UPDATED_ZIP)
    if os.path.exists(zpath):
        sys.path.insert(0, zpath)
        try:
            import yt_dlp  # noqa: F401
            return yt_dlp
        except Exception:
            sys.path.remove(zpath)
            for mod in [m for m in sys.modules if m == "yt_dlp" or m.startswith("yt_dlp.")]:
                del sys.modules[mod]
    import yt_dlp
    return yt_dlp


def ytdlp_version() -> str:
    import yt_dlp
    return yt_dlp.version.__version__


def update_ytdlp() -> str:
    """Downloads the newest yt-dlp if there is one. Returns a message for the user."""
    req = urllib.request.Request(
        "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest",
        headers={"Accept": "application/vnd.github+json", "User-Agent": APP_NAME},
    )
    with urllib.request.urlopen(req, timeout=20) as r:
        rel = json.load(r)
    latest = rel["tag_name"]
    if latest == ytdlp_version():
        return f"yt-dlp is up to date ({latest})."
    asset = next((a for a in rel["assets"] if a["name"] == "yt-dlp"), None)
    if not asset:
        raise RuntimeError("Couldn't find the yt-dlp download.")

    target = os.path.join(data_dir(), _UPDATED_ZIP)
    tmp = target + ".part"
    req = urllib.request.Request(asset["browser_download_url"], headers={"User-Agent": APP_NAME})
    with urllib.request.urlopen(req, timeout=60) as r, open(tmp, "wb") as f:
        shutil.copyfileobj(r, f)
    with zipfile.ZipFile(tmp) as z:  # sanity check: it must be the yt-dlp zipimport build
        if "yt_dlp/__init__.py" not in z.namelist():
            os.remove(tmp)
            raise RuntimeError("Downloaded file isn't a yt-dlp build.")
    os.replace(tmp, target)
    return f"yt-dlp {latest} downloaded. Restart the app to use it."
