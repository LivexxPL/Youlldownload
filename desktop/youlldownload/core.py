"""One download/convert job, independent of the UI."""
from __future__ import annotations

import os
import shutil
import tempfile
from typing import Callable

from .downloader import Downloader
from .formats import OutFormat, Resolution
from .media import Media

Progress = Callable[[float | None, str], None]


class Worker:
    def __init__(self, downloader: Downloader, media: Media):
        self.downloader = downloader
        self.media = media

    def cancel(self):
        self.downloader.cancel()
        self.media.cancel()

    def run(
        self,
        *,
        url: str | None,
        file: str | None,
        fmt: OutFormat,
        res: Resolution,
        compress: bool,
        save_dir: str,
        progress: Progress,
    ) -> str:
        """Downloads `url` or converts `file`, saves into `save_dir`, returns the saved path."""
        self.media.reset()
        work = tempfile.mkdtemp(prefix="youlldownload-")
        try:
            if url:
                progress(None, "Starting…")
                src = self.downloader.download(url, fmt, res, compress, work, progress)
            else:
                src = file
            out_dir = os.path.join(work, "out")
            os.makedirs(out_dir, exist_ok=True)

            def report(f: float, msg: str):
                progress(f, f"{msg} · {int(f * 100)}%")

            if fmt == OutFormat.GIF:
                progress(None, "Making GIF…")
                final = self.media.make_gif(src, res.height, compress, out_dir, report)
            elif compress:
                progress(None, "Preparing compression…")
                final = self.media.compress(src, fmt, res.height, out_dir, report)
            elif file:
                progress(None, "Converting…")
                final = self.media.convert(src, fmt, res.height, out_dir, report)
            else:
                final = src

            progress(None, "Saving…")
            return _save(final, save_dir, keep_original=(final == file))
        finally:
            shutil.rmtree(work, ignore_errors=True)


def _save(path: str, save_dir: str, keep_original: bool) -> str:
    os.makedirs(save_dir, exist_ok=True)
    name = os.path.basename(path)
    if name.startswith("tmp_"):
        name = name[4:]
    stem, ext = os.path.splitext(name)
    dest = os.path.join(save_dir, name)
    i = 1
    while os.path.exists(dest):
        dest = os.path.join(save_dir, f"{stem} ({i}){ext}")
        i += 1
    if keep_original:
        shutil.copy2(path, dest)  # never move the user's own file
    else:
        shutil.move(path, dest)
    return dest
