package com.xmitya.seafilesync.sync

import android.os.FileObserver
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Notices local edits sooner than the poll interval would.
 *
 * A watch is not a substitute for the periodic scan and is not treated as one. inotify has a
 * per-process watch limit, misses changes made while the process was dead, and reports nothing
 * for a directory that appears after the watch is set up. The scan is what makes sync correct;
 * this only makes it prompt.
 *
 * Events are debounced because a single save from an editor produces a burst of them, and
 * because a file still being written must not be uploaded half-finished.
 */
class LocalChangeWatcher(
    private val scope: CoroutineScope,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE,
    private val onChanged: suspend (repoId: String) -> Unit,
) {

    private val observers = ConcurrentHashMap<String, List<FileObserver>>()
    private val pending = mutableMapOf<String, Job>()
    private val lock = Mutex()

    fun watch(repoId: String, root: File) {
        stop(repoId)
        val directories = collectDirectories(root)
        if (directories.size > MAX_WATCHES) {
            // Better to fall back to the periodic scan entirely than to watch an arbitrary
            // subset and give the impression that everything is covered.
            Log.i(TAG, "Not watching $repoId: ${directories.size} directories exceeds the watch budget")
            return
        }
        observers[repoId] = directories.map { directory: File ->
            object : FileObserver(directory, WATCHED_EVENTS) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null) return
                    if (LocalTreeBuilder.isAlwaysIgnored(File(directory, path))) return
                    schedule(repoId)
                }
            }.also { it.startWatching() }
        }
    }

    fun stop(repoId: String) {
        observers.remove(repoId)?.forEach { it.stopWatching() }
        pending.remove(repoId)?.cancel()
    }

    fun stopAll() {
        observers.keys.toList().forEach(::stop)
    }

    /**
     * Collapses a burst of events into one sync, and waits out the tail of it so a file that is
     * still being written is not read mid-save.
     */
    private fun schedule(repoId: String) {
        scope.launch {
            lock.withLock {
                pending.remove(repoId)?.cancel()
                pending[repoId] = scope.launch {
                    delay(debounceMillis)
                    lock.withLock { pending.remove(repoId) }
                    onChanged(repoId)
                }
            }
        }
    }

    private fun collectDirectories(root: File): List<File> {
        if (!root.isDirectory) return emptyList()
        val found = mutableListOf(root)
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty() && found.size <= MAX_WATCHES) {
            val directory = queue.removeFirst()
            directory.listFiles().orEmpty()
                .filter { it.isDirectory && !LocalTreeBuilder.isAlwaysIgnored(it) }
                .forEach { found += it; queue += it }
        }
        return found
    }

    private companion object {
        const val TAG = "SeafileSync"

        /**
         * Create, delete, move and close-after-write. CLOSE_WRITE rather than MODIFY, because
         * MODIFY fires on every buffer flush during a long write while CLOSE_WRITE fires once,
         * when the writer is actually finished.
         */
        const val WATCHED_EVENTS = FileObserver.CREATE or FileObserver.DELETE or
            FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE

        const val DEFAULT_DEBOUNCE = 3_000L

        /**
         * inotify watches are a limited per-process resource shared with every other app
         * component, so a deep tree falls back to polling rather than exhausting it.
         */
        const val MAX_WATCHES = 1_000
    }
}
