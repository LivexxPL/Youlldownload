"""Builds the standalone desktop app with PyInstaller (ffmpeg + deno bundled).

    pip install -r requirements.txt pyinstaller
    python build.py

Output: dist/Youlldownload (Windows/Linux folder) or dist/Youlldownload.app (macOS).
"""
import os
import shutil
import sys

import deno
import imageio_ffmpeg
import PyInstaller.__main__

HERE = os.path.dirname(os.path.abspath(__file__))
EXE = ".exe" if sys.platform == "win32" else ""


def main():
    bin_dir = os.path.join(HERE, "build", "bin")
    shutil.rmtree(bin_dir, ignore_errors=True)
    os.makedirs(bin_dir)
    for src, name in ((imageio_ffmpeg.get_ffmpeg_exe(), "ffmpeg"), (deno.find_deno_bin(), "deno")):
        dest = os.path.join(bin_dir, name + EXE)
        shutil.copy2(src, dest)
        os.chmod(dest, 0o755)
        print(f"bundling {src} -> {dest}")

    args = [
        os.path.join(HERE, "run.py"),
        "--name", "Youlldownload",
        "--windowed",
        "--noconfirm",
        "--clean",
        "--paths", HERE,
        "--distpath", os.path.join(HERE, "dist"),
        "--workpath", os.path.join(HERE, "build", "pyi"),
        "--specpath", os.path.join(HERE, "build"),
        "--collect-data", "yt_dlp_ejs",
        "--osx-bundle-identifier", "com.youlldownload.desktop",
    ]
    for name in os.listdir(bin_dir):
        args += ["--add-binary", f"{os.path.join(bin_dir, name)}{os.pathsep}bin"]
    PyInstaller.__main__.run(args)


if __name__ == "__main__":
    main()
