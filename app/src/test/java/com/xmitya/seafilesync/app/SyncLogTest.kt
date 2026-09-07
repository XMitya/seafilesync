package com.xmitya.seafilesync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Robolectric only because SyncLog also writes to android.util.Log. */
@RunWith(RobolectricTestRunner::class)
class SyncLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_700_000_000_000L

    private fun log(directory: File = folder.root) = SyncLog(directory) { now }

    private fun directorySize(directory: File): Long =
        directory.listFiles().orEmpty().sumOf { it.length() }

    @Test
    fun `messages are written with a timestamp and a level`() {
        val log = log()

        log.info("started")
        log.warn("something went wrong")

        val text = log.read()
        assertTrue(text, text.contains("I started"))
        assertTrue(text, text.contains("W something went wrong"))
    }

    @Test
    fun `a failure keeps its stack trace`() {
        val log = log()

        log.warn("upload failed", IllegalStateException("no quota"))

        val text = log.read()
        assertTrue(text.contains("no quota"))
        assertTrue("the trace is what makes a report useful", text.contains("SyncLogTest"))
    }

    @Test
    fun `the log stays bounded however much is written`() {
        // A sync client can log a line per file, so an unbounded log would eat the storage it is
        // meant to be syncing into.
        val log = log()

        repeat(20_000) { log.info("line $it with enough text to make this add up quickly") }

        val size = directorySize(folder.root)
        assertTrue("log grew to $size bytes", size <= 2 * 512 * 1024 + 4096)
    }

    @Test
    fun `rotation keeps the most recent lines`() {
        val log = log()

        repeat(20_000) { log.info("line $it") }
        log.info("the newest line")

        assertTrue(log.read().endsWith("the newest line\n"))
    }

    @Test
    fun `clearing removes both files`() {
        val log = log()
        repeat(20_000) { log.info("line $it with enough text to force a rotation") }

        log.clear()

        assertEquals("", log.read())
        assertEquals(0L, directorySize(folder.root))
    }

    @Test
    fun `an unwritable directory costs the line and nothing else`() {
        // Logging must never be the reason a sync fails.
        val blocked = folder.newFile("not-a-directory")
        val log = log(blocked)

        log.warn("this cannot be written anywhere")

        assertEquals("", log.read())
    }

    @Test
    fun `export writes the whole log where it can be shared`() {
        val log = log()
        log.info("first")
        log.info("second")

        val exported = log.exportTo(folder.newFolder("cache"))

        assertTrue(exported.readText().contains("first"))
        assertTrue(exported.readText().contains("second"))
    }
}
