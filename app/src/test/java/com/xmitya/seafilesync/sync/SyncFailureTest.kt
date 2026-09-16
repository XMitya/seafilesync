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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A transfer that fails must come back as an outcome, not as an exception in whoever asked for
 * the sync. The service's loop is a plain coroutine with no handler: a failure that escaped it
 * took the whole process down, and because the loop restarts with the app, a library that could
 * not be reassembled crashed the app on every launch.
 */
@RunWith(RobolectricTestRunner::class)
class SyncFailureTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val repoId = "79dc614e-d7f4-47a5-8bcc-10400a0a08cb"
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room
        .inMemoryDatabaseBuilder(context, SyncDatabase::class.java)
        .allowMainThreadQueries()
        .build()

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
                    // Whatever this is, it is not the block whose id was asked for, so the
                    // integrity check in the downloader rejects it.
                    path.contains("/block/") -> MockResponse().setBody("not the block that was asked for")
                    else -> json("{}")
                }
            }
        }
        start()
    }

    private val client = OkHttpClient()

    private object PlainCipher : TokenCipher {
        override fun encrypt(plaintext: String) = plaintext

        override fun decrypt(ciphertext: String) = ciphertext
    }

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
    fun `a failed transfer is an outcome, not the caller's problem`() = runBlocking {
        val repo = trackedRepo()
        val callerCarriedOn = AtomicBoolean(false)

        // Stands in for the service's sync loop: it expects to keep running after a failure.
        val caller = launch(Dispatchers.IO) {
            val outcome = engine.sync(account, repo)
            assertTrue("unexpected outcome $outcome", outcome is SyncEngine.SyncOutcome.Failed)
            // A suspension point: this throws if the failure cancelled the job we are in.
            delay(1)
            callerCarriedOn.set(true)
        }
        caller.join()

        assertTrue("the failed transfer cancelled the coroutine that asked for the sync", callerCarriedOn.get())
        assertTrue(caller.isCompleted && !caller.isCancelled)
    }

    @Test
    fun `a failed transfer is recorded against the library`() = runBlocking {
        val repo = trackedRepo()

        val outcome = engine.sync(account, repo)

        val failed = outcome as SyncEngine.SyncOutcome.Failed
        assertTrue(failed.reason, failed.reason.contains("corrupted"))
        val stored = database.syncedRepos().byId(repoId)
        assertEquals(SyncedRepoEntity.STATUS_ERROR, stored?.status)
        assertEquals(failed.reason, stored?.errorMessage)
        delay(100)
        assertTrue(log.read(), log.read().contains("Sync of My Library failed"))
    }
}
