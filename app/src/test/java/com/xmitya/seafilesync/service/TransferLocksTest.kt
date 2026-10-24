package com.xmitya.seafilesync.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lock handling is driven by every progress tick of a transfer, so what matters is what it
 * does on the hundredth call, not the first -- and on firmware that refuses a lock, not only on
 * a device that grants it.
 */
class TransferLocksTest {

    private class FakeLock(
        /** False models Boox firmware, which refuses app wake locks without throwing. */
        private val grants: Boolean = true,
    ) : TransferLocks.Lock {
        var acquires = 0
        var releases = 0
        override var isHeld = false
            private set

        override fun acquire() {
            acquires++
            if (grants) isHeld = true
        }

        override fun release() {
            releases++
            isHeld = false
        }
    }

    private var now = 1_000L

    @Test
    fun `a refused wake lock does not make the wifi lock be taken again on every tick`() {
        val wake = FakeLock(grants = false)
        val wifi = FakeLock()
        val locks = TransferLocks(wake, wifi) { now }

        repeat(500) {
            locks.hold()
            now += 100
        }

        assertEquals(1, wifi.acquires)
        assertTrue(wifi.isHeld)
    }

    @Test
    fun `a refused wake lock is retried once per renewal interval rather than on every tick`() {
        // Each refusal is a Log.wtf the system files in the dropbox; once per tick that is
        // several a second for as long as the transfer runs.
        val wake = FakeLock(grants = false)
        val locks = TransferLocks(wake, FakeLock()) { now }

        locks.hold()
        repeat(100) {
            now += (TransferLocks.RENEW_MILLIS - 1) / 100
            locks.hold()
        }
        assertEquals(1, wake.acquires)

        now += TransferLocks.RENEW_MILLIS
        locks.hold()
        assertEquals(2, wake.acquires)
    }

    @Test
    fun `a granted wake lock is renewed during a long transfer, before its timeout runs out`() {
        val wake = FakeLock()
        val locks = TransferLocks(wake, FakeLock()) { now }

        locks.hold()
        now += TransferLocks.RENEW_MILLIS
        locks.hold()

        assertEquals(2, wake.acquires)
    }

    @Test
    fun `release lets go of both locks`() {
        val wake = FakeLock()
        val wifi = FakeLock()
        val locks = TransferLocks(wake, wifi) { now }

        locks.hold()
        locks.release()

        assertFalse(wake.isHeld)
        assertFalse(wifi.isHeld)
    }

    @Test
    fun `the next transfer takes the locks again straight away`() {
        val wake = FakeLock()
        val wifi = FakeLock()
        val locks = TransferLocks(wake, wifi) { now }

        locks.hold()
        locks.release()
        now += 10
        locks.hold()

        assertEquals(2, wake.acquires)
        assertEquals(2, wifi.acquires)
        assertTrue(wake.isHeld)
        assertTrue(wifi.isHeld)
    }

    @Test
    fun `releasing while idle touches neither lock`() {
        // Every status change outside a transfer releases, so this runs far more often than hold.
        val wake = FakeLock()
        val wifi = FakeLock()
        val locks = TransferLocks(wake, wifi) { now }

        repeat(10) { locks.release() }

        assertEquals(0, wake.releases)
        assertEquals(0, wifi.releases)
    }
}
