package com.jeevavibeapp.spendwise.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.jeevavibeapp.spendwise.core.ParseMisses
import kotlinx.coroutines.flow.Flow

/**
 * A bank message the parser could not read — as its shape only.
 *
 * There is no body column, and that is the design rather than an omission:
 * the raw message is the most sensitive text on the phone, and the backup
 * deliberately carries none. [shape] is [ParseMisses.redact]'s output, made
 * before this row exists. [bodyHash] lets a rescan recognise a message it has
 * already logged; it is never shown and never exported.
 *
 * [cleared] is a soft delete for the same reason fraud alerts keep their
 * dismissed rows: the launch scan meets the same ninety days of inbox on
 * every launch, and a hard delete would have every cleared message back the
 * next time the app opened.
 */
@Entity(
    tableName = "parse_misses",
    indices = [Index(value = ["bodyHash"], unique = true)],
)
data class ParseMissEntity(
    @PrimaryKey val id: String,
    val bodyHash: String,
    /** The DLT entity ("HDFCBK"); null when pasted. */
    val sender: String? = null,
    val source: String,                     // sms | paste
    val shape: String,
    val reason: String,
    /** Rows from another parser are stale: that parser may read them now. */
    val parserVersion: String,
    val seenCount: Int = 1,
    /** Epoch millis, when the message reached the phone. */
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val cleared: Boolean = false,
)

/**
 * Separate from [SpendDao] because nothing here is the ledger. Every write is
 * bounded by [ParseMisses.MAX_ROWS] and [ParseMisses.MAX_AGE_DAYS], so the
 * log cannot grow with the inbox that feeds it.
 */
@Dao
abstract class ParseMissDao {

    @Query("SELECT * FROM parse_misses WHERE bodyHash = :hash LIMIT 1")
    abstract suspend fun byHash(hash: String): ParseMissEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(row: ParseMissEntity): Long

    /** The same message arriving again, live. A clear hid what had been
     *  seen; a fresh arrival is news, so it shows again. */
    @Query("""
        UPDATE parse_misses
        SET seenCount = seenCount + 1, lastSeenAt = MAX(lastSeenAt, :at), cleared = 0
        WHERE id = :id""")
    abstract suspend fun seenAgain(id: String, at: Long)

    @Query("SELECT COUNT(*) FROM parse_misses")
    abstract suspend fun count(): Int

    @Query("SELECT MIN(lastSeenAt) FROM parse_misses")
    abstract suspend fun oldest(): Long?

    @Query("DELETE FROM parse_misses WHERE parserVersion != :version")
    abstract suspend fun dropOtherParsers(version: String): Int

    @Query("DELETE FROM parse_misses WHERE lastSeenAt < :before")
    abstract suspend fun dropSeenBefore(before: Long): Int

    @Query("""
        DELETE FROM parse_misses WHERE id NOT IN (
            SELECT id FROM parse_misses ORDER BY lastSeenAt DESC, id DESC LIMIT :keep)""")
    abstract suspend fun keepNewest(keep: Int): Int

    /**
     * Log one miss, bounded, in one transaction.
     *
     * A rescan ([live] false) finding a row already there changes nothing —
     * the same rule the capture path keeps, or every launch would add one to
     * the count of a message that arrived once. Pruning runs only on an
     * insert, which is the only thing that grows the table.
     *
     * @return true when the message is in the log after this call.
     */
    @Transaction
    open suspend fun record(row: ParseMissEntity, live: Boolean, keep: Int, before: Long): Boolean {
        val existing = byHash(row.bodyHash)
        if (existing != null) {
            if (live) seenAgain(existing.id, row.lastSeenAt)
            return true
        }
        dropOtherParsers(row.parserVersion)
        dropSeenBefore(before)
        if (!ParseMisses.admits(count(), oldest(), row.lastSeenAt, keep)) return false
        if (insert(row) == -1L) return false
        keepNewest(keep)
        return true
    }

    /** What the screen lists: this parser's, not cleared. */
    @Query("""
        SELECT * FROM parse_misses
        WHERE cleared = 0 AND parserVersion = :version
        ORDER BY lastSeenAt DESC""")
    abstract fun showing(version: String): Flow<List<ParseMissEntity>>

    @Query("""
        SELECT * FROM parse_misses
        WHERE cleared = 0 AND parserVersion = :version
        ORDER BY lastSeenAt DESC""")
    abstract suspend fun showingNow(version: String): List<ParseMissEntity>

    @Query("UPDATE parse_misses SET cleared = 1 WHERE cleared = 0")
    abstract suspend fun clearAll(): Int
}

/**
 * v3 → v4: the unreadable-message log.
 *
 * Additive, and real for the reason every migration here is real: the
 * destructive fallback answers "we added a table" by deleting the ledger.
 * Written against what Room 2.6.1 generates for [ParseMissEntity] — the
 * conventions are the ones in schemas/…/3.json: a non-null Kotlin type is
 * NOT NULL, Boolean and Long are INTEGER, a Kotlin default is not an SQL
 * DEFAULT, and the index is `index_<table>_<column>`. Room re-reads the
 * table after this runs and refuses to open a database that differs.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `parse_misses` (" +
                "`id` TEXT NOT NULL, " +
                "`bodyHash` TEXT NOT NULL, " +
                "`sender` TEXT, " +
                "`source` TEXT NOT NULL, " +
                "`shape` TEXT NOT NULL, " +
                "`reason` TEXT NOT NULL, " +
                "`parserVersion` TEXT NOT NULL, " +
                "`seenCount` INTEGER NOT NULL, " +
                "`firstSeenAt` INTEGER NOT NULL, " +
                "`lastSeenAt` INTEGER NOT NULL, " +
                "`cleared` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_parse_misses_bodyHash` " +
                "ON `parse_misses` (`bodyHash`)")
    }
}
