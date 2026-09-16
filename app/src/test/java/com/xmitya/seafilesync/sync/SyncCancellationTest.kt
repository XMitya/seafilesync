package com.xmitya.seafilesync.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xmitya.seafilesync.app.SyncLog
import com.xmitya.seafilesync.data.api.SeafHttpApi
import com.xmitya.seafilesync.data.api.SeafileApi
import com.xmitya.seafilesync.data.db.SyncDatabase
import com.xmitya.seafilesync.data.db.SyncedRepoEntity
import com.xmitya.seafilesync.data.prefs.Account
import com.xmitya.seafilesync.data.prefs.TokenCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Switching a library off used to leave the transfer running: the database row went away while
 * files kept arriving in a folder the user had just unsynced. These tests hold the server's
 * block response open, so the sync is provably still in flight when it is stopped.
 */
@RunWith(RobolectricTestRunner::class)
class SyncCancellationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val repoId = "79dc614e-d7f4-47a5-8bcc-10400a0a08cb"
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room
        .inMemoryDatabaseBuilder(context, SyncDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    private val blockRequested = java.util.concurrent.CountDownLatch(1)

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("download-info") -> json(fixture("download-info.json"))
                    path.endsWith("/commit/HEAD") -> json(fixture("commit-head.json"))
                    path.contains("/commit/") -> json(fixture("commit-object.json"))
                    path.contains("pack-fs") ->
                        MockResponse().setBody(Buffer().write(fixtureBytes("pack-fs.bin")))
                    path.contains("/block/") -> {
                        // Never answers, so the sync is unambiguously mid-transfer.
                        blockRequested.countDown()
                        MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    }
                    else -> json("{}")
                }
            }
        }
        start()
    }

    private val client = OkHttpClient
        .Builder()
        // Long on purpose: if cancellation did not work, the test would wait this out instead of
        // finishing, which is what distinguishes a real stop from a timeout.
        .readTimeout(1, TimeUnit.MINUTES)
        .build()

    private object PlainCipher : TokenCipher {
        override fun encrypt(plaintext: String) = plaintext

        override fun decrypt(ciphertext: String) = ciphertext
    }

    // Built in @Before, because the TemporaryFolder rule has not run when fields initialise.
    private lateinit var log: SyncLog
    private lateinit var engine: SyncEngine
    private lateinit var account: Account

    @Before
    fun setUp() {
        log = SyncLog(folder.newFolder("logs"))
        engine = SyncEngine(
            repos = database.syncedRepos(),
            fileIndex = database.fileIndex(),
            apiFor = { url, _ -> SeafileApi(url, client) },
            seafHttpFor = { url, _ -> SeafHttpApi(url, client) },
            cipher = PlainCipher,
            log = log,
        )
        account = Account(
            serverUrl = server.url("/").toString(),
            email = "test@example.com",
            token = "0".repeat(40),
            syncRoot = folder.newFolder("sync").path,
            deviceId = "a1b2c3d4e5f60718",
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        database.close()
    }

    private fun fixtureBytes(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")).use { it.readBytes() }

    private fun fixture(name: String) = fixtureBytes(name).decodeToString()

    private fun json(body: String) = MockResponse().setBody(body)

    private suspend fun trackedRepo(): SyncedRepoEntity {
        val localPath = File(account.syncRoot, "My Library").also { it.mkdirs() }.path
        val repo = SyncedRepoEntity(repoId = repoId, name = "My Library", localPath = localPath)
        database.syncedRepos().upsert(repo)
        return repo
    }

    @Test
    fun `stopping a library ends a transfer that is still running`() = runBlocking {
        val repo = trackedRepo()

        coroutineScope {
            val sync = async(Dispatchers.IO) { engine.sync(account, repo) }

            assertTrue(
                "the sync never reached the block download",
                blockRequested.await(20, TimeUnit.SECONDS),
            )

            engine.disable(repoId)

            // Without cancellation this waits for the read timeout, a minute away.
            val outcome = withTimeout(15_000) { sync.await() }
            assertTrue("unexpected outcome $outcome", outcome is SyncEngine.SyncOutcome.Stopped)
        }
    }

    @Test
    fun `stopping removes the library and its index`() = runBlocking {
        val repo = trackedRepo()

        coroutineScope {
            val sync = async(Dispatchers.IO) { engine.sync(account, repo) }
            blockRequested.await(20, TimeUnit.SECONDS)

            engine.disable(repoId)
            withTimeout(15_000) { sync.await() }
        }

        assertNull(database.syncedRepos().byId(repoId))
        assertEquals(emptyList<Any>(), database.fileIndex().forRepo(repoId))
    }

    @Test
    fun `a stopped sync is recorded rather than passing silently`() = runBlocking {
        val repo = trackedRepo()

        coroutineScope {
            val sync = async(Dispatchers.IO) { engine.sync(account, repo) }
            blockRequested.await(20, TimeUnit.SECONDS)
            engine.disable(repoId)
            withTimeout(15_000) { sync.await() }
        }

        delay(100)
        assertTrue(log.read(), log.read().contains("was stopped"))
    }
}
