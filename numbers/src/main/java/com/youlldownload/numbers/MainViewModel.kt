package com.youlldownload.numbers

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.youlldownload.numbers.core.DocxWriter
import com.youlldownload.numbers.core.NumbersFormatException
import com.youlldownload.numbers.core.NumbersReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class SavedFile(val uri: Uri, val name: String, val sizeBytes: Long, val mime: String, val label: String)

data class UiState(
    val fileUri: Uri? = null,
    val fileName: String? = null,
    val fileSize: Long = 0,
    val pdf: Boolean = true,
    val word: Boolean = true,
    val busy: Boolean = false,
    val status: String = "",
    val results: List<SavedFile> = emptyList(),
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        UiState(pdf = prefs.getBoolean("pdf", true), word = prefs.getBoolean("word", true))
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun setFile(uri: Uri) {
        val (name, size) = queryNameAndSize(uri)
        _state.update { it.copy(fileUri = uri, fileName = name, fileSize = size, results = emptyList(), error = null, status = "") }
    }

    fun setPdf(v: Boolean) {
        prefs.edit().putBoolean("pdf", v).apply()
        _state.update { it.copy(pdf = v) }
    }

    fun setWord(v: Boolean) {
        prefs.edit().putBoolean("word", v).apply()
        _state.update { it.copy(word = v) }
    }

    fun convert() {
        val s = _state.value
        val uri = s.fileUri ?: return
        if (s.busy || (!s.pdf && !s.word)) return
        _state.update { it.copy(busy = true, error = null, results = emptyList(), status = "Reading spreadsheet…") }

        viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) { run(uri, s) }
                _state.update { it.copy(busy = false, status = "", results = results) }
            } catch (e: NumbersFormatException) {
                _state.update { it.copy(busy = false, status = "", error = e.message) }
            } catch (e: OutOfMemoryError) {
                _state.update { it.copy(busy = false, status = "", error = "This spreadsheet is too big to convert on this phone.") }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, status = "", error = "Conversion failed: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    private fun run(uri: Uri, s: UiState): List<SavedFile> {
        val ctx = getApplication<Application>()
        val work = File(ctx.cacheDir, "convert").apply { deleteRecursively(); mkdirs() }
        try {
            val input = File(work, "input.numbers")
            ctx.contentResolver.openInputStream(uri)?.use { inp -> input.outputStream().use { inp.copyTo(it) } }
                ?: throw RuntimeException("Couldn't open the file.")

            val book = NumbersReader.read(input)
            if (book.tableCount == 0) throw NumbersFormatException("This spreadsheet has no tables to convert.")
            val base = baseName(s.fileName)
            val out = ArrayList<SavedFile>()

            if (s.pdf) {
                _state.update { it.copy(status = "Creating PDF…") }
                val f = File(work, "$base.pdf")
                f.outputStream().use { PdfWriter(base).write(book, it) }
                out += save(ctx, f, "application/pdf", "PDF")
            }
            if (s.word) {
                _state.update { it.copy(status = "Creating Word document…") }
                val f = File(work, "$base.docx")
                f.outputStream().use { DocxWriter.write(book, base, it) }
                out += save(ctx, f, DOCX_MIME, "Word")
            }
            return out
        } finally {
            work.deleteRecursively()
        }
    }

    private fun save(ctx: Context, f: File, mime: String, label: String): SavedFile {
        val size = f.length()
        val uri = Saver.save(ctx, f, mime)
        return SavedFile(uri, f.name, size, mime, label)
    }

    private fun baseName(name: String?): String {
        val n = (name ?: "Spreadsheet").substringBeforeLast('.').ifBlank { "Spreadsheet" }
        // Keep it a safe file name.
        return n.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(100)
    }

    private fun queryNameAndSize(uri: Uri): Pair<String?, Long> {
        val cr = getApplication<Application>().contentResolver
        return runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }
                    val size = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) } ?: 0L
                    name to size
                } else null
            }
        }.getOrNull() ?: (uri.lastPathSegment?.substringAfterLast('/') to 0L)
    }

    companion object {
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
