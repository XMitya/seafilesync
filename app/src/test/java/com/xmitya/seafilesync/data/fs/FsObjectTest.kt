package com.xmitya.seafilesync.data.fs

import com.xmitya.seafilesync.data.api.ObjectPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These are the tests the upload path depends on. If an fs object's id is computed differently
 * from the server's, uploads produce a tree whose ids nobody else agrees with, and nothing
 * reports an error. So the reference data here is a real `pack-fs` response from a Seafile
 * 11.0.13 server, and the ids it carries are the ids the server itself assigned.
 */
class FsObjectTest {

    private val pack = ObjectPack.read(fixture("pack-fs.bin").inputStream())

    private val fileEntry = pack.single { it.id == "b88ab96740ef53249b9d21fb3fa28050842266ba" }
    private val dirEntry = pack.single { it.id == "7a1d3cb8ecdd09c69892777550a88d3a90a51a61" }

    @Test
    fun `recomputed ids match the ids the server assigned`() {
        for (entry in pack) {
            val parsed = FsObject.parse(entry.json)
            assertEquals("id mismatch for ${entry.id}", entry.id, parsed.id)
        }
        assertEquals(2, pack.size)
    }

    @Test
    fun `canonical json reproduces the server bytes exactly`() {
        for (entry in pack) {
            val reserialized = SeafJson.canonicalize(FsObject.parse(entry.json).toJson())
            assertEquals(entry.json.decodeToString(), reserialized)
        }
    }

    @Test
    fun `file object exposes its blocks`() {
        val file = FsObject.parse(fileEntry.json) as SeafFile
        assertEquals(listOf("8663a70ef30a5987b440a621483af2044bae1e0a"), file.blockIds)
        assertEquals(300544L, file.size)
        assertEquals(FS_OBJECT_VERSION, file.version)
    }

    @Test
    fun `directory entry keeps the fields the server wrote`() {
        val dir = FsObject.parse(dirEntry.json) as SeafDir
        val entry = dir.entries.single()
        assertEquals("seafile-tutorial.doc", entry.name)
        assertEquals("b88ab96740ef53249b9d21fb3fa28050842266ba", entry.id)
        assertEquals(MODE_FILE, entry.mode)
        assertEquals(300544L, entry.size)
        assertEquals(1611385958L, entry.mtime)
        assertTrue(entry.isFile)
    }

    @Test
    fun `subdirectory entries omit modifier and size`() {
        // The server writes those two fields only for regular files. Emitting them for a
        // directory would silently change the parent's id.
        val json = SeafDirent.directory(id = "a".repeat(40), name = "docs", mtime = 42).toJson()
        assertEquals(
            """{"id": "${"a".repeat(40)}", "mode": 16384, "mtime": 42, "name": "docs"}""",
            SeafJson.canonicalize(json),
        )
    }

    @Test
    fun `dirents sort descending by utf-8 bytes`() {
        val entries = listOf("apple", "Banana", "cherry", "äpfel").map {
            SeafDirent.file(id = "b".repeat(40), name = it, mtime = 0, size = 0, modifier = "u")
        }
        val sorted = SeafDir(entries).sorted().entries.map { it.name }
        // Raw byte order: multi-byte UTF-8 outranks ASCII, and uppercase sorts below lowercase.
        assertEquals(listOf("äpfel", "cherry", "apple", "Banana"), sorted)
    }

    @Test
    fun `object pack survives a round trip`() {
        val reencoded = ObjectPack.read(ObjectPack.encode(pack).inputStream())
        assertEquals(pack, reencoded)
    }

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "missing fixture $name"
        }.use { it.readBytes() }
}
