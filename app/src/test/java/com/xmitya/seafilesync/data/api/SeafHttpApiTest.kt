package com.xmitya.seafilesync.data.api

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

class SeafHttpApiTest {

    private val server = MockWebServer().apply { start() }
    private val api = SeafHttpApi(server.url("/").toString(), OkHttpClient())

    private val repoId = "79dc614e-d7f4-47a5-8bcc-10400a0a08cb"
    private val token = "1".repeat(40)

    @After
    fun tearDown() = server.shutdown()

    private fun fixtureBytes(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "missing fixture $name"
        }.use { it.readBytes() }

    private fun fixture(name: String): String = fixtureBytes(name).decodeToString()

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    @Test
    fun `the sync token goes in both headers`() = runTest {
        enqueue(fixture("commit-head.json"))

        api.headCommitId(token, repoId)

        val request = server.takeRequest()
        // The desktop daemon sends both, and deployments differ in which one they read.
        assertEquals(token, request.getHeader("Seafile-Repo-Token"))
        assertEquals("Token $token", request.getHeader("Authorization"))
        assertEquals("/seafhttp/repo/$repoId/commit/HEAD", request.path)
    }

    @Test
    fun `head commit is parsed`() = runTest {
        enqueue(fixture("commit-head.json"))

        val head = api.headCommitId(token, repoId)
        assertEquals("b2be155598a7ef094045f343939daa410b3ff03f", head.headCommitId)
        assertTrue(!head.corrupted)
    }

    @Test
    fun `several heads come back in one round trip`() = runTest {
        enqueue(fixture("head-commits-multi.json"))

        val heads = api.headCommits(token, listOf(repoId))

        assertEquals("b2be155598a7ef094045f343939daa410b3ff03f", heads[repoId])
        assertEquals("POST", server.takeRequest().method)
    }

    @Test
    fun `commit object is parsed`() = runTest {
        enqueue(fixture("commit-object.json"))

        val commit = api.commit(token, repoId, "b2be155598a7ef094045f343939daa410b3ff03f")

        assertEquals("7a1d3cb8ecdd09c69892777550a88d3a90a51a61", commit.rootId)
        assertEquals("f5af326c6c7f54819659057ed42f30341423e340", commit.parentId)
        assertEquals(null, commit.secondParentId)
        assertEquals("""Added "seafile-tutorial.doc"""", commit.description)
    }

    @Test
    fun `fs-id-list asks for a delta when a client head is known`() = runTest {
        enqueue(fixture("fs-id-list.json"))

        val ids = api.fsIdList(token, repoId, serverHead = "b".repeat(40), clientHead = "c".repeat(40))

        assertEquals(2, ids.size)
        val query = server.takeRequest().requestUrl!!
        assertEquals("b".repeat(40), query.queryParameter("server-head"))
        // Without client-head the server enumerates the whole tree on every sync.
        assertEquals("c".repeat(40), query.queryParameter("client-head"))
    }

    @Test
    fun `pack-fs entries are inflated`() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(fixtureBytes("pack-fs.bin"))))

        val entries = api.packFs(token, repoId, listOf("b88ab96740ef53249b9d21fb3fa28050842266ba"))

        assertEquals(2, entries.size)
        assertTrue(
            entries
                .first()
                .json
                .decodeToString()
                .startsWith("{"),
        )
    }

    @Test
    fun `check-blocks reports what the server is missing`() = runTest {
        enqueue(fixture("check-blocks.json"))

        val missing = api.missingBlocks(token, repoId, listOf("a".repeat(40), "0".repeat(39) + "1"))

        // The response lists what must still be uploaded, not what is already there.
        assertEquals(listOf("0".repeat(39) + "1"), missing)
    }

    @Test
    fun `blocks are streamed rather than buffered`() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(1024) { it.toByte() })))

        val size = api.downloadBlock(token, repoId, "a".repeat(40)) { stream ->
            stream.readBytes().size
        }

        assertEquals(1024, size)
    }

    @Test
    fun `missing blocks on push are recoverable rather than fatal`() = runTest {
        enqueue("Failed to check blocks", code = 446)

        val failure = assertFailsWith<SeafileException.BlocksMissing> {
            api.updateHead(token, repoId, "a".repeat(40))
        }
        assertTrue(failure.body.contains("Failed to check blocks"))
    }

    @Test
    fun `a gc conflict is reported as retryable`() = runTest {
        enqueue("GC Conflict.", code = 409)

        assertFailsWith<SeafileException.GarbageCollectionConflict> {
            api.updateHead(token, repoId, "a".repeat(40))
        }
    }

    @Test
    fun `quota exhaustion is distinguishable from other failures`() = runTest {
        enqueue("Out of quota.", code = 443)

        assertFailsWith<SeafileException.OutOfQuota> { api.quotaCheck(token, repoId, delta = 1024) }
    }

    @Test
    fun `fileserver codes are not read as seahub codes`() = runTest {
        // 444 is "repo deleted" to the fileserver. Seahub has no such code, and its 44x range
        // means something else entirely, so the two mappings must stay separate.
        enqueue("", code = 444)

        assertFailsWith<SeafileException.LibraryDeleted> { api.headCommitId(token, repoId) }
    }

    @Test
    fun `uploading a block sends a PUT to the block path`() = runTest {
        enqueue("")

        api.uploadBlock(token, repoId, "a".repeat(40), ByteArray(16).toRequestBody())

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/seafhttp/repo/$repoId/block/${"a".repeat(40)}", request.path)
        assertEquals(16, request.bodySize)
    }

    @Test
    fun `routes carrying a query string keep their trailing slash`() = runTest {
        // The fileserver answers 404 for these paths without the slash even though its route
        // declares it optional. Download hides the problem because pack-fs takes no query
        // parameters, so only the endpoints below actually break.
        enqueue("")
        api.quotaCheck(token, repoId, delta = 1)
        assertTrue(server.takeRequest().path!!.startsWith("/seafhttp/repo/$repoId/quota-check/?"))

        enqueue("")
        api.permissionCheck(token, repoId, "upload")
        assertTrue(server.takeRequest().path!!.startsWith("/seafhttp/repo/$repoId/permission-check/?"))

        enqueue(fixture("fs-id-list.json"))
        api.fsIdList(token, repoId, serverHead = "b".repeat(40))
        assertTrue(server.takeRequest().path!!.startsWith("/seafhttp/repo/$repoId/fs-id-list/?"))

        enqueue("")
        api.updateHead(token, repoId, "d".repeat(40))
        assertTrue(server.takeRequest().path!!.startsWith("/seafhttp/repo/$repoId/commit/HEAD/?"))
    }

    @Test
    fun `a pushed commit carries the fields the server validates`() = runTest {
        // kotlinx omits properties still equal to their default, which silently dropped the
        // all-zero creator id -- the value a client actually writes -- and the server rejected
        // the commit for having a creator that is not 40 characters.
        enqueue("")

        api.putCommit(
            token,
            repoId,
            com.xmitya.seafilesync.data.api.model.CommitDto(
                commitId = "d".repeat(40),
                rootId = "e".repeat(40),
                repoId = repoId,
                creatorName = "test@example.com",
                description = "Added \"a.txt\"",
                ctime = 1_700_000_000,
                parentId = "f".repeat(40),
            ),
        )

        val body = server.takeRequest().body.readUtf8()
        assertTrue("creator must be present", body.contains(""""creator":"${"0".repeat(40)}""""))
        assertTrue(body.contains(""""repo_id":"$repoId""""))
        assertTrue(body.contains(""""root_id":"${"e".repeat(40)}""""))
        assertTrue(body.contains(""""version":1"""))
        // Nulls are dropped: the server parses several of these as plain ints.
        assertTrue("no nulls in the body", !body.contains("null"))
    }

    @Test
    fun `head update passes the commit as a query parameter`() = runTest {
        enqueue("")

        api.updateHead(token, repoId, "d".repeat(40))

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("d".repeat(40), request.requestUrl!!.queryParameter("head"))
    }
}
