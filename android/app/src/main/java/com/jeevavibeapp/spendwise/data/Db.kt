package com.jeevavibeapp.spendwise.data

import androidx.room.*
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

    @Query("SELECT * FROM transactions WHERE isDeleted = 0 ORDER BY occurredAt DESC LIMIT :limit")
    fun recent(limit: Int = 200): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE isDeleted = 0 ORDER BY occurredAt DESC")
    suspend fun all(): List<TransactionEntity>

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

    @Update suspend fun update(tx: TransactionEntity)

    @Query("UPDATE transactions SET isDeleted = :deleted WHERE id = :id")
    suspend fun setDeleted(id: String, deleted: Boolean)

    @Query("SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE isDeleted = 0 AND type = :type AND occurredAt >= :from AND occurredAt < :to")
    fun totalBetween(type: String, from: Long, to: Long): Flow<Double>

    @Query("SELECT COUNT(*) FROM transactions WHERE isDeleted = 0")
    fun count(): Flow<Int>

    // ── Categories ───────────────────────────────────────────────────────
    @Query("SELECT * FROM categories WHERE isArchived = 0 ORDER BY name")
    fun categories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories")
    suspend fun allCategories(): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategories(items: List<CategoryEntity>)

    @Update suspend fun updateCategory(c: CategoryEntity)

    // ── Merchants and learning ───────────────────────────────────────────
    @Query("SELECT * FROM merchants WHERE canonicalName = :name LIMIT 1")
    suspend fun merchantByName(name: String): MerchantEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMerchant(m: MerchantEntity)

    @Query("SELECT * FROM learning WHERE rawName = :rawName LIMIT 1")
    suspend fun learningFor(rawName: String): LearningEntity?

    @Query("SELECT * FROM learning")
    suspend fun allLearning(): List<LearningEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLearning(l: LearningEntity)

    // ── Senders and quarantine ───────────────────────────────────────────
    @Query("SELECT * FROM sms_senders WHERE sender = :sender LIMIT 1")
    suspend fun senderByName(sender: String): SenderEntity?

    @Query("SELECT * FROM sms_senders ORDER BY lastSeenAt DESC")
    fun senders(): Flow<List<SenderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSender(s: SenderEntity)

    @Query("SELECT * FROM quarantine WHERE status = 'held' ORDER BY createdAt DESC")
    fun held(): Flow<List<QuarantineEntity>>

    @Query("SELECT * FROM quarantine WHERE bodyHash = :hash LIMIT 1")
    suspend fun quarantineByHash(hash: String): QuarantineEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQuarantine(q: QuarantineEntity)

    @Query("UPDATE quarantine SET status = :status WHERE id = :id")
    suspend fun setQuarantineStatus(id: String, status: String)
}

@Database(
    entities = [TransactionEntity::class, CategoryEntity::class, MerchantEntity::class,
                LearningEntity::class, SenderEntity::class, QuarantineEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class SpendDatabase : RoomDatabase() {
    abstract fun dao(): SpendDao
}
