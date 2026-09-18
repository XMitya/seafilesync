package com.xmitya.seafilesync.sync

import com.xmitya.seafilesync.data.api.SeafHttpApi
import com.xmitya.seafilesync.data.api.model.CommitDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A library's encryption settings live on its head commit, not on a record of their own: the
 * server rereads them from whatever commit is published as HEAD. A commit pushed without them
 * therefore does not just omit them, it turns the library unencrypted -- while every block in it
 * stays ciphertext, so the web UI can no longer decode a single file and this client stops asking
 * for the password.
 */
class UploaderCommitTest {

    private val repoId = "7c54e5d8-b140-4e8f-a5c9-ab1d67b47b63"
    private val pushedCommits = ConcurrentLinkedQueue<String>()

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (request.method == "PUT" && path.contains("/commit/") && !path.contains("HEAD")) {
                    pushedCommits += request.body.readUtf8()
                }
                return when {
                    // Nothing is missing, so no blocks or objects are transferred.
                    path.contains("check-blocks") || path.contains("check-fs") -> MockResponse().setBody("[]")
                    else -> MockResponse().setBody("{}")
                }
            }
        }
        start()
    }

    private val uploader = Uploader(SeafHttpApi(server.url("/").toString(), OkHttpClient()))

    /** An encrypted library's head, as the fileserver returns it. */
    private val encryptedParent = CommitDto(
        commitId = "196a1e8adda8cf6e0d5a3f2b7c4e9a0f1d2b3c4e",
        rootId = "0".repeat(40),
        repoId = repoId,
        repoName = "secrets",
        repoDesc = "kept private",
        version = 1,
        encrypted = "true",
        encVersion = 2,
        magic = "c1e9b3773334b8e4f65a1d2c3b4a5968778899aabbccddeeff00112233445566",
        randomKey = "60bc048dd516db405e52" + "0".repeat(76),
    )

    @After
    fun tearDown() = server.shutdown()

    private fun push(parent: CommitDto) = runBlocking {
        uploader.push(
            token = "0".repeat(40),
            repoId = repoId,
            repoName = "secrets",
            tree = LocalTree(rootId = "1".repeat(40), objects = emptyMap(), files = emptyMap(), blocks = emptyMap()),
            parent = parent,
            creatorName = "test@example.com",
            deviceName = "Android",
            clientVersion = "1.0",
            description = "Updated files",
            now = 1_789_000_000_000,
        )
        Json.parseToJsonElement(checkNotNull(pushedCommits.poll()) { "no commit was pushed" }).jsonObject
    }

    private fun field(commit: kotlinx.serialization.json.JsonObject, name: String) =
        commit[name]?.jsonPrimitive?.content

    @Test
    fun `a commit on an encrypted library keeps it encrypted`() {
        val commit = push(encryptedParent)

        assertEquals("true", field(commit, "encrypted"))
        assertEquals("2", field(commit, "enc_version"))
        assertEquals(encryptedParent.magic, field(commit, "magic"))
        assertEquals(encryptedParent.randomKey, field(commit, "key"))
    }

    @Test
    fun `the library's own description survives a push`() {
        val commit = push(encryptedParent)

        assertEquals("kept private", field(commit, "repo_desc"))
        assertEquals("secrets", field(commit, "repo_name"))
    }

    @Test
    fun `a plain library is not made to look encrypted`() {
        val commit = push(encryptedParent.copy(encrypted = null, encVersion = null, magic = null, randomKey = null))

        assertTrue("encrypted=${field(commit, "encrypted")}", commit["encrypted"] == null)
        assertTrue("magic=${field(commit, "magic")}", commit["magic"] == null)
    }

    @Test
    fun `the new commit points at the parent`() {
        val commit = push(encryptedParent)

        assertEquals(encryptedParent.commitId, field(commit, "parent_id"))
        assertEquals("1".repeat(40), field(commit, "root_id"))
    }
}
