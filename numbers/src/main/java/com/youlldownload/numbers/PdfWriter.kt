package com.youlldownload.numbers

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.youlldownload.numbers.core.Cell
import com.youlldownload.numbers.core.Table
import com.youlldownload.numbers.core.Workbook
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Renders a workbook to PDF (A4): each sheet starts on a new page, tables are drawn as grids with
 * header rows repeated on every page. Wide tables switch the sheet to landscape, then shrink to fit,
 * and if they're still too wide, are split into column groups (header columns repeated in each group).
 */
class PdfWriter(private val title: String) {
    private val pdf = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var pageNumber = 0
    private var landscape = false
    private var y = 0f

    private val pageW get() = if (landscape) A4_H else A4_W
    private val pageH get() = if (landscape) A4_W else A4_H
    private val contentW get() = pageW - 2 * MARGIN
    private val bottom get() = pageH - MARGIN - FOOTER

    private val cellPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f; color = Color.rgb(20, 20, 20) }
    private val headerPaint = TextPaint(cellPaint).apply { typeface = Typeface.DEFAULT_BOLD }
    private val sheetPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 16f; typeface = Typeface.DEFAULT_BOLD; color = Color.rgb(31, 42, 68)
    }
    private val tablePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD; color = Color.rgb(63, 81, 181)
    }
    private val notePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = Color.GRAY }
    private val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 7.5f; color = Color.GRAY }
    private val gridPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.5f; color = Color.rgb(160, 160, 160) }
    private val headerFill = Paint().apply { style = Paint.Style.FILL; color = Color.rgb(232, 234, 240) }

    fun write(book: Workbook, out: OutputStream) {
        try {
            if (book.sheets.isEmpty()) {
                newPage()
                drawText("This spreadsheet has no sheets.", notePaint, contentW)
            }
            for (sheet in book.sheets) {
                // Landscape when any table on the sheet is wider than a portrait page.
                landscape = sheet.tables.any { t -> naturalWidths(t).sum() > A4_W - 2 * MARGIN }
                newPage()
                drawText(sheet.name, sheetPaint, contentW)
                y += 6f
                if (sheet.tables.isEmpty()) drawText("(No tables on this sheet)", notePaint, contentW)
                for (table in sheet.tables) drawTable(table, showName = book.tableCount > 1 || table.showName)
            }
            finishPage()
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    private fun newPage() {
        finishPage()
        pageNumber++
        val info = PdfDocument.PageInfo.Builder(pageW.toInt(), pageH.toInt(), pageNumber).create()
        page = pdf.startPage(info)
        canvas = page!!.canvas
        y = MARGIN
    }

    private fun finishPage() {
        val p = page ?: return
        val footer = "$title · $pageNumber"
        canvas!!.drawText(footer, pageW - MARGIN - footerPaint.measureText(footer), pageH - MARGIN + 4f, footerPaint)
        pdf.finishPage(p)
        page = null
        canvas = null
    }

    private fun drawText(text: String, paint: TextPaint, width: Float) {
        val layout = layout(text, paint, width.toInt(), Layout.Alignment.ALIGN_NORMAL)
        if (y + layout.height > bottom) newPage()
        val c = canvas!!
        c.save()
        c.translate(MARGIN, y)
        layout.draw(c)
        c.restore()
        y += layout.height + 4f
    }

    private fun drawTable(t: Table, showName: Boolean) {
        // Keep the title with at least a few rows.
        if (y + 60f > bottom) newPage()
        if (showName) drawText(t.name, tablePaint, contentW)
        if (t.rows.isEmpty()) {
            drawText("(Empty table)", notePaint, contentW)
            y += 10f
            return
        }

        val natural = naturalWidths(t)
        val fixedCols = (0 until min(t.headerColumns, t.columnCount)).toList()
        for ((gi, group) in columnGroups(t, natural, fixedCols).withIndex()) {
            val cols = fixedCols + group
            val widths = fitWidths(cols.map { natural[it] })
            if (gi > 0) {
                if (y + 60f > bottom) newPage()
                drawText("${t.name} (continued: columns ${columnName(group.first())}–${columnName(group.last())})", notePaint, contentW)
            }
            drawGrid(t, cols, widths)
            y += 14f
        }
    }

    /**
     * Narrows the widest columns first (their text wraps) until the table fits; narrow columns keep their
     * size. Only if that isn't enough is everything scaled down.
     */
    private fun fitWidths(natural: List<Float>): List<Float> {
        if (natural.sum() <= contentW) return natural
        var lo = MIN_SHRUNK
        var hi = natural.max()
        repeat(30) {
            val cap = (lo + hi) / 2
            if (natural.sumOf { min(it, cap).toDouble() } > contentW) hi = cap else lo = cap
        }
        val capped = natural.map { min(it, lo) }
        val total = capped.sum()
        return if (total > contentW) capped.map { it * contentW / total } else capped
    }

    /** Splits non-header columns into groups that fit the page width (after shrinking to a minimum). */
    private fun columnGroups(t: Table, natural: List<Float>, fixedCols: List<Int>): List<List<Int>> {
        val rest = (fixedCols.size until t.columnCount).toList()
        if (rest.isEmpty()) return listOf(emptyList())
        if (natural.sum() <= contentW) return listOf(rest)
        val fixedMin = fixedCols.sumOf { min(natural[it], MIN_SHRUNK).toDouble() }.toFloat()
        val groups = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        var used = fixedMin
        for (c in rest) {
            val w = min(natural[c], MIN_SHRUNK)
            if (current.isNotEmpty() && used + w > contentW) {
                groups += current
                current = ArrayList()
                used = fixedMin
            }
            current += c
            used += w
        }
        if (current.isNotEmpty()) groups += current
        return groups
    }

    private fun drawGrid(t: Table, cols: List<Int>, widths: List<Float>) {
        val headerRows = t.rows.take(t.headerRows)

        fun rowHeight(row: List<Cell>, header: Boolean): Pair<Float, List<StaticLayout>> {
            val layouts = cols.mapIndexed { i, c ->
                val cell = row[c]
                val isHeader = header || c < t.headerColumns
                val align = if (cell.numeric && !isHeader) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
                layout(cell.text, if (isHeader) headerPaint else cellPaint, max(1, (widths[i] - 2 * PAD).toInt()), align)
            }
            val h = (layouts.maxOfOrNull { it.height } ?: 0) + 2 * PAD
            return min(h.toFloat(), bottom - MARGIN - 20f) to layouts
        }

        fun drawRow(row: List<Cell>, header: Boolean, height: Float, layouts: List<StaticLayout>) {
            val c = canvas!!
            var x = MARGIN
            for ((i, col) in cols.withIndex()) {
                val w = widths[i]
                if (header || col < t.headerColumns) c.drawRect(x, y, x + w, y + height, headerFill)
                c.drawRect(x, y, x + w, y + height, gridPaint)
                c.save()
                c.clipRect(x, y, x + w, y + height)
                c.translate(x + PAD, y + PAD)
                layouts[i].draw(c)
                c.restore()
                x += w
            }
            y += height
        }

        val headerLayouts = headerRows.map { rowHeight(it, header = true) }
        fun drawHeaders() = headerRows.forEachIndexed { i, r -> drawRow(r, true, headerLayouts[i].first, headerLayouts[i].second) }

        val headerHeight = headerLayouts.sumOf { it.first.toDouble() }.toFloat()
        if (y + headerHeight + 20f > bottom) newPage()
        drawHeaders()
        for (r in t.headerRows until t.rows.size) {
            val row = t.rows[r]
            val (h, layouts) = rowHeight(row, header = false)
            if (y + h > bottom) {
                newPage()
                drawHeaders()
            }
            drawRow(row, false, h, layouts)
        }
    }

    /** Preferred column widths: longest line in the column, clamped. */
    private fun naturalWidths(t: Table): List<Float> = (0 until t.columnCount).map { c ->
        var w = MIN_COL
        for ((r, row) in t.rows.withIndex()) {
            val paint = if (r < t.headerRows || c < t.headerColumns) headerPaint else cellPaint
            for (line in row[c].text.split('\n')) w = max(w, paint.measureText(line) + 2 * PAD + 1)
            if (w >= MAX_COL) break
        }
        min(ceil(w), MAX_COL)
    }

    private fun layout(text: String, paint: TextPaint, width: Int, align: Layout.Alignment): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(align)
            .setIncludePad(false)
            .setEllipsize(null as TextUtils.TruncateAt?)
            .build()

    private fun columnName(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, ('A' + rem))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private companion object {
        const val A4_W = 595f
        const val A4_H = 842f
        const val MARGIN = 36f
        const val FOOTER = 12f
        const val PAD = 3f
        const val MIN_COL = 24f
        const val MAX_COL = 220f
        const val MIN_SHRUNK = 60f
    }
}
