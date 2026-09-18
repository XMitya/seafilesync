package com.xmitya.seafilesync.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A dirent's modifier is part of its directory's id, so who a file is attributed to decides
 * whether a rebuilt tree still matches the server's.
 *
 * Claiming every file for the signed-in account made an untouched library produce a root id the
 * server had never seen, so every pass published a commit that changed nothing -- and a commit is
 * exactly what rewrites a library's settings.
 */
class LocalTreeModifierTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val builder = LocalTreeBuilder()
    private val serverSide = "65b78c6ba9894a9c9952663c7d11811f@auth.local"
    private val thisDevice = "test@example.com"

    private lateinit var root: File

    private fun library(): File {
        root = folder.newFolder("library")
        File(root, "notes.txt").writeText("something the server already has")
        File(root, "nested").mkdirs()
        File(root, "nested/deeper.txt").writeText("and so does this")
        return root
    }

    /** The tree as the server holds it: every file attributed to whoever uploaded it. */
    private fun asServerHasIt() = builder.build(library(), serverSide).rootId

    @Test
    fun `a file nobody touched is left attributed to the server's modifier`() {
        val server = asServerHasIt()

        val rebuilt = builder.build(root, thisDevice) { _, _ -> serverSide }.rootId

        assertEquals("an untouched library rebuilt to a different tree", server, rebuilt)
    }

    @Test
    fun `claiming every file for this device changes the tree`() {
        val server = asServerHasIt()

        val rebuilt = builder.build(root, thisDevice).rootId

        assertNotEquals("the modifier no longer affects the tree; this test is obsolete", server, rebuilt)
    }

    @Test
    fun `a file this device actually changed is attributed to this device`() {
        asServerHasIt()

        // Nothing matches the recorded content id, which is what an edited file looks like.
        val rebuilt = builder.build(root, thisDevice) { _, fileId ->
            serverSide.takeIf { fileId == "0".repeat(40) }
        }
        val entry = checkNotNull(rebuilt.files["/notes.txt"])

        assertEquals(thisDevice, entry.modifier)
        assertEquals(builder.build(root, thisDevice).rootId, rebuilt.rootId)
    }

    @Test
    fun `the modifier that was written is reported back on the entry`() {
        asServerHasIt()

        val rebuilt = builder.build(root, thisDevice) { _, _ -> serverSide }

        assertEquals(serverSide, rebuilt.files.getValue("/notes.txt").modifier)
        assertEquals(serverSide, rebuilt.files.getValue("/nested/deeper.txt").modifier)
    }
}
