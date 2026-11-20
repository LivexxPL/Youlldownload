package com.youlldownload.numbers.core

import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** A whole spreadsheet: sheets in document order, each with its tables. */
data class Workbook(val sheets: List<Sheet>) {
    val tableCount: Int get() = sheets.sumOf { it.tables.size }
}

data class Sheet(val name: String, val tables: List<Table>)

data class Cell(val text: String, val numeric: Boolean = false) {
    companion object {
        val EMPTY = Cell("")
    }
}

/** Cells as displayed text. Rows are trimmed of trailing empty rows/columns, so all rows have [columnCount] cells. */
data class Table(
    val name: String,
    val showName: Boolean,
    val rows: List<List<Cell>>,
    val headerRows: Int,
    val headerColumns: Int,
) {
    val columnCount: Int get() = rows.firstOrNull()?.size ?: 0
}

/**
 * Reads Apple Numbers documents (Numbers 3.0 / 2013 and later: the "IWA" format).
 *
 * A .numbers file is either a zip archive or (on a Mac) a folder; both contain Index/ *.iwa files, which hold
 * Snappy-compressed protobuf objects. Older bundles have an Index.zip inside instead.
 */
object NumbersReader {
    // Object type ids from the iWork registry.
    private const val DOCUMENT = 1
    private const val SHEET = 2
    private const val TABLE_INFO = 6000
    private const val TABLE_MODEL = 6001
    private const val TILE = 6002

    fun read(file: File, locale: Locale = Locale.getDefault()): Workbook {
        val store = ObjectStore()
        if (file.isDirectory) {
            loadDirectory(file, store)
        } else {
            try {
                ZipFile(file).use { loadZip(it, store) }
            } catch (e: java.util.zip.ZipException) {
                throw NumbersFormatException("This isn't a Numbers file (not a zip archive).")
            }
        }
        if (store.isEmpty) {
            throw NumbersFormatException(
                "No spreadsheet data found. Files from Numbers '09 or older aren't supported — " +
                    "open and re-save them in a newer Numbers first."
            )
        }
        return build(store, locale)
    }

    private fun loadDirectory(dir: File, store: ObjectStore) {
        dir.walkTopDown().filter { it.isFile }.forEach { f ->
            when {
                f.name.endsWith(".iwa") -> store.addIwa(f.readBytes())
                f.name == "Index.zip" -> ZipFile(f).use { loadZip(it, store) }
            }
        }
    }

    private fun loadZip(zip: ZipFile, store: ObjectStore) {
        for (entry in zip.entries()) {
            if (entry.isDirectory) continue
            val name = entry.name
            // Skip macOS resource forks that some zip tools add.
            if (name.startsWith("__MACOSX/") || name.substringAfterLast('/').startsWith("._")) continue
            when {
                name.endsWith(".iwa") -> store.addIwa(zip.getInputStream(entry).use { it.readBytes() })
                name.endsWith("Index.zip") -> ZipInputStream(zip.getInputStream(entry)).use { loadNestedZip(it, store) }
            }
        }
    }

    private fun loadNestedZip(zis: ZipInputStream, store: ObjectStore) {
        while (true) {
            val entry = zis.nextEntry ?: break
            if (!entry.isDirectory && entry.name.endsWith(".iwa")) store.addIwa(zis.readBytes())
        }
    }

    private fun build(store: ObjectStore, locale: Locale): Workbook {
        val formatter = CellFormatter(locale)
        val doc = store.ofType(DOCUMENT).firstOrNull()?.second
            ?: throw NumbersFormatException("This file has no Numbers document in it (is it a Pages or Keynote file?).")
        val sheets = doc.proto.refs(1).mapNotNull { sheetId ->
            val sheet = store[sheetId]?.takeIf { it.type == SHEET } ?: return@mapNotNull null
            val tables = sheet.proto.refs(2).mapNotNull { drawableId ->
                val info = store[drawableId]?.takeIf { it.type == TABLE_INFO } ?: return@mapNotNull null
                val model = store[info.proto.ref(2)]?.takeIf { it.type == TABLE_MODEL } ?: return@mapNotNull null
                try {
                    readTable(store, model.proto, formatter)
                } catch (e: NumbersFormatException) {
                    throw e
                } catch (e: RuntimeException) {
                    throw NumbersFormatException("Couldn't read a table: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            Sheet(sheet.proto.string(1) ?: "Sheet", tables)
        }
        return Workbook(sheets)
    }

    /** TST.TableModelArchive */
    private fun readTable(store: ObjectStore, model: Proto, formatter: CellFormatter): Table {
        val name = model.string(8) ?: "Table"
        val showName = model.bool(22, default = true)
        val numRows = model.int(6)
        val numCols = model.int(7)
        val headerRows = model.int(9)
        val headerCols = model.int(10)
        val ds = model.msg(4) ?: throw NumbersFormatException("Table \"$name\" has no data")

        val ctx = TableContext(
            strings = dataList(store, ds.ref(4)) { it.string(3) ?: "" },
            richText = dataList(store, ds.ref(17)) { richText(store, it) },
            formats = dataList(store, ds.ref(22)) { it.msg(6) },
        )

        val grid = Array(numRows) { arrayOfNulls<Cell>(numCols) }
        val tileStorage = ds.msg(3)
        val tileSize = tileStorage?.int(2)?.takeIf { it > 0 } ?: 256
        for (tileRef in tileStorage?.msgs(1).orEmpty()) {
            val tileId = tileRef.int(1)
            val tile = store[tileRef.ref(2)]?.takeIf { it.type == TILE }?.proto ?: continue
            for (rowInfo in tile.msgs(5)) {
                val row = tileId * tileSize + rowInfo.int(1)
                if (row !in 0 until numRows) continue
                val buffer = rowInfo.bytes(6)
                val offsets = rowInfo.bytes(7)
                if (buffer == null || offsets == null) {
                    if (rowInfo.int(2) > 0) {
                        throw NumbersFormatException("This file was saved by a very old Numbers version; re-save it in a newer Numbers.")
                    }
                    continue
                }
                readRow(buffer, offsets, rowInfo.bool(8), numCols) { col, cellBuf ->
                    grid[row][col] = parseCell(cellBuf, ctx, formatter)
                }
            }
        }

        // Trim trailing empty rows/columns (Numbers tables come with lots of blank cells by default),
        // but always keep the header rows/columns.
        fun Cell?.blank() = this == null || text.isBlank()
        var lastRow = numRows - 1
        while (lastRow >= headerRows && grid[lastRow].all { it.blank() }) lastRow--
        var lastCol = numCols - 1
        while (lastCol >= headerCols && (0..lastRow).all { grid[it][lastCol].blank() }) lastCol--
        val rows = (0..lastRow).map { r -> (0..lastCol).map { c -> grid[r][c] ?: Cell.EMPTY } }
        return Table(
            name = name,
            showName = showName,
            rows = if (lastCol < 0) emptyList() else rows,
            headerRows = headerRows.coerceAtMost(rows.size),
            headerColumns = headerCols.coerceAtMost(lastCol + 1),
        )
    }

    /** Splits a row's storage buffer into per-cell buffers using the 16-bit offset table (-1 = empty). */
    private inline fun readRow(buffer: ByteArray, offsets: ByteArray, wide: Boolean, numCols: Int, onCell: (Int, ByteArray) -> Unit) {
        val bb = ByteBuffer.wrap(offsets).order(ByteOrder.LITTLE_ENDIAN)
        val count = minOf(offsets.size / 2, numCols)
        val starts = IntArray(count) { i ->
            val o = bb.getShort(i * 2).toInt()
            if (o < 0) -1 else if (wide) o * 4 else o
        }
        for (col in 0 until count) {
            val start = starts[col]
            if (start < 0 || start >= buffer.size) continue
            var end = buffer.size
            for (j in col + 1 until count) if (starts[j] >= 0) { end = starts[j]; break }
            if (end <= start) continue
            onCell(col, buffer.copyOfRange(start, end))
        }
    }

    private class TableContext(
        val strings: Map<Int, String>,
        val richText: Map<Int, String>,
        val formats: Map<Int, Proto?>,
    )

    /** TST.TableDataList { repeated ListEntry entries = 3 } with ListEntry { uint32 key = 1; ... } */
    private fun <T> dataList(store: ObjectStore, id: Long?, value: (Proto) -> T): Map<Int, T> {
        val list = store[id]?.proto ?: return emptyMap()
        return list.msgs(3).associate { it.int(1) to value(it) }
    }

    /** ListEntry.rich_text_payload -> TST.RichTextPayloadArchive.storage -> TSWP.StorageArchive.text */
    private fun richText(store: ObjectStore, entry: Proto): String {
        val payload = store[entry.ref(9)]?.proto ?: return ""
        val storage = store[payload.ref(1)]?.proto ?: return ""
        return cleanText(storage.strings(3).firstOrNull() ?: "")
    }

    private fun cleanText(s: String): String = s
        .replace(' ', '\n') // paragraph separator
        .replace(' ', '\n') // line separator
        .replace("￼", "") // object replacement (attachments)
        .replace('\u000B', '\n')
        .trimEnd('\n')

    private const val EPOCH_2001 = 978307200L // 2001-01-01T00:00:00Z in Unix seconds

    /** Cell storage version 5 (Numbers 10+, and every file saved since). */
    private fun parseCell(buf: ByteArray, ctx: TableContext, fmt: CellFormatter): Cell? {
        if (buf.size < 12) return null
        if (buf[0].toInt() != 5) throw NumbersFormatException("Unsupported cell format (v${buf[0]}). Re-save the file in a newer Numbers.")
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val type = buf[1].toInt() and 0xFF
        val flags = bb.getInt(8)
        var p = 12
        var d128: BigDecimal? = null
        var double: Double? = null
        var seconds: Double? = null
        val ids = IntArray(21) { -1 }
        if (flags and 0x1 != 0) { d128 = decimal128(buf, p); p += 16 }
        if (flags and 0x2 != 0) { double = bb.getDouble(p); p += 8 }
        if (flags and 0x4 != 0) { seconds = bb.getDouble(p); p += 8 }
        for (bit in 3..20) {
            if (flags and (1 shl bit) != 0) {
                if (p + 4 > buf.size) break
                ids[bit] = bb.getInt(p)
                p += 4
            }
        }
        fun format(bit: Int): Proto? = ids[bit].takeIf { it >= 0 }?.let { ctx.formats[it] }
        val stringId = ids[3]
        val richId = ids[4]
        val numFormat = format(13)
        val currencyFormat = format(14)
        val dateFormat = format(15)
        val durationFormat = format(16)
        val boolFormat = format(18)

        return when (type) {
            0 -> null
            3 -> Cell(cleanText(ctx.strings[stringId] ?: ""))
            9 -> Cell(ctx.richText[richId] ?: "")
            2, 10 -> {
                val value = d128 ?: double?.let { BigDecimal(it) } ?: BigDecimal.ZERO
                Cell(fmt.number(value, currencyFormat ?: numFormat), numeric = true)
            }
            5 -> {
                val unix = EPOCH_2001 * 1000 + Math.round((seconds ?: 0.0) * 1000)
                Cell(fmt.date(unix, dateFormat), numeric = true)
            }
            6 -> Cell(fmt.bool((double ?: d128?.toDouble() ?: 0.0) > 0.0, boolFormat ?: numFormat), numeric = false)
            7 -> Cell(fmt.duration(double ?: d128?.toDouble() ?: 0.0, durationFormat), numeric = true)
            8 -> Cell("#ERROR")
            else -> null
        }
    }

    /** IEEE 754-2008 decimal128, binary integer decimal encoding. */
    private fun decimal128(b: ByteArray, off: Int): BigDecimal {
        val hi15 = b[off + 15].toInt() and 0xFF
        val hi14 = b[off + 14].toInt() and 0xFF
        val exp = (((hi15 and 0x7F) shl 7) or (hi14 ushr 1)) - 0x1820
        val mag = ByteArray(15)
        mag[0] = (hi14 and 1).toByte()
        for (i in 0..13) mag[14 - i] = b[off + i]
        var mantissa = BigInteger(1, mag)
        if (hi15 and 0x80 != 0) mantissa = mantissa.negate()
        return BigDecimal(mantissa, -exp)
    }
}
