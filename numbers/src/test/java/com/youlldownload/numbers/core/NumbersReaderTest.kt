package com.youlldownload.numbers.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream

class NumbersReaderTest {
    private fun sample(): File {
        val url = javaClass.classLoader!!.getResource("sample.numbers")
        return File(url.toURI())
    }

    @Test
    fun readsSheetsTablesAndValues() {
        val book = NumbersReader.read(sample(), Locale.US)
        assertEquals(listOf("Budżet", "Second"), book.sheets.map { it.name })

        val t = book.sheets[0].tables.single()
        assertEquals("Wydatki", t.name)
        assertEquals(1, t.headerRows)
        // 320 rows spans two 256-row tiles.
        assertEquals(320, t.rows.size)
        assertEquals(4, t.columnCount)
        assertEquals(listOf("Pozycja", "Kwota", "Data", "Zapłacone"), t.rows[0].map { it.text })
        assertEquals(listOf("Żółć łąka 10", "12.5", "2024-01-11", "TRUE"), t.rows[10].map { it.text })
        assertEquals(listOf("Żółć łąka 319", "398.75", "2024-01-12", "FALSE"), t.rows[319].map { it.text })
        assertTrue(t.rows[1][1].numeric)

        val second = book.sheets[1].tables.single()
        assertEquals("hello", second.rows[0][0].text)
        assertEquals("42", second.rows[2][3].text)
    }

    @Test
    fun writesWellFormedDocx() {
        val book = NumbersReader.read(sample(), Locale.US)
        val bytes = ByteArrayOutputStream().also { DocxWriter.write(book, "sample", it) }.toByteArray()
        val entries = HashMap<String, String>()
        ZipInputStream(bytes.inputStream()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                entries[e.name] = zis.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertTrue(entries.keys.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml")))
        val doc = entries.getValue("word/document.xml")
        // Parses as XML.
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(doc.byteInputStream())
        assertTrue(doc.contains("Żółć łąka 319"))
    }

    @Test
    fun formatsNumbers() {
        val f = CellFormatter(Locale.US)
        assertEquals("1234.5", f.number(java.math.BigDecimal("1234.5"), null))
        assertEquals("0.1", f.number(java.math.BigDecimal(0.1), null))
        assertEquals("2h 24m", f.duration(8640.0, null))
    }

    @Test(expected = NumbersFormatException::class)
    fun rejectsNonNumbersFiles() {
        val f = File.createTempFile("not", ".numbers")
        try {
            f.writeText("hello")
            NumbersReader.read(f)
        } finally {
            f.delete()
        }
    }
}
