package com.xmitya.seafilesync.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The format belongs to Seafile, not to this app: the server writes these names itself when it
 * merges concurrent commits. Diverging would leave one library with two conventions.
 */
class ConflictNamingTest {

    private val utc = ZoneId.of("UTC")

    // 2026-09-07T15:04:05Z
    private val at = 1788793445000L

    @Test
    fun `matches the format the server writes`() {
        assertEquals(
            "report.doc (SFConflict test@example.com 2026-Sep-7-15-04-05).doc",
            ConflictNaming.conflictName("report.doc", "test@example.com", at, utc),
        )
    }

    @Test
    fun `a name without an extension gets no trailing dot`() {
        assertEquals(
            "README (SFConflict test@example.com 2026-Sep-7-15-04-05)",
            ConflictNaming.conflictName("README", "test@example.com", at, utc),
        )
    }

    @Test
    fun `the extension comes from the first dot, keeping the whole original name`() {
        // Looks wrong, and is reproduced deliberately: this is what the server produces, so
        // "fixing" it here would make the two disagree.
        assertEquals(
            "a.tar.gz (SFConflict u 2026-Sep-7-15-04-05).tar.gz",
            ConflictNaming.conflictName("a.tar.gz", "u", at, utc),
        )
    }

    @Test
    fun `an unknown modifier is left out rather than left blank`() {
        assertEquals(
            "a.txt (SFConflict 2026-Sep-7-15-04-05).txt",
            ConflictNaming.conflictName("a.txt", "", at, utc),
        )
    }

    @Test
    fun `the directory is preserved`() {
        assertEquals(
            "/docs/notes.md (SFConflict u 2026-Sep-7-15-04-05).md",
            ConflictNaming.conflictPath("/docs/notes.md", "u", at, utc),
        )
    }

    @Test
    fun `a conflict copy is recognisable so it is never resolved again`() {
        val name = ConflictNaming.conflictName("a.txt", "u", at, utc)
        assertTrue(ConflictNaming.isConflictCopy(name))
        assertTrue(!ConflictNaming.isConflictCopy("a.txt"))
    }
}
