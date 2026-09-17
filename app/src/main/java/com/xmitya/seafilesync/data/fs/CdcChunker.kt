package com.xmitya.seafilesync.data.fs

import java.io.InputStream

/**
 * Content-defined chunking, as Seafile does it.
 *
 * Fixed-size blocks are perfectly valid on the wire -- the server deduplicates by the hash of a
 * block's contents and never asks where the boundaries fell. What they cost is deduplication:
 * inserting a byte near the start of a file shifts every later boundary, so every block changes
 * and the whole file is re-uploaded. Choosing boundaries from the content instead means an edit
 * only disturbs the blocks around it, and matching Seafile's parameters exactly means the blocks
 * also match what the desktop client already uploaded.
 *
 * Ported from `common/cdc/cdc.c`, including its buffering, because the boundary positions depend
 * on it: the fingerprint is seeded once per block with a direct computation and rolled from
 * there, and those two do not agree (see RabinFingerprintTest).
 */
class CdcChunker(
    private val minSize: Int = BLOCK_MIN_SIZE,
    private val averageSize: Int = BLOCK_AVERAGE_SIZE,
    private val maxSize: Int = BLOCK_MAX_SIZE,
) {

    init {
        RabinFingerprint.init(WINDOW_SIZE)
    }

    /** Where one block sits in the file. */
    data class Chunk(
        val offset: Long,
        val length: Int,
    )

    /**
     * Splits [input] into blocks, reporting each as it is found. Never holds more than [maxSize]
     * bytes, so a large file costs the same as a small one.
     *
     * The block's bytes are handed to [onChunk] in the chunker's own buffer, valid only for the
     * duration of the call: the caller hashes them there instead of the file being read twice.
     */
    fun chunk(input: InputStream, onChunk: (chunk: Chunk, data: ByteArray) -> Unit) {
        val mask = (averageSize - 1).toUInt()
        val breakValue = BREAK_VALUE and mask

        val buffer = ByteArray(maxSize)
        var tail = 0
        var cur = 0
        var offset = 0L
        var fingerprint = 0u

        while (true) {
            val wanted = if (tail < minSize) {
                minOf(minSize - tail + READ_SIZE, buffer.size - tail)
            } else {
                minOf(READ_SIZE, buffer.size - tail)
            }
            val read = if (wanted > 0) input.readAtMost(buffer, tail, wanted) else 0
            tail += read

            // Either the file ended with less than a whole block left, or no boundary was found
            // before it ended. Both mean the remainder is the last block.
            if (tail < minSize || cur >= tail) {
                if (tail > 0) onChunk(Chunk(offset, tail), buffer)
                return
            }

            // A block is never shorter than the minimum, so scanning starts there.
            if (cur < minSize - 1) cur = minSize - 1

            while (cur < tail) {
                fingerprint = if (cur == minSize - 1) {
                    RabinFingerprint.checksum(buffer, cur - WINDOW_SIZE + 1, WINDOW_SIZE)
                } else {
                    RabinFingerprint.roll(fingerprint, buffer[cur - WINDOW_SIZE], buffer[cur])
                }

                if ((fingerprint and mask) == breakValue || cur + 1 >= maxSize) {
                    val length = cur + 1
                    onChunk(Chunk(offset, length), buffer)
                    offset += length
                    System.arraycopy(buffer, length, buffer, 0, tail - length)
                    tail -= length
                    cur = 0
                    break
                }
                cur++
            }
        }
    }

    /** Reads up to [count] bytes, returning fewer only at end of stream. */
    private fun InputStream.readAtMost(into: ByteArray, at: Int, count: Int): Int {
        var total = 0
        while (total < count) {
            val n = read(into, at + total, count - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    companion object {
        /** All from `cdc.c`; changing any of them changes every boundary. */
        const val BLOCK_MIN_SIZE = 1024 * 256
        const val BLOCK_AVERAGE_SIZE = 1024 * 1024
        const val BLOCK_MAX_SIZE = 1024 * 1024 * 4
        const val WINDOW_SIZE = 48

        /**
         * Not a `const val`: folding `Int.toUInt()` at compile time crashes the Kotlin backend
         * (InterpreterMethodNotFoundError), so the unsigned type is declared outright.
         */
        val BREAK_VALUE: UInt = 0x0013u
        private const val READ_SIZE = 1024 * 4
    }
}
