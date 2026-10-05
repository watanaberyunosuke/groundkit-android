package com.harrydatabub.motherduck_aviation_data_android.data.parquet

import java.math.BigInteger

class ParquetException(message: String) : Exception(message)

enum class PhysicalType { BOOLEAN, INT32, INT64, INT96, FLOAT, DOUBLE, BYTE_ARRAY, FIXED_LEN_BYTE_ARRAY }

/** How a column's stored values are meant to be read. */
enum class ValueKind { BOOLEAN, INTEGER, DECIMAL, FLOAT, STRING, BINARY, DATE, TIMESTAMP_MILLIS, TIMESTAMP_MICROS, TIMESTAMP_NANOS }

/**
 * A minimal Parquet reader for the flat tables the aviation API exports (DuckDB
 * `COPY ... (FORMAT parquet, COMPRESSION zstd)`): one leaf column per field, any number of
 * row groups, data pages v1 and v2, PLAIN / dictionary / RLE / DELTA / BYTE_STREAM_SPLIT
 * encodings and UNCOMPRESSED / SNAPPY / GZIP / ZSTD pages. Nested (repeated) columns are
 * skipped rather than misread.
 *
 * The whole file is decoded into memory column by column; the API's files are at most a
 * few MB.
 */
object Parquet {
    private val MAGIC = "PAR1".toByteArray()

    fun read(file: ByteArray): ParquetTable {
        if (file.size < 12 || !file.copyOfRange(0, 4).contentEquals(MAGIC) ||
            !file.copyOfRange(file.size - 4, file.size).contentEquals(MAGIC)
        ) throw ParquetException("Not a Parquet file (${file.size} bytes)")
        val footerLen = le(file, file.size - 8, 4)
        val footerStart = file.size - 8 - footerLen
        if (footerStart < 4) throw ParquetException("Corrupt footer length $footerLen")
        val meta = ThriftCompact(file, footerStart).readStruct()

        val leaves = leafColumns(meta.list(2).orEmpty().map { it.asStruct() })
        val rowGroups = meta.list(4).orEmpty().map { it.asStruct() }
        val numRows = rowGroups.sumOf { it.long(3) ?: 0L }.toInt()
        val columns = leaves.map { ParquetColumn(it.name, it.type, it.kind, it.scale, numRows) }

        var firstRow = 0
        for (rg in rowGroups) {
            val chunks = rg.list(1).orEmpty().map { it.asStruct() }
            val rows = (rg.long(3) ?: 0L).toInt()
            for ((i, leaf) in leaves.withIndex()) {
                if (leaf.maxRep > 0) continue
                val chunk = chunks.getOrNull(leaf.index)?.struct(3)
                    ?: throw ParquetException("Row group has no chunk for ${leaf.name}")
                ChunkDecoder(file, chunk, leaf, columns[i], firstRow).decode()
            }
            firstRow += rows
        }
        return ParquetTable(numRows, columns.filterIndexed { i, _ -> leaves[i].maxRep == 0 })
    }

    private class Leaf(
        val index: Int, val name: String, val type: PhysicalType, val kind: ValueKind,
        val typeLength: Int, val scale: Int, val maxDef: Int, val maxRep: Int,
    )

    /** Leaf columns in file order with their definition / repetition level maxima. */
    private fun leafColumns(schema: List<Map<Int, Any?>>): List<Leaf> {
        val leaves = mutableListOf<Leaf>()
        var i = 1 // element 0 is the root
        fun walk(path: List<String>, def: Int, rep: Int) {
            val el = schema[i++]
            val repetition = el.int(3) ?: 0
            val d = def + if (repetition != REQUIRED) 1 else 0
            val r = rep + if (repetition == REPEATED) 1 else 0
            val name = el.string(4) ?: "col${leaves.size}"
            val children = el.int(5) ?: 0
            if (children > 0) {
                repeat(children) { walk(path + name, d, r) }
            } else {
                val type = PhysicalType.entries[el.int(1) ?: 0]
                leaves += Leaf(
                    index = leaves.size,
                    name = (path + name).joinToString("."),
                    type = type,
                    kind = kindOf(type, el),
                    typeLength = el.int(2) ?: 0,
                    scale = el.int(7) ?: el.struct(10)?.struct(5)?.int(1) ?: 0,
                    maxDef = d,
                    maxRep = r,
                )
            }
        }
        val rootChildren = schema.firstOrNull()?.int(5) ?: 0
        repeat(rootChildren) { walk(emptyList(), 0, 0) }
        return leaves
    }

    private fun kindOf(type: PhysicalType, el: Map<Int, Any?>): ValueKind {
        val logical = el.struct(10)
        val converted = el.int(6)
        logical?.struct(8)?.struct(2)?.let { unit ->
            return when {
                unit.containsKey(1) -> ValueKind.TIMESTAMP_MILLIS
                unit.containsKey(3) -> ValueKind.TIMESTAMP_NANOS
                else -> ValueKind.TIMESTAMP_MICROS
            }
        }
        return when {
            converted == CT_TIMESTAMP_MILLIS -> ValueKind.TIMESTAMP_MILLIS
            converted == CT_TIMESTAMP_MICROS -> ValueKind.TIMESTAMP_MICROS
            converted == CT_DATE || logical?.containsKey(6) == true -> ValueKind.DATE
            converted == CT_DECIMAL || logical?.containsKey(5) == true -> ValueKind.DECIMAL
            type == PhysicalType.INT96 -> ValueKind.TIMESTAMP_NANOS
            type == PhysicalType.BOOLEAN -> ValueKind.BOOLEAN
            type == PhysicalType.INT32 || type == PhysicalType.INT64 -> ValueKind.INTEGER
            type == PhysicalType.FLOAT || type == PhysicalType.DOUBLE -> ValueKind.FLOAT
            converted == CT_UTF8 || converted == CT_ENUM || converted == CT_JSON ||
                logical?.containsKey(1) == true -> ValueKind.STRING
            type == PhysicalType.BYTE_ARRAY -> ValueKind.STRING
            else -> ValueKind.BINARY
        }
    }

    /** Decodes one column chunk's pages into the column, starting at `firstRow`. */
    private class ChunkDecoder(
        private val file: ByteArray,
        private val meta: Map<Int, Any?>,
        private val leaf: Leaf,
        private val column: ParquetColumn,
        firstRow: Int,
    ) {
        private val codec = meta.int(4) ?: 0
        private var row = firstRow
        private var dictionary: Values? = null

        fun decode() {
            val dataOffset = meta.long(9) ?: 0L
            val dictOffset = meta.long(11)
            val start = if (dictOffset != null && dictOffset > 0 && dictOffset < dataOffset) dictOffset else dataOffset
            val end = start + (meta.long(7) ?: 0L)
            val total = meta.long(5) ?: 0L
            var pos = start.toInt()
            var seen = 0L
            while (pos < end && seen < total) {
                val reader = ThriftCompact(file, pos)
                val header = reader.readStruct()
                pos = reader.pos
                val compressed = header.int(3) ?: 0
                val uncompressed = header.int(2) ?: 0
                when (header.int(1)) {
                    DICTIONARY_PAGE -> {
                        val h = header.struct(7) ?: throw ParquetException("Dictionary page without header")
                        val data = Codecs.decompress(codec, file, pos, compressed, uncompressed)
                        dictionary = plain(data, 0, h.int(1) ?: 0)
                    }
                    DATA_PAGE -> {
                        val h = header.struct(5) ?: throw ParquetException("Data page without header")
                        val data = Codecs.decompress(codec, file, pos, compressed, uncompressed)
                        val n = h.int(1) ?: 0
                        var p = 0
                        val defs = if (leaf.maxDef > 0) {
                            val len = le(data, 0, 4)
                            IntArray(n).also { decodeHybrid(data, 4, 4 + len, bitWidth(leaf.maxDef), n, it) }
                                .also { p = 4 + len }
                        } else null
                        page(n, defs, h.int(2) ?: 0, data, p)
                        seen += n
                    }
                    DATA_PAGE_V2 -> {
                        val h = header.struct(8) ?: throw ParquetException("Data page v2 without header")
                        val n = h.int(1) ?: 0
                        val repLen = h.int(6) ?: 0
                        val defLen = h.int(5) ?: 0
                        val defs = if (leaf.maxDef > 0) {
                            IntArray(n).also {
                                decodeHybrid(file, pos + repLen, pos + repLen + defLen, bitWidth(leaf.maxDef), n, it)
                            }
                        } else null
                        val valuesAt = pos + repLen + defLen
                        val valuesLen = compressed - repLen - defLen
                        val data = if (h.bool(7) != false) {
                            Codecs.decompress(codec, file, valuesAt, valuesLen, uncompressed - repLen - defLen)
                        } else file.copyOfRange(valuesAt, valuesAt + valuesLen)
                        page(n, defs, h.int(4) ?: 0, data, 0)
                        seen += n
                    }
                    else -> Unit // index pages carry nothing we need
                }
                pos += compressed
            }
        }

        private fun page(n: Int, defs: IntArray?, encoding: Int, data: ByteArray, at: Int) {
            val present = defs?.count { it == leaf.maxDef } ?: n
            val values = when (encoding) {
                PLAIN -> plain(data, at, present)
                PLAIN_DICTIONARY, RLE_DICTIONARY -> {
                    val dict = dictionary ?: throw ParquetException("${leaf.name}: dictionary page missing")
                    val idx = IntArray(present)
                    if (present > 0) decodeHybrid(data, at + 1, data.size, data[at].toInt() and 0xff, present, idx)
                    dict.select(idx)
                }
                RLE -> {
                    val len = le(data, at, 4)
                    val bits = IntArray(present)
                    decodeHybrid(data, at + 4, at + 4 + len, 1, present, bits)
                    Values.Bools(BooleanArray(present) { bits[it] != 0 })
                }
                DELTA_BINARY_PACKED -> Values.Longs(decodeDeltaBinaryPacked(data, at).first)
                DELTA_LENGTH_BYTE_ARRAY -> deltaLengthByteArray(data, at)
                DELTA_BYTE_ARRAY -> deltaByteArray(data, at)
                BYTE_STREAM_SPLIT -> byteStreamSplit(data, at, present)
                else -> throw ParquetException("${leaf.name}: unsupported encoding $encoding")
            }
            var v = 0
            for (i in 0 until n) {
                if (defs == null || defs[i] == leaf.maxDef) column.set(row, values, v++) else column.setNull(row)
                row++
            }
        }

        private fun plain(data: ByteArray, at: Int, count: Int): Values = when (leaf.type) {
            PhysicalType.BOOLEAN -> Values.Bools(BooleanArray(count) {
                (data[at + (it ushr 3)].toInt() ushr (it and 7)) and 1 == 1
            })
            PhysicalType.INT32 -> Values.Longs(LongArray(count) { le(data, at + 4 * it, 4).toLong() })
            PhysicalType.INT64 -> Values.Longs(LongArray(count) { le64(data, at + 8 * it) })
            PhysicalType.INT96 -> Values.Longs(LongArray(count) {
                val nanosOfDay = le64(data, at + 12 * it)
                val julianDay = le(data, at + 12 * it + 8, 4).toLong()
                (julianDay - JULIAN_EPOCH_DAY) * 86_400_000_000_000L + nanosOfDay
            })
            PhysicalType.FLOAT -> Values.Doubles(DoubleArray(count) {
                java.lang.Float.intBitsToFloat(le(data, at + 4 * it, 4)).toDouble()
            })
            PhysicalType.DOUBLE -> Values.Doubles(DoubleArray(count) {
                java.lang.Double.longBitsToDouble(le64(data, at + 8 * it))
            })
            PhysicalType.BYTE_ARRAY -> {
                var p = at
                Values.Bytes(Array(count) {
                    val len = le(data, p, 4)
                    data.copyOfRange(p + 4, p + 4 + len).also { p += 4 + len }
                })
            }
            PhysicalType.FIXED_LEN_BYTE_ARRAY -> Values.Bytes(Array(count) {
                data.copyOfRange(at + leaf.typeLength * it, at + leaf.typeLength * (it + 1))
            })
        }

        private fun deltaLengthByteArray(data: ByteArray, at: Int): Values {
            val (lengths, start) = decodeDeltaBinaryPacked(data, at)
            var p = start
            return Values.Bytes(Array(lengths.size) { i ->
                val len = lengths[i].toInt()
                data.copyOfRange(p, p + len).also { p += len }
            })
        }

        private fun deltaByteArray(data: ByteArray, at: Int): Values {
            val (prefixes, afterPrefixes) = decodeDeltaBinaryPacked(data, at)
            val (suffixes, start) = decodeDeltaBinaryPacked(data, afterPrefixes)
            var p = start
            var prev = ByteArray(0)
            return Values.Bytes(Array(prefixes.size) {
                val prefix = prefixes[it].toInt()
                val suffix = suffixes[it].toInt()
                val value = ByteArray(prefix + suffix)
                System.arraycopy(prev, 0, value, 0, prefix)
                System.arraycopy(data, p, value, prefix, suffix)
                p += suffix
                prev = value
                value
            })
        }

        private fun byteStreamSplit(data: ByteArray, at: Int, count: Int): Values {
            val width = when (leaf.type) {
                PhysicalType.INT32, PhysicalType.FLOAT -> 4
                PhysicalType.INT64, PhysicalType.DOUBLE -> 8
                PhysicalType.FIXED_LEN_BYTE_ARRAY -> leaf.typeLength
                else -> throw ParquetException("${leaf.name}: BYTE_STREAM_SPLIT on ${leaf.type}")
            }
            // Byte b of value i sits at stream b, position i: put the values back together.
            val joined = ByteArray(width * count)
            for (i in 0 until count) for (b in 0 until width) joined[i * width + b] = data[at + b * count + i]
            return plain(joined, 0, count)
        }
    }

    private fun bitWidth(max: Int) = 32 - Integer.numberOfLeadingZeros(max)

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asStruct() = this as Map<Int, Any?>

    private const val REQUIRED = 0
    private const val REPEATED = 2

    private const val CT_UTF8 = 0
    private const val CT_ENUM = 4
    private const val CT_DECIMAL = 5
    private const val CT_DATE = 6
    private const val CT_TIMESTAMP_MILLIS = 9
    private const val CT_TIMESTAMP_MICROS = 10
    private const val CT_JSON = 19

    private const val DATA_PAGE = 0
    private const val DICTIONARY_PAGE = 2
    private const val DATA_PAGE_V2 = 3

    private const val PLAIN = 0
    private const val PLAIN_DICTIONARY = 2
    private const val RLE = 3
    private const val DELTA_BINARY_PACKED = 5
    private const val DELTA_LENGTH_BYTE_ARRAY = 6
    private const val DELTA_BYTE_ARRAY = 7
    private const val RLE_DICTIONARY = 8
    private const val BYTE_STREAM_SPLIT = 9

    private const val JULIAN_EPOCH_DAY = 2_440_588L
}

/** Decoded values of one page (or a dictionary), before nulls are spread back in. */
internal sealed class Values {
    abstract fun select(idx: IntArray): Values

    class Longs(val a: LongArray) : Values() {
        override fun select(idx: IntArray) = Longs(LongArray(idx.size) { a[idx[it]] })
    }

    class Doubles(val a: DoubleArray) : Values() {
        override fun select(idx: IntArray) = Doubles(DoubleArray(idx.size) { a[idx[it]] })
    }

    class Bools(val a: BooleanArray) : Values() {
        override fun select(idx: IntArray) = Bools(BooleanArray(idx.size) { a[idx[it]] })
    }

    /** Byte strings; a dictionary's entries are decoded to text once and shared. */
    class Bytes(val a: Array<ByteArray>) : Values() {
        var text: Array<String>? = null
        override fun select(idx: IntArray): Values {
            val t = text ?: Array(a.size) { a[it].toString(Charsets.UTF_8) }.also { text = it }
            return Strings(Array(idx.size) { t[idx[it]] }, Array(idx.size) { a[idx[it]] })
        }
    }

    class Strings(val a: Array<String>, val raw: Array<ByteArray>) : Values() {
        override fun select(idx: IntArray) = Strings(Array(idx.size) { a[idx[it]] }, Array(idx.size) { raw[idx[it]] })
    }
}

/** One decoded column: typed storage plus a null mask. */
class ParquetColumn internal constructor(
    val name: String,
    val type: PhysicalType,
    val kind: ValueKind,
    private val scale: Int,
    size: Int,
) {
    private val nulls = BooleanArray(size)
    private val longs = if (type == PhysicalType.INT32 || type == PhysicalType.INT64 || type == PhysicalType.INT96) LongArray(size) else null
    private val doubles = if (type == PhysicalType.FLOAT || type == PhysicalType.DOUBLE) DoubleArray(size) else null
    private val bools = if (type == PhysicalType.BOOLEAN) BooleanArray(size) else null
    private val strings = if (type == PhysicalType.BYTE_ARRAY || type == PhysicalType.FIXED_LEN_BYTE_ARRAY) arrayOfNulls<String>(size) else null
    private val bytes = if (kind == ValueKind.DECIMAL && type == PhysicalType.FIXED_LEN_BYTE_ARRAY ||
        type == PhysicalType.BYTE_ARRAY && kind == ValueKind.DECIMAL
    ) arrayOfNulls<ByteArray>(size) else null

    internal fun setNull(row: Int) {
        nulls[row] = true
    }

    internal fun set(row: Int, values: Values, i: Int) {
        when (values) {
            is Values.Longs -> longs!![row] = values.a[i]
            is Values.Doubles -> doubles!![row] = values.a[i]
            is Values.Bools -> bools!![row] = values.a[i]
            is Values.Bytes -> {
                strings!![row] = values.a[i].toString(Charsets.UTF_8)
                bytes?.set(row, values.a[i])
            }
            is Values.Strings -> {
                strings!![row] = values.a[i]
                bytes?.set(row, values.raw[i])
            }
        }
    }

    fun isNull(row: Int) = nulls[row]

    fun string(row: Int): String? = when {
        nulls[row] -> null
        strings != null -> strings[row]
        else -> (long(row) ?: double(row) ?: bool(row))?.toString()
    }

    /** The stored integer: days for dates, the unit's ticks for timestamps. */
    fun long(row: Int): Long? = if (nulls[row] || longs == null) null else longs[row]

    fun int(row: Int): Int? = long(row)?.toInt()

    fun double(row: Int): Double? = when {
        nulls[row] -> null
        doubles != null -> doubles[row]
        kind == ValueKind.DECIMAL && longs != null -> longs[row] / Math.pow(10.0, scale.toDouble())
        kind == ValueKind.DECIMAL && bytes != null -> BigInteger(bytes[row]!!).toDouble() / Math.pow(10.0, scale.toDouble())
        longs != null -> longs[row].toDouble()
        else -> null
    }

    fun bool(row: Int): Boolean? = if (nulls[row] || bools == null) null else bools[row]

    /** Timestamps as epoch milliseconds (UTC). */
    fun epochMillis(row: Int): Long? {
        val v = long(row) ?: return null
        return when (kind) {
            ValueKind.TIMESTAMP_MILLIS -> v
            ValueKind.TIMESTAMP_MICROS -> Math.floorDiv(v, 1_000L)
            ValueKind.TIMESTAMP_NANOS -> Math.floorDiv(v, 1_000_000L)
            ValueKind.DATE -> v * 86_400_000L
            else -> null
        }
    }
}

class ParquetTable internal constructor(val numRows: Int, val columns: List<ParquetColumn>) {
    private val byName = columns.associateBy { it.name }

    fun has(name: String) = name in byName

    operator fun get(name: String): ParquetColumn =
        byName[name] ?: throw ParquetException("No column '$name' (have ${byName.keys.joinToString()})")

    fun columnOrNull(name: String): ParquetColumn? = byName[name]
}
