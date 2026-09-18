package com.xmitya.seafilesync.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

/** Block id lists are fixed-width hex, so joining them is cheaper than a second table. */
class ObjectIdListConverter {

    @TypeConverter
    fun fromList(ids: List<String>): String = ids.joinToString(SEPARATOR)

    @TypeConverter
    fun toList(stored: String): List<String> =
        if (stored.isEmpty()) emptyList() else stored.split(SEPARATOR)

    private companion object {
        const val SEPARATOR = ","
    }
}

@Dao
interface SyncedRepoDao {

    @Query("SELECT * FROM synced_repo")
    fun observeAll(): Flow<List<SyncedRepoEntity>>

    @Query("SELECT * FROM synced_repo")
    suspend fun all(): List<SyncedRepoEntity>

    @Query("SELECT * FROM synced_repo WHERE repoId = :repoId")
    suspend fun byId(repoId: String): SyncedRepoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(repo: SyncedRepoEntity)

    @Query("UPDATE synced_repo SET status = :status, errorMessage = :error WHERE repoId = :repoId")
    suspend fun updateStatus(repoId: String, status: String, error: String? = null)

    @Query(
        "UPDATE synced_repo SET lastSyncedCommitId = :commitId, lastSyncedAtMillis = :atMillis, " +
            "status = '${SyncedRepoEntity.STATUS_IDLE}', errorMessage = NULL WHERE repoId = :repoId",
    )
    suspend fun markSynced(repoId: String, commitId: String, atMillis: Long)

    @Query("UPDATE synced_repo SET syncToken = :token WHERE repoId = :repoId")
    suspend fun updateToken(repoId: String, token: String)

    @Query("UPDATE synced_repo SET encryptedPassword = NULL WHERE repoId = :repoId")
    suspend fun forgetPassword(repoId: String)

    @Query("DELETE FROM synced_repo WHERE repoId = :repoId")
    suspend fun delete(repoId: String)

    @Query("DELETE FROM synced_repo")
    suspend fun deleteAll()
}

@Dao
interface FileIndexDao {

    @Query("SELECT * FROM file_index WHERE repoId = :repoId")
    suspend fun forRepo(repoId: String): List<FileIndexEntity>

    @Query("SELECT * FROM file_index WHERE repoId = :repoId AND path = :path")
    suspend fun byPath(repoId: String, path: String): FileIndexEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: FileIndexEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<FileIndexEntity>)

    @Query("DELETE FROM file_index WHERE repoId = :repoId AND path = :path")
    suspend fun delete(repoId: String, path: String)

    @Query("DELETE FROM file_index WHERE repoId = :repoId")
    suspend fun deleteForRepo(repoId: String)
}

@Dao
interface PendingBlockDao {

    @Query("SELECT * FROM pending_block WHERE repoId = :repoId AND path = :path ORDER BY blockIndex")
    suspend fun forFile(repoId: String, path: String): List<PendingBlockEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(blocks: List<PendingBlockEntity>)

    @Query("DELETE FROM pending_block WHERE repoId = :repoId AND path = :path AND blockIndex = :index")
    suspend fun clear(repoId: String, path: String, index: Int)

    @Query("DELETE FROM pending_block WHERE repoId = :repoId AND path = :path")
    suspend fun clearFile(repoId: String, path: String)

    @Query("DELETE FROM pending_block WHERE repoId = :repoId")
    suspend fun clearRepo(repoId: String)
}

@Database(
    entities = [SyncedRepoEntity::class, FileIndexEntity::class, PendingBlockEntity::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(ObjectIdListConverter::class)
abstract class SyncDatabase : RoomDatabase() {

    abstract fun syncedRepos(): SyncedRepoDao

    abstract fun fileIndex(): FileIndexDao

    abstract fun pendingBlocks(): PendingBlockDao

    companion object {
        /**
         * Adds the server's dirent modifier to the file index. Empty for rows written before
         * this, which reads as "not known" and falls back to the signed-in account, so the worst
         * an upgraded install sees is the one spurious commit it would have made anyway.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE file_index ADD COLUMN modifier TEXT NOT NULL DEFAULT ''")
            }
        }

        fun open(context: Context): SyncDatabase =
            Room
                .databaseBuilder(context, SyncDatabase::class.java, "sync.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
