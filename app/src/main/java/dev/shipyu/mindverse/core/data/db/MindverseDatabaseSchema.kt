package dev.shipyu.mindverse.core.data.db

/**
 * Auxiliary index schema owned by code, not by Room entities (ticket: "sqlite-vec
 * (loadable extension) single-vector column per Memory"; "FTS5 index over
 * title+summary+tags+extracted text").
 *
 * Why code-owned:
 * - Room's @Fts3/@Fts4 cannot declare FTS5, and Android framework SQLite lacks FTS5 —
 *   we run Room on the androidx BundledSQLiteDriver, whose SQLite is compiled with
 *   SQLITE_ENABLE_FTS5 and SQLITE_ENABLE_LOAD_EXTENSION (verified in the androidx build
 *   scripts). The sqlite-vec extension (libvec0.so, vendored prebuilt loadable binaries
 *   of sqlite-vec v0.1.9, jniLibs + test resources) is loaded into the driver via
 *   BundledSQLiteDriver.addExtension.
 * - `memory_vec` is a vec0 virtual table living OUTSIDE Room's entity schema, so
 *   re-indexing to a new embedding model never needs a Room migration: drop and
 *   recreate this table (statements below are all IF NOT EXISTS / idempotent).
 *
 * CHUNK-READY (spec R4-3, ticket): v1 is 1 card = 1 chunk — one vec row per Memory keyed
 * by `memory_id` = Memory row UUID. The future chunk table fragments long text into
 * multiple vec rows reusing this exact table shape (each chunk row references its
 * Memory); no Room migration is needed for aux tables, only code evolution of this file.
 */
object MindverseDatabaseSchema {
    /** MiniLM-class on-device model output width (spec R4-2). */
    const val EMBEDDING_DIMENSION = 384

    /**
     * External-content FTS5 index over the Memory row. Rowid-mapped to `memory.rowid`;
     * trigger-synced (standard external-content pattern below). Tombstoned rows stay out
     * of results because all queries join `memory` and filter deleted = 0.
     */
    val statements: List<String> = listOf(
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(
            title, summary, tags, extracted_text,
            content='memory', content_rowid='rowid', tokenize='porter'
        )
        """.trimIndent(),
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS memory_vec USING vec0(
            memory_id TEXT PRIMARY KEY,
            embedding float[$EMBEDDING_DIMENSION] distance_metric=cosine
        )
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS memory_fts_ai AFTER INSERT ON memory BEGIN
            INSERT INTO memory_fts(rowid, title, summary, tags, extracted_text)
            VALUES (new.rowid, new.title, new.summary, new.tags, new.extracted_text);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS memory_fts_ad AFTER DELETE ON memory BEGIN
            INSERT INTO memory_fts(memory_fts, rowid, title, summary, tags, extracted_text)
            VALUES ('delete', old.rowid, old.title, old.summary, old.tags, old.extracted_text);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS memory_fts_au AFTER UPDATE ON memory BEGIN
            INSERT INTO memory_fts(memory_fts, rowid, title, summary, tags, extracted_text)
            VALUES ('delete', old.rowid, old.title, old.summary, old.tags, old.extracted_text);
            INSERT INTO memory_fts(rowid, title, summary, tags, extracted_text)
            VALUES (new.rowid, new.title, new.summary, new.tags, new.extracted_text);
        END
        """.trimIndent(),
    )

    /** Attaches (or repairs) the auxiliary schema; every statement is idempotent. */
    fun attach(connection: androidx.sqlite.SQLiteConnection) {
        statements.forEach { sql ->
            connection.prepare(sql).use { statement -> statement.step() }
        }
    }
}
