package com.youlldownload.numbers.core

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a Word document (.docx, Office Open XML): one heading per sheet, each table as a real Word table
 * with header rows repeated on every page. Pages turn landscape when a table is wide.
 */
object DocxWriter {
    fun write(book: Workbook, title: String, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", CONTENT_TYPES)
            put("_rels/.rels", RELS)
            put("word/_rels/document.xml.rels", DOC_RELS)
            put("word/styles.xml", STYLES)
            put("docProps/core.xml", core(title))
            put("word/document.xml", document(book))
        }
    }

    private fun document(book: Workbook): String {
        val maxCols = book.sheets.flatMap { it.tables }.maxOfOrNull { it.columnCount } ?: 0
        val landscape = maxCols > 6
        // Letter/A4-agnostic: A4 in twentieths of a point, 0.5" margins.
        val pageW = if (landscape) 16838 else 11906
        val pageH = if (landscape) 11906 else 16838
        val margin = 720
        val usable = pageW - 2 * margin

        val body = StringBuilder()
        for ((si, sheet) in book.sheets.withIndex()) {
            body.append(paragraph(sheet.name, style = "Heading1", pageBreakBefore = si > 0))
            if (sheet.tables.isEmpty()) body.append(paragraph("(No tables on this sheet)"))
            for (table in sheet.tables) {
                if (book.tableCount > 1 || table.showName) body.append(paragraph(table.name, style = "Heading2"))
                if (table.rows.isEmpty()) {
                    body.append(paragraph("(Empty table)"))
                    continue
                }
                body.append(table(table, usable))
                body.append(paragraph(""))
            }
        }
        val orient = if (landscape) " w:orient=\"landscape\"" else ""
        body.append(
            "<w:sectPr><w:pgSz w:w=\"$pageW\" w:h=\"$pageH\"$orient/>" +
                "<w:pgMar w:top=\"$margin\" w:right=\"$margin\" w:bottom=\"$margin\" w:left=\"$margin\" " +
                "w:header=\"360\" w:footer=\"360\" w:gutter=\"0\"/></w:sectPr>"
        )
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<w:document xmlns:w=\"$W\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
            "<w:body>$body</w:body></w:document>"
    }

    private fun table(t: Table, usable: Int): String {
        val cols = t.columnCount
        // Column widths proportional to content length (clamped), filling the usable width.
        val weights = (0 until cols).map { c ->
            val longest = t.rows.maxOf { row -> row[c].text.lines().maxOf { it.length } }
            longest.coerceIn(4, 40).toDouble()
        }
        val total = weights.sum()
        val widths = weights.map { (it / total * usable).toInt() }
        val fontHalfPts = when {
            cols > 12 -> 14
            cols > 8 -> 16
            else -> 19
        }

        val sb = StringBuilder()
        sb.append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"$usable\" w:type=\"dxa\"/>")
        sb.append("<w:tblLayout w:type=\"fixed\"/>")
        sb.append("<w:tblBorders>")
        for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
            sb.append("<w:$side w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"A0A0A0\"/>")
        }
        sb.append("</w:tblBorders><w:tblCellMar><w:left w:w=\"70\" w:type=\"dxa\"/><w:right w:w=\"70\" w:type=\"dxa\"/></w:tblCellMar>")
        sb.append("</w:tblPr><w:tblGrid>")
        widths.forEach { sb.append("<w:gridCol w:w=\"$it\"/>") }
        sb.append("</w:tblGrid>")

        for ((r, row) in t.rows.withIndex()) {
            val headerRow = r < t.headerRows
            sb.append("<w:tr>")
            if (headerRow) sb.append("<w:trPr><w:tblHeader/><w:cantSplit/></w:trPr>") else sb.append("<w:trPr><w:cantSplit/></w:trPr>")
            for ((c, cell) in row.withIndex()) {
                val header = headerRow || c < t.headerColumns
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"${widths[c]}\" w:type=\"dxa\"/>")
                if (header) sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"E8EAF0\"/>")
                sb.append("</w:tcPr>")
                val align = if (cell.numeric && !header) "right" else "left"
                val lines = cell.text.split('\n')
                for (line in lines) {
                    sb.append("<w:p><w:pPr><w:spacing w:before=\"20\" w:after=\"20\"/><w:jc w:val=\"$align\"/></w:pPr>")
                    if (line.isNotEmpty()) {
                        sb.append("<w:r><w:rPr>")
                        if (header) sb.append("<w:b/>")
                        sb.append("<w:sz w:val=\"$fontHalfPts\"/></w:rPr>")
                        sb.append("<w:t xml:space=\"preserve\">${esc(line)}</w:t></w:r>")
                    }
                    sb.append("</w:p>")
                }
                sb.append("</w:tc>")
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        return sb.toString()
    }

    private fun paragraph(text: String, style: String? = null, pageBreakBefore: Boolean = false): String {
        val ppr = buildString {
            if (style != null || pageBreakBefore) {
                append("<w:pPr>")
                if (style != null) append("<w:pStyle w:val=\"$style\"/>")
                if (pageBreakBefore) append("<w:pageBreakBefore/>")
                append("</w:pPr>")
            }
        }
        val run = if (text.isEmpty()) "" else "<w:r><w:t xml:space=\"preserve\">${esc(text)}</w:t></w:r>"
        return "<w:p>$ppr$run</w:p>"
    }

    /** XML-escapes text and drops characters XML 1.0 can't contain. */
    internal fun esc(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch == '\t' -> sb.append(' ')
                ch < ' ' -> {}
                ch == '￾' || ch == '￿' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun core(title: String) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>${esc(title)}</dc:title></cp:coreProperties>"""

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
</Types>"""

    private const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
</Relationships>"""

    private const val DOC_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Calibri" w:cs="Calibri"/><w:sz w:val="20"/><w:lang w:val="en-US"/></w:rPr></w:rPrDefault>
<w:pPrDefault><w:pPr><w:spacing w:after="80"/></w:pPr></w:pPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
<w:pPr><w:keepNext/><w:spacing w:before="120" w:after="160"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:color w:val="1F2A44"/><w:sz w:val="32"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
<w:pPr><w:keepNext/><w:spacing w:before="160" w:after="80"/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/><w:color w:val="3F51B5"/><w:sz w:val="24"/></w:rPr></w:style>
<w:style w:type="table" w:default="1" w:styleId="TableNormal"><w:name w:val="Normal Table"/><w:tblPr><w:tblInd w:w="0" w:type="dxa"/><w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="108" w:type="dxa"/><w:bottom w:w="0" w:type="dxa"/><w:right w:w="108" w:type="dxa"/></w:tblCellMar></w:tblPr></w:style>
<w:style w:type="table" w:styleId="TableGrid"><w:name w:val="Table Grid"/><w:basedOn w:val="TableNormal"/><w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="auto"/></w:pPr></w:style>
</w:styles>"""
}
