package com.youlldownload.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shrinks a file to fit under [SIZE_LIMIT_BYTES] while keeping as much quality as possible:
 *  - the bitrate is computed from the duration so the whole size budget gets used,
 *  - two-pass encoding spends those bits where the video needs them and lands on the target size,
 *  - resolution and frame rate are only lowered when the budget is too small for them.
 * Uses the ffmpeg binary bundled by youtubedl-android.
 */
class Compressor(context: Context) {

    private val binDir = File(context.applicationInfo.nativeLibraryDir)
    private val ffmpeg = File(binDir, "libffmpeg.so")
    private val ffprobe = File(binDir, "libffprobe.so")
    private val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
    private val libPath = listOf("ffmpeg", "python")
        .joinToString(":") { File(packages, "$it/usr/lib").absolutePath }

    @Volatile private var current: Process? = null
    @Volatile private var cancelled = false

    data class Info(
        val durationSec: Double,
        val width: Int,
        val height: Int,
        val fps: Double,
        val hasVideo: Boolean,
        val hasAudio: Boolean,
        val bitrateKbps: Int,
    )

    fun cancel() {
        cancelled = true
        current?.destroy()
    }

    fun probe(file: File): Info {
        val out = run(
            listOf(
                ffprobe.absolutePath, "-v", "error", "-print_format", "json",
                "-show_format", "-show_streams", file.absolutePath,
            ),
            file.parentFile!!,
        )
        val json = JSONObject(out)
        val fmt = json.optJSONObject("format")
        val streams = json.optJSONArray("streams")
        var w = 0; var h = 0; var fps = 30.0; var hasV = false; var hasA = false
        for (i in 0 until (streams?.length() ?: 0)) {
            val s = streams!!.getJSONObject(i)
            when (s.optString("codec_type")) {
                "video" -> if (!hasV && s.optJSONObject("disposition")?.optInt("attached_pic") != 1) {
                    hasV = true
                    w = s.optInt("width"); h = s.optInt("height")
                    fps = parseRate(s.optString("avg_frame_rate")) ?: parseRate(s.optString("r_frame_rate")) ?: 30.0
                }
                "audio" -> hasA = true
            }
        }
        val duration = fmt?.optString("duration")?.toDoubleOrNull() ?: 0.0
        val bitrate = (fmt?.optString("bit_rate")?.toLongOrNull() ?: 0L) / 1000
        return Info(duration, w, h, fps, hasV, hasA, bitrate.toInt())
    }

    /**
     * @param maxHeight user's chosen resolution cap (short side), or null for no cap.
     * @return the compressed file, or [input] itself if it was already small enough.
     */
    fun compress(
        input: File,
        format: OutFormat,
        maxHeight: Int?,
        outDir: File,
        onProgress: (fraction: Float, status: String) -> Unit,
    ): File {
        cancelled = false
        val info = probe(input)
        if (info.durationSec <= 0.0) throw RuntimeException("Couldn't read the video length.")

        val output = File(outDir, input.nameWithoutExtension + "." + format.ext)

        // Already small enough: keep the original quality, only change the container if needed.
        if (input.length() < SIZE_LIMIT_BYTES) {
            if (input.extension.equals(format.ext, true)) return input
            onProgress(0f, "Already under 10 MB, converting container…")
            if (tryRemux(input, output, format)) return output
        }

        return if (format.isAudio || !info.hasVideo) {
            compressAudio(input, output, format, info, onProgress)
        } else {
            compressVideo(input, output, format, info, maxHeight, onProgress)
        }
    }

    // ---- video ---------------------------------------------------------------------------

    private fun compressVideo(
        input: File,
        output: File,
        format: OutFormat,
        info: Info,
        maxHeight: Int?,
        onProgress: (Float, String) -> Unit,
    ): File {
        // Leave ~4% headroom for container overhead and encoder variance.
        val totalKbps = SIZE_LIMIT_BYTES * 8 * 0.96 / info.durationSec / 1000
        val audioKbps = when {
            !info.hasAudio -> 0
            totalKbps >= 1200 -> 128
            totalKbps >= 600 -> 96
            totalKbps >= 300 -> 64
            else -> 48
        }
        var videoKbps = (totalKbps - audioKbps).roundToInt()
        if (info.bitrateKbps > 0) videoKbps = min(videoKbps, info.bitrateKbps) // never inflate
        if (videoKbps < 60) {
            val maxMin = (SIZE_LIMIT_BYTES * 8 * 0.96 / (60 + audioKbps) / 1000 / 60).roundToInt()
            throw RuntimeException(
                "This video is too long to fit under 10 MB with watchable quality " +
                    "(about $maxMin min is the max). Try MP3/M4A for audio only."
            )
        }

        val tmp = File(output.parentFile, "tmp_" + output.name)
        for (attempt in 0 until 3) {
            val target = targetShortSide(videoKbps)
            val shortSide = min(info.width, info.height).takeIf { it > 0 } ?: target
            val side = minOf(target, shortSide, maxHeight ?: Int.MAX_VALUE).let { it - it % 2 }
            val filters = mutableListOf<String>()
            if (side < shortSide) {
                filters += if (info.width >= info.height) "scale=-2:$side" else "scale=$side:-2"
            }
            if (info.fps > 31 && videoKbps < 2500) filters += "fps=30"

            val label = "Compressing to ${side}p" + (if (attempt > 0) " (retry ${attempt})" else "")
            twoPass(input, tmp, format, info, videoKbps, audioKbps, filters) { f, pass ->
                onProgress(f, "$label · pass $pass of 2")
            }

            val size = tmp.length()
            if (size in 1 until SIZE_LIMIT_BYTES) {
                output.delete()
                tmp.renameTo(output)
                return output
            }
            // Overshot (rare with two-pass): scale bitrate down proportionally and retry.
            videoKbps = (videoKbps * SIZE_LIMIT_BYTES * 0.95 / size).roundToInt()
            if (videoKbps < 60) break
        }
        tmp.delete()
        throw RuntimeException("Couldn't get the file under 10 MB.")
    }

    /** Short-side resolution that still looks clean at this bitrate (H.264-ish numbers). */
    private fun targetShortSide(videoKbps: Int) = when {
        videoKbps >= 2200 -> 1080
        videoKbps >= 1100 -> 720
        videoKbps >= 650 -> 540
        videoKbps >= 450 -> 480
        videoKbps >= 250 -> 360
        else -> 240
    }

    private fun twoPass(
        input: File,
        output: File,
        format: OutFormat,
        info: Info,
        videoKbps: Int,
        audioKbps: Int,
        filters: List<String>,
        onProgress: (Float, Int) -> Unit,
    ) {
        val dir = output.parentFile!!
        val log = File(dir, "passlog").absolutePath
        val vf = if (filters.isEmpty()) emptyList() else listOf("-vf", filters.joinToString(","))
        val vp9 = format == OutFormat.WEBM
        val videoCodec = if (vp9) {
            listOf(
                "-c:v", "libvpx-vp9", "-b:v", "${videoKbps}k", "-row-mt", "1",
                "-deadline", "good", "-pix_fmt", "yuv420p",
            )
        } else {
            listOf(
                "-c:v", "libx264", "-preset", "medium", "-b:v", "${videoKbps}k",
                "-pix_fmt", "yuv420p", "-profile:v", "high",
            )
        }
        val common = listOf(ffmpeg.absolutePath, "-y", "-hide_banner", "-nostats", "-progress", "pipe:1", "-i", input.absolutePath)

        // Pass 1: analysis only.
        val pass1 = common + listOf("-map", "0:v:0") + vf + videoCodec +
            (if (vp9) listOf("-cpu-used", "4") else emptyList()) +
            listOf("-pass", "1", "-passlogfile", log, "-an", "-f", "null", "/dev/null")
        runFfmpeg(pass1, dir, info.durationSec) { onProgress(it * 0.4f, 1) }

        // Pass 2: the real encode.
        val audio = if (info.hasAudio) {
            if (vp9) listOf("-c:a", "libopus", "-b:a", "${audioKbps}k")
            else listOf("-c:a", "aac", "-b:a", "${audioKbps}k", "-ac", "2")
        } else listOf("-an")
        val pass2 = common + listOf("-map", "0:v:0", "-map", "0:a:0?") + vf + videoCodec +
            (if (vp9) listOf("-cpu-used", "2") else emptyList()) +
            listOf("-pass", "2", "-passlogfile", log) + audio +
            (if (format == OutFormat.MP4) listOf("-movflags", "+faststart") else emptyList()) +
            listOf(output.absolutePath)
        runFfmpeg(pass2, dir, info.durationSec) { onProgress(0.4f + it * 0.6f, 2) }

        dir.listFiles()?.filter { it.name.startsWith("passlog") }?.forEach { it.delete() }
    }

    // ---- audio ---------------------------------------------------------------------------

    private fun compressAudio(
        input: File,
        output: File,
        format: OutFormat,
        info: Info,
        onProgress: (Float, String) -> Unit,
    ): File {
        val kbps = min(320, (SIZE_LIMIT_BYTES * 8 * 0.95 / info.durationSec / 1000).toInt())
        if (kbps < 32) throw RuntimeException("This audio is too long to fit under 10 MB.")
        val codec = if (format == OutFormat.MP3) listOf("-c:a", "libmp3lame") else listOf("-c:a", "aac")
        val tmp = File(output.parentFile, "tmp_" + output.name)
        val cmd = listOf(ffmpeg.absolutePath, "-y", "-hide_banner", "-nostats", "-progress", "pipe:1",
            "-i", input.absolutePath, "-vn", "-map", "0:a:0") + codec +
            listOf("-b:a", "${kbps}k", tmp.absolutePath)
        runFfmpeg(cmd, output.parentFile!!, info.durationSec) { onProgress(it, "Compressing audio to ${kbps} kbps") }
        output.delete()
        tmp.renameTo(output)
        return output
    }

    private fun tryRemux(input: File, output: File, format: OutFormat): Boolean = try {
        val cmd = listOf(ffmpeg.absolutePath, "-y", "-hide_banner", "-i", input.absolutePath,
            "-map", "0:v:0?", "-map", "0:a:0?", "-c", "copy") +
            (if (format == OutFormat.MP4) listOf("-movflags", "+faststart") else emptyList()) +
            listOf(output.absolutePath)
        run(cmd, output.parentFile!!)
        output.length() in 1 until SIZE_LIMIT_BYTES
    } catch (e: Exception) {
        if (cancelled) throw e
        output.delete()
        false
    }

    // ---- process helpers -----------------------------------------------------------------

    private fun start(cmd: List<String>, dir: File, errFile: File): Process {
        if (cancelled) throw InterruptedException("Cancelled")
        val pb = ProcessBuilder(cmd).directory(dir).redirectError(errFile)
        pb.environment()["LD_LIBRARY_PATH"] = libPath
        pb.environment()["TMPDIR"] = dir.absolutePath
        return pb.start().also { current = it }
    }

    /** Runs a command and returns stdout. */
    private fun run(cmd: List<String>, dir: File): String {
        val err = File(dir, "ffmpeg_err.txt")
        val p = start(cmd, dir, err)
        val out = p.inputStream.bufferedReader().readText()
        finish(p, err)
        return out
    }

    /** Runs ffmpeg with `-progress pipe:1` and reports 0..1 progress. */
    private fun runFfmpeg(cmd: List<String>, dir: File, durationSec: Double, onProgress: (Float) -> Unit) {
        val err = File(dir, "ffmpeg_err.txt")
        val p = start(cmd, dir, err)
        val totalUs = durationSec * 1_000_000
        p.inputStream.bufferedReader().forEachLine { line ->
            if (line.startsWith("out_time_us=") || line.startsWith("out_time_ms=")) {
                val us = line.substringAfter('=').toLongOrNull() ?: return@forEachLine
                onProgress(max(0f, min(1f, (us / totalUs).toFloat())))
            }
        }
        finish(p, err)
    }

    private fun finish(p: Process, err: File) {
        val code = p.waitFor()
        current = null
        if (cancelled) throw InterruptedException("Cancelled")
        if (code != 0) {
            val msg = err.takeIf { it.exists() }?.readLines()?.lastOrNull { it.isNotBlank() } ?: "exit code $code"
            throw RuntimeException("ffmpeg failed: $msg")
        }
    }

    private fun parseRate(r: String): Double? {
        val parts = r.split('/')
        val n = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val d = parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0
        return if (n > 0 && d > 0) n / d else null
    }
}
