package com.youlldownload.app

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/** Thin wrapper around yt-dlp (via youtubedl-android) that downloads one link into [workDir]. */
class Engine(private val context: Context) {

    fun init() {
        YoutubeDL.getInstance().init(context)
        FFmpeg.getInstance().init(context)
    }

    /** Updates yt-dlp itself. Sites change often, so a fresh yt-dlp fixes most "unsupported" errors. */
    fun update(): Boolean =
        YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE) ==
            YoutubeDL.UpdateStatus.DONE

    fun version(): String? = YoutubeDL.getInstance().versionName(context)

    /**
     * @param forCompression the file will be re-encoded afterwards, so skip remuxing and
     *        don't fetch more than 1080p (10 MB can't hold more anyway).
     */
    fun download(
        url: String,
        format: OutFormat,
        resolution: Resolution,
        forCompression: Boolean,
        workDir: File,
        processId: String,
        onProgress: (percent: Float, line: String) -> Unit,
    ): File {
        val req = YoutubeDLRequest(url.trim())
        req.addOption("-o", File(workDir, "%(title).80B [%(id)s].%(ext)s").absolutePath)
        req.addOption("--no-playlist")
        req.addOption("--no-mtime")
        req.addOption("--newline")

        if (format.isAudio) {
            req.addOption("-f", "ba/b")
            req.addOption("-x")
            req.addOption("--audio-format", format.ext)
            req.addOption("--audio-quality", "0")
        } else {
            var height = resolution.height
            if (forCompression) height = minOf(height ?: 1080, 1080)
            val res = height?.let { "res:$it" } ?: "res"
            req.addOption("-f", "bv*+ba/b")
            when (format) {
                OutFormat.WEBM -> req.addOption("-S", "$res,vcodec:vp9,acodec:opus")
                else -> req.addOption("-S", "$res,vcodec:h264,acodec:m4a")
            }
            if (forCompression) {
                // Intermediate file only; MKV accepts any codec combination.
                req.addOption("--merge-output-format", "mkv")
            } else when (format) {
                OutFormat.MP4 -> {
                    req.addOption("--merge-output-format", "mp4")
                    req.addOption("--remux-video", "mp4")
                }
                OutFormat.MKV -> {
                    req.addOption("--merge-output-format", "mkv")
                    req.addOption("--remux-video", "mkv")
                }
                OutFormat.WEBM -> {
                    req.addOption("--merge-output-format", "webm/mkv")
                    req.addOption("--recode-video", "webm") // only re-encodes when the site has no VP9
                }
                else -> Unit
            }
        }

        try {
            YoutubeDL.getInstance().execute(req, processId) { progress, _, line ->
                onProgress(progress, line)
            }
        } catch (e: YoutubeDLException) {
            throw RuntimeException(cleanError(e.message), e)
        }

        return workDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.length() }
            ?: throw RuntimeException("Download finished but no file was produced.")
    }

    fun cancel(processId: String) {
        YoutubeDL.getInstance().destroyProcessById(processId)
    }

    private fun cleanError(raw: String?): String {
        val lines = raw.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        val err = lines.lastOrNull { it.startsWith("ERROR:") } ?: lines.lastOrNull()
        return err?.removePrefix("ERROR:")?.trim()?.take(300) ?: "Download failed."
    }
}
