package com.xmitya.seafilesync.data.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The expected boundaries come from compiling Seafile's own chunking loop from
 * `common/cdc/cdc.c` and running it over the same input. They are not produced by this code, so a
 * translation error moves a boundary and fails here rather than quietly ending deduplication
 * against every other client.
 */
class CdcChunkerTest {

    private val chunker = CdcChunker()

    /** The same generator the C harness used, so the input can be regenerated. */
    private fun input(size: Int): ByteArray {
        var state = 999u
        return ByteArray(size) {
            state = state * 1103515245u + 12345u
            ((state shr 16) and 0xFFu).toByte()
        }
    }

    private fun chunk(data: ByteArray): List<CdcChunker.Chunk> =
        buildList { chunker.chunk(data.inputStream()) { chunk, _ -> add(chunk) } }

    @Test
    fun `boundaries match the reference implementation`() {
        val expected = listOf(
            0L to 2962810,
            2962810L to 500175,
            3462985L to 1922677,
            5385662L to 416747,
            5802409L to 404190,
            6206599L to 447652,
            6654251L to 588657,
            7242908L to 1149491,
            8392399L to 1013084,
            9405483L to 972430,
            10377913L to 619906,
            10997819L to 761877,
            11759696L to 750744,
            12510440L to 72472,
        ).map { CdcChunker.Chunk(it.first, it.second) }

        assertEquals(expected, chunk(input(12 * 1024 * 1024)))
    }

    @Test
    fun `chunks cover the input exactly once`() {
        val data = input(12 * 1024 * 1024)
        val chunks = chunk(data)

        var expectedOffset = 0L
        for (chunk in chunks) {
            assertEquals("chunks must be contiguous", expectedOffset, chunk.offset)
            expectedOffset += chunk.length
        }
        assertEquals(data.size.toLong(), expectedOffset)
    }

    @Test
    fun `a file smaller than the minimum block is one chunk`() {
        val data = input(1000)

        assertEquals(listOf(CdcChunker.Chunk(0, 1000)), chunk(data))
    }

    @Test
    fun `an empty file produces no chunks`() {
        assertEquals(emptyList<CdcChunker.Chunk>(), chunk(ByteArray(0)))
    }

    @Test
    fun `no chunk exceeds the maximum or falls below the minimum except the last`() {
        val chunks = chunk(input(12 * 1024 * 1024))

        chunks.dropLast(1).forEach {
            assertTrue("chunk too small: ${it.length}", it.length >= CdcChunker.BLOCK_MIN_SIZE)
            assertTrue("chunk too large: ${it.length}", it.length <= CdcChunker.BLOCK_MAX_SIZE)
        }
    }

    @Test
    fun `incompressible content still terminates at the maximum block size`() {
        // Constant bytes never produce the break value, so the only thing stopping a block is the
        // maximum. Without that guard the chunker would buffer the whole file.
        val chunks = chunk(ByteArray(10 * 1024 * 1024) { 0 })

        assertEquals(CdcChunker.BLOCK_MAX_SIZE, chunks.first().length)
        assertEquals(10L * 1024 * 1024, chunks.sumOf { it.length.toLong() })
    }

    @Test
    fun `inserting bytes early leaves most blocks untouched`() {
        // This is the whole reason for content-defined chunking. With fixed-size blocks an
        // insertion near the start shifts every later boundary and the entire file has to be
        // re-uploaded.
        val original = input(12 * 1024 * 1024)
        val edited = ByteArray(original.size + 17)
        original.copyInto(edited, 0, 0, 1000)
        ByteArray(17) { 42 }.copyInto(edited, 1000)
        original.copyInto(edited, 1017, 1000)

        val before = chunk(original).map { it.length }
        val after = chunk(edited).map { it.length }

        // Everything after the disturbed region realigns and matches again.
        val shared = before.drop(1).intersect(after.drop(1).toSet())
        assertTrue(
            "expected most blocks to survive the edit, kept ${shared.size} of ${before.size}",
            shared.size >= before.size - 3,
        )
    }
}
