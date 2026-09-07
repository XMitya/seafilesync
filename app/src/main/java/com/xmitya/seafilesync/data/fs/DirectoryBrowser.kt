package com.xmitya.seafilesync.data.fs

import android.os.Environment
import java.io.File

/**
 * Directory listing for the sync-folder picker.
 *
 * Deliberately plain java.io.File rather than the Storage Access Framework. The app holds
 * all-files access, so it can walk the tree directly, and the sync engine needs real paths
 * anyway: a tree Uri would have to be converted back into one for every file operation, and
 * FileObserver does not work through SAF at all.
 *
 * Kept free of Android UI types so it can be unit tested against a temporary directory.
 */
class DirectoryBrowser(private val roots: List<File> = defaultRoots()) {

    data class Entry(val file: File, val name: String, val childDirectoryCount: Int)

    data class Listing(
        val current: File,
        val parent: File?,
        val entries: List<Entry>,
        val isWritable: Boolean,
    )

    fun initialDirectory(): File = roots.firstOrNull { it.isDirectory } ?: File("/")

    fun list(directory: File): Listing {
        val children = directory.listFiles().orEmpty()
            .filter { it.isDirectory && !it.isHidden }
            .sortedBy { it.name.lowercase() }
            .map { Entry(it, it.name, it.listFiles().orEmpty().count { child -> child.isDirectory }) }

        return Listing(
            current = directory,
            // Stop at whichever root this path came from rather than exposing the whole
            // filesystem, most of which is unreadable anyway.
            parent = directory.parentFile?.takeIf { parent -> roots.none { it.path == directory.path } },
            entries = children,
            isWritable = directory.canWrite(),
        )
    }

    /**
     * Creating the folder is the only reliable writability check: canWrite can report true on
     * paths the kernel still refuses, particularly on emulated storage.
     */
    fun createDirectory(parent: File, name: String): Result<File> {
        val sanitized = name.trim()
        if (sanitized.isEmpty() || sanitized.contains('/') || sanitized == "." || sanitized == "..") {
            return Result.failure(IllegalArgumentException("Invalid folder name"))
        }
        val target = File(parent, sanitized)
        return when {
            target.isDirectory -> Result.success(target)
            target.mkdirs() -> Result.success(target)
            else -> Result.failure(IllegalStateException("Could not create ${target.path}"))
        }
    }

    fun canUseAsSyncRoot(directory: File): Boolean {
        if (!directory.isDirectory) return false
        val probe = File(directory, ".seafile-sync-write-probe")
        return runCatching {
            probe.createNewFile().also { if (it) probe.delete() } || probe.exists().also { probe.delete() }
        }.getOrDefault(false)
    }

    companion object {
        fun defaultRoots(): List<File> = listOfNotNull(
            Environment.getExternalStorageDirectory(),
        ).filter { it.isDirectory }
    }
}
