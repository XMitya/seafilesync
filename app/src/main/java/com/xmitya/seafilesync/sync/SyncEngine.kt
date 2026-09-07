package com.xmitya.seafilesync.sync

import com.xmitya.seafilesync.data.api.SeafHttpApi
import com.xmitya.seafilesync.data.api.SeafileApi
import com.xmitya.seafilesync.data.db.FileIndexDao
import com.xmitya.seafilesync.data.db.FileIndexEntity
import com.xmitya.seafilesync.data.db.SyncedRepoDao
import com.xmitya.seafilesync.data.db.SyncedRepoEntity
import com.xmitya.seafilesync.data.crypto.LibraryCipher
import com.xmitya.seafilesync.data.crypto.LibraryCrypto
import com.xmitya.seafilesync.data.crypto.WrongLibraryPasswordException
import com.xmitya.seafilesync.data.prefs.Account
import com.xmitya.seafilesync.data.prefs.TokenCipher
import android.util.Log
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
    /** Protects the stored library password; the same Keystore key as the account token. */
    private val cipher: TokenCipher,
    private val deviceName: String = "Android",
    private val clientVersion: String = "1.0",
    private val treeBuilder: LocalTreeBuilder = LocalTreeBuilder(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private class Session(val api: SeafileApi, val seafHttp: SeafHttpApi) {
        val treeReader = RemoteTreeReader(seafHttp)
        val downloader = Downloader(seafHttp)
        val uploader = Uploader(seafHttp)
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
    suspend fun enable(
        account: Account,
        repoId: String,
        name: String,
        isWritable: Boolean,
        /** Required for an encrypted library; verified against the server's magic before storing. */
        password: String? = null,
    ) {
        val localPath = File(account.syncRoot, name).path
        File(localPath).mkdirs()

        val info = apiFor(account.serverUrl).downloadInfo(account.token, repoId)
        if (info.isEncrypted) {
            val given = password ?: throw WrongLibraryPasswordException()
            // Checked locally against the magic the server already published, so the password
            // itself never leaves the device.
            if (!LibraryCrypto.verifyPassword(repoId, given, info.encVersion, info.salt, info.magic)) {
                throw WrongLibraryPasswordException()
            }
        }

        repos.upsert(
            SyncedRepoEntity(
                repoId = repoId,
                name = name,
                localPath = localPath,
                isWritable = isWritable,
                syncToken = info.token,
                encVersion = if (info.isEncrypted) info.encVersion else 0,
                randomKey = info.randomKey,
                encSalt = info.salt,
                encryptedPassword = password?.takeIf { info.isEncrypted }?.let(cipher::encrypt),
            )
        )
    }

    /**
     * Builds the block cipher for a library, or null when it is not encrypted.
     *
     * A missing or unreadable stored password is a hard failure rather than a silent skip:
     * carrying on would upload plaintext into a library the user chose to encrypt.
     */
    private fun cipherFor(repo: SyncedRepoEntity): LibraryCipher? {
        if (!repo.isEncrypted) return null
        val password = repo.encryptedPassword?.let { runCatching { cipher.decrypt(it) }.getOrNull() }
            ?: throw WrongLibraryPasswordException()
        return LibraryCipher.forLibrary(password, repo.randomKey, repo.encVersion, repo.encSalt)
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

        // Repos whose head moved go first; the rest are still visited, because a local-only
        // change produces no remote movement and would otherwise never be noticed.
        val (moved, unchanged) = tracked.partition { heads[it.repoId] != it.lastSyncedCommitId }
        return moved + unchanged
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
            Log.w(TAG, "Sync of ${repo.name} failed", failure)
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
            // The server has not moved, but the user may have. Local changes have to be looked
            // for here as well, or an edit made while nobody else touched the library would sit
            // on the device forever.
            val commit = session.seafHttp.commit(token, repo.repoId, headCommitId)
            val pushed = pushLocalChanges(
                session, account, repo, token, File(repo.localPath), headCommitId, commit.rootId,
            )
            repos.updateStatus(repo.repoId, SyncedRepoEntity.STATUS_IDLE)
            return if (pushed == null) {
                SyncOutcome.UpToDate(repo.repoId)
            } else {
                SyncOutcome.Synced(repo.repoId, pushed, 0)
            }
        }

        val commit = session.seafHttp.commit(token, repo.repoId, headCommitId)
        val snapshot = session.treeReader.read(token, repo.repoId, headCommitId, commit.rootId)
        val root = File(repo.localPath)
        val index = fileIndex.forRepo(repo.repoId)
        val recorded = index.associateBy { it.path }

        val libraryCipher = cipherFor(repo)

        val plan = SyncPlanner.plan(
            remote = snapshot,
            index = index,
            modifier = account.email,
            nowMillis = clock(),
            contentMatches = { path, file -> hasSameContent(File(root, path.trimStart('/')), file, libraryCipher) },
        ) { path ->
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

        guardAgainstMassDeletion(plan, index.size, root)

        var downloaded = 0
        val skipped = mutableListOf<String>()

        for (operation in plan.operations) {
            when (operation) {
                is SyncOperation.CreateDirectory -> File(root, operation.path.trimStart('/')).mkdirs()

                is SyncOperation.DownloadFile -> {
                    val target = File(root, operation.path.trimStart('/'))
                    updateProgress(repo.repoId) { it.copy(currentPath = operation.path) }
                    session.downloader.download(token, repo.repoId, operation.remote, target, libraryCipher) { bytes ->
                        updateProgress(repo.repoId) { it.copy(transferredBytes = it.transferredBytes + bytes) }
                    }
                    fileIndex.upsert(indexEntry(repo.repoId, operation.path, operation.remote, target))
                    downloaded++
                    updateProgress(repo.repoId) { it.copy(filesRemaining = (it.filesRemaining - 1).coerceAtLeast(0)) }
                }

                is SyncOperation.DeleteFile -> {
                    File(root, operation.path.trimStart('/')).delete()
                    fileIndex.delete(repo.repoId, operation.path)
                }

                is SyncOperation.ResolveConflict -> {
                    val target = File(root, operation.path.trimStart('/'))
                    val kept = File(root, operation.keepLocalAs.trimStart('/'))
                    // Move the local edit aside first. If the download then fails, the user still
                    // has their version under the conflict name rather than nothing at all.
                    if (target.exists() && !target.renameTo(kept)) {
                        skipped += "${operation.path}: could not set the local copy aside"
                        continue
                    }
                    updateProgress(repo.repoId) { it.copy(currentPath = operation.path) }
                    session.downloader.download(token, repo.repoId, operation.remote, target, libraryCipher) { bytes ->
                        updateProgress(repo.repoId) { it.copy(transferredBytes = it.transferredBytes + bytes) }
                    }
                    fileIndex.upsert(indexEntry(repo.repoId, operation.path, operation.remote, target))
                    downloaded++
                    skipped += "${operation.path}: kept your version as ${kept.name}"
                }

                is SyncOperation.AdoptLocal -> {
                    val target = File(root, operation.path.trimStart('/'))
                    fileIndex.upsert(indexEntry(repo.repoId, operation.path, operation.remote, target))
                }

                is SyncOperation.ConflictSkipped -> skipped += "${operation.path}: ${operation.reason}"
            }
        }

        _status.update { it.copy(skippedConflicts = it.skippedConflicts + (repo.repoId to skipped)) }

        // Only now is the working tree actually at this commit. Recording it earlier would make
        // an interrupted sync look complete and leave files permanently stale.
        repos.markSynced(repo.repoId, headCommitId, clock())

        val pushed = pushLocalChanges(session, account, repo, token, root, headCommitId, commit.rootId)
        return SyncOutcome.Synced(repo.repoId, pushed ?: headCommitId, downloaded)
    }

    /**
     * Publishes local changes, if there are any and the library allows writing.
     *
     * Whether anything changed is decided by rebuilding the tree and comparing its root id with
     * the server's. Because ids are content hashes, an identical tree produces an identical root,
     * so this is an exact answer rather than a heuristic -- and it costs a scan, not a transfer.
     */
    private suspend fun pushLocalChanges(
        session: Session,
        account: Account,
        repo: SyncedRepoEntity,
        token: String,
        root: File,
        remoteCommitId: String,
        remoteRootId: String,
    ): String? {
        if (!repo.isWritable) return null

        val tree = treeBuilder.build(root, account.email, cipher = cipherFor(repo))
        if (tree.rootId == remoteRootId) return null

        val previous = fileIndex.forRepo(repo.repoId).associateBy { it.path }
        val added = tree.files.keys.filterNot { it in previous }
        val modified = tree.files.filter { (path, entry) ->
            previous[path]?.fileId?.let { it != entry.fileId } == true
        }.keys.toList()
        val removed = previous.keys.filterNot { it in tree.files }

        val result = session.uploader.push(
            token = token,
            repoId = repo.repoId,
            repoName = repo.name,
            tree = tree,
            parentCommitId = remoteCommitId,
            creatorName = account.email,
            deviceName = deviceName,
            clientVersion = clientVersion,
            description = CommitDescription.describe(added, modified, removed),
            now = clock(),
        )

        // The server may have merged this with someone else's commit, so the head it now reports
        // can differ from what was just published. Re-reading it keeps the next delta correct.
        val head = session.seafHttp.headCommitId(token, repo.repoId).headCommitId ?: result.commitId

        fileIndex.deleteForRepo(repo.repoId)
        fileIndex.upsertAll(
            tree.files.values.map { entry ->
                val onDisk = File(root, entry.path.trimStart('/'))
                FileIndexEntity(
                    repoId = repo.repoId,
                    path = entry.path,
                    fileId = entry.fileId,
                    sizeBytes = entry.sizeBytes,
                    serverModifiedSeconds = entry.modifiedSeconds,
                    localSizeBytes = onDisk.length(),
                    localModifiedMillis = onDisk.lastModified(),
                    blockIds = entry.blocks.map { it.id },
                )
            }
        )
        repos.markSynced(repo.repoId, head, clock())
        return head
    }

    /**
     * Refuses to act on a plan that would wipe out most of a library.
     *
     * The dangerous case is not a user deleting files, it is the sync directory becoming
     * unreadable -- an unmounted SD card, a revoked permission, a path that now resolves
     * somewhere empty. Every file then looks locally deleted, and propagating that would delete
     * the library on the server for every other device too.
     */
    private fun guardAgainstMassDeletion(plan: SyncPlan, indexedCount: Int, root: File) {
        if (indexedCount < MASS_DELETE_FLOOR) return
        val deletions = plan.operations.count { it is SyncOperation.DeleteFile }
        if (deletions < indexedCount * MASS_DELETE_FRACTION) return
        if (!root.isDirectory) {
            throw IOException("Sync folder ${root.path} is not readable; refusing to delete $deletions files")
        }
    }

    /**
     * Whether the file on disk is byte-for-byte the server's version.
     *
     * Size is checked first because it rules out almost everything for free; only a size match
     * justifies hashing the file. The hash is the same computation the server used to name the
     * object, so equality here is exact rather than probabilistic.
     */
    private fun hasSameContent(file: File, remote: RemoteFile, cipher: LibraryCipher?): Boolean {
        if (!file.isFile || file.length() != remote.sizeBytes) return false
        return runCatching { treeBuilder.fileId(file, cipher) == remote.fileId }.getOrDefault(false)
    }

    private fun indexEntry(repoId: String, path: String, remote: RemoteFile, onDisk: File) =
        FileIndexEntity(
            repoId = repoId,
            path = path,
            fileId = remote.fileId,
            sizeBytes = remote.sizeBytes,
            serverModifiedSeconds = remote.modifiedSeconds,
            localSizeBytes = onDisk.length(),
            localModifiedMillis = onDisk.lastModified(),
            blockIds = remote.blockIds,
        )

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

    private companion object {
        const val TAG = "SeafileSync"

        /** Below this many tracked files the fraction check is noise rather than signal. */
        const val MASS_DELETE_FLOOR = 10
        const val MASS_DELETE_FRACTION = 0.5
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
