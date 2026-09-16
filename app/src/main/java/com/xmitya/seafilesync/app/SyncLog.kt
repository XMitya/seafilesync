package com.xmitya.seafilesync.app

import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A log the user can hand over when something goes wrong.
 *
 * logcat is not enough for this app: the interesting failures happen in a background service,
 * hours after the user last looked at the screen, and by the time anyone asks, the ring buffer
 * has long since overwritten them. So the same lines go to a file that survives restarts.
 *
 * Deliberately small and bounded. A sync client can produce a line per file, so an unbounded log
 * would quietly consume the storage it was meant to be syncing into.
 */
class SyncLog(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()

    private val current: File get() = File(directory, FILE_NAME)
    private val previous: File get() = File(directory, "$FILE_NAME.1")

    fun info(message: String) = write("I", message, null)

    fun warn(message: String, failure: Throwable? = null) = write("W", message, failure)

    private fun write(level: String, message: String, failure: Throwable?) {
        Log.println(if (level == "W") Log.WARN else Log.INFO, TAG, message)
        failure?.let { Log.w(TAG, it) }

        val line = buildString {
            append(TIMESTAMP.format(Date(clock())))
            append(' ').append(level).append(' ').append(message)
            failure?.let { append('\n').append(it.stackTraceToString().trim()) }
            append('\n')
        }

        synchronized(lock) {
            try {
                directory.mkdirs()
                if (current.length() > MAX_BYTES) rotate()
                current.appendText(line)
            } catch (ignored: IOException) {
                // Logging must never be the reason a sync fails, so a full or unwritable disk
                // costs the log line and nothing else.
            }
        }
    }

    private fun rotate() {
        previous.delete()
        current.renameTo(previous)
    }

    /** Newest last, oldest first, across both files. */
    fun read(): String = synchronized(lock) {
        buildString {
            if (previous.isFile) append(previous.readText())
            if (current.isFile) append(current.readText())
        }
    }

    fun clear() = synchronized(lock) {
        current.delete()
        previous.delete()
    }

    /**
     * Writes the log where another app can read it, for sharing. Under the app's own cache so it
     * disappears on its own rather than accumulating copies of the user's diagnostics.
     */
    fun exportTo(cacheDirectory: File): File {
        val target = File(cacheDirectory, "seafile-sync-log.txt")
        target.parentFile?.mkdirs()
        target.writeText(read())
        return target
    }

    private companion object {
        const val TAG = "SeafileSync"
        const val FILE_NAME = "sync.log"

        /** Two files of this size, so the log costs at most a megabyte. */
        const val MAX_BYTES = 512L * 1024

        val TIMESTAMP = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }
}
