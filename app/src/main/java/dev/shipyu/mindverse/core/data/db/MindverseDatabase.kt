package dev.shipyu.mindverse.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Transactor
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File

/**
 * Local storage core (ADR-0003, spec R2-5): Room entities + code-owned FTS5 and vec0
 * auxiliary indexes, running on the androidx bundled SQLite driver so FTS5 and loadable
 * extensions (sqlite-vec) are available deterministically on-device and in JVM tests.
 *
 * Driver-mode notes (androidx.room 2.7.x):
 * - `openHelper`/`compileStatement`/`androidx.room.withTransaction` are legacy paths that
 *   do NOT work when a SQLiteDriver is configured; all raw access and transactions here go
 *   through `useWriterConnection`/`useReaderConnection` (suspend-DAO calls made inside the
 *   transaction block reuse the confined connection).
 * - Sync-ready invariants live on the entities (UUID ids, mutation timestamps, tombstones);
 *   the auxiliary indexes are rebuildable from rows, so they stay outside Room's schema
 *   version (no entity migration required when index shape evolves).
 */
@Database(
    entities = [CaptureEntity::class, MemoryEntity::class, MemoryItemEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(ListConverters::class)
abstract class MindverseDatabase : RoomDatabase() {
    abstract fun captureDao(): CaptureDao
    abstract fun memoryDao(): MemoryDao
    abstract fun memoryItemDao(): MemoryItemDao

    /**
     * Write one chunk-vector for [memoryId] (v1: exactly one row per Memory; CHUNK-READY
     * note in MindverseDatabaseSchema for the future chunk table).
     */
    suspend fun insertMemoryVector(memoryId: String, vector: FloatArray) {
        useWriterConnection { connection ->
            connection.usePrepared("INSERT INTO memory_vec(memory_id, embedding) VALUES (?, ?)") { statement ->
                statement.bindText(1, memoryId)
                statement.bindBlob(2, EmbeddingCodec.encode(vector))
                statement.step()
            }
        }
    }

    /** Remove the vector row(s) of a Memory (tombstone path; idempotent). */
    suspend fun deleteMemoryVector(memoryId: String) {
        useWriterConnection { connection ->
            connection.usePrepared("DELETE FROM memory_vec WHERE memory_id = ?") { statement ->
                statement.bindText(1, memoryId)
                statement.step()
            }
        }
    }

    /**
     * KNN cosine search over Memory vectors. Query vector must be [MindverseDatabaseSchema.EMBEDDING_DIMENSION]-wide.
     */
    suspend fun searchMemoryVectors(queryVector: FloatArray, k: Int): List<MemoryVectorHit> {
        require(queryVector.size == MindverseDatabaseSchema.EMBEDDING_DIMENSION) {
            "vector dimension mismatch: ${queryVector.size} != ${MindverseDatabaseSchema.EMBEDDING_DIMENSION}"
        }
        require(k > 0)
        return useReaderConnection { connection ->
            connection.usePrepared(
                "SELECT memory_id, distance FROM memory_vec WHERE embedding MATCH ? AND k = ?",
            ) { statement ->
                statement.bindBlob(1, EmbeddingCodec.encode(queryVector))
                statement.bindLong(2, k.toLong())
                val hits = ArrayList<MemoryVectorHit>()
                while (statement.step()) {
                    hits.add(MemoryVectorHit(statement.getText(0), statement.getDouble(1)))
                }
                hits
            }
        }
    }

    suspend fun vectorCount(): Int =
        useReaderConnection { connection ->
            connection.usePrepared("SELECT COUNT(*) FROM memory_vec") { statement ->
                if (statement.step()) statement.getLong(0).toInt() else 0
            }
        }

    /**
     * Run [block] in one immediate transaction on the writer connection; suspend DAO calls
     * inside reuse the confined connection. `useWriterConnection` refreshes the
     * invalidation tracker afterwards.
     */
    internal suspend fun <T> inTransaction(block: suspend () -> T): T =
        useWriterConnection { connection ->
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { block() }
        }

    internal fun simpleQuery(sql: String, args: Array<out Any?>): SupportSQLiteQuery =
        SimpleSQLiteQuery(sql, args)

    companion object {
        const val DATABASE_NAME = "mindverse.db"

        /** Device database with the sqlite-vec extension loaded from packaged jniLibs. */
        fun forDevice(context: Context): MindverseDatabase {
            val driver = BundledSQLiteDriver()
            driver.addExtension(vecExtensionPath(context), VecExtension.ENTRY_POINT)
            return Room.databaseBuilder(context, MindverseDatabase::class.java, DATABASE_NAME)
                .setDriver(driver)
                .addCallback(SchemaCallback())
                .build()
        }

        /**
         * Builder shared by device/tests; tests supply their own extension path
         * (see the test-side TestDatabaseFactory).
         */
        fun builder(context: Context, name: String, driver: BundledSQLiteDriver): RoomDatabase.Builder<MindverseDatabase> =
            Room.databaseBuilder(context, MindverseDatabase::class.java, name)
                .setDriver(driver)
                .addCallback(SchemaCallback())

        private fun vecExtensionPath(context: Context): String {
            val dir = context.applicationInfo.nativeLibraryDir
            val file = File(dir, VecExtension.LIB_FILE_NAME)
            check(file.exists()) {
                "sqlite-vec extension binary missing at $file — jniLibs packaging broken"
            }
            return file.absolutePath
        }
    }
}

/** sqlite-vec loadable-extension constants (vendored prebuilt binaries, v0.1.9). */
object VecExtension {
    /** jniLibs packaged name on-device; on JVM tests the path is resolved from resources. */
    const val LIB_FILE_NAME = "libvec0.so"

    /** Public symbol exported by the sqlite-vec loadable binary. */
    const val ENTRY_POINT = "sqlite3_vec_init"
}

/**
 * Creates + maintains the auxiliary indexes. onOpen attaches only when the FTS table is
 * missing (idempotent, covers external deletion/recovery paths); onCreate always runs it.
 * internal: reused by the test-side factory.
 */
internal class SchemaCallback : RoomDatabase.Callback() {
    override fun onCreate(connection: androidx.sqlite.SQLiteConnection) {
        MindverseDatabaseSchema.attach(connection)
    }

    override fun onOpen(connection: androidx.sqlite.SQLiteConnection) {
        val hasFts = connection.prepare(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'memory_fts'",
        ).use { statement ->
            statement.step() && statement.getLong(0) > 0
        }
        if (!hasFts) MindverseDatabaseSchema.attach(connection)
    }
}
