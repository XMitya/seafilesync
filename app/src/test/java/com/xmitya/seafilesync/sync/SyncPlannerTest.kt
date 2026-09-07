package com.xmitya.seafilesync.sync

import com.xmitya.seafilesync.data.db.FileIndexEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The planner decides what gets written to and deleted from the user's disk, so the cases that
 * matter most here are the ones where getting it wrong destroys data rather than merely syncing
 * something twice.
 */
class SyncPlannerTest {

    private fun remoteFile(path: String, fileId: String, size: Long = 100) = RemoteFile(
        path = path,
        fileId = fileId,
        sizeBytes = size,
        modifiedSeconds = 1_700_000_000,
        blockIds = listOf(fileId),
    )

    private fun snapshot(vararg files: RemoteFile) = RemoteSnapshot(
        commitId = "c".repeat(40),
        rootId = "r".repeat(40),
        files = files.associateBy { it.path },
        directories = setOf("/"),
    )

    private fun indexed(path: String, fileId: String, size: Long = 100) = FileIndexEntity(
        repoId = "repo",
        path = path,
        fileId = fileId,
        sizeBytes = size,
        serverModifiedSeconds = 1_700_000_000,
        localSizeBytes = size,
        localModifiedMillis = 1_700_000_000_000,
        blockIds = listOf(fileId),
    )

    private fun plan(
        remote: RemoteSnapshot,
        index: List<FileIndexEntity> = emptyList(),
        local: Map<String, LocalState> = emptyMap(),
        identical: Set<String> = emptySet(),
    ) = SyncPlanner.plan(
        remote = remote,
        index = index,
        modifier = "test@example.com",
        nowMillis = 1_700_000_000_000,
        contentMatches = { path, _ -> path in identical },
    ) { local[it] ?: LocalState.Missing }

    @Test
    fun `a new remote file is downloaded`() {
        val result = plan(snapshot(remoteFile("/a.txt", "a".repeat(40))))

        assertEquals(
            listOf<SyncOperation>(
                SyncOperation.CreateDirectory("/"),
                SyncOperation.DownloadFile("/a.txt", remoteFile("/a.txt", "a".repeat(40))),
            ),
            result.operations,
        )
        assertEquals(100L, result.bytesToDownload)
    }

    @Test
    fun `an unchanged file is left alone`() {
        val result = plan(
            snapshot(remoteFile("/a.txt", "a".repeat(40))),
            index = listOf(indexed("/a.txt", "a".repeat(40))),
            local = mapOf("/a.txt" to LocalState.Unchanged),
        )

        assertEquals(0L, result.bytesToDownload)
        assertTrue(result.operations.none { it is SyncOperation.DownloadFile })
    }

    @Test
    fun `a file the app never wrote is kept alongside the server copy`() {
        // The user dropped a file into the sync folder that happens to share a name with one on
        // the server. Overwriting it would destroy their copy without warning.
        val result = plan(
            snapshot(remoteFile("/notes.txt", "a".repeat(40))),
            index = emptyList(),
            local = mapOf("/notes.txt" to LocalState.Modified),
        )

        val conflict = result.operations.filterIsInstance<SyncOperation.ResolveConflict>().single()
        assertEquals("/notes.txt", conflict.path)
        assertTrue(conflict.keepLocalAs.contains("(SFConflict test@example.com "))
    }

    @Test
    fun `an untracked local file with identical content is adopted, not duplicated`() {
        // The state after a reinstall, or after re-adding a library to a folder that already
        // holds it. Treating every file as a conflict there would duplicate the whole library.
        val result = plan(
            snapshot(remoteFile("/a.txt", "a".repeat(40))),
            index = emptyList(),
            local = mapOf("/a.txt" to LocalState.Modified),
            identical = setOf("/a.txt"),
        )

        assertEquals(1, result.operations.filterIsInstance<SyncOperation.AdoptLocal>().size)
        assertTrue(result.operations.none { it is SyncOperation.ResolveConflict })
        assertEquals(0L, result.bytesToDownload)
    }

    @Test
    fun `a file changed on both sides keeps both versions`() {
        val result = plan(
            snapshot(remoteFile("/a.txt", "b".repeat(40))),
            index = listOf(indexed("/a.txt", "a".repeat(40))),
            local = mapOf("/a.txt" to LocalState.Modified),
        )

        // The server version takes the path, matching what the server does on a merge, and the
        // local edit survives under a conflict name rather than being discarded.
        val conflict = result.operations.filterIsInstance<SyncOperation.ResolveConflict>().single()
        assertEquals("/a.txt", conflict.path)
        assertEquals("b".repeat(40), conflict.remote.fileId)
    }

    @Test
    fun `a file changed only on the server is downloaded`() {
        val result = plan(
            snapshot(remoteFile("/a.txt", "b".repeat(40))),
            index = listOf(indexed("/a.txt", "a".repeat(40))),
            local = mapOf("/a.txt" to LocalState.Unchanged),
        )

        assertEquals(1, result.operations.filterIsInstance<SyncOperation.DownloadFile>().size)
    }

    @Test
    fun `a file changed only locally is not touched by the download pass`() {
        val result = plan(
            snapshot(remoteFile("/a.txt", "a".repeat(40))),
            index = listOf(indexed("/a.txt", "a".repeat(40))),
            local = mapOf("/a.txt" to LocalState.Modified),
        )

        assertTrue(result.operations.none { it is SyncOperation.DownloadFile })
        assertTrue(result.operations.none { it is SyncOperation.DeleteFile })
    }

    @Test
    fun `a file deleted locally is fetched back`() {
        // It is still on the server and the index says it was synced, so its absence is a local
        // deletion that download-only sync undoes rather than propagates.
        val result = plan(
            snapshot(remoteFile("/a.txt", "a".repeat(40))),
            index = listOf(indexed("/a.txt", "a".repeat(40))),
            local = mapOf("/a.txt" to LocalState.Missing),
        )

        assertEquals(1, result.operations.filterIsInstance<SyncOperation.DownloadFile>().size)
    }

    @Test
    fun `a file deleted on the server is removed locally`() {
        val result = plan(
            snapshot(),
            index = listOf(indexed("/gone.txt", "a".repeat(40))),
            local = mapOf("/gone.txt" to LocalState.Unchanged),
        )

        assertEquals(
            listOf(SyncOperation.DeleteFile("/gone.txt")),
            result.operations.filterIsInstance<SyncOperation.DeleteFile>(),
        )
    }

    @Test
    fun `a locally edited file is not deleted just because the server dropped it`() {
        // Losing an edit the user just made, because someone else deleted the file elsewhere, is
        // the worst outcome available here.
        val result = plan(
            snapshot(),
            index = listOf(indexed("/gone.txt", "a".repeat(40))),
            local = mapOf("/gone.txt" to LocalState.Modified),
        )

        assertTrue(result.operations.none { it is SyncOperation.DeleteFile })
        assertEquals(1, result.operations.filterIsInstance<SyncOperation.ConflictSkipped>().size)
    }

    @Test
    fun `directories are created before anything is written into them`() {
        val result = SyncPlanner.plan(
            RemoteSnapshot(
                commitId = "c".repeat(40),
                rootId = "r".repeat(40),
                files = mapOf("/docs/a.txt" to remoteFile("/docs/a.txt", "a".repeat(40))),
                directories = setOf("/", "/docs"),
            ),
            index = emptyList(),
        ) { LocalState.Missing }

        val firstDownload = result.operations.indexOfFirst { it is SyncOperation.DownloadFile }
        val lastDirectory = result.operations.indexOfLast { it is SyncOperation.CreateDirectory }
        assertTrue("directories must come first", lastDirectory < firstDownload)
    }
}
