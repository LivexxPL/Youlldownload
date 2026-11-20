package com.youlldownload.numbers.core

/**
 * Minimal schema-less protobuf reader. Fields are kept by number: varints / fixed values as Long,
 * length-delimited values as byte slices (sub-messages, strings, bytes).
 */
internal class Proto private constructor(private val fields: Map<Int, List<Any>>) {

    fun long(field: Int): Long? = fields[field]?.firstOrNull() as? Long
    fun int(field: Int, default: Int = 0): Int = long(field)?.toInt() ?: default
    fun bool(field: Int, default: Boolean = false): Boolean = long(field)?.let { it != 0L } ?: default
    fun bytes(field: Int): ByteArray? = fields[field]?.firstOrNull() as? ByteArray
    fun string(field: Int): String? = bytes(field)?.toString(Charsets.UTF_8)
    fun strings(field: Int): List<String> = all(field).filterIsInstance<ByteArray>().map { it.toString(Charsets.UTF_8) }
    fun msg(field: Int): Proto? = bytes(field)?.let { parse(it) }
    fun msgs(field: Int): List<Proto> = all(field).filterIsInstance<ByteArray>().map { parse(it) }
    fun has(field: Int): Boolean = fields.containsKey(field)

    /** TSP.Reference { uint64 identifier = 1 } */
    fun ref(field: Int): Long? = msg(field)?.long(1)
    fun refs(field: Int): List<Long> = msgs(field).mapNotNull { it.long(1) }

    private fun all(field: Int): List<Any> = fields[field] ?: emptyList()

    companion object {
        fun parse(data: ByteArray, start: Int = 0, end: Int = data.size): Proto {
            val r = Reader(data, start, end)
            val map = HashMap<Int, MutableList<Any>>()
            while (r.pos < end) {
                val key = r.varint()
                val field = (key ushr 3).toInt()
                val value: Any = when ((key and 7).toInt()) {
                    0 -> r.varint()
                    1 -> r.fixed(8)
                    2 -> {
                        val len = r.varint().toInt()
                        if (len < 0 || r.pos + len > end) throw NumbersFormatException("Bad protobuf length")
                        data.copyOfRange(r.pos, r.pos + len).also { r.pos += len }
                    }
                    5 -> r.fixed(4)
                    else -> throw NumbersFormatException("Unsupported protobuf wire type")
                }
                map.getOrPut(field) { ArrayList(1) }.add(value)
            }
            return Proto(map)
        }
    }

    class Reader(private val data: ByteArray, var pos: Int, private val end: Int) {
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= end) throw NumbersFormatException("Truncated varint")
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw NumbersFormatException("Varint too long")
            }
        }

        fun fixed(n: Int): Long {
            if (pos + n > end) throw NumbersFormatException("Truncated fixed value")
            var v = 0L
            for (i in 0 until n) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
            pos += n
            return v
        }
    }
}

/** An archived object: its type id (from the iWork registry) and its main message. */
internal class IwaObject(val type: Int, val data: ByteArray) {
    val proto: Proto by lazy { Proto.parse(data) }
}

/**
 * All objects from every .iwa file of a document, by identifier.
 *
 * Decoded stream layout: repeated { varint len, TSP.ArchiveInfo, message payloads... } where
 * ArchiveInfo = { uint64 identifier = 1; repeated MessageInfo message_infos = 2 } and
 * MessageInfo = { uint32 type = 1; uint32 length = 3 }.
 */
internal class ObjectStore {
    private val objects = HashMap<Long, IwaObject>()

    fun addIwa(raw: ByteArray) {
        val data = Snappy.decodeIwa(raw)
        val r = Proto.Reader(data, 0, data.size)
        while (r.pos < data.size) {
            val infoLen = r.varint().toInt()
            val infoStart = r.pos
            if (infoLen < 0 || infoStart + infoLen > data.size) throw NumbersFormatException("Bad archive header")
            val info = Proto.parse(data, infoStart, infoStart + infoLen)
            var p = infoStart + infoLen
            val id = info.long(1)
            var first = true
            for (mi in info.msgs(2)) {
                val len = mi.int(3)
                if (len < 0 || p + len > data.size) throw NumbersFormatException("Bad archive message")
                if (first && id != null) objects[id] = IwaObject(mi.int(1), data.copyOfRange(p, p + len))
                first = false
                p += len
            }
            r.pos = p
        }
    }

    operator fun get(id: Long?): IwaObject? = id?.let { objects[it] }

    fun ofType(type: Int): List<Pair<Long, IwaObject>> = objects.filter { it.value.type == type }.map { it.key to it.value }

    val isEmpty: Boolean get() = objects.isEmpty()
}
