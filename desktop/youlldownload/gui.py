"""Simple Tkinter window: Download link / Convert file tabs, format, resolution, 10 MB compression."""
from __future__ import annotations

import json
import os
import queue
import subprocess
import sys
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

from . import __version__, tools
from .core import Worker
from .downloader import Downloader
from .formats import OutFormat, Resolution
from .media import Media

PAD = 12


class App:
    def __init__(self, root: tk.Tk):
        self.root = root
        root.title("Youlldownload")
        root.minsize(520, 0)
        _set_theme(root)

        self.settings_path = os.path.join(tools.data_dir(), "settings.json")
        s = self._load_settings()

        self.yt_dlp = tools.load_ytdlp()
        ffmpeg = tools.find_ffmpeg()
        self.worker = Worker(Downloader(self.yt_dlp, ffmpeg, tools.find_deno()), Media(ffmpeg or "ffmpeg"))
        self.events: queue.Queue = queue.Queue()
        self.busy = False
        self.result_path: str | None = None

        self.url = tk.StringVar()
        self.file = tk.StringVar()
        self.fmt = tk.StringVar(value=_enum(OutFormat, s.get("format"), OutFormat.MP4).label)
        self.res = tk.StringVar(value=_enum(Resolution, s.get("resolution"), Resolution.BEST).label)
        self.compress = tk.BooleanVar(value=bool(s.get("compress", False)))
        self.save_dir = tk.StringVar(value=s.get("save_dir") or tools.default_save_dir())
        self.status = tk.StringVar()

        self._build()
        if not ffmpeg:
            self._error("ffmpeg wasn't found, so merging, converting and compressing won't work.")
        root.after(100, self._poll)

    # ---- layout ----------------------------------------------------------------------

    def _build(self):
        main = ttk.Frame(self.root, padding=PAD)
        main.pack(fill="both", expand=True)
        main.columnconfigure(0, weight=1)

        menu = tk.Menu(self.root)
        helpm = tk.Menu(menu, tearoff=False)
        helpm.add_command(label="Update yt-dlp", command=self._update)
        helpm.add_command(label="About", command=lambda: messagebox.showinfo(
            "Youlldownload", f"Youlldownload {__version__}\nyt-dlp {tools.ytdlp_version()}"))
        menu.add_cascade(label="Help", menu=helpm)
        self.root.config(menu=menu)

        # Tabs
        self.tabs = ttk.Notebook(main)
        self.tabs.grid(row=0, column=0, sticky="ew")
        link_tab = ttk.Frame(self.tabs, padding=PAD)
        file_tab = ttk.Frame(self.tabs, padding=PAD)
        self.tabs.add(link_tab, text="Download link")
        self.tabs.add(file_tab, text="Convert file")

        link_tab.columnconfigure(0, weight=1)
        ttk.Label(link_tab, text="Paste a video link (YouTube, TikTok, Instagram, X, Reddit…)").grid(
            row=0, column=0, columnspan=2, sticky="w")
        self.url_entry = ttk.Entry(link_tab, textvariable=self.url)
        self.url_entry.grid(row=1, column=0, sticky="ew", pady=(4, 0))
        self.url_entry.bind("<Return>", lambda e: self._start())
        self.paste_btn = ttk.Button(link_tab, text="Paste", command=self._paste)
        self.paste_btn.grid(row=1, column=1, padx=(8, 0), pady=(4, 0))

        file_tab.columnconfigure(0, weight=1)
        ttk.Label(file_tab, text="Any video or audio file: TS, MOV, AVI, MKV, WEBM, FLV, WAV, FLAC…").grid(
            row=0, column=0, columnspan=2, sticky="w")
        self.file_entry = ttk.Entry(file_tab, textvariable=self.file)
        self.file_entry.grid(row=1, column=0, sticky="ew", pady=(4, 0))
        self.browse_btn = ttk.Button(file_tab, text="Browse…", command=self._browse)
        self.browse_btn.grid(row=1, column=1, padx=(8, 0), pady=(4, 0))

        # Options
        opts = ttk.Frame(main)
        opts.grid(row=1, column=0, sticky="ew", pady=(PAD, 0))
        opts.columnconfigure(1, weight=1)
        opts.columnconfigure(3, weight=1)
        ttk.Label(opts, text="Format").grid(row=0, column=0, sticky="w", padx=(0, 8))
        self.fmt_box = ttk.Combobox(opts, textvariable=self.fmt, state="readonly",
                                    values=[f.label for f in OutFormat])
        self.fmt_box.grid(row=0, column=1, sticky="ew")
        self.fmt_box.bind("<<ComboboxSelected>>", lambda e: (self._save_settings(), self._refresh()))
        self.res_label = ttk.Label(opts, text="Resolution")
        self.res_label.grid(row=0, column=2, sticky="w", padx=(PAD, 8))
        self.res_box = ttk.Combobox(opts, textvariable=self.res, state="readonly",
                                    values=[r.label for r in Resolution])
        self.res_box.grid(row=0, column=3, sticky="ew")
        self.res_box.bind("<<ComboboxSelected>>", lambda e: self._save_settings())

        self.compress_chk = ttk.Checkbutton(
            main, variable=self.compress, command=lambda: (self._save_settings(), self._refresh()),
            text="Compress to under 10 MB (uses the full 10 MB for the best quality; takes longer)")
        self.compress_chk.grid(row=2, column=0, sticky="w", pady=(PAD, 0))

        save = ttk.Frame(main)
        save.grid(row=3, column=0, sticky="ew", pady=(PAD, 0))
        save.columnconfigure(1, weight=1)
        ttk.Label(save, text="Save to").grid(row=0, column=0, padx=(0, 8))
        ttk.Entry(save, textvariable=self.save_dir, state="readonly").grid(row=0, column=1, sticky="ew")
        self.change_btn = ttk.Button(save, text="Change…", command=self._choose_dir)
        self.change_btn.grid(row=0, column=2, padx=(8, 0))

        # Action + progress
        self.go_btn = ttk.Button(main, text="Download", command=self._start_or_cancel)
        self.go_btn.grid(row=4, column=0, sticky="ew", pady=(PAD, 0), ipady=6)
        self.bar = ttk.Progressbar(main, mode="determinate", maximum=1.0)
        self.bar.grid(row=5, column=0, sticky="ew", pady=(PAD, 0))
        self.status_lbl = ttk.Label(main, textvariable=self.status, wraplength=480)
        self.status_lbl.grid(row=6, column=0, sticky="w", pady=(6, 0))

        self.result = ttk.Frame(main)
        self.result.grid(row=7, column=0, sticky="ew", pady=(6, 0))
        self.result_lbl = ttk.Label(self.result, wraplength=480)
        self.result_lbl.pack(side="top", anchor="w")
        btns = ttk.Frame(self.result)
        btns.pack(side="top", anchor="w", pady=(6, 0))
        ttk.Button(btns, text="Open", command=lambda: _open(self.result_path)).pack(side="left")
        ttk.Button(btns, text="Show in folder", command=lambda: _reveal(self.result_path)).pack(side="left", padx=(8, 0))
        self.result.grid_remove()
        self.bar.grid_remove()

        self.tabs.bind("<<NotebookTabChanged>>", lambda e: self._refresh())
        self._refresh()
        self.url_entry.focus_set()

    def _file_mode(self) -> bool:
        return self.tabs.index("current") == 1

    def _refresh(self):
        fmt = self._fmt()
        file_mode = self._file_mode()
        labels = [("Keep original" if file_mode and r == Resolution.BEST else r.label) for r in Resolution]
        current = self._res()
        self.res_box.config(values=labels)
        self.res.set(labels[list(Resolution).index(current)])
        self.res_label.config(text="Max resolution" if self.compress.get() or fmt == OutFormat.GIF else "Resolution")

        state = "disabled" if self.busy else "normal"
        for w in (self.url_entry, self.paste_btn, self.file_entry, self.browse_btn, self.change_btn, self.compress_chk):
            w.config(state=state)
        self.fmt_box.config(state="disabled" if self.busy else "readonly")
        self.res_box.config(state="disabled" if self.busy or fmt.is_audio else "readonly")
        for i in range(2):
            self.tabs.tab(i, state="normal" if not self.busy or i == self.tabs.index("current") else "disabled")
        self.go_btn.config(text="Cancel" if self.busy else ("Convert" if file_mode else "Download"))

    def _fmt(self) -> OutFormat:
        return next((f for f in OutFormat if f.label == self.fmt.get()), OutFormat.MP4)

    def _res(self) -> Resolution:
        v = self.res.get()
        if v == "Keep original":
            return Resolution.BEST
        return next((r for r in Resolution if r.label == v), Resolution.BEST)

    # ---- actions ---------------------------------------------------------------------

    def _paste(self):
        try:
            text = self.root.clipboard_get().strip()
        except tk.TclError:
            text = ""
        if text:
            self.url.set(text.split()[0] if text.split() else text)

    def _browse(self):
        path = filedialog.askopenfilename(title="Choose a video or audio file")
        if path:
            self.file.set(path)

    def _choose_dir(self):
        path = filedialog.askdirectory(title="Save files to", initialdir=self.save_dir.get())
        if path:
            self.save_dir.set(path)
            self._save_settings()

    def _start_or_cancel(self):
        if self.busy:
            self.status.set("Cancelling…")
            self.worker.cancel()
        else:
            self._start()

    def _start(self):
        if self.busy:
            return
        file_mode = self._file_mode()
        url = self.url.get().strip()
        file = self.file.get().strip()
        if file_mode:
            if not file or not os.path.isfile(file):
                return self._error("Choose a file first.")
        elif not url.startswith(("http://", "https://")):
            return self._error("That doesn't look like a link. Paste a full https:// URL.")

        self.busy = True
        self.result.grid_remove()
        self.bar.grid()
        self.bar.config(mode="indeterminate")
        self.bar.start(12)
        self.status_lbl.config(foreground="")
        self._refresh()

        args = dict(url=None if file_mode else url, file=file if file_mode else None, fmt=self._fmt(),
                    res=self._res(), compress=self.compress.get(), save_dir=self.save_dir.get(),
                    progress=lambda f, msg: self.events.put(("progress", f, msg)))

        def run():
            try:
                self.events.put(("done", self.worker.run(**args)))
            except Exception as e:  # noqa: BLE001 - shown to the user
                cancelled = type(e).__name__ in ("Cancelled", "DownloadCancelled")
                self.events.put(("cancelled",) if cancelled else ("error", str(e) or type(e).__name__))

        threading.Thread(target=run, daemon=True).start()

    def _update(self):
        if self.busy:
            return
        self.status_lbl.config(foreground="")
        self.status.set("Checking for a newer yt-dlp…")

        def run():
            try:
                self.events.put(("info", tools.update_ytdlp()))
            except Exception as e:  # noqa: BLE001
                self.events.put(("error", f"Update failed: {e}"))

        threading.Thread(target=run, daemon=True).start()

    # ---- worker → UI -----------------------------------------------------------------

    def _poll(self):
        try:
            while True:
                ev = self.events.get_nowait()
                kind = ev[0]
                if kind == "progress":
                    _, f, msg = ev
                    if f is None:
                        if str(self.bar.cget("mode")) != "indeterminate":
                            self.bar.config(mode="indeterminate")
                            self.bar.start(12)
                    else:
                        if str(self.bar.cget("mode")) != "determinate":
                            self.bar.stop()
                            self.bar.config(mode="determinate")
                        self.bar["value"] = f
                    self.status.set(msg)
                elif kind == "info":
                    self.status.set(ev[1])
                else:
                    self._finish()
                    if kind == "done":
                        self.result_path = ev[1]
                        size = os.path.getsize(ev[1]) / 1e6
                        self.status.set("")
                        self.result_lbl.config(text=f"Saved ✓  {os.path.basename(ev[1])}  ({size:.1f} MB)")
                        self.result.grid()
                    elif kind == "cancelled":
                        self.status.set("Cancelled.")
                    else:
                        self._error(ev[1])
        except queue.Empty:
            pass
        self.root.after(100, self._poll)

    def _finish(self):
        self.busy = False
        self.bar.stop()
        self.bar.grid_remove()
        self._refresh()

    def _error(self, msg: str):
        self.status_lbl.config(foreground="#c62828")
        self.status.set(msg)

    # ---- settings --------------------------------------------------------------------

    def _load_settings(self) -> dict:
        try:
            with open(self.settings_path, encoding="utf-8") as f:
                return json.load(f)
        except (OSError, ValueError):
            return {}

    def _save_settings(self):
        data = {"format": self._fmt().name, "resolution": self._res().name,
                "compress": self.compress.get(), "save_dir": self.save_dir.get()}
        try:
            with open(self.settings_path, "w", encoding="utf-8") as f:
                json.dump(data, f)
        except OSError:
            pass


def _enum(cls, name, default):
    try:
        return cls[name]
    except (KeyError, TypeError):
        return default


def _set_theme(root: tk.Tk):
    style = ttk.Style(root)
    for theme in ("vista", "aqua", "clam"):  # native look on Windows / macOS, clean fallback on Linux
        if theme in style.theme_names():
            style.theme_use(theme)
            break


def _open(path: str | None):
    if not path:
        return
    if sys.platform == "win32":
        os.startfile(path)  # type: ignore[attr-defined]
    elif sys.platform == "darwin":
        subprocess.Popen(["open", path])
    else:
        subprocess.Popen(["xdg-open", path])


def _reveal(path: str | None):
    if not path:
        return
    if sys.platform == "win32":
        subprocess.Popen(["explorer", "/select,", os.path.normpath(path)])
    elif sys.platform == "darwin":
        subprocess.Popen(["open", "-R", path])
    else:
        subprocess.Popen(["xdg-open", os.path.dirname(path)])


def main():
    root = tk.Tk()
    App(root)
    root.mainloop()
