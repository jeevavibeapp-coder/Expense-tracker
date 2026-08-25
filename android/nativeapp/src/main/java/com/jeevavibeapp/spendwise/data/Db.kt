package com.jeevavibeapp.spendwise.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * The whole database. Room over SQLite, opened directly in-process.
 *
 * The app previously reached its own data by making an HTTP request to a
 * Python web server running on a loopback port inside itself. Every screen
 * cost a socket round-trip and a WSGI dispatch, and the server not starting
 * was a class of failure that could take the entire app down. A DAO call is
 * a function call.
 */

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["occurredAt"]),
        Index(value = ["merchantName"]),
        Index(value = ["categoryId"]),
        // The dedup key is what stops one purchase becoming two rows when a
        // bank sends both an SMS and a UPI confirmation.
        Index(value = ["dedupKey"], unique = true),
    ],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val amount: Double,
    val type: String,                       // "expense" | "income"
    val categoryId: String? = null,
    val rawMerchant: String? = null,
    val merchantName: String? = null,
    val notes: String? = null,
    val referenceNumber: String? = null,
    /** Epoch millis, device-local wall clock. */
    val occurredAt: Long,
    val source: String = "manual",          // manual | sms | import | restore
    val confidence: Int? = null,
    val status: String = "confirmed",       // confirmed | pending_confirmation | needs_review
    val categoryPrompted: Boolean = false,
    val dedupKey: String? = null,
    val smsSender: String? = null,
    /** Soft delete. Nothing is ever really removed, so undo always works. */
    val isDeleted: Boolean = false,
    val createdAt: Long,
)

@Entity(tableName = "categories", indices = [Index(value = ["name"], unique = true)])
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String = "expense",
    val icon: String = "tag",
    val color: String = "#6366f1",
    val budgetAmount: Double? = null,
    val isArchived: Boolean = false,
)

@Entity(tableName = "merchants", indices = [Index(value = ["canonicalName"], unique = true)])
data class MerchantEntity(
    @PrimaryKey val id: String,
    val canonicalName: String,
    val categoryId: String? = null,
)

@Entity(tableName = "learning", indices = [Index(value = ["rawName"], unique = true)])
data class LearningEntity(
    @PrimaryKey val id: String,
    val rawName: String,
    val merchantName: String,
    val categoryId: String? = null,
    val confirmationCount: Int = 0,
    val correctionCount: Int = 0,
    val sampleCount: Int = 0,
    val avgAmount: Double = 0.0,
    val amountMin: Double = 0.0,
    val amountMax: Double = 0.0,
    /** 24 comma-separated counts. Stored as text because Room has no list
     *  type and a separate table for 24 integers would be absurd. */
    val hourHistogram: String = "",
    val lastSeenAt: Long? = null,
)

@Entity(tableName = "sms_senders", indices = [Index(value = ["sender"], unique = true)])
data class SenderEntity(
    @PrimaryKey val id: String,
    val sender: String,
    val display: String? = null,
    val kind: String = "other",
    val entity: String? = null,
    val bank: String? = null,
    val trust: String = "unknown",
    val messageCount: Int = 0,
    val capturedCount: Int = 0,
    val confirmedCount: Int = 0,
    val quarantinedCount: Int = 0,
    val lastRisk: Int = 0,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
)

/**
 * Every preference, in one row.
 *
 * Room rather than DataStore: the app already has a database, and a second
 * storage mechanism would mean a restore that is atomic in one of them and
 * not the other — the settings a backup carries would land outside the
 * transaction that lands the ledger.
 *
 * [id] is pinned to [SettingsEntity.ROW] so writing settings is an upsert of
 * one known row rather than a read-modify-write that can race with itself.
 */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = ROW,
    /** Set the first time the introduction is dismissed, so it is shown
     *  once rather than on every launch. */
    val onboardingSeen: Boolean = false,
    val currency: String = "INR",
    val theme: String = "system",
    /** The two gates Pipeline.ingest is given. Defaulted to the values the
     *  core module itself defaults to, so an untouched install behaves
     *  exactly as the JVM tests describe. */
    val autoSaveThreshold: Int = 80,
    val confirmThreshold: Int = 50,
    val highValueAmount: Double? = null,
) {
    companion object { const val ROW = 1 }
}

@Entity(tableName = "quarantine")
data class QuarantineEntity(
    @PrimaryKey val id: String,
    val sender: String? = null,
    val body: String,
    val bodyHash: String,
    val risk: Int = 0,
    val indicators: String = "",
    val reason: String? = null,
    val amount: Double? = null,
    val type: String? = null,
    val rawMerchant: String? = null,
    val occurredAt: Long? = null,
    val seenCount: Int = 1,
    val status: String = "held",            // held | approved | rejected
    val createdAt: Long,
)

@Dao
interface SpendDao {

    // ── Transactions ─────────────────────────────────────────────────────
    // Flows, so a screen re-renders when the data changes rather than when
    // something remembers to refresh it.

    /** The whole ledger, not a page of it. The report, the forecast and the
     *  six-month cash flow are computed from whatever this emits, so a LIMIT
     *  here would not shorten a list — it would silently change the
     *  arithmetic on every screen that aggregates. */
    @Query("SELECT * FROM transactions WHERE isDeleted = 0 ORDER BY occurredAt DESC")
    fun stream(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE isDeleted = 0 ORDER BY occurredAt DESC")
    suspend fun all(): List<TransactionEntity>

    /** Soft-deleted rows included. A backup that dropped them would turn the
     *  undo behind every delete into a one-way door the moment the ledger
     *  was restored, and their ids are what stops a re-import resurrecting
     *  something the user threw away. */
    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC")
    suspend fun everyTransaction(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun byId(id: String): TransactionEntity?

    /** Search across merchant, notes and reference in one pass. */
    @Query("""
        SELECT * FROM transactions
        WHERE isDeleted = 0 AND (
            merchantName LIKE '%' || :q || '%' OR
            rawMerchant  LIKE '%' || :q || '%' OR
            notes        LIKE '%' || :q || '%' OR
            referenceNumber LIKE '%' || :q || '%')
        ORDER BY occurredAt DESC LIMIT 200""")
    fun search(q: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE isDeleted = 0 AND status != 'confirmed' ORDER BY occurredAt DESC")
    fun needingReview(): Flow<List<TransactionEntity>>

    /** IGNORE, not REPLACE: the unique dedupKey is what makes re-ingesting
     *  the same bank message harmless, and REPLACE would overwrite a row the
     *  user had already corrected. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tx: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransactions(items: List<TransactionEntity>)

    @Update suspend fun update(tx: TransactionEntity)

    @Query("UPDATE transactions SET isDeleted = :deleted WHERE id = :id")
    suspend fun setDeleted(id: String, deleted: Boolean)

    /** Junk captured before the filtering improved. Soft, like every other
     *  delete here, and confirmed rows are left alone: the offer the user
     *  accepted was to clear what they have NOT reviewed. */
    @Query("""
        UPDATE transactions SET isDeleted = 1
        WHERE isDeleted = 0 AND source = 'sms' AND status != 'confirmed'""")
    suspend fun clearUnreviewedCaptures(): Int

    @Query("SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE isDeleted = 0 AND type = :type AND occurredAt >= :from AND occurredAt < :to")
    fun totalBetween(type: String, from: Long, to: Long): Flow<Double>

    @Query("SELECT COUNT(*) FROM transactions WHERE isDeleted = 0")
    fun count(): Flow<Int>

    // ── Categories ───────────────────────────────────────────────────────
    @Query("SELECT * FROM categories WHERE isArchived = 0 ORDER BY name")
    fun categories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories")
    suspend fun allCategories(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1")
    suspend fun categoryByName(name: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategories(items: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategory(c: CategoryEntity): Long

    @Update suspend fun updateCategory(c: CategoryEntity)

    /** Null clears the budget. That is a different state from zero — see
     *  Budget.amount in :core — so the column is nullable and this query
     *  writes the null rather than a 0.0 nobody could ever be under. */
    @Query("UPDATE categories SET budgetAmount = :amount WHERE id = :id")
    suspend fun setBudget(id: String, amount: Double?)

    // ── Merchants and learning ───────────────────────────────────────────
    @Query("SELECT * FROM merchants WHERE canonicalName = :name LIMIT 1")
    suspend fun merchantByName(name: String): MerchantEntity?

    @Query("SELECT * FROM merchants")
    suspend fun allMerchants(): List<MerchantEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMerchant(m: MerchantEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMerchants(items: List<MerchantEntity>)

    @Query("SELECT * FROM learning WHERE rawName = :rawName LIMIT 1")
    suspend fun learningFor(rawName: String): LearningEntity?

    @Query("SELECT * FROM learning")
    suspend fun allLearning(): List<LearningEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLearning(l: LearningEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLearning(items: List<LearningEntity>)

    // ── Senders and quarantine ───────────────────────────────────────────
    @Query("SELECT * FROM sms_senders WHERE sender = :sender LIMIT 1")
    suspend fun senderByName(sender: String): SenderEntity?

    @Query("SELECT * FROM sms_senders ORDER BY lastSeenAt DESC")
    fun senders(): Flow<List<SenderEntity>>

    @Query("SELECT * FROM sms_senders")
    suspend fun allSenders(): List<SenderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSender(s: SenderEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSenders(items: List<SenderEntity>)

    @Query("SELECT * FROM quarantine WHERE status = 'held' ORDER BY createdAt DESC")
    fun held(): Flow<List<QuarantineEntity>>

    @Query("SELECT * FROM quarantine WHERE status = 'held' ORDER BY createdAt DESC")
    suspend fun allHeld(): List<QuarantineEntity>

    @Query("SELECT * FROM quarantine WHERE id = :id LIMIT 1")
    suspend fun quarantineById(id: String): QuarantineEntity?

    @Query("SELECT * FROM quarantine WHERE bodyHash = :hash LIMIT 1")
    suspend fun quarantineByHash(hash: String): QuarantineEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQuarantine(q: QuarantineEntity)

    @Query("UPDATE quarantine SET status = :status WHERE id = :id")
    suspend fun setQuarantineStatus(id: String, status: String)

    // ── Settings ─────────────────────────────────────────────────────────
    // Returned as a list rather than a nullable row because a table with no
    // row yet is the ordinary first-launch state, and the caller substitutes
    // the defaults.

    @Query("SELECT * FROM settings WHERE id = 1")
    fun settingsStream(): Flow<List<SettingsEntity>>

    @Query("SELECT * FROM settings WHERE id = 1")
    suspend fun settingsRow(): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSettings(s: SettingsEntity)

    // ── Restoring a backup ───────────────────────────────────────────────

    @Query("DELETE FROM transactions") suspend fun deleteAllTransactions()
    @Query("DELETE FROM categories") suspend fun deleteAllCategories()
    @Query("DELETE FROM merchants") suspend fun deleteAllMerchants()
    @Query("DELETE FROM learning") suspend fun deleteAllLearning()
    @Query("DELETE FROM sms_senders") suspend fun deleteAllSenders()

    /**
     * A whole restore, or none of it.
     *
     * :core validates the file and produces a plan but cannot enforce how it
     * lands — its KDoc says so and leaves the guarantee here. On a finance
     * app with no cloud copy a half-applied restore is the worst outcome
     * available, so every write is one transaction: an interrupted restore
     * rolls back to the ledger the user already had.
     *
     * The order is the plan's own: categories before the merchants, learning
     * and transactions that point at them, so a row never lands before the
     * category it references.
     *
     * IGNORE throughout. The plan already excluded everything this ledger
     * holds, so a collision here means the two disagree — and dropping the
     * incoming row is the only resolution that cannot destroy something the
     * user still has.
     */
    @Transaction
    suspend fun applyBackup(
        clearFirst: Boolean,
        categories: List<CategoryEntity>,
        merchants: List<MerchantEntity>,
        learning: List<LearningEntity>,
        transactions: List<TransactionEntity>,
        senders: List<SenderEntity>,
        settings: SettingsEntity?,
    ) {
        if (clearFirst) {
            // Quarantine is deliberately not cleared: a backup carries no
            // held messages, so emptying that table would delete the only
            // copy of something the file cannot put back.
            deleteAllSenders()
            deleteAllTransactions()
            deleteAllLearning()
            deleteAllMerchants()
            deleteAllCategories()
        }
        insertCategories(categories)
        insertMerchants(merchants)
        insertLearning(learning)
        insertTransactions(transactions)
        insertSenders(senders)
        settings?.let { putSettings(it) }
    }
}

@Database(
    entities = [TransactionEntity::class, CategoryEntity::class, MerchantEntity::class,
                LearningEntity::class, SenderEntity::class, QuarantineEntity::class,
                SettingsEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class SpendDatabase : RoomDatabase() {
    abstract fun dao(): SpendDao
}

/**
 * v1 → v2: preferences moved into the database.
 *
 * A real migration rather than a destructive fallback, because the fallback
 * would answer "we added a settings table" by deleting the ledger. The
 * statement has to match what Room generates for [SettingsEntity] column for
 * column — Room re-reads the table afterwards and refuses to open a database
 * the migration left in a shape it did not expect.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `settings` (" +
                "`id` INTEGER NOT NULL, " +
                "`onboardingSeen` INTEGER NOT NULL, " +
                "`currency` TEXT NOT NULL, " +
                "`theme` TEXT NOT NULL, " +
                "`autoSaveThreshold` INTEGER NOT NULL, " +
                "`confirmThreshold` INTEGER NOT NULL, " +
                "`highValueAmount` REAL, " +
                "PRIMARY KEY(`id`))")
    }
}
