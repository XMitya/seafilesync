package com.xmitya.seafilesync.service

import android.net.wifi.WifiManager
import android.os.PowerManager

/**
 * The wake and Wi-Fi locks held while bytes are moving.
 *
 * Each lock is created once and is only ever acquired and released, never replaced. A held lock
 * that is replaced instead of released stays held until the process dies, and Android allows an
 * app at most 50 Wi-Fi locks. That is how the first transfer on a Boox crashed within seconds:
 * its firmware refuses app wake locks outright, so isHeld never turned true, and a guard on it
 * created a fresh pair of locks on every progress tick.
 *
 * Not thread-safe; the service drives it from the main thread only.
 */
class TransferLocks(
    private val wakeLock: Lock,
    private val wifiLock: Lock,
    private val clock: () -> Long,
) {

    /** The part of a platform lock this needs, so the logic can be tested off a device. */
    interface Lock {
        val isHeld: Boolean

        fun acquire()

        fun release()
    }

    /** When the wake lock was last asked for during this transfer, or null between transfers. */
    private var wakeLockRequestedAt: Long? = null

    /** Called on every progress tick, so it must cost nothing once the locks are taken. */
    fun hold() {
        if (!wifiLock.isHeld) wifiLock.acquire()

        // The wake lock has a timeout, so a long transfer renews it. Renewal is paced by the
        // clock rather than by isHeld: a refused wake lock never reports held, and each refusal
        // is a Log.wtf the system files in the dropbox -- several a second if retried per tick.
        val now = clock()
        val requestedAt = wakeLockRequestedAt
        if (requestedAt == null || now - requestedAt >= RENEW_MILLIS) {
            wakeLock.acquire()
            wakeLockRequestedAt = now
        }
    }

    fun release() {
        wakeLockRequestedAt = null
        if (wakeLock.isHeld) wakeLock.release()
        if (wifiLock.isHeld) wifiLock.release()
    }

    companion object {
        /**
         * Well inside the wake lock's own timeout, and inside the shorter cap Boox firmware puts
         * on the wake locks it does allow, so a transfer that keeps moving never loses it.
         */
        internal const val RENEW_MILLIS = 60 * 1000L

        fun wakeLock(lock: PowerManager.WakeLock, timeoutMillis: Long): Lock {
            lock.setReferenceCounted(false)
            return object : Lock {
                override val isHeld get() = lock.isHeld

                override fun acquire() = lock.acquire(timeoutMillis)

                override fun release() = lock.release()
            }
        }

        fun wifiLock(lock: WifiManager.WifiLock): Lock {
            lock.setReferenceCounted(false)
            return object : Lock {
                override val isHeld get() = lock.isHeld

                override fun acquire() = lock.acquire()

                override fun release() = lock.release()
            }
        }
    }
}
