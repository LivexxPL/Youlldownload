package com.youlldownload.app

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class SavedFile(val uri: Uri, val name: String, val sizeBytes: Long, val mime: String)

enum class Mode { LINK, FILE }

data class UiState(
    val mode: Mode = Mode.LINK,
    val url: String = "",
    /** File mode: the chosen file. */
    val fileUri: Uri? = null,
    val fileName: String? = null,
    val fileSize: Long = 0,
    val format: OutFormat = OutFormat.MP4,
    val resolution: Resolution = Resolution.BEST,
    val compress: Boolean = false,
    val engineReady: Boolean = false,
    val busy: Boolean = false,
    /** 0..1, or null for an indeterminate bar. */
    val progress: Float? = null,
    val status: String = "Preparing downloader…",
    val result: SavedFile? = null,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val engine = Engine(app)
    private val compressor = Compressor(app)

    private val _state = MutableStateFlow(
        UiState(
            format = prefs.getString("format", null)?.let { runCatching { OutFormat.valueOf(it) }.getOrNull() } ?: OutFormat.MP4,
            resolution = prefs.getString("resolution", null)?.let { runCatching { Resolution.valueOf(it) }.getOrNull() } ?: Resolution.BEST,
            compress = prefs.getBoolean("compress", false),
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null
    private var processId: String? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                engine.init()
                _state.update { it.copy(engineReady = true, status = "") }
            } catch (e: Exception) {
                _state.update { it.copy(status = "", error = "Couldn't start the downloader: ${e.message}") }
                return@launch
            }
            // Keep yt-dlp fresh (sites change often). Silent, at most once a day.
            val last = prefs.getLong("lastUpdate", 0L)
            if (System.currentTimeMillis() - last > 24 * 3600 * 1000L) {
                runCatching { engine.update() }.onSuccess {
                    prefs.edit().putLong("lastUpdate", System.currentTimeMillis()).apply()
                }
            }
        }
    }

    fun setUrl(v: String) = _state.update { it.copy(url = v, error = null) }

    fun setMode(m: Mode) = _state.update { if (it.busy) it else it.copy(mode = m, error = null) }

    fun setFile(uri: Uri) {
        var name: String? = null
        var size = 0L
        runCatching {
            getApplication<Application>().contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    size = if (c.isNull(1)) 0L else c.getLong(1)
                }
            }
        }
        val fallback = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        _state.update {
            if (it.busy) it
            else it.copy(mode = Mode.FILE, fileUri = uri, fileName = name ?: fallback, fileSize = size, error = null, result = null)
        }
    }

    fun setFormat(v: OutFormat) {
        prefs.edit().putString("format", v.name).apply()
        _state.update { it.copy(format = v) }
    }

    fun setResolution(v: Resolution) {
        prefs.edit().putString("resolution", v.name).apply()
        _state.update { it.copy(resolution = v) }
    }

    fun setCompress(v: Boolean) {
        prefs.edit().putBoolean("compress", v).apply()
        _state.update { it.copy(compress = v) }
    }

    /** Pulls the first http(s) link out of shared text like "Check this out https://…". */
    fun onSharedText(text: String) {
        val link = Regex("https?://\\S+").find(text)?.value ?: text.trim()
        _state.update { if (it.busy) it else it.copy(mode = Mode.LINK) }
        setUrl(link)
    }

    fun updateEngine() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progress = null, status = "Updating yt-dlp…", error = null) }
            val msg = withContext(Dispatchers.IO) {
                runCatching { engine.update() }.fold(
                    onSuccess = { updated ->
                        prefs.edit().putLong("lastUpdate", System.currentTimeMillis()).apply()
                        val v = engine.version().orEmpty()
                        if (updated) "yt-dlp updated $v" else "yt-dlp is up to date $v"
                    },
                    onFailure = { "Update failed: ${it.message}" },
                )
            }
            _state.update { it.copy(busy = false, status = msg.trim()) }
        }
    }

    fun start() {
        val s = _state.value
        if (s.busy || !s.engineReady) return
        if (s.mode == Mode.FILE) startConvert(s) else startDownload(s)
    }

    private fun startDownload(s: UiState) {
        val url = s.url.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _state.update { it.copy(error = "That doesn't look like a link. Paste a full https:// URL.") }
            return
        }
        runJob(s) { workDir, id ->
            val downloaded = engine.download(url, s.format, s.resolution, s.compress, workDir, id) { pct, line ->
                val label = when {
                    line.contains("[Merger]") -> "Merging video and audio…"
                    line.contains("[ExtractAudio]") -> "Extracting audio…"
                    line.contains("[VideoConvertor]") || line.contains("[VideoRemuxer]") -> "Converting…"
                    pct > 0 -> "Downloading… ${pct.toInt()}%"
                    else -> null
                }
                _state.update {
                    it.copy(
                        progress = if (pct > 0 && label?.startsWith("Downloading") == true) pct / 100f else null,
                        status = label ?: it.status,
                    )
                }
            }
            postProcess(downloaded, s, workDir, fromFile = false)
        }
    }

    private fun startConvert(s: UiState) {
        val uri = s.fileUri
        if (uri == null) {
            _state.update { it.copy(error = "Choose a file first.") }
            return
        }
        val app = getApplication<Application>()
        runJob(s) { workDir, _ ->
            _state.update { it.copy(progress = null, status = "Reading file…") }
            val inDir = File(workDir, "in").apply { mkdirs() }
            val name = (s.fileName ?: "file").replace('/', '_')
            val input = File(inDir, name)
            val stream = app.contentResolver.openInputStream(uri)
                ?: throw RuntimeException("Couldn't open that file. Try choosing it again.")
            stream.use { inp -> input.outputStream().use { inp.copyTo(it) } }
            postProcess(input, s, workDir, fromFile = true)
        }
    }

    /** GIF / compression / plain conversion step shared by links and files. */
    private fun postProcess(input: File, s: UiState, workDir: File, fromFile: Boolean): File {
        val out = File(workDir, "out").apply { mkdirs() }
        val report: (Float, String) -> Unit = { f, msg ->
            _state.update { it.copy(progress = f, status = "$msg · ${(f * 100).toInt()}%") }
        }
        return when {
            s.format == OutFormat.GIF -> {
                _state.update { it.copy(progress = null, status = "Making GIF…") }
                compressor.makeGif(input, s.resolution.height, s.compress, out, report)
            }
            s.compress -> {
                _state.update { it.copy(progress = null, status = "Preparing compression…") }
                compressor.compress(input, s.format, s.resolution.height, out, report)
            }
            fromFile -> {
                _state.update { it.copy(progress = null, status = "Converting…") }
                compressor.convert(input, s.format, s.resolution.height, out, report)
            }
            else -> input
        }
    }

    private fun runJob(s: UiState, work: (workDir: File, id: String) -> File) {
        val app = getApplication<Application>()
        val id = UUID.randomUUID().toString()
        processId = id

        job = viewModelScope.launch {
            _state.update {
                it.copy(busy = true, progress = null, status = "Starting…", result = null, error = null)
            }
            val workDir = File(app.cacheDir, "jobs/$id").apply { mkdirs() }
            try {
                val saved = withContext(Dispatchers.IO) {
                    val finalFile = work(workDir, id)
                    _state.update { it.copy(progress = null, status = "Saving to Downloads…") }
                    val cleanName = finalFile.name.removePrefix("tmp_")
                    val named = if (cleanName != finalFile.name) File(finalFile.parentFile, cleanName).also { finalFile.renameTo(it) } else finalFile
                    val uri = Saver.save(app, named, s.format.mime)
                    SavedFile(uri, named.name, named.length(), s.format.mime)
                }
                _state.update { it.copy(busy = false, progress = null, status = "", result = saved) }
            } catch (e: Throwable) {
                val cancelled = e is kotlinx.coroutines.CancellationException ||
                    e is InterruptedException || e.cause is com.yausername.youtubedl_android.YoutubeDL.CanceledException ||
                    e is com.yausername.youtubedl_android.YoutubeDL.CanceledException
                _state.update {
                    it.copy(
                        busy = false, progress = null, status = if (cancelled) "Cancelled." else "",
                        error = if (cancelled) null else (e.message ?: e.javaClass.simpleName),
                    )
                }
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { workDir.deleteRecursively() }
                processId = null
            }
        }
    }

    fun cancel() {
        processId?.let { engine.cancel(it) }
        compressor.cancel()
        job?.cancel()
    }

    override fun onCleared() {
        cancel()
    }
}
