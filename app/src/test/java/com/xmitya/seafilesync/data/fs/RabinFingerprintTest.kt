package com.xmitya.seafilesync.data.fs

import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test

/**
 * Expected values come from compiling Seafile's own `common/cdc/rabin-checksum.c` and running it
 * over the buffer built below. They are not derived from this implementation, so a transcription
 * slip in the 64-bit table arithmetic shows up here rather than as blocks that quietly fail to
 * deduplicate against the desktop client.
 */
class RabinFingerprintTest {

    companion object {
        private const val WINDOW = 48

        @BeforeClass
        @JvmStatic
        fun setUp() = RabinFingerprint.init(WINDOW)

        /** The same generator the C harness used, so the vectors can be regenerated. */
        private fun probeBuffer(size: Int = 4096): ByteArray {
            var state = 12345u
            return ByteArray(size) {
                state = state * 1103515245u + 12345u
                ((state shr 16) and 0xFFu).toByte()
            }
        }
    }

    private val buffer = probeBuffer()

    @Test
    fun `the first window matches the reference implementation`() {
        assertEquals(957401928u, RabinFingerprint.checksum(buffer, 0, WINDOW))
    }

    @Test
    fun `rolling the window matches the reference implementation`() {
        var csum = RabinFingerprint.checksum(buffer, 0, WINDOW)
        val expected = mapOf(
            48 to 298245501u,
            49 to 2020577745u,
            50 to 2726760155u,
            51 to 1042047054u,
            52 to 2864614333u,
            4091 to 2091842489u,
            4092 to 2251487825u,
            4093 to 3103926591u,
            4094 to 140122153u,
            4095 to 3972459615u,
        )
        for (cur in WINDOW until buffer.size) {
            csum = RabinFingerprint.roll(csum, buffer[cur - WINDOW], buffer[cur])
            expected[cur]?.let { assertEquals("at offset $cur", it, csum) }
        }
        assertEquals(3972459615u, csum)
    }

    @Test
    fun `whole-window checksums match at several offsets`() {
        val expected = mapOf(
            0 to 957401928u,
            1000 to 1370164290u,
            2000 to 1263045093u,
            3000 to 1509297729u,
            4000 to 4129329695u,
        )
        for ((offset, value) in expected) {
            assertEquals("at offset $offset", value, RabinFingerprint.checksum(buffer, offset, WINDOW))
        }
    }

    @Test
    fun `rolling and computing outright deliberately disagree`() {
        // A true rolling hash would give the same answer either way. This one does not, because
        // the checksum is truncated to 32 bits while the table arithmetic is 64-bit, so the high
        // bits that would cancel are thrown away.
        //
        // That is not a defect to fix here: the reference C produces exactly these two numbers,
        // and the chunker relies on the behaviour, seeding each block with the direct computation
        // and rolling from there. "Correcting" it would move every block boundary and silently
        // end deduplication against every other client.
        var rolled = RabinFingerprint.checksum(buffer, 0, WINDOW)
        for (cur in WINDOW until 1000 + WINDOW) {
            rolled = RabinFingerprint.roll(rolled, buffer[cur - WINDOW], buffer[cur])
        }

        assertEquals(928837762u, rolled)
        assertEquals(1370164290u, RabinFingerprint.checksum(buffer, 1000, WINDOW))
    }
}
