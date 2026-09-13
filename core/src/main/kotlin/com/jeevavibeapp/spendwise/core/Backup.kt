package com.jeevavibeapp.spendwise.core

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Full-fidelity backup and restore, entirely on the device.
 *
 * The CSV export was never a backup. It is a report: it drops merchant
 * links, learning, category budgets, sender trust and every id, so restoring
 * from it would produce a ledger that looks similar and behaves differently.
 * For an app whose whole premise is that your data lives on your phone and
 * nowhere else, "your phone is the only copy" is not a feature — a lost
 * phone is a lost ledger, and there is no server to recover it from.
 *
 * So: a single JSON file the user holds. It carries the parts of the ledger
 * that mean something — transactions, categories, merchants, learned
 * merchant mappings, sender trust and preferences.
 *
 * Deliberately NOT included: raw SMS bodies, quarantined messages and parse
 * misses. They are the most sensitive text on the device and they are
 * recoverable — the inbox is still there and rescanning rebuilds them. A
 * backup file that contains a year of bank messages is a much worse thing to
 * lose than one that does not. For the same reason a captured transaction
 * carries no sender header either: the sender registry is backed up whole,
 * so the per-row copy adds nothing a restore needs, and a field that is not
 * in the file is a field that cannot leak out of it.
 *
 * Two rules shape everything here:
 *
 *  * A restore must never half-happen. This file therefore restores nothing;
 *    it produces a [Plan] — a pure, fully validated description of what to
 *    write. A damaged file yields a rejection and no plan at all, so there
 *    is no state in which half a backup has been applied. The caller writes
 *    a plan inside ONE database transaction, in the order the plan lists
 *    them (categories, merchants, learning, transactions, senders), so that
 *    a row never lands before the category it points at.
 *  * A restore must never trust the file. It is a document the user can open
 *    in a text editor, so every row is validated on the way in with the same
 *    rules the SMS and manual paths use: amounts go through
 *    [Parsing.safeAmount], thresholds are clamped, and a row that fails is
 *    dropped on its own rather than failing the restore.
 */
object Backup {

    const val FORMAT = 1
    const val APP = "SpendWise"

    // A hand-edited file can carry a megabyte in any field. These are the
    // widths the rest of the app is willing to store and to show.
    private const val MAX_ID = 64
    private const val MAX_TEXT = 2000
    private const val MAX_NAME = 60
    private const val MAX_MERCHANT = 120
    private const val MAX_SENDER = 40
    private const val MAX_CURRENCY = 8

    // ── the rows a backup carries ────────────────────────────────────────
    // Field lists are explicit rather than "whatever the table has": a later
    // migration that adds a column must not silently change what a backup
    // contains, and a backup written by a newer build must still restore
    // into an older one.

    data class Txn(
        val id: String,
        val amount: Double,
        val type: String,
        val occurredAt: LocalDateTime,
        val createdAt: LocalDateTime,
        val categoryId: String? = null,
        val rawMerchant: String? = null,
        val merchantName: String? = null,
        val notes: String? = null,
        val referenceNumber: String? = null,
        val source: String = "restore",
        val confidence: Int? = null,
        val status: String = "confirmed",
        val categoryPrompted: Boolean = false,
        val dedupKey: String? = null,
        val isDeleted: Boolean = false,
    )

    data class Category(
        val id: String,
        val name: String,
        val type: String = "expense",
        val icon: String = "tag",
        val color: String = "#6366f1",
        val budgetAmount: Double? = null,
        val isArchived: Boolean = false,
    )

    data class Merchant(
        val id: String,
        val canonicalName: String,
        val categoryId: String? = null,
    )

    data class Learned(
        val id: String,
        val rawName: String,
        val merchantName: String,
        val categoryId: String? = null,
        val confirmationCount: Int = 0,
        val correctionCount: Int = 0,
        val sampleCount: Int = 0,
        val avgAmount: Double = 0.0,
        val amountMin: Double = 0.0,
        val amountMax: Double = 0.0,
        val hourHistogram: String = "",
        val lastSeenAt: LocalDateTime? = null,
    )

    data class Sender(
        val id: String,
        val sender: String,
        val firstSeenAt: LocalDateTime,
        val lastSeenAt: LocalDateTime,
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
    )

    /** Preferences. A null field means the file carried no usable value for
     *  it, so whatever is set on the device stays as it is. */
    data class Prefs(
        val currency: String? = null,
        val theme: String? = null,
        val autoSaveThreshold: Int? = null,
        val confirmThreshold: Int? = null,
        val highValueAmount: Double? = null,
    )

    /**
     * A backup that has been read and validated.
     *
     * [dropped] counts the rows the file contained but this build refused,
     * per section, so a restore can tell the user that 3 of 412 transactions
     * were unreadable instead of quietly losing them.
     */
    data class Document(
        val createdAt: String,
        val categories: List<Category> = emptyList(),
        val merchants: List<Merchant> = emptyList(),
        val learning: List<Learned> = emptyList(),
        val transactions: List<Txn> = emptyList(),
        val senders: List<Sender> = emptyList(),
        val prefs: Prefs? = null,
        val dropped: Map<String, Int> = emptyMap(),
    )

    /** What the user is about to restore. Nobody should press a button
     *  called Restore without being told what is in the file. */
    data class Summary(
        val createdAt: String,
        val transactions: Int,
        val categories: Int,
        val merchants: Int,
        val learning: Int,
        val senders: Int,
        val first: LocalDate?,
        val last: LocalDate?,
        val dropped: Int,
    )

    sealed class Read {
        data class Ok(val doc: Document, val summary: Summary) : Read()

        /** [reason] is shown to the user, so it is a sentence about their
         *  file rather than the name of whatever went wrong. */
        data class Rejected(val reason: String) : Read()
    }

    /**
     * The keys already in this ledger. Merging skips anything matching one
     * of them, which is what makes restoring the same file twice a no-op the
     * second time.
     *
     * The natural keys are here as well as the ids because the database
     * refuses a duplicate category name, merchant name, learned raw name,
     * sender or dedup key whatever id it arrives under — a plan that
     * promised to add such a row would be lying about what will happen.
     */
    data class Existing(
        val transactionIds: Set<String> = emptySet(),
        val dedupKeys: Set<String> = emptySet(),
        val categoryIds: Set<String> = emptySet(),
        val categoryNames: Set<String> = emptySet(),
        val merchantIds: Set<String> = emptySet(),
        val merchantNames: Set<String> = emptySet(),
        val learningIds: Set<String> = emptySet(),
        val learningRawNames: Set<String> = emptySet(),
        val senderIds: Set<String> = emptySet(),
        val senderNames: Set<String> = emptySet(),
    )

    /**
     * Exactly what to write, with nothing left to decide.
     *
     * [clearFirst] is the "new phone" path: delete this ledger's rows before
     * inserting. It is the only mode that can lose data, so the UI asks for
     * it explicitly. The lists are already in insertion order.
     */
    data class Plan(
        val clearFirst: Boolean,
        val categories: List<Category>,
        val merchants: List<Merchant>,
        val learning: List<Learned>,
        val transactions: List<Txn>,
        val senders: List<Sender>,
        val prefs: Prefs?,
        val added: Map<String, Int>,
        val skipped: Map<String, Int>,
    ) {
        val totalAdded: Int get() = added.values.sum()
        val totalSkipped: Int get() = skipped.values.sum()
    }

    sealed class Restore {
        data class Ready(val plan: Plan, val summary: Summary) : Restore()
        data class Rejected(val reason: String) : Restore()
    }

    // ── export ───────────────────────────────────────────────────────────

    /** The timestamp a backup is stamped with. Seconds are enough, and a
     *  filename or a "restored from" line reads better without nanoseconds. */
    fun stamp(now: LocalDateTime): String = now.withNano(0).toString()

    /** The backup file, as text ready to be written to storage. */
    fun write(doc: Document): String {
        val tables = linkedMapOf(
            "categories" to doc.categories.map { c ->
                obj(
                    "id" to Json.S(c.id),
                    "name" to Json.S(c.name),
                    "type" to Json.S(c.type),
                    "icon" to Json.S(c.icon),
                    "color" to Json.S(c.color),
                    "budget_amount" to num(c.budgetAmount),
                    "is_archived" to Json.B(c.isArchived),
                )
            },
            "merchants" to doc.merchants.map { m ->
                obj(
                    "id" to Json.S(m.id),
                    "canonical_name" to Json.S(m.canonicalName),
                    "category_id" to text(m.categoryId),
                )
            },
            "learning" to doc.learning.map { l ->
                obj(
                    "id" to Json.S(l.id),
                    "raw_name" to Json.S(l.rawName),
                    "merchant_name" to Json.S(l.merchantName),
                    "category_id" to text(l.categoryId),
                    "confirmation_count" to Json.N(l.confirmationCount.toString()),
                    "correction_count" to Json.N(l.correctionCount.toString()),
                    "sample_count" to Json.N(l.sampleCount.toString()),
                    "avg_amount" to Json.N(l.avgAmount.toString()),
                    "amount_min" to Json.N(l.amountMin.toString()),
                    "amount_max" to Json.N(l.amountMax.toString()),
                    "hour_histogram" to Json.S(l.hourHistogram),
                    "last_seen_at" to text(l.lastSeenAt?.toString()),
                )
            },
            "transactions" to doc.transactions.map { t ->
                obj(
                    "id" to Json.S(t.id),
                    "amount" to Json.N(t.amount.toString()),
                    "type" to Json.S(t.type),
                    "category_id" to text(t.categoryId),
                    "raw_merchant" to text(t.rawMerchant),
                    "merchant_name" to text(t.merchantName),
                    "notes" to text(t.notes),
                    "reference_number" to text(t.referenceNumber),
                    "occurred_at" to Json.S(t.occurredAt.toString()),
                    "source" to Json.S(t.source),
                    "confidence" to num(t.confidence?.toDouble()),
                    "status" to Json.S(t.status),
                    "category_prompted" to Json.B(t.categoryPrompted),
                    "dedup_key" to text(t.dedupKey),
                    "is_deleted" to Json.B(t.isDeleted),
                    "created_at" to Json.S(t.createdAt.toString()),
                )
            },
            "sms_senders" to doc.senders.map { s ->
                obj(
                    "id" to Json.S(s.id),
                    "sender" to Json.S(s.sender),
                    "display" to text(s.display),
                    "kind" to Json.S(s.kind),
                    "entity" to text(s.entity),
                    "bank" to text(s.bank),
                    "trust" to Json.S(s.trust),
                    "message_count" to Json.N(s.messageCount.toString()),
                    "captured_count" to Json.N(s.capturedCount.toString()),
                    "confirmed_count" to Json.N(s.confirmedCount.toString()),
                    "quarantined_count" to Json.N(s.quarantinedCount.toString()),
                    "last_risk" to Json.N(s.lastRisk.toString()),
                    "first_seen_at" to Json.S(s.firstSeenAt.toString()),
                    "last_seen_at" to Json.S(s.lastSeenAt.toString()),
                )
            },
        )

        val p = doc.prefs
        val settings = if (p == null) Json.O(linkedMapOf()) else obj(
            "currency" to text(p.currency),
            "theme" to text(p.theme),
            "auto_save_threshold" to num(p.autoSaveThreshold?.toDouble()),
            "confirm_threshold" to num(p.confirmThreshold?.toDouble()),
            "high_value_amount" to num(p.highValueAmount),
        )

        val root = obj(
            "format" to Json.N(FORMAT.toString()),
            "app" to Json.S(APP),
            "created_at" to Json.S(doc.createdAt),
            "tables" to Json.O(LinkedHashMap<String, Json>().also { m ->
                tables.forEach { (name, rows) -> m[name] = Json.A(rows) }
            }),
            "settings" to settings,
            // For a human opening the file. Never read back: a count in a
            // document the user can edit is a claim, not a fact.
            "counts" to Json.O(LinkedHashMap<String, Json>().also { m ->
                tables.forEach { (name, rows) -> m[name] = Json.N(rows.size.toString()) }
            }),
        )
        return Json.write(root)
    }

    // ── reading a file back ──────────────────────────────────────────────

    /** UTF-8 bytes from storage. Separate from the [String] entry point so
     *  that a file which is not text at all — a photo, a zip — is refused
     *  with a sentence about the file rather than mojibake reaching the
     *  parser. */
    fun read(bytes: ByteArray, now: LocalDateTime = LocalDateTime.now()): Read {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            return Read.Rejected(
                "That file isn't a SpendWise backup — it isn't text this app can read.")
        }
        return read(text, now)
    }

    /**
     * Turn a file into a validated backup document, or say why it is not
     * one. This is the only place that decides whether a file is acceptable;
     * [plan] assumes it has already run.
     *
     * [now] is passed in rather than read off the clock, so the whole path
     * is a function of its inputs and can be tested.
     */
    fun read(raw: String, now: LocalDateTime = LocalDateTime.now()): Read {
        val text = raw.removePrefix("\uFEFF")
        if (text.isBlank()) return Read.Rejected("That file is empty.")

        val root = try {
            Json.parse(text)
        } catch (e: Json.Malformed) {
            return Read.Rejected(
                "That file isn't a SpendWise backup — it isn't valid JSON. " +
                    "If you meant to import a CSV, use Import instead.")
        }
        if (root !is Json.O) return Read.Rejected("That file isn't a SpendWise backup.")

        val format = root.fields["format"]
        if (format == null || root.fields["app"].asText(MAX_TEXT) != APP) {
            return Read.Rejected("That file isn't a SpendWise backup.")
        }
        val version = (format as? Json.N)?.raw?.toDoubleOrNull()?.toInt()
            ?: return Read.Rejected("That file isn't a SpendWise backup.")
        if (version > FORMAT) {
            return Read.Rejected(
                "That backup was written by a newer version of SpendWise " +
                    "(format $version). Update the app, then restore.")
        }

        val tables = root.fields["tables"] as? Json.O
            ?: return Read.Rejected("That backup is missing its data.")

        // A section that is present but is not a list is damage, not an
        // empty ledger, and restoring "nothing" from it would look like a
        // success. A section that is simply absent is fine.
        val sections = LinkedHashMap<String, List<Json>>()
        for ((key, label) in SECTIONS) {
            val value = tables.fields[key]
            if (value == null || value is Json.Null) { sections[key] = emptyList(); continue }
            sections[key] = (value as? Json.A)?.items
                ?: return Read.Rejected("The $label section of that backup is damaged.")
        }

        val dropped = LinkedHashMap<String, Int>()
        fun <T> rows(key: String, coerce: (Json.O) -> T?): List<T> {
            val out = ArrayList<T>()
            var lost = 0
            for (row in sections.getValue(key)) {
                val value = (row as? Json.O)?.let(coerce)
                if (value == null) lost++ else out.add(value)
            }
            dropped[key] = lost
            return out
        }

        val doc = Document(
            createdAt = root.fields["created_at"].asText(40) ?: "",
            categories = rows("categories") { category(it) },
            merchants = rows("merchants") { merchant(it) },
            learning = rows("learning") { learned(it) },
            transactions = rows("transactions") { txn(it) },
            senders = rows("sms_senders") { sender(it, now) },
            prefs = (root.fields["settings"] as? Json.O)?.let { prefs(it) },
            dropped = dropped,
        )
        return Read.Ok(doc, summarise(doc))
    }

    fun summarise(doc: Document): Summary {
        val dates = doc.transactions.map { it.occurredAt.toLocalDate() }.sorted()
        return Summary(
            createdAt = doc.createdAt,
            transactions = doc.transactions.size,
            categories = doc.categories.size,
            merchants = doc.merchants.size,
            learning = doc.learning.size,
            senders = doc.senders.size,
            first = dates.firstOrNull(),
            last = dates.lastOrNull(),
            dropped = doc.dropped.values.sum(),
        )
    }

    // ── the restore plan ─────────────────────────────────────────────────

    /**
     * What restoring [doc] into a ledger already holding [existing] would
     * write.
     *
     * Merging (the default) keeps everything already here and adds only rows
     * this ledger does not have. It is the safe mode and it is idempotent:
     * planning the same file against the ledger that file produced adds
     * nothing.
     *
     * A category id pointing at a category neither the file nor the ledger
     * has is dropped to null rather than failing the row: a transaction
     * filed under a category that does not exist is a worse outcome than an
     * uncategorised one, and the user can re-file that in a tap.
     */
    fun plan(doc: Document, existing: Existing = Existing(),
             replace: Boolean = false): Plan {
        // Replacing empties the ledger first, so nothing in it now can
        // collide with what the file carries.
        val base = if (replace) Existing() else existing

        val added = LinkedHashMap<String, Int>()
        val skipped = LinkedHashMap<String, Int>()
        SECTIONS.keys.forEach { skipped[it] = doc.dropped[it] ?: 0 }
        fun skip(key: String) { skipped[key] = (skipped[key] ?: 0) + 1 }

        val categoryIds = base.categoryIds.toMutableSet()
        val categoryNames = base.categoryNames.toMutableSet()
        val categories = doc.categories.filter { c ->
            if (c.id in categoryIds || c.name in categoryNames) {
                skip("categories"); false
            } else {
                categoryIds.add(c.id); categoryNames.add(c.name); true
            }
        }
        added["categories"] = categories.size

        // Resolved against the ledger AND this plan, because a row may
        // legitimately point at a category the same file is adding.
        fun categoryRef(id: String?): String? = if (id != null && id in categoryIds) id else null

        val merchantIds = base.merchantIds.toMutableSet()
        val merchantNames = base.merchantNames.toMutableSet()
        val merchants = doc.merchants.mapNotNull { m ->
            if (m.id in merchantIds || m.canonicalName in merchantNames) {
                skip("merchants"); null
            } else {
                merchantIds.add(m.id); merchantNames.add(m.canonicalName)
                m.copy(categoryId = categoryRef(m.categoryId))
            }
        }
        added["merchants"] = merchants.size

        val learningIds = base.learningIds.toMutableSet()
        val rawNames = base.learningRawNames.toMutableSet()
        val learning = doc.learning.mapNotNull { l ->
            if (l.id in learningIds || l.rawName in rawNames) {
                skip("learning"); null
            } else {
                learningIds.add(l.id); rawNames.add(l.rawName)
                l.copy(categoryId = categoryRef(l.categoryId))
            }
        }
        added["learning"] = learning.size

        val txIds = base.transactionIds.toMutableSet()
        val dedupKeys = base.dedupKeys.toMutableSet()
        val transactions = doc.transactions.mapNotNull { t ->
            val key = t.dedupKey
            if (t.id in txIds || (key != null && key in dedupKeys)) {
                skip("transactions"); null
            } else {
                txIds.add(t.id)
                if (key != null) dedupKeys.add(key)
                t.copy(categoryId = categoryRef(t.categoryId))
            }
        }
        added["transactions"] = transactions.size

        val senderIds = base.senderIds.toMutableSet()
        val senderNames = base.senderNames.toMutableSet()
        val senders = doc.senders.filter { s ->
            if (s.id in senderIds || s.sender in senderNames) {
                skip("sms_senders"); false
            } else {
                senderIds.add(s.id); senderNames.add(s.sender); true
            }
        }
        added["sms_senders"] = senders.size

        return Plan(
            clearFirst = replace,
            categories = categories,
            merchants = merchants,
            learning = learning,
            transactions = transactions,
            senders = senders,
            prefs = doc.prefs,
            added = added,
            skipped = skipped,
        )
    }

    /** Read a file and plan its restore in one step. Either the file is
     *  acceptable and there is a complete plan, or it is refused and there
     *  is none — there is no third answer in which some of it was used. */
    fun planFrom(raw: String, existing: Existing = Existing(),
                 replace: Boolean = false,
                 now: LocalDateTime = LocalDateTime.now()): Restore =
        when (val result = read(raw, now)) {
            is Read.Rejected -> Restore.Rejected(result.reason)
            is Read.Ok -> Restore.Ready(plan(result.doc, existing, replace), result.summary)
        }

    // ── one row at a time ────────────────────────────────────────────────
    // Nothing below trusts a single value in the file. A row that cannot be
    // made sense of returns null and is counted as dropped; none of it ever
    // throws, because one bad row must not cost the user the other four
    // hundred.

    /** Section key in the file, and what to call it when telling the user
     *  that section is damaged. */
    private val SECTIONS = linkedMapOf(
        "categories" to "categories",
        "merchants" to "merchants",
        "learning" to "learning",
        "transactions" to "transactions",
        "sms_senders" to "senders",
    )

    private fun id(row: Json.O): String? {
        val value = (row.fields["id"] as? Json.S)?.v?.trim() ?: return null
        return if (value.isEmpty() || value.length > MAX_ID) null else value
    }

    private fun category(row: Json.O): Category? {
        val id = id(row) ?: return null
        val name = row.fields["name"].asText(MAX_NAME) ?: return null
        return Category(
            id = id,
            name = name,
            type = row.fields["type"].asText(20) ?: "expense",
            icon = row.fields["icon"].asText(40) ?: "tag",
            color = row.fields["color"].asText(20) ?: "#6366f1",
            budgetAmount = Parsing.safeAmount(row.fields["budget_amount"].asNumber()),
            isArchived = row.fields["is_archived"].asBool(),
        )
    }

    private fun merchant(row: Json.O): Merchant? {
        val id = id(row) ?: return null
        val name = row.fields["canonical_name"].asText(MAX_MERCHANT) ?: return null
        return Merchant(id, name, row.fields["category_id"].asText(MAX_ID))
    }

    private fun learned(row: Json.O): Learned? {
        val id = id(row) ?: return null
        // raw_name is what a learned mapping is keyed by, and a mapping is
        // worthless without the name it maps to.
        val rawName = row.fields["raw_name"].asText(MAX_MERCHANT) ?: return null
        val merchantName = row.fields["merchant_name"].asText(MAX_MERCHANT) ?: return null
        return Learned(
            id = id,
            rawName = rawName,
            merchantName = merchantName,
            categoryId = row.fields["category_id"].asText(MAX_ID),
            confirmationCount = row.fields["confirmation_count"].asInt(),
            correctionCount = row.fields["correction_count"].asInt(),
            sampleCount = row.fields["sample_count"].asInt(),
            avgAmount = Parsing.safeAmount(row.fields["avg_amount"].asNumber()) ?: 0.0,
            amountMin = Parsing.safeAmount(row.fields["amount_min"].asNumber()) ?: 0.0,
            amountMax = Parsing.safeAmount(row.fields["amount_max"].asNumber()) ?: 0.0,
            hourHistogram = (row.fields["hour_histogram"] as? Json.S)?.v?.take(200) ?: "",
            lastSeenAt = time(row.fields["last_seen_at"]),
        )
    }

    private fun txn(row: Json.O): Txn? {
        val id = id(row) ?: return null
        // The same gate the SMS parser uses: no zero, no negative, no
        // 1e300 grocery bill, no "abc".
        val amount = Parsing.safeAmount(row.fields["amount"].asNumber()) ?: return null
        val type = row.fields["type"].asText(20)
        if (type != "expense" && type != "income") return null
        // A date that cannot be read is not a date, and a transaction with
        // no date lands at an arbitrary point in the ledger — worse than
        // losing the row and saying so.
        val occurred = time(row.fields["occurred_at"]) ?: return null
        return Txn(
            id = id,
            amount = amount,
            type = type,
            occurredAt = occurred,
            createdAt = time(row.fields["created_at"]) ?: occurred,
            categoryId = row.fields["category_id"].asText(MAX_ID),
            rawMerchant = row.fields["raw_merchant"].asText(MAX_TEXT),
            merchantName = row.fields["merchant_name"].asText(MAX_MERCHANT),
            notes = row.fields["notes"].asText(MAX_TEXT),
            referenceNumber = row.fields["reference_number"].asText(MAX_TEXT),
            source = row.fields["source"].asText(20) ?: "restore",
            confidence = row.fields["confidence"].asIntOrNull()?.coerceIn(0, 100),
            status = row.fields["status"].asText(30) ?: "confirmed",
            categoryPrompted = row.fields["category_prompted"].asBool(),
            dedupKey = row.fields["dedup_key"].asText(MAX_TEXT),
            isDeleted = row.fields["is_deleted"].asBool(),
        )
    }

    private fun sender(row: Json.O, now: LocalDateTime): Sender? {
        val id = id(row) ?: return null
        val name = row.fields["sender"].asText(MAX_SENDER) ?: return null
        // A sender with no dates is still worth its trust setting, so it
        // falls back to now rather than being dropped.
        val first = time(row.fields["first_seen_at"]) ?: now
        return Sender(
            id = id,
            sender = name,
            firstSeenAt = first,
            lastSeenAt = time(row.fields["last_seen_at"]) ?: first,
            display = row.fields["display"].asText(MAX_SENDER),
            kind = row.fields["kind"].asText(20) ?: "other",
            entity = row.fields["entity"].asText(MAX_SENDER),
            bank = row.fields["bank"].asText(MAX_SENDER),
            trust = row.fields["trust"].asText(20) ?: "unknown",
            messageCount = row.fields["message_count"].asInt(),
            capturedCount = row.fields["captured_count"].asInt(),
            confirmedCount = row.fields["confirmed_count"].asInt(),
            quarantinedCount = row.fields["quarantined_count"].asInt(),
            lastRisk = row.fields["last_risk"].asInt(),
        )
    }

    /** Preferences carry the same clamps the settings screen applies — a
     *  hand-edited backup must not be able to set a 900% auto-save
     *  threshold, which would bank every message without asking. */
    private fun prefs(row: Json.O): Prefs {
        val theme = row.fields["theme"].asText(20)
        return Prefs(
            currency = row.fields["currency"].asText(MAX_CURRENCY),
            theme = if (theme == "system" || theme == "light" || theme == "dark") theme else null,
            autoSaveThreshold = row.fields["auto_save_threshold"].asIntOrNull()?.coerceIn(0, 100),
            confirmThreshold = row.fields["confirm_threshold"].asIntOrNull()?.coerceIn(0, 100),
            highValueAmount = Parsing.safeAmount(row.fields["high_value_amount"].asNumber()),
        )
    }

    /** ISO-8601 local time, as [write] emits it. A bare date, a space
     *  instead of the T and a trailing zone are accepted too: a file the
     *  user may have edited by hand is worth being generous with. */
    private fun time(value: Json?): LocalDateTime? {
        var s = (value as? Json.S)?.v?.trim() ?: return null
        if (s.isEmpty()) return null
        s = s.replace(' ', 'T')
        if (s.endsWith("Z") || s.endsWith("z")) s = s.dropLast(1)
        val offset = s.indexOf('+')
        if (offset > 10) s = s.substring(0, offset)
        return try {
            if (s.length == 10) LocalDate.parse(s).atStartOfDay() else LocalDateTime.parse(s)
        } catch (e: Exception) {
            null
        }
    }

    private fun Json?.asText(limit: Int): String? {
        val s = when (this) {
            is Json.S -> v
            is Json.N -> raw
            else -> return null
        }.trim()
        return if (s.isEmpty()) null else s.take(limit)
    }

    /** Handed to [Parsing.safeAmount], which is the only thing in the app
     *  allowed to decide that a number is a usable amount. */
    private fun Json?.asNumber(): Any? = when (this) {
        is Json.N -> raw
        is Json.S -> v
        else -> null
    }

    private fun Json?.asIntOrNull(): Int? = when (this) {
        is Json.N -> raw.toDoubleOrNull()?.takeIf { !it.isNaN() }?.toInt()
        is Json.S -> v.trim().toDoubleOrNull()?.takeIf { !it.isNaN() }?.toInt()
        is Json.B -> if (v) 1 else 0
        else -> null
    }

    private fun Json?.asInt(): Int = asIntOrNull() ?: 0

    private fun Json?.asBool(): Boolean = when (this) {
        is Json.B -> v
        is Json.N -> raw.toDoubleOrNull()?.let { it != 0.0 } ?: false
        is Json.S -> v.trim().lowercase() == "true" || v.trim() == "1"
        else -> false
    }

    private fun text(value: String?): Json = if (value == null) Json.Null else Json.S(value)

    private fun num(value: Double?): Json =
        if (value == null) Json.Null else Json.N(value.toString())

    private fun obj(vararg pairs: Pair<String, Json>): Json.O =
        Json.O(LinkedHashMap<String, Json>().also { m -> pairs.forEach { m[it.first] = it.second } })

    // ── JSON, by hand ────────────────────────────────────────────────────
    /**
     * A minimal JSON reader and writer.
     *
     * :core deliberately has no dependencies — that is what lets the whole
     * domain layer be verified on a bare JVM in seconds — and one document
     * format is a small price next to a serialisation library in the module
     * that has to stay portable.
     */
    internal sealed class Json {
        object Null : Json()

        data class B(val v: Boolean) : Json()

        /** Kept as the text that was in the file: reading a number into a
         *  Double and writing it back out is a lossy round trip for anything
         *  carrying more digits than a Double holds. */
        data class N(val raw: String) : Json()

        data class S(val v: String) : Json()

        data class A(val items: List<Json>) : Json()

        data class O(val fields: Map<String, Json>) : Json()

        class Malformed(message: String) : Exception(message)

        companion object {
            // Deep enough for any backup this app writes, shallow enough
            // that a file built to nest ten thousand arrays cannot exhaust
            // the stack of whatever reads it.
            const val MAX_DEPTH = 32

            fun parse(text: String): Json {
                val p = Parser(text)
                val value = p.value(0)
                p.skipSpace()
                if (!p.atEnd()) throw Malformed("trailing text")
                return value
            }

            fun write(value: Json): String =
                StringBuilder().also { render(value, it) }.toString()

            private fun render(value: Json, out: StringBuilder) {
                when (value) {
                    is Null -> out.append("null")
                    is B -> out.append(if (value.v) "true" else "false")
                    is N -> out.append(value.raw)
                    is S -> escape(value.v, out)
                    is A -> {
                        out.append('[')
                        value.items.forEachIndexed { i, item ->
                            if (i > 0) out.append(',')
                            render(item, out)
                        }
                        out.append(']')
                    }
                    is O -> {
                        out.append('{')
                        var first = true
                        for ((k, v) in value.fields) {
                            if (!first) out.append(',')
                            first = false
                            escape(k, out)
                            out.append(':')
                            render(v, out)
                        }
                        out.append('}')
                    }
                }
            }

            private fun escape(s: String, out: StringBuilder) {
                out.append('"')
                for (c in s) {
                    when {
                        c == '"' -> out.append("\\\"")
                        c == '\\' -> out.append("\\\\")
                        c == '\n' -> out.append("\\n")
                        c == '\r' -> out.append("\\r")
                        c == '\t' -> out.append("\\t")
                        c.code < 0x20 -> out.append("\\u").append("%04x".format(c.code))
                        // Anything above ASCII is written as itself: the file
                        // is UTF-8, and a merchant name in Devanagari should
                        // be readable when the user opens it.
                        else -> out.append(c)
                    }
                }
                out.append('"')
            }
        }

        private class Parser(private val src: String) {
            private var i = 0

            fun atEnd(): Boolean = i >= src.length

            fun skipSpace() {
                while (i < src.length && (src[i] == ' ' || src[i] == '\t' ||
                            src[i] == '\n' || src[i] == '\r')) i++
            }

            fun value(depth: Int): Json {
                if (depth > MAX_DEPTH) throw Malformed("nested too deeply")
                skipSpace()
                if (atEnd()) throw Malformed("unexpected end of file")
                val c = src[i]
                return when {
                    c == '{' -> obj(depth)
                    c == '[' -> arr(depth)
                    c == '"' -> S(string())
                    c == 't' -> literal("true", B(true))
                    c == 'f' -> literal("false", B(false))
                    c == 'n' -> literal("null", Null)
                    c == '-' || c in '0'..'9' -> number()
                    else -> throw Malformed("unexpected character")
                }
            }

            private fun literal(word: String, value: Json): Json {
                if (!src.startsWith(word, i)) throw Malformed("unexpected word")
                i += word.length
                return value
            }

            private fun obj(depth: Int): Json {
                i++
                val fields = LinkedHashMap<String, Json>()
                skipSpace()
                if (!atEnd() && src[i] == '}') { i++; return O(fields) }
                while (true) {
                    skipSpace()
                    if (atEnd() || src[i] != '"') throw Malformed("expected a field name")
                    val key = string()
                    skipSpace()
                    if (atEnd() || src[i] != ':') throw Malformed("expected ':'")
                    i++
                    fields[key] = value(depth + 1)
                    skipSpace()
                    if (atEnd()) throw Malformed("unexpected end of file")
                    when (src[i]) {
                        ',' -> i++
                        '}' -> { i++; return O(fields) }
                        else -> throw Malformed("expected ',' or '}'")
                    }
                }
            }

            private fun arr(depth: Int): Json {
                i++
                val items = ArrayList<Json>()
                skipSpace()
                if (!atEnd() && src[i] == ']') { i++; return A(items) }
                while (true) {
                    items.add(value(depth + 1))
                    skipSpace()
                    if (atEnd()) throw Malformed("unexpected end of file")
                    when (src[i]) {
                        ',' -> i++
                        ']' -> { i++; return A(items) }
                        else -> throw Malformed("expected ',' or ']'")
                    }
                }
            }

            private fun string(): String {
                i++
                val out = StringBuilder()
                while (true) {
                    if (atEnd()) throw Malformed("unterminated text")
                    val c = src[i]
                    when (c) {
                        '"' -> { i++; return out.toString() }
                        '\\' -> {
                            i++
                            if (atEnd()) throw Malformed("unterminated escape")
                            when (src[i]) {
                                '"' -> out.append('"')
                                '\\' -> out.append('\\')
                                '/' -> out.append('/')
                                'b' -> out.append('\b')
                                'f' -> out.append('\u000C')
                                'n' -> out.append('\n')
                                'r' -> out.append('\r')
                                't' -> out.append('\t')
                                'u' -> {
                                    if (i + 4 >= src.length) throw Malformed("bad unicode escape")
                                    val code = src.substring(i + 1, i + 5).toIntOrNull(16)
                                        ?: throw Malformed("bad unicode escape")
                                    out.append(code.toChar())
                                    i += 4
                                }
                                else -> throw Malformed("bad escape")
                            }
                            i++
                        }
                        else -> { out.append(c); i++ }
                    }
                }
            }

            private fun number(): Json {
                val start = i
                if (!atEnd() && src[i] == '-') i++
                while (!atEnd() && src[i] in '0'..'9') i++
                if (!atEnd() && src[i] == '.') {
                    i++
                    while (!atEnd() && src[i] in '0'..'9') i++
                }
                if (!atEnd() && (src[i] == 'e' || src[i] == 'E')) {
                    i++
                    if (!atEnd() && (src[i] == '+' || src[i] == '-')) i++
                    while (!atEnd() && src[i] in '0'..'9') i++
                }
                val raw = src.substring(start, i)
                if (raw.toDoubleOrNull() == null) throw Malformed("bad number")
                return N(raw)
            }
        }
    }
}
