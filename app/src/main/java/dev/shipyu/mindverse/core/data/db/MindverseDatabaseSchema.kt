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
 * CHUNK-READY (spec R4-3, ticket): v1 is 1 Memory = 1 chunk — one vec row per Memory keyed
 * by `memory_id` = Memory row UUID. The future chunk table fragments long text into
 * multiple vec rows reusing this exact table shape (each chunk row references its
 * Memory); no Room migration is needed for aux tables, only code evolution of this file.
 */
object MindverseDatabaseSchema {
    /** MiniLM-class on-device model output width (spec R4-2). */
    const val EMBEDDING_DIMENSION = 384

    /**
     * External-content FTS5 index over the Memory row. Rowid-mapped to `memory.rowid`;
     * trigger-synced (standard external-content pattern below).
     *
     * Tombstone hygiene (ADR-0003, local-first privacy): the UPDATE trigger deletes the
     * old FTS entry on every update and re-inserts only when `new.deleted = 0`, so a
     * tombstone (`UPDATE ... SET deleted = 1`) purges the Memory's term entries from the
     * index instead of re-indexing them. Verified at the MATCH level (term queries return
     * nothing even without the `deleted = 0` join); note FTS5 keeps enumerating the rowid
     * in full scans until segment merge, so repository queries must always MATCH + join
     * `memory` with `deleted = 0` as defense in depth.
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
        "DROP TRIGGER IF EXISTS memory_fts_ai",
        "DROP TRIGGER IF EXISTS memory_fts_ad",
        "DROP TRIGGER IF EXISTS memory_fts_au",
        """
        CREATE TRIGGER memory_fts_ai AFTER INSERT ON memory BEGIN
            INSERT INTO memory_fts(rowid, title, summary, tags, extracted_text)
            SELECT new.rowid, new.title, new.summary, new.tags, new.extracted_text
            WHERE new.deleted = 0;
        END
        """.trimIndent(),
        """
        CREATE TRIGGER memory_fts_ad AFTER DELETE ON memory BEGIN
            INSERT INTO memory_fts(memory_fts, rowid, title, summary, tags, extracted_text)
            VALUES ('delete', old.rowid, old.title, old.summary, old.tags, old.extracted_text);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER memory_fts_au AFTER UPDATE ON memory BEGIN
            INSERT INTO memory_fts(memory_fts, rowid, title, summary, tags, extracted_text)
            VALUES ('delete', old.rowid, old.title, old.summary, old.tags, old.extracted_text);
            INSERT INTO memory_fts(rowid, title, summary, tags, extracted_text)
            SELECT new.rowid, new.title, new.summary, new.tags, new.extracted_text
            WHERE new.deleted = 0;
        END
        """.trimIndent(),
    )

    /**
     * Attaches (or repairs/upgrades) the auxiliary schema; every statement is idempotent
     * (drops precede trigger creates so trigger-definition upgrades apply to existing
     * databases). Also purges FTS entries of already-tombstoned rows — repairs databases
     * created before the tombstone-purge trigger existed.
     */
    fun attach(connection: androidx.sqlite.SQLiteConnection) {
        statements.forEach { sql ->
            connection.prepare(sql).use { statement -> statement.step() }
        }
        purgeTombstonedFromFts(connection)
    }

    private fun purgeTombstonedFromFts(connection: androidx.sqlite.SQLiteConnection) {
        val tombstoned = connection.prepare(
            "SELECT rowid, title, summary, tags, extracted_text FROM memory WHERE deleted = 1",
        ).use { statement ->
            val rows = ArrayList<TombstonedRow>()
            while (statement.step()) {
                rows.add(
                    TombstonedRow(
                        rowId = statement.getLong(0),
                        title = if (statement.isNull(1)) null else statement.getText(1),
                        summary = if (statement.isNull(2)) null else statement.getText(2),
                        tags = if (statement.isNull(3)) null else statement.getText(3),
                        extractedText = if (statement.isNull(4)) null else statement.getText(4),
                    ),
                )
            }
            rows
        }
        tombstoned.forEach { row ->
            connection.prepare(
                "INSERT INTO memory_fts(memory_fts, rowid, title, summary, tags, extracted_text)" +
                    " VALUES ('delete', ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.bindLong(1, row.rowId)
                if (row.title == null) statement.bindNull(2) else statement.bindText(2, row.title)
                if (row.summary == null) statement.bindNull(3) else statement.bindText(3, row.summary)
                if (row.tags == null) statement.bindNull(4) else statement.bindText(4, row.tags)
                if (row.extractedText == null) statement.bindNull(5) else statement.bindText(5, row.extractedText)
                statement.step()
            }
        }
    }

    private data class TombstonedRow(
        val rowId: Long,
        val title: String?,
        val summary: String?,
        val tags: String?,
        val extractedText: String?,
    )
}
