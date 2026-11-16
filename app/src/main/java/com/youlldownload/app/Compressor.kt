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
        val videoCodec: String = "",
        val audioCodec: String = "",
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
        var vCodec = ""; var aCodec = ""
        for (i in 0 until (streams?.length() ?: 0)) {
            val s = streams!!.getJSONObject(i)
            when (s.optString("codec_type")) {
                "video" -> if (!hasV && s.optJSONObject("disposition")?.optInt("attached_pic") != 1) {
                    hasV = true
                    vCodec = s.optString("codec_name")
                    w = s.optInt("width"); h = s.optInt("height")
                    fps = parseRate(s.optString("avg_frame_rate")) ?: parseRate(s.optString("r_frame_rate")) ?: 30.0
                }
                "audio" -> if (!hasA) { hasA = true; aCodec = s.optString("codec_name") }
            }
        }
        val duration = fmt?.optString("duration")?.toDoubleOrNull() ?: 0.0
        val bitrate = (fmt?.optString("bit_rate")?.toLongOrNull() ?: 0L) / 1000
        if (!hasV && !hasA) throw RuntimeException("This file isn't a video or audio file that can be read.")
        return Info(duration, w, h, fps, hasV, hasA, bitrate.toInt(), vCodec, aCodec)
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
        // Same frame timing in both passes: otherwise the MP4 muxer may duplicate a frame in pass 2,
        // x264 then gets more frames than its pass-1 stats and errors out, crashes or hangs.
        val common = listOf(
            ffmpeg.absolutePath, "-y", "-hide_banner", "-nostats", "-progress", "pipe:1",
            "-i", input.absolutePath, "-fps_mode", "passthrough",
        )

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

    // ---- plain conversion (files) --------------------------------------------------------

    /**
     * Converts a local file to [format] with as little quality loss as possible: each stream
     * (video, audio) is copied untouched when the target container supports its codec, and only
     * re-encoded (high quality) when it doesn't, or when [maxHeight] asks for a smaller size.
     * E.g. a .ts with H.264 + AAC becomes an .mp4 in seconds, with zero quality loss.
     */
    fun convert(
        input: File,
        format: OutFormat,
        maxHeight: Int?,
        outDir: File,
        onProgress: (fraction: Float, status: String) -> Unit,
    ): File {
        cancelled = false
        val info = probe(input)
        val output = File(outDir, input.nameWithoutExtension + "." + format.ext)
        if (format.isAudio && !info.hasAudio) throw RuntimeException("This file has no sound to turn into ${format.ext.uppercase()}.")
        if (!format.isAudio && !info.hasVideo) throw RuntimeException("This file has no video. Pick MP3 or M4A instead.")

        val shortSide = min(info.width, info.height)
        val scale = maxHeight != null && shortSide > maxHeight
        val vc = info.videoCodec
        val ac = info.audioCodec
        val copyVideo = !scale && when (format) {
            OutFormat.MP4 -> vc in setOf("h264", "hevc", "av1", "mpeg4")
            OutFormat.WEBM -> vc in setOf("vp8", "vp9", "av1")
            OutFormat.MKV -> true
            else -> false
        }
        val copyAudio = when (format) {
            OutFormat.MP4 -> ac in setOf("aac", "mp3")
            OutFormat.WEBM -> ac in setOf("opus", "vorbis")
            OutFormat.MKV -> true
            OutFormat.MP3 -> ac == "mp3"
            OutFormat.M4A -> ac in setOf("aac", "alac")
            OutFormat.GIF -> false
        }

        fun command(copyV: Boolean, copyA: Boolean): List<String> {
            val cmd = mutableListOf(ffmpeg.absolutePath, "-y", "-hide_banner", "-nostats", "-progress", "pipe:1",
                "-i", input.absolutePath)
            if (format.isAudio) {
                cmd += listOf("-vn", "-map", "0:a:0")
            } else {
                cmd += listOf("-map", "0:v:0", "-map", "0:a:0?")
                cmd += when {
                    copyV -> listOf("-c:v", "copy") + (if (format == OutFormat.MP4 && vc == "hevc") listOf("-tag:v", "hvc1") else emptyList())
                    format == OutFormat.WEBM -> listOf("-c:v", "libvpx-vp9", "-crf", "31", "-b:v", "0", "-row-mt", "1",
                        "-deadline", "good", "-cpu-used", "4", "-pix_fmt", "yuv420p")
                    else -> listOf("-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p")
                }
                if (!copyV && scale) {
                    cmd += listOf("-vf", if (info.width >= info.height) "scale=-2:$maxHeight" else "scale=$maxHeight:-2")
                }
            }
            if (info.hasAudio) cmd += when {
                copyA -> listOf("-c:a", "copy")
                format == OutFormat.MP3 -> listOf("-c:a", "libmp3lame", "-q:a", "0")
                format == OutFormat.WEBM -> listOf("-c:a", "libopus", "-b:a", "160k")
                else -> listOf("-c:a", "aac", "-b:a", "192k")
            }
            if (format == OutFormat.MP4 || format == OutFormat.M4A) cmd += listOf("-movflags", "+faststart")
            cmd += output.absolutePath
            return cmd
        }

        val v = if (format.isAudio) copyAudio else copyVideo && (copyAudio || !info.hasAudio)
        val label = if (v) "Converting (no quality loss)" else "Converting to ${format.ext.uppercase()} (re-encoding)"
        try {
            runFfmpeg(command(copyVideo, copyAudio), outDir, info.durationSec) { onProgress(it, label) }
        } catch (e: Exception) {
            if (cancelled || (!copyVideo && !copyAudio)) throw e
            // Copying a stream failed (odd timestamps etc.): fall back to a full re-encode.
            output.delete()
            val again = "Converting to ${format.ext.uppercase()} (re-encoding)"
            runFfmpeg(command(false, false), outDir, info.durationSec) { onProgress(it, again) }
        }
        return output
    }

    // ---- gif -----------------------------------------------------------------------------

    /**
     * Converts a video to an animated GIF with a palette made for that clip (much cleaner colours
     * than ffmpeg's default). Without [limit] it uses up to 480p (or the chosen resolution) at 15 fps.
     * With [limit] it steps size and frame rate down until the GIF is under 10 MB.
     */
    fun makeGif(
        input: File,
        maxSide: Int?,
        limit: Boolean,
        outDir: File,
        onProgress: (fraction: Float, status: String) -> Unit,
    ): File {
        cancelled = false
        if (input.extension.equals("gif", true) && (!limit || input.length() < SIZE_LIMIT_BYTES)) return input

        val info = probe(input)
        if (!info.hasVideo) throw RuntimeException("This link has no video to turn into a GIF.")
        val shortSide = min(info.width, info.height).takeIf { it > 0 } ?: 480
        val startSide = minOf(shortSide, maxSide ?: 480)
        val srcFps = info.fps.roundToInt().coerceAtLeast(1)
        val steps = if (limit) {
            listOf(1.0 to 15, 0.75 to 12, 0.6 to 10, 0.5 to 10, 0.4 to 8, 0.3 to 8)
        } else listOf(1.0 to 15)

        val dir = outDir
        val palette = File(dir, "palette.png")
        val output = File(dir, input.nameWithoutExtension + ".gif")

        steps.forEachIndexed { i, (factor, maxFps) ->
            val side = max(64, (startSide * factor).toInt()).let { it - it % 2 }
            val fps = min(maxFps, srcFps)
            val scale = if (info.width >= info.height) "scale=-2:$side:flags=lanczos" else "scale=$side:-2:flags=lanczos"
            val base = "fps=$fps,$scale"
            val label = "Making GIF ${side}p, $fps fps" + (if (i > 0) " (smaller, try ${i + 1})" else "")

            // Pass 1: build the best 256-colour palette for this clip.
            runFfmpeg(
                listOf(ffmpeg.absolutePath, "-y", "-hide_banner", "-nostats", "-progress", "pipe:1",
                    "-i", input.absolutePath, "-an", "-vf", "$base,palettegen=stats_mode=diff",
                    "-update", "1", palette.absolutePath),
                dir, info.durationSec,
            ) { onProgress(it * 0.3f, label) }

            // Pass 2: render the GIF with that palette.
            runFfmpeg(
                listOf(ffmpeg.absolutePath, "-y", "-hide_banner", "-nostats", "-progress", "pipe:1",
                    "-i", input.absolutePath, "-i", palette.absolutePath, "-an",
                    "-lavfi", "$base[x];[x][1:v]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle",
                    "-loop", "0", output.absolutePath),
                dir, info.durationSec,
            ) { onProgress(0.3f + it * 0.7f, label) }

            if (!limit || output.length() in 1 until SIZE_LIMIT_BYTES) {
                palette.delete()
                return output
            }
        }
        palette.delete()
        output.delete()
        throw RuntimeException(
            "This clip is too long to fit as a GIF under 10 MB. Try MP4 with compression instead (much smaller)."
        )
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
        val maps = if (format.isAudio) listOf("-map", "0:a:0") else listOf("-map", "0:v:0?", "-map", "0:a:0?")
        val cmd = listOf(ffmpeg.absolutePath, "-y", "-hide_banner", "-i", input.absolutePath) + maps +
            listOf("-c", "copy") +
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
