package com.harrydatahub.groundkit.data.parquet

/**
 * Generic decoder for the Thrift compact protocol, which Parquet uses for its footer and
 * page headers. Structs come back as field id -> value maps, so the reader can pick the
 * fields it needs without generated classes. Lists are [List]s, binaries [ByteArray]s.
 */
internal class ThriftCompact(private val buf: ByteArray, var pos: Int) {

    fun readStruct(): Map<Int, Any?> {
        val fields = HashMap<Int, Any?>()
        var lastId = 0
        while (true) {
            val header = byte()
            val type = header and 0x0f
            if (type == STOP) return fields
            val delta = header ushr 4
            val id = if (delta != 0) lastId + delta else zigzag(varint()).toInt()
            lastId = id
            fields[id] = value(type)
        }
    }

    private fun value(type: Int): Any? = when (type) {
        BOOL_TRUE -> true
        BOOL_FALSE -> false
        BYTE -> buf[pos++]
        I16, I32 -> zigzag(varint()).toInt()
        I64 -> zigzag(varint())
        DOUBLE -> java.lang.Double.longBitsToDouble(le64())
        BINARY -> {
            val n = varint().toInt()
            buf.copyOfRange(pos, pos + n).also { pos += n }
        }
        LIST, SET -> list()
        MAP -> map()
        STRUCT -> readStruct()
        else -> throw ParquetException("Unknown Thrift compact type $type at $pos")
    }

    private fun list(): List<Any?> {
        val header = byte()
        var size = header ushr 4
        val elemType = header and 0x0f
        if (size == 15) size = varint().toInt()
        return List(size) {
            // Booleans inside collections take a whole byte each: 1 is true.
            if (elemType == BOOL_TRUE || elemType == BOOL_FALSE) byte() == 1 else value(elemType)
        }
    }

    private fun map(): Map<Any?, Any?> {
        val size = varint().toInt()
        if (size == 0) return emptyMap()
        val types = byte()
        val keyType = types ushr 4
        val valueType = types and 0x0f
        val out = LinkedHashMap<Any?, Any?>(size)
        repeat(size) { out[value(keyType)] = value(valueType) }
        return out
    }

    private fun byte(): Int = buf[pos++].toInt() and 0xff

    private fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = byte()
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    private fun le64(): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((buf[pos + i].toLong() and 0xff) shl (8 * i))
        pos += 8
        return v
    }

    private fun zigzag(n: Long): Long = (n ushr 1) xor -(n and 1)

    private companion object {
        const val STOP = 0
        const val BOOL_TRUE = 1
        const val BOOL_FALSE = 2
        const val BYTE = 3
        const val I16 = 4
        const val I32 = 5
        const val I64 = 6
        const val DOUBLE = 7
        const val BINARY = 8
        const val LIST = 9
        const val SET = 10
        const val MAP = 11
        const val STRUCT = 12
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Map<Int, Any?>.struct(id: Int): Map<Int, Any?>? = this[id] as Map<Int, Any?>?

@Suppress("UNCHECKED_CAST")
internal fun Map<Int, Any?>.list(id: Int): List<Any?>? = this[id] as List<Any?>?

internal fun Map<Int, Any?>.int(id: Int): Int? = (this[id] as Number?)?.toInt()

internal fun Map<Int, Any?>.long(id: Int): Long? = (this[id] as Number?)?.toLong()

internal fun Map<Int, Any?>.bool(id: Int): Boolean? = this[id] as Boolean?

internal fun Map<Int, Any?>.string(id: Int): String? = (this[id] as ByteArray?)?.toString(Charsets.UTF_8)
