package com.xmitya.seafilesync.sync

import com.xmitya.seafilesync.data.api.SeafHttpApi
import com.xmitya.seafilesync.data.api.SeafileApi
import com.xmitya.seafilesync.data.db.FileIndexDao
import com.xmitya.seafilesync.data.db.FileIndexEntity
import com.xmitya.seafilesync.data.db.SyncedRepoDao
import com.xmitya.seafilesync.data.db.SyncedRepoEntity
import com.xmitya.seafilesync.data.prefs.Account
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

/** What the UI and the notification both render. */
data class RepoProgress(
    val repoId: String,
    val name: String,
    val transferredBytes: Long = 0,
    val totalBytes: Long = 0,
    val currentPath: String? = null,
    val filesRemaining: Int = 0,
) {
    val fraction: Float?
        get() = if (totalBytes > 0) (transferredBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else null
}

data class SyncStatus(
    val activeRepos: Map<String, RepoProgress> = emptyMap(),
    val skippedConflicts: Map<String, List<String>> = emptyMap(),
) {
    val isTransferring: Boolean get() = activeRepos.isNotEmpty()
}

/**
 * Brings selected libraries in line with the server, one library at a time.
 *
 * This milestone is download-only: local changes are detected but never pushed, and a file that
 * changed on both sides is reported rather than overwritten, so nothing the user did is lost
 * while the upload half does not exist yet.
 */
class SyncEngine(
    private val repos: SyncedRepoDao,
    private val fileIndex: FileIndexDao,
    /**
     * Clients are built per account rather than held as fields. The account may not exist yet
     * when the engine is constructed -- on a fresh install nothing is signed in -- and reading
     * it eagerly meant blocking the main thread for a server URL that was still empty.
     */
    private val apiFor: (serverUrl: String) -> SeafileApi,
    private val seafHttpFor: (serverUrl: String) -> SeafHttpApi,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private class Session(val api: SeafileApi, val seafHttp: SeafHttpApi) {
        val treeReader = RemoteTreeReader(seafHttp)
        val downloader = Downloader(seafHttp)
    }

    private fun sessionFor(account: Account) =
        Session(apiFor(account.serverUrl), seafHttpFor(account.serverUrl))

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** One library at a time: parallel syncs would fight over bandwidth and confuse progress. */
    private val lock = Mutex()

    /**
     * Registers a library for syncing. The directory is created eagerly so the user can see the
     * result immediately even before anything transfers.
     */
    suspend fun enable(account: Account, repoId: String, name: String, isWritable: Boolean) {
        val localPath = File(account.syncRoot, name).path
        File(localPath).mkdirs()
        repos.upsert(
            SyncedRepoEntity(
                repoId = repoId,
                name = name,
                localPath = localPath,
                isWritable = isWritable,
            )
        )
    }

    /**
     * Stops syncing but leaves the files. They were downloaded on purpose; deleting them because
     * the user switched syncing off would be a surprise.
     */
    suspend fun disable(repoId: String) {
        repos.delete(repoId)
        fileIndex.deleteForRepo(repoId)
        _status.update { it.copy(activeRepos = it.activeRepos - repoId) }
    }

    /**
     * Asks the server which libraries moved. One request covers all of them, which matters
     * because there is no push channel and this runs on a timer.
     */
    suspend fun reposNeedingSync(account: Account): List<SyncedRepoEntity> {
        val tracked = repos.all()
        if (tracked.isEmpty()) return emptyList()

        val session = sessionFor(account)
        val token = tracked.firstNotNullOfOrNull { it.syncToken }
            ?: syncToken(session, account, tracked.first().repoId)
        val heads = runCatching { session.seafHttp.headCommits(token, tracked.map { it.repoId }) }
            // If the heads cannot be fetched, assume everything might have moved rather than
            // silently skipping a sync pass.
            .getOrElse { return tracked }

        return tracked.filter { heads[it.repoId] != it.lastSyncedCommitId }
    }

    suspend fun syncAll(account: Account): List<SyncOutcome> =
        repos.all().map { sync(account, it) }

    sealed interface SyncOutcome {
        data class UpToDate(val repoId: String) : SyncOutcome
        data class Synced(val repoId: String, val commitId: String, val downloaded: Int) : SyncOutcome
        data class Failed(val repoId: String, val reason: String) : SyncOutcome
    }

    suspend fun sync(account: Account, repo: SyncedRepoEntity): SyncOutcome = lock.withLock {
        try {
            repos.updateStatus(repo.repoId, SyncedRepoEntity.STATUS_SYNCING)
            val outcome = runSync(sessionFor(account), account, repo)
            _status.update { it.copy(activeRepos = it.activeRepos - repo.repoId) }
            outcome
        } catch (failure: IOException) {
            val reason = failure.message ?: failure::class.simpleName.orEmpty()
            repos.updateStatus(repo.repoId, SyncedRepoEntity.STATUS_ERROR, reason)
            _status.update { it.copy(activeRepos = it.activeRepos - repo.repoId) }
            SyncOutcome.Failed(repo.repoId, reason)
        }
    }

    private suspend fun runSync(session: Session, account: Account, repo: SyncedRepoEntity): SyncOutcome {
        val token = repo.syncToken ?: syncToken(session, account, repo.repoId)
        val head = session.seafHttp.headCommitId(token, repo.repoId)
        if (head.corrupted) throw IOException("Library is corrupted on the server")
        val headCommitId = head.headCommitId ?: return SyncOutcome.UpToDate(repo.repoId)

        if (headCommitId == repo.lastSyncedCommitId) {
            repos.updateStatus(repo.repoId, SyncedRepoEntity.STATUS_IDLE)
            return SyncOutcome.UpToDate(repo.repoId)
        }

        val commit = session.seafHttp.commit(token, repo.repoId, headCommitId)
        val snapshot = session.treeReader.read(token, repo.repoId, headCommitId, commit.rootId)
        val root = File(repo.localPath)
        val index = fileIndex.forRepo(repo.repoId)
        val recorded = index.associateBy { it.path }

        val plan = SyncPlanner.plan(snapshot, index) { path ->
            localStateOf(File(root, path.trimStart('/')), recorded[path])
        }

        _status.update {
            it.copy(
                activeRepos = it.activeRepos + (repo.repoId to RepoProgress(
                    repoId = repo.repoId,
                    name = repo.name,
                    totalBytes = plan.bytesToDownload,
                    filesRemaining = plan.operations.count { op -> op is SyncOperation.DownloadFile },
                ))
            )
        }

        var downloaded = 0
        val skipped = mutableListOf<String>()

        for (operation in plan.operations) {
            when (operation) {
                is SyncOperation.CreateDirectory -> File(root, operation.path.trimStart('/')).mkdirs()

                is SyncOperation.DownloadFile -> {
                    val target = File(root, operation.path.trimStart('/'))
                    updateProgress(repo.repoId) { it.copy(currentPath = operation.path) }
                    session.downloader.download(token, repo.repoId, operation.remote, target) { bytes ->
                        updateProgress(repo.repoId) { it.copy(transferredBytes = it.transferredBytes + bytes) }
                    }
                    fileIndex.upsert(
                        FileIndexEntity(
                            repoId = repo.repoId,
                            path = operation.path,
                            fileId = operation.remote.fileId,
                            sizeBytes = operation.remote.sizeBytes,
                            serverModifiedSeconds = operation.remote.modifiedSeconds,
                            localSizeBytes = target.length(),
                            localModifiedMillis = target.lastModified(),
                            blockIds = operation.remote.blockIds,
                        )
                    )
                    downloaded++
                    updateProgress(repo.repoId) { it.copy(filesRemaining = (it.filesRemaining - 1).coerceAtLeast(0)) }
                }

                is SyncOperation.DeleteFile -> {
                    File(root, operation.path.trimStart('/')).delete()
                    fileIndex.delete(repo.repoId, operation.path)
                }

                is SyncOperation.ConflictSkipped -> skipped += "${operation.path}: ${operation.reason}"
            }
        }

        _status.update { it.copy(skippedConflicts = it.skippedConflicts + (repo.repoId to skipped)) }

        // Only now is the working tree actually at this commit. Recording it earlier would make
        // an interrupted sync look complete and leave files permanently stale.
        repos.markSynced(repo.repoId, headCommitId, clock())
        return SyncOutcome.Synced(repo.repoId, headCommitId, downloaded)
    }

    private suspend fun syncToken(session: Session, account: Account, repoId: String): String {
        val info = session.api.downloadInfo(account.token, repoId)
        repos.updateToken(repoId, info.token)
        return info.token
    }

    private fun updateProgress(repoId: String, transform: (RepoProgress) -> RepoProgress) {
        _status.update { status ->
            val current = status.activeRepos[repoId] ?: return@update status
            status.copy(activeRepos = status.activeRepos + (repoId to transform(current)))
        }
    }

    /**
     * Compares the file on disk with what the last sync wrote. Size and mtime rather than a hash:
     * rehashing every file on every pass would dominate the sync on a phone, and this is only a
     * hint that something needs a closer look.
     */
    private fun localStateOf(file: File, recorded: FileIndexEntity?): LocalState = when {
        !file.exists() -> LocalState.Missing
        recorded == null -> LocalState.Modified
        file.length() != recorded.localSizeBytes -> LocalState.Modified
        file.lastModified() != recorded.localModifiedMillis -> LocalState.Modified
        else -> LocalState.Unchanged
    }
}
