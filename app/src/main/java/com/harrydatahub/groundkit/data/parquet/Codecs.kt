package com.harrydatahub.groundkit.data.parquet

import com.github.luben.zstd.Zstd
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/** Page decompression. The API writes ZSTD; the others are cheap to support. */
internal object Codecs {
    private const val UNCOMPRESSED = 0
    private const val SNAPPY = 1
    private const val GZIP = 2
    private const val ZSTD = 6

    fun decompress(codec: Int, src: ByteArray, from: Int, length: Int, uncompressedSize: Int): ByteArray =
        when (codec) {
            UNCOMPRESSED -> src.copyOfRange(from, from + length)
            ZSTD -> {
                val out = ByteArray(uncompressedSize)
                val n = Zstd.decompressByteArray(out, 0, uncompressedSize, src, from, length)
                if (Zstd.isError(n)) throw ParquetException("ZSTD: ${Zstd.getErrorName(n)}")
                out
            }
            SNAPPY -> snappy(src, from, from + length)
            GZIP -> GZIPInputStream(ByteArrayInputStream(src, from, length)).use { it.readBytes() }
            else -> throw ParquetException("Unsupported compression codec $codec")
        }

    /** Raw (unframed) Snappy, as Parquet stores it. */
    private fun snappy(src: ByteArray, start: Int, end: Int): ByteArray {
        var p = start
        var size = 0
        var shift = 0
        while (true) {
            val b = src[p++].toInt() and 0xff
            size = size or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        val out = ByteArray(size)
        var o = 0
        while (p < end) {
            val tag = src[p++].toInt() and 0xff
            when (tag and 3) {
                0 -> {
                    var len = tag ushr 2
                    if (len >= 60) {
                        val bytes = len - 59
                        len = 0
                        for (i in 0 until bytes) len = len or ((src[p + i].toInt() and 0xff) shl (8 * i))
                        p += bytes
                    }
                    len += 1
                    System.arraycopy(src, p, out, o, len)
                    p += len
                    o += len
                }
                else -> {
                    val len: Int
                    val offset: Int
                    when (tag and 3) {
                        1 -> {
                            len = 4 + ((tag ushr 2) and 7)
                            offset = ((tag ushr 5) shl 8) or (src[p++].toInt() and 0xff)
                        }
                        2 -> {
                            len = (tag ushr 2) + 1
                            offset = le(src, p, 2)
                            p += 2
                        }
                        else -> {
                            len = (tag ushr 2) + 1
                            offset = le(src, p, 4)
                            p += 4
                        }
                    }
                    // Byte by byte: copies may overlap their own output.
                    for (i in 0 until len) out[o + i] = out[o - offset + i]
                    o += len
                }
            }
        }
        return out
    }
}

internal fun le(buf: ByteArray, at: Int, bytes: Int): Int {
    var v = 0
    for (i in 0 until bytes) v = v or ((buf[at + i].toInt() and 0xff) shl (8 * i))
    return v
}

internal fun le64(buf: ByteArray, at: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = v or ((buf[at + i].toLong() and 0xff) shl (8 * i))
    return v
}

/** Unsigned LEB128 varint; returns the value and the position after it. */
internal fun varintAt(buf: ByteArray, at: Int): Pair<Long, Int> {
    var p = at
    var result = 0L
    var shift = 0
    while (true) {
        val b = buf[p++].toInt() and 0xff
        result = result or ((b and 0x7f).toLong() shl shift)
        if (b and 0x80 == 0) return result to p
        shift += 7
    }
}

private fun zigzag(n: Long): Long = (n ushr 1) xor -(n and 1)

/** `width` bits (0-64) starting at absolute bit `bit`, least significant bit first. */
internal fun bitsAt(buf: ByteArray, bit: Long, width: Int): Long {
    var v = 0L
    var got = 0
    var b = bit
    while (got < width) {
        val byte = buf[(b ushr 3).toInt()].toInt() and 0xff
        val off = (b and 7).toInt()
        val take = minOf(8 - off, width - got)
        v = v or (((byte ushr off) and ((1 shl take) - 1)).toLong() shl got)
        got += take
        b += take
    }
    return v
}

/**
 * RLE / bit-packed hybrid (definition levels, dictionary indices, RLE booleans).
 * Reads `count` values into `out`; returns the position after the last run read.
 */
internal fun decodeHybrid(buf: ByteArray, start: Int, end: Int, bitWidth: Int, count: Int, out: IntArray): Int {
    var p = start
    var n = 0
    val byteWidth = (bitWidth + 7) / 8
    while (n < count) {
        if (p >= end) throw ParquetException("RLE data ends after $n of $count values")
        val (header, next) = varintAt(buf, p)
        p = next
        if (header and 1L == 0L) {
            val run = (header ushr 1).toInt()
            val value = le(buf, p, byteWidth)
            p += byteWidth
            val take = minOf(run, count - n)
            out.fill(value, n, n + take)
            n += take
        } else {
            val groups = (header ushr 1).toInt()
            val take = minOf(groups * 8, count - n)
            val base = p.toLong() * 8
            for (i in 0 until take) out[n + i] = bitsAt(buf, base + i.toLong() * bitWidth, bitWidth).toInt()
            n += take
            p += groups * bitWidth
        }
    }
    return p
}

/** DELTA_BINARY_PACKED. Returns the values and the position after the encoded block. */
internal fun decodeDeltaBinaryPacked(buf: ByteArray, start: Int): Pair<LongArray, Int> {
    var (blockSize, p) = varintAt(buf, start)
    val miniblocks: Long
    varintAt(buf, p).let { miniblocks = it.first; p = it.second }
    val total: Long
    varintAt(buf, p).let { total = it.first; p = it.second }
    val first: Long
    varintAt(buf, p).let { first = zigzag(it.first); p = it.second }
    val out = LongArray(total.toInt())
    if (total == 0L) return out to p
    out[0] = first
    var n = 1
    val perMini = (blockSize / miniblocks).toInt()
    var prev = first
    while (n < total) {
        val minDelta: Long
        varintAt(buf, p).let { minDelta = zigzag(it.first); p = it.second }
        val widths = IntArray(miniblocks.toInt()) { buf[p + it].toInt() and 0xff }
        p += miniblocks.toInt()
        for (w in widths) {
            if (n >= total) break
            val base = p.toLong() * 8
            val take = minOf(perMini.toLong(), total - n).toInt()
            for (i in 0 until take) {
                prev += minDelta + bitsAt(buf, base + i.toLong() * w, w)
                out[n++] = prev
            }
            // A miniblock is stored whole, padded, even when it holds the last value.
            p += perMini * w / 8
        }
    }
    return out to p
}
