package com.xmitya.seafilesync.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * An install that already syncs libraries carries a version 1 database, and Room validates the
 * schema it finds against the one it expects on every open. A migration that does not produce
 * exactly that schema does not degrade anything -- it throws on open, which for this app means a
 * crash on launch for everyone who upgrades.
 */
@RunWith(RobolectricTestRunner::class)
class SyncDatabaseMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-test.db"
    private val file: File get() = context.getDatabasePath(name)

    /** The schema exactly as version 1 shipped it, from schemas/1.json. */
    private val version1 = listOf(
        "CREATE TABLE IF NOT EXISTS `synced_repo` (`repoId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
            "`localPath` TEXT NOT NULL, `lastSyncedCommitId` TEXT, `syncToken` TEXT, `status` TEXT NOT NULL, " +
            "`errorMessage` TEXT, `isWritable` INTEGER NOT NULL, `lastSyncedAtMillis` INTEGER NOT NULL, " +
            "`encVersion` INTEGER NOT NULL, `randomKey` TEXT NOT NULL, `encSalt` TEXT NOT NULL, " +
            "`encryptedPassword` TEXT, PRIMARY KEY(`repoId`))",
        "CREATE TABLE IF NOT EXISTS `file_index` (`repoId` TEXT NOT NULL, `path` TEXT NOT NULL, " +
            "`fileId` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `serverModifiedSeconds` INTEGER NOT NULL, " +
            "`localSizeBytes` INTEGER NOT NULL, `localModifiedMillis` INTEGER NOT NULL, " +
            "`blockIds` TEXT NOT NULL, PRIMARY KEY(`repoId`, `path`))",
        "CREATE INDEX IF NOT EXISTS `index_file_index_repoId` ON `file_index` (`repoId`)",
        "CREATE TABLE IF NOT EXISTS `pending_block` (`repoId` TEXT NOT NULL, `path` TEXT NOT NULL, " +
            "`blockIndex` INTEGER NOT NULL, `blockId` TEXT NOT NULL, PRIMARY KEY(`repoId`, `path`, `blockIndex`))",
        "CREATE INDEX IF NOT EXISTS `index_pending_block_repoId` ON `pending_block` (`repoId`)",
        // Room checks this table before it trusts anything else, so the old identity hash has to
        // be there or the open fails for a reason that has nothing to do with the migration.
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
        "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
            "VALUES(42, 'b2898b9c16cad15d93cb134bd19f0d00')",
    )

    @Before
    fun createVersion1Database() {
        file.delete()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = version1.forEach(db::execSQL)

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        helper.writableDatabase.execSQL(
            "INSERT INTO file_index VALUES ('repo-1', '/notes.txt', 'abc', 10, 1788969829, 10, 1788969829000, 'b1,b2')",
        )
        helper.close()
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun `an existing install upgrades without losing its file index`() = runBlocking {
        val database = Room
            .databaseBuilder(context, SyncDatabase::class.java, name)
            .addMigrations(SyncDatabase.MIGRATION_1_2)
            .build()

        // Nothing happens until the database is actually opened, so the query is the test.
        val rows = database.fileIndex().forRepo("repo-1")
        database.close()

        assertEquals(1, rows.size)
        assertEquals("/notes.txt", rows.single().path)
        assertEquals(listOf("b1", "b2"), rows.single().blockIds)
        // Nothing knew who last wrote it, which reads as "ask the server next time".
        assertEquals("", rows.single().modifier)
    }
}
