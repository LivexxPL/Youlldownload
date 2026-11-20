package com.youlldownload.numbers.core

import java.io.ByteArrayOutputStream

/**
 * iWork ".iwa" files are a series of chunks: a 0x00 byte, a 3-byte little-endian length, then a raw
 * (unframed, no CRC) Snappy block. This decodes the whole file into one byte stream.
 */
internal object Snappy {
    fun decodeIwa(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size * 4)
        var pos = 0
        while (pos + 4 <= data.size) {
            val type = data[pos].toInt() and 0xFF
            val len = (data[pos + 1].toInt() and 0xFF) or
                ((data[pos + 2].toInt() and 0xFF) shl 8) or
                ((data[pos + 3].toInt() and 0xFF) shl 16)
            pos += 4
            if (pos + len > data.size) throw NumbersFormatException("Truncated .iwa chunk")
            if (type != 0) throw NumbersFormatException("Unknown .iwa chunk type $type")
            out.write(decompress(data, pos, len))
            pos += len
        }
        return out.toByteArray()
    }

    fun decompress(src: ByteArray, start: Int, length: Int): ByteArray {
        val end = start + length
        var pos = start
        // Uncompressed length (varint).
        var outLen = 0
        var shift = 0
        while (true) {
            if (pos >= end) throw NumbersFormatException("Bad Snappy header")
            val b = src[pos++].toInt() and 0xFF
            outLen = outLen or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) throw NumbersFormatException("Bad Snappy header")
        }
        val out = ByteArray(outLen)
        var op = 0
        while (pos < end) {
            val tag = src[pos++].toInt() and 0xFF
            when (tag and 3) {
                0 -> { // literal
                    var litLen = tag ushr 2
                    if (litLen >= 60) {
                        val extra = litLen - 59
                        litLen = 0
                        for (i in 0 until extra) litLen = litLen or ((src[pos++].toInt() and 0xFF) shl (8 * i))
                    }
                    litLen += 1
                    if (pos + litLen > end || op + litLen > outLen) throw NumbersFormatException("Bad Snappy literal")
                    System.arraycopy(src, pos, out, op, litLen)
                    pos += litLen
                    op += litLen
                }
                else -> {
                    val len: Int
                    val offset: Int
                    when (tag and 3) {
                        1 -> {
                            len = ((tag ushr 2) and 7) + 4
                            offset = ((tag ushr 5) shl 8) or (src[pos++].toInt() and 0xFF)
                        }
                        2 -> {
                            len = (tag ushr 2) + 1
                            offset = (src[pos].toInt() and 0xFF) or ((src[pos + 1].toInt() and 0xFF) shl 8)
                            pos += 2
                        }
                        else -> {
                            len = (tag ushr 2) + 1
                            offset = (src[pos].toInt() and 0xFF) or
                                ((src[pos + 1].toInt() and 0xFF) shl 8) or
                                ((src[pos + 2].toInt() and 0xFF) shl 16) or
                                ((src[pos + 3].toInt() and 0xFF) shl 24)
                            pos += 4
                        }
                    }
                    if (offset <= 0 || offset > op || op + len > outLen) throw NumbersFormatException("Bad Snappy copy")
                    // Byte-by-byte: copies may overlap their own output.
                    for (i in 0 until len) out[op + i] = out[op - offset + i]
                    op += len
                }
            }
        }
        if (op != outLen) throw NumbersFormatException("Snappy length mismatch")
        return out
    }
}

class NumbersFormatException(message: String) : Exception(message)
