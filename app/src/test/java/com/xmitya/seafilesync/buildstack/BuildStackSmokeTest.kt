package com.xmitya.seafilesync.buildstack

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the build stack itself rather than any product behaviour: Room's KSP code
 * generation, kotlinx.serialization, OkHttp + MockWebServer, coroutines-test and
 * Robolectric. Room codegen in particular is only exercised when annotations are
 * actually present, so a green build without this test would prove nothing.
 */
@RunWith(RobolectricTestRunner::class)
class BuildStackSmokeTest {

    @Entity
    data class Row(@PrimaryKey val id: Long, val name: String)

    @Dao
    interface RowDao {
        @Insert
        suspend fun insert(row: Row)

        @Query("SELECT * FROM Row WHERE id = :id")
        suspend fun byId(id: Long): Row?
    }

    @Database(entities = [Row::class], version = 1, exportSchema = false)
    abstract class Db : RoomDatabase() {
        abstract fun rows(): RowDao
    }

    @Serializable
    data class Payload(val token: String)

    @Test
    fun `room generates a working dao`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, Db::class.java).build()
        try {
            db.rows().insert(Row(id = 1, name = "seafile"))
            assertEquals("seafile", db.rows().byId(1)?.name)
        } finally {
            db.close()
        }
    }

    @Test
    fun `okhttp and kotlinx serialization round-trip a response`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"deadbeef"}"""))
        server.start()
        try {
            val request = Request.Builder().url(server.url("/api2/auth-token/")).build()
            val body = OkHttpClient().newCall(request).execute().use { it.body!!.string() }
            assertEquals("deadbeef", Json.decodeFromString<Payload>(body).token)
        } finally {
            server.shutdown()
        }
    }
}
