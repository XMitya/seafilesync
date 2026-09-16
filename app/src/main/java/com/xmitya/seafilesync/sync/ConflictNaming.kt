package com.xmitya.seafilesync.sync

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Names for the copy kept when the same file changed in two places.
 *
 * The format is not ours to choose: the Seafile server generates these itself when it merges
 * concurrent commits, and the desktop client generates them locally. Producing a different shape
 * would leave a library with two competing conventions.
 *
 * Reproduced from `genConflictPath` in the server's merge.go, including the part that looks like
 * a mistake and is not ours to fix: the extension is taken from the **first** dot in the name
 * while the whole original name is kept as the base, so `a.tar.gz` becomes
 * `a.tar.gz (SFConflict user ...).tar.gz`.
 */
object ConflictNaming {

    private val FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MMM-d-HH-mm-ss", Locale.ENGLISH)

    fun conflictName(
        originalName: String,
        modifier: String,
        atMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val timestamp = FORMAT.format(Instant.ofEpochMilli(atMillis).atZone(zone))
        val marker = if (modifier.isEmpty()) {
            "(SFConflict $timestamp)"
        } else {
            "(SFConflict $modifier $timestamp)"
        }

        val dot = originalName.indexOf('.')
        return if (dot < 0) {
            "$originalName $marker"
        } else {
            "$originalName $marker.${originalName.substring(dot + 1)}"
        }
    }

    /** Same rule applied to a library-relative path, keeping the directory it lives in. */
    fun conflictPath(path: String, modifier: String, atMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val directory = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        return "$directory/${conflictName(name, modifier, atMillis, zone)}"
    }

    /** Conflict copies must never be treated as ordinary files that themselves need resolving. */
    fun isConflictCopy(name: String): Boolean = name.contains("(SFConflict ")
}
