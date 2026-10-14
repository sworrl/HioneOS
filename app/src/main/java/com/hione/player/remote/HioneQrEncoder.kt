package com.hione.player.remote

/**
 * Minimal, dependency-free QR Code encoder (ISO/IEC 18004), byte mode, versions 1–40, all four
 * error-correction levels, automatic version + mask selection. Written so the Settings card can
 * draw the PWA URL as a scannable code without pulling ZXing into the APK.
 *
 * Usage: `HioneQrEncoder.encode("https://…")` → [Matrix] where `get(x, y)` is true for a dark
 * module. Draw with a quiet zone of ≥4 modules.
 */
object HioneQrEncoder {

    enum class Ecc(internal val formatBits: Int) { LOW(1), MEDIUM(0), QUARTILE(3), HIGH(2) }

    class Matrix internal constructor(val size: Int, private val modules: Array<BooleanArray>) {
        fun get(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && modules[y][x]
    }

    fun encode(text: String, ecc: Ecc = Ecc.MEDIUM): Matrix {
        val data = text.toByteArray(Charsets.UTF_8)
        // Pick the smallest version whose data capacity fits mode(4) + count + payload bits.
        var version = 1
        while (true) {
            val capacityBits = numDataCodewords(version, ecc) * 8
            val needed = 4 + charCountBits(version) + data.size * 8
            if (needed <= capacityBits) break
            if (version == 40) throw IllegalArgumentException("Text too long for a QR code")
            version++
        }
        // Bit stream: mode 0100, char count, bytes, terminator, byte-align, pad codewords.
        val bits = BitBuffer()
        bits.append(4, 4)
        bits.append(data.size, charCountBits(version))
        for (b in data) bits.append(b.toInt() and 0xFF, 8)
        val capacity = numDataCodewords(version, ecc) * 8
        bits.append(0, minOf(4, capacity - bits.length))
        bits.append(0, (8 - bits.length % 8) % 8)
        var pad = 0xEC
        while (bits.length < capacity) { bits.append(pad, 8); pad = pad xor (0xEC xor 0x11) }
        val dataCodewords = bits.toBytes()
        return Builder(version, ecc, dataCodewords).build()
    }

    // ------------------------------------------------------------------ tables

    private fun charCountBits(version: Int) = if (version <= 9) 8 else 16

    private val ECC_CODEWORDS_PER_BLOCK = arrayOf(
        // L
        intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        // M
        intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
        // Q
        intArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        // H
        intArrayOf(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30)
    )

    private val NUM_ERROR_CORRECTION_BLOCKS = arrayOf(
        // L
        intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
        // M
        intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
        // Q
        intArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
        // H
        intArrayOf(-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81)
    )

    private fun tableIndex(ecc: Ecc) = when (ecc) { Ecc.LOW -> 0; Ecc.MEDIUM -> 1; Ecc.QUARTILE -> 2; Ecc.HIGH -> 3 }

    private fun numRawDataModules(version: Int): Int {
        var result = (16 * version + 128) * version + 64
        if (version >= 2) {
            val numAlign = version / 7 + 2
            result -= (25 * numAlign - 10) * numAlign - 55
            if (version >= 7) result -= 36
        }
        return result
    }

    private fun numDataCodewords(version: Int, ecc: Ecc): Int =
        numRawDataModules(version) / 8 -
            ECC_CODEWORDS_PER_BLOCK[tableIndex(ecc)][version] * NUM_ERROR_CORRECTION_BLOCKS[tableIndex(ecc)][version]

    // ------------------------------------------------------------------ bit buffer

    private class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val length get() = bits.size
        fun append(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits.add(((value ushr i) and 1) != 0)
        }
        fun toBytes(): ByteArray {
            val out = ByteArray((bits.size + 7) / 8)
            for (i in bits.indices) if (bits[i]) out[i ushr 3] = (out[i ushr 3].toInt() or (0x80 ushr (i and 7))).toByte()
            return out
        }
    }

    // ------------------------------------------------------------------ Reed–Solomon (GF(2^8), 0x11D)

    private fun rsMultiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z and 0xFF
    }

    private fun rsDivisor(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in result.indices) {
                result[j] = rsMultiply(result[j], root) xor (if (j + 1 < result.size) result[j + 1] else 0)
            }
            root = rsMultiply(root, 0x02)
        }
        return result
    }

    private fun rsRemainder(data: IntArray, divisor: IntArray): IntArray {
        val result = IntArray(divisor.size)
        for (b in data) {
            val factor = b xor result[0]
            System.arraycopy(result, 1, result, 0, result.size - 1)
            result[result.size - 1] = 0
            for (i in result.indices) result[i] = result[i] xor rsMultiply(divisor[i], factor)
        }
        return result
    }

    // ------------------------------------------------------------------ symbol builder

    private class Builder(val version: Int, val ecc: Ecc, val dataCodewords: ByteArray) {
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        fun build(): Matrix {
            drawFunctionPatterns()
            drawCodewords(addEccAndInterleave())
            var best = 0
            var bestPenalty = Int.MAX_VALUE
            for (mask in 0..7) {
                applyMask(mask)
                drawFormatBits(mask)
                val p = penalty()
                if (p < bestPenalty) { bestPenalty = p; best = mask }
                applyMask(mask) // XOR is its own inverse
            }
            applyMask(best)
            drawFormatBits(best)
            return Matrix(size, modules)
        }

        private fun set(x: Int, y: Int, dark: Boolean) { modules[y][x] = dark; isFunction[y][x] = true }

        private fun drawFunctionPatterns() {
            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            drawFinder(3, 3); drawFinder(size - 4, 3); drawFinder(3, size - 4)
            val align = alignmentPositions()
            val n = align.size
            for (i in 0 until n) for (j in 0 until n) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue
                drawAlignment(align[i], align[j])
            }
            drawFormatBits(0) // placeholder — reserves the modules
            drawVersion()
        }

        private fun drawFinder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val dist = maxOf(Math.abs(dx), Math.abs(dy))
                val x = cx + dx; val y = cy + dy
                if (x in 0 until size && y in 0 until size) set(x, y, dist != 2 && dist != 4)
            }
        }

        private fun drawAlignment(cx: Int, cy: Int) {
            for (dy in -2..2) for (dx in -2..2) set(cx + dx, cy + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
        }

        private fun alignmentPositions(): IntArray {
            if (version == 1) return IntArray(0)
            val numAlign = version / 7 + 2
            val step = if (version == 32) 26 else (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2
            val result = IntArray(numAlign)
            result[0] = 6
            var pos = size - 7
            for (i in numAlign - 1 downTo 1) { result[i] = pos; pos -= step }
            return result
        }

        private fun drawFormatBits(mask: Int) {
            val data = (ecc.formatBits shl 3) or mask
            var rem = data
            for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
            val bits = ((data shl 10) or rem) xor 0x5412
            for (i in 0..5) set(8, i, bit(bits, i))
            set(8, 7, bit(bits, 6)); set(8, 8, bit(bits, 7)); set(7, 8, bit(bits, 8))
            for (i in 9..14) set(14 - i, 8, bit(bits, i))
            for (i in 0..7) set(size - 1 - i, 8, bit(bits, i))
            for (i in 8..14) set(8, size - 15 + i, bit(bits, i))
            set(8, size - 8, true)
        }

        private fun drawVersion() {
            if (version < 7) return
            var rem = version
            for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val b = bit(bits, i)
                val a = size - 11 + i % 3
                val c = i / 3
                set(a, c, b); set(c, a, b)
            }
        }

        private fun addEccAndInterleave(): ByteArray {
            val ti = when (ecc) { Ecc.LOW -> 0; Ecc.MEDIUM -> 1; Ecc.QUARTILE -> 2; Ecc.HIGH -> 3 }
            val numBlocks = NUM_ERROR_CORRECTION_BLOCKS[ti][version]
            val blockEccLen = ECC_CODEWORDS_PER_BLOCK[ti][version]
            val rawCodewords = numRawDataModules(version) / 8
            val numShortBlocks = numBlocks - rawCodewords % numBlocks
            val shortBlockLen = rawCodewords / numBlocks
            val divisor = rsDivisor(blockEccLen)
            val blocks = ArrayList<IntArray>()
            var k = 0
            for (i in 0 until numBlocks) {
                val datLen = shortBlockLen - blockEccLen + (if (i < numShortBlocks) 0 else 1)
                val dat = IntArray(datLen) { dataCodewords[k + it].toInt() and 0xFF }
                k += datLen
                val block = IntArray(shortBlockLen + 1)
                System.arraycopy(dat, 0, block, 0, datLen)
                val eccBytes = rsRemainder(dat, divisor)
                System.arraycopy(eccBytes, 0, block, block.size - blockEccLen, eccBytes.size)
                blocks.add(block)
            }
            val result = ByteArray(rawCodewords)
            var idx = 0
            for (i in blocks[0].indices) for (j in blocks.indices) {
                if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) result[idx++] = blocks[j][i].toByte()
            }
            return result
        }

        private fun drawCodewords(data: ByteArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFunction[y][x] && i < data.size * 8) {
                        modules[y][x] = ((data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0
                        i++
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert && !isFunction[y][x]) modules[y][x] = !modules[y][x]
            }
        }

        /** ISO 18004 §7.8.3 penalty rules N1..N4 — only used to rank masks. */
        private fun penalty(): Int {
            var result = 0
            // N1: runs of ≥5 same-colour modules in rows and columns; N3: finder-like patterns.
            for (y in 0 until size) result += linePenalty { x -> modules[y][x] }
            for (x in 0 until size) result += linePenalty { y -> modules[y][x] }
            // N2: 2x2 blocks of one colour.
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3
            }
            // N4: dark-module proportion deviation from 50%.
            var dark = 0
            for (row in modules) for (m in row) if (m) dark++
            val total = size * size
            val k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1
            result += k * 10
            return result
        }

        private inline fun linePenalty(at: (Int) -> Boolean): Int {
            var result = 0
            var runColor = false
            var runLen = 0
            for (i in 0 until size) {
                val c = at(i)
                if (c == runColor) {
                    runLen++
                    if (runLen == 5) result += 3 else if (runLen > 5) result++
                } else {
                    runColor = c; runLen = 1
                }
            }
            // Finder-like 1:1:3:1:1 with 4 light modules on either side.
            val sb = StringBuilder(size)
            for (i in 0 until size) sb.append(if (at(i)) '1' else '0')
            val s = sb.toString()
            var idx = s.indexOf("00001011101")
            while (idx >= 0) { result += 40; idx = s.indexOf("00001011101", idx + 1) }
            idx = s.indexOf("10111010000")
            while (idx >= 0) { result += 40; idx = s.indexOf("10111010000", idx + 1) }
            return result
        }

        private fun bit(x: Int, i: Int) = ((x ushr i) and 1) != 0
    }
}
