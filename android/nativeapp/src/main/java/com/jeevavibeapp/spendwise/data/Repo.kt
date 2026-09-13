package com.jeevavibeapp.spendwise.data

import com.jeevavibeapp.spendwise.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * The one place the verified core logic meets storage.
 *
 * Everything decision-shaped lives in :core and is tested on the JVM; this
 * class only reads rows, hands them to that logic, and writes the result. If
 * a rule looks like it is being decided here, it is in the wrong file.
 */
class Repo(private val dao: SpendDao) {

    companion object {
        /** Longer than a chip can show, and the review screen creates
         *  categories from free text. */
        const val MAX_CATEGORY_NAME = 40

        fun newId(): String = UUID.randomUUID().toString()

        fun millis(t: LocalDateTime): Long =
            t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        fun localTime(ms: Long): LocalDateTime =
            LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())

        fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
                .joinToString("") { "%02x".format(it) }

        /**
         * Name, type and colour of the categories a fresh install starts
         * with. The names are not decoration: `Engine.SEED_MERCHANTS` files
         * its built-in merchants under these exact strings, so changing one
         * here detaches every seeded merchant from its category.
         */
        val DEFAULT_CATEGORIES: List<Triple<String, String, String>> = listOf(
            Triple("Salary", "income", "#10b981"),
            Triple("Business", "income", "#3b82f6"),
            Triple("Other Income", "income", "#64748b"),
            Triple("Food & Dining", "expense", "#f43f5e"),
            Triple("Groceries", "expense", "#22c55e"),
            Triple("Shopping", "expense", "#ec4899"),
            Triple("Transport", "expense", "#f59e0b"),
            Triple("Bills & Utilities", "expense", "#3b82f6"),
            Triple("Entertainment", "expense", "#8b5cf6"),
            Triple("Health", "expense", "#ef4444"),
            Triple("Other Expense", "expense", "#64748b"),
        )

        private val CsvDate: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        /** Fixed point, always. Kotlin renders a Double above ten million in
         *  scientific notation, and "1.0E7" in the amount column of a
         *  spreadsheet is a number nobody can sum. */
        private fun csvAmount(value: Double): String = String.format(Locale.US, "%.2f", value)

        /** Quoted always. A merchant called "Cafe, The" and a note holding a
         *  line break both break a spreadsheet that only quotes when it
         *  thinks it has to. */
        private fun csvCell(value: String?): String =
            "\"" + (value ?: "").replace("\"", "\"\"") + "\""
    }

    /**
     * Serialises the read-modify-write on the single settings row.
     *
     * A restore merges the file's preferences onto whatever is stored, and
     * the settings screen writes the same row. Both are read-then-write, so
     * without this one of them can silently overwrite the other's change with
     * a value it read before that change existed.
     */
    private val settingsGate = Mutex()

    /** Streams rather than one-shot reads, so a screen re-renders when a row
     *  changes instead of when something remembers to reload it. */
    fun stream(): Flow<List<TransactionEntity>> = dao.stream()

    fun heldMessages(): Flow<List<QuarantineEntity>> = dao.held()

    /** Every sender that has ever sent something financial-looking. The DAO
     *  orders by recency; the order the screen shows is SenderBook's. */
    fun senders(): Flow<List<SenderEntity>> = dao.senders()

    /**
     * Record what the user decided about a sender.
     *
     * [Senders.assess] treats this as final — it outranks every heuristic the
     * module owns — so the value is held to the three SenderBook permits. A
     * caller passing `known` or `suspicious` would be writing a machine
     * verdict into the column reserved for a person's, and the capture path
     * would then stop revising it.
     *
     * @return false if nothing was stored.
     */
    suspend fun setSenderTrust(sender: String, trust: String): Boolean =
        withContext(Dispatchers.IO) {
            val key = Senders.normalizeSender(sender)
            if (key.isEmpty() || !SenderBook.isUserDecision(trust)) return@withContext false
            dao.setSenderTrust(key, trust)
            true
        }

    /** Open only. The alert list is a queue of things to look at, not a log
     *  of things that were looked at. */
    fun fraudAlerts(): Flow<List<FraudAlertEntity>> = dao.openAlerts()

    /**
     * The user has looked at an alert.
     *
     * One action, not the Python build's dismissed-or-resolved pair. This app
     * cannot freeze a card or call a bank, so both of those buttons produced
     * exactly the same outcome on screen — a choice with no consequence,
     * asked of someone who has just been told they may have been charged
     * twice.
     */
    suspend fun dismissAlert(id: String) = dao.dismissAlert(id)

    fun categories(): Flow<List<CategoryEntity>> = dao.categories()

    /** An install with no settings row yet is the ordinary first launch, not
     *  an error, so the defaults stand in for it and every reader downstream
     *  gets a row rather than a null it has to think about. */
    fun settings(): Flow<SettingsEntity> =
        dao.settingsStream().map { it.firstOrNull() ?: SettingsEntity() }

    /** Soft, like every delete here, so the undo behind it stays possible. */
    suspend fun delete(id: String) = dao.setDeleted(id, true)

    /** The other half of [delete]. The row was never removed, so this brings
     *  back the same transaction rather than a rebuilt copy of it. */
    suspend fun undelete(id: String) = dao.setDeleted(id, false)

    /**
     * Add or edit one transaction.
     *
     * Insert or update is decided from what is already stored rather than
     * from a flag the caller passes: the sheet composes an edit as a copy of
     * the original row, so the id is the only reliable witness to which of
     * the two this is.
     */
    suspend fun save(tx: TransactionEntity) = withContext(Dispatchers.IO) {
        val existing = dao.byId(tx.id)
        if (existing == null) {
            dao.insert(tx)
            raiseFraudAlerts(tx)
        } else dao.update(tx)

        // Saving a payee with a category is the user stating what that payee
        // is, which is the only thing that ever teaches the engine. A changed
        // category on a row that already had one is a correction, and the
        // scoring weights the two differently.
        val raw = tx.rawMerchant ?: tx.merchantName
        val display = tx.merchantName ?: tx.rawMerchant
        if (raw != null && display != null && tx.categoryId != null) {
            recordConfirmation(
                rawMerchant = raw,
                merchantName = display,
                categoryId = tx.categoryId,
                amount = tx.amount,
                occurredAt = localTime(tx.occurredAt),
                wasCorrection = existing != null &&
                    existing.categoryId != null && existing.categoryId != tx.categoryId,
            )
        }
    }

    /**
     * The user naming the payee on a capture the app filed but would not
     * vouch for, and the engine learning from it.
     *
     * [save] cannot do this job: it carries the stored status forward, which
     * is right for an edit — correcting an amount is not a review — and
     * leaves a low-confidence capture reading "Needs review" no matter how
     * often it is edited. This is the one path that moves a stored row to
     * confirmed.
     *
     * @return false when the row is gone or the name is blank, so the caller
     *   can say nothing happened rather than claim a confirmation.
     */
    suspend fun confirm(id: String, merchantName: String,
                        categoryId: String? = null): Boolean = withContext(Dispatchers.IO) {
        val row = dao.byId(id) ?: return@withContext false
        val decided = Pipeline.confirm(row.merchantName, row.categoryId, merchantName, categoryId)
            ?: return@withContext false

        dao.update(row.copy(
            merchantName = decided.merchantName,
            categoryId = decided.categoryId,
            status = decided.status,
            confidence = decided.confidence,
        ))

        // Teach from the RAW payee the bank sent, which is the string the
        // next message will arrive with — learning under the display name
        // would train a key no incoming SMS ever matches. A manually entered
        // row has no raw payee, and there the confirmed name is that string,
        // which is the same fallback [save] uses.
        recordConfirmation(
            rawMerchant = row.rawMerchant ?: decided.merchantName,
            merchantName = decided.merchantName,
            categoryId = decided.categoryId,
            amount = row.amount,
            occurredAt = localTime(row.occurredAt),
            wasCorrection = decided.isCorrection,
        )
        true
    }

    /**
     * "It's real" on a held message: the transaction it describes is banked
     * and the message leaves the alert list, in one transaction.
     *
     * The status is `confirmed` rather than whatever confidence would have
     * chosen. A person looked at this message and vouched for it, and asking
     * them to confirm it a second time on the review screen would be the app
     * disagreeing with a decision it just asked for.
     */
    suspend fun approveHeld(id: String): Ingest = withContext(Dispatchers.IO) {
        val row = dao.quarantineById(id) ?: return@withContext Ingest.NotFinancial

        // Re-read the stored body with the same parser the live path uses,
        // rather than rebuilding from the columns beside it. The quarantine
        // table has no reference-number column, and the reference is part of
        // the dedup key — so a message approved from its columns would get a
        // different key than the one the capture path would have written, and
        // the bank's own confirmation of the same purchase would land twice.
        val parsed = Parsing.parse(row.body).takeIf { it.amount != null }
            ?: ParsedSms(
                amount = row.amount,
                type = row.type ?: "expense",
                rawMerchant = row.rawMerchant,
                occurredAt = row.occurredAt?.let { localTime(it) },
            )

        val amount = parsed.amount ?: run {
            // Nothing to file. The screen only offers approval when there is
            // an amount, so this is a stale tap rather than a normal path.
            dao.setQuarantineStatus(id, "approved")
            return@withContext Ingest.NotFinancial
        }

        val at = parsed.occurredAt ?: row.occurredAt?.let { localTime(it) }
            ?: localTime(row.createdAt)
        val resolved = resolveMerchant(parsed.rawMerchant, amount, at)
        val tx = TransactionEntity(
            id = newId(),
            amount = amount,
            type = parsed.type,
            categoryId = resolved.categoryId,
            rawMerchant = parsed.rawMerchant,
            merchantName = resolved.merchantName,
            referenceNumber = parsed.referenceNumber,
            occurredAt = millis(at),
            source = "sms",
            confidence = resolved.confidence,
            status = "confirmed",
            dedupKey = sha256(Pipeline.dedupKey(parsed, at)),
            smsSender = row.sender,
            createdAt = System.currentTimeMillis(),
        )
        val banked = dao.bankHeldMessage(tx, id)
        if (banked) raiseFraudAlerts(tx)
        // Senders.assess reads confirmedCount back as evidence, so vouching
        // for a message is what stops the next one from the same sender
        // being held too.
        row.sender?.let { dao.noteSenderConfirmed(Senders.normalizeSender(it)) }
        if (banked) Ingest.Captured(tx.id, resolved.categoryId == null)
        else Ingest.Duplicate(tx.dedupKey!!)
    }

    /** Discarded, not deleted: the row survives with a rejected status, so a
     *  mistaken reject stays recoverable and a repeat of the same scam is
     *  still recognised by its body hash. */
    suspend fun rejectHeld(id: String) = dao.setQuarantineStatus(id, "rejected")

    /**
     * Discard the message and block whoever sent it, in one decision.
     *
     * The sender list can do this too, but the moment a person is certain is
     * the moment they are reading the scam — sending them off to find the
     * same sender in a list afterwards is how a repeat offender keeps its
     * welcome.
     *
     * @return the sender that was blocked, or null if the message carried no
     *   usable sender; the message is discarded either way.
     */
    suspend fun rejectHeldAndBlock(id: String): String? = withContext(Dispatchers.IO) {
        val row = dao.quarantineById(id)
        dao.setQuarantineStatus(id, "rejected")
        val key = row?.sender?.let { Senders.normalizeSender(it) }?.ifEmpty { null }
        key?.takeIf { setSenderTrust(it, Senders.TRUST_BLOCKED) }
    }

    /** What happened to an incoming message, for the caller to log or show. */
    sealed class Ingest {
        data class Captured(val id: String, val needsCategory: Boolean) : Ingest()
        data class Held(val reason: String, val risk: Int) : Ingest()
        data class Duplicate(val id: String) : Ingest()
        data object NotFinancial : Ingest()
    }

    /**
     * The whole SMS pipeline, in the order the decisions have to happen.
     *
     * Parse first, and only then judge the sender. The parse gate is entirely
     * generic on purpose: a sender profile can enrich a message that already
     * looks like a transaction, but it must never be able to MAKE something a
     * transaction — otherwise a spoofed header could inject rows.
     *
     * @param live a message arriving now, rather than one the inbox scan is
     *   walking past for the fourth time. A rescan must not be able to change
     *   anything a first pass already decided: without this the same held
     *   scam reports "Seen 6 times" after six launches, and every sender's
     *   message counts drift upward on their own.
     * @param receivedAt when the message reached the phone. Used only where
     *   the message itself carries no date — and there it is load-bearing,
     *   because the dedup key falls back to it. Reading the clock instead
     *   would give the same undated purchase a new key every day.
     */
    suspend fun ingestSms(
        sender: String?,
        body: String,
        live: Boolean = true,
        receivedAt: LocalDateTime = LocalDateTime.now(),
    ): Ingest = withContext(Dispatchers.IO) {
        if (!live) {
            // Already banked. Checked first because the alternative is paying
            // for a full merchant resolution — which retrains the categoriser
            // over the whole ledger — once per already-captured message, on
            // every launch.
            alreadyBanked(body, receivedAt)?.let { return@withContext Ingest.Duplicate(it) }
            dao.quarantineByHash(sha256(body))?.let { seen ->
                return@withContext Ingest.Held(
                    seen.reason ?: "Held for you to check.", seen.risk)
            }
        }

        // The decision sequence itself lives in :core and is covered by 24
        // JVM checks. This function only supplies the lookups and writes the
        // result — if a rule appears to be decided here, it is in the wrong
        // file.
        //
        // :core has no coroutines dependency at all, so the two lookups
        // Pipeline.ingest takes are ordinary functions. Both need the
        // database, and each bridges that gap differently below because the
        // two lookups cost very different amounts.

        // Pipeline asks for this sender under a key derivable here, so the row
        // is read once up front and the callback stays pure. The Capture
        // branch below reuses it instead of asking again.
        val senderKey = sender?.let { Senders.normalizeSender(it) }
        val senderRow = senderKey?.let { dao.senderByName(it) }

        // The two sliders on the settings screen are these. Read per message
        // rather than cached, so moving one changes the next message that
        // arrives instead of the next launch.
        val prefs = dao.settingsRow() ?: SettingsEntity()

        val outcome = Pipeline.ingest(
            sender = sender,
            body = body,
            now = receivedAt,
            lookupSender = { key ->
                // Fail closed on a key that was not the one prefetched:
                // handing back some other sender's reputation would let one
                // sender's trust decide another sender's quarantine.
                senderRow?.takeIf { key == senderKey }
                    ?.let { SenderRegistry(it.trust, it.confirmedCount, it.quarantinedCount) }
            },
            resolve = { raw, amount, at ->
                // Prefetching this one would mean resolving every message that
                // reaches the parser. Pipeline calls resolve only after a
                // message has cleared the parse and sender gates, and
                // resolveMerchant retrains the categoriser over the whole
                // ledger — a cost an inbox rescan must not pay for every OTP
                // and promo it walks past. So the laziness is kept and one IO
                // thread is parked for the lookup instead.
                val r = runBlocking { resolveMerchant(raw, amount, at) }
                Pipeline.Resolution(r.merchantName, r.categoryId, r.confidence)
            },
            autoAt = prefs.autoSaveThreshold,
            confirmAt = prefs.confirmThreshold,
        )

        when (outcome) {
            is Pipeline.Outcome.NotFinancial -> Ingest.NotFinancial

            is Pipeline.Outcome.Hold -> {
                recordSender(outcome.verdict, captured = false)
                holdMessage(sender, body, outcome.parsed, outcome.verdict)
                Ingest.Held(outcome.reason, outcome.verdict.risk)
            }

            is Pipeline.Outcome.Capture -> {
                sender?.let {
                    recordSender(Senders.assess(it, body,
                        senderRow?.let { r ->
                            SenderRegistry(r.trust, r.confirmedCount, r.quarantinedCount) }),
                        captured = true)
                }
                val at = outcome.parsed.occurredAt ?: receivedAt
                val tx = TransactionEntity(
                    id = newId(),
                    amount = outcome.parsed.amount!!,
                    type = outcome.parsed.type,
                    categoryId = outcome.categoryId,
                    rawMerchant = outcome.parsed.rawMerchant,
                    merchantName = outcome.merchantName,
                    referenceNumber = outcome.parsed.referenceNumber,
                    occurredAt = millis(at),
                    source = "sms",
                    confidence = outcome.confidence,
                    status = outcome.status,
                    dedupKey = sha256(outcome.dedupKey),
                    smsSender = sender,
                    createdAt = System.currentTimeMillis(),
                )
                // insert returns -1 when the unique dedupKey already exists,
                // which is what makes rescanning the inbox harmless.
                if (dao.insert(tx) == -1L) Ingest.Duplicate(tx.dedupKey!!)
                else {
                    raiseFraudAlerts(tx)
                    Ingest.Captured(tx.id, outcome.needsCategory)
                }
            }
        }
    }

    /**
     * Run the detectors over one newly filed expense and store what they say.
     *
     * Only where a row is INSERTED. An edit is the user restating a
     * transaction they already have, and re-evaluating there would mean
     * fixing a typo in a note raises a duplicate-charge alert against the
     * charge's own earlier self.
     *
     * The whole ledger goes in because every detector measures this charge
     * against the user's own distribution — a mean, a spread, a daily average
     * — and there is no smaller slice those can be computed from. The write
     * is an IGNORE on the alert's unique key, so reaching here twice for the
     * same charge still leaves one alert rather than two.
     */
    private suspend fun raiseFraudAlerts(tx: TransactionEntity) {
        if (tx.type != "expense" || tx.isDeleted) return
        // The launch scan backfills ninety days of inbox at once, and a
        // transaction can also be hand-entered from an old receipt. :core
        // decides how old is too old to be worth saying anything about.
        if (!Fraud.worthAlerting(localTime(tx.occurredAt), LocalDateTime.now())) return
        // Read per transaction rather than cached: a limit typed on the
        // settings screen has to apply to the next message that arrives, not
        // the next launch.
        val limit = (dao.settingsRow() ?: SettingsEntity()).highValueAmount
        val alerts = Fraud.evaluate(fraudTx(tx), dao.all().map { fraudTx(it) }, limit)
        if (alerts.isEmpty()) return
        val now = System.currentTimeMillis()
        dao.insertAlerts(alerts.map {
            FraudAlertEntity(newId(), it.key, it.txId, it.kind, it.severity, it.message,
                createdAt = now)
        })
    }

    /** The resolved payee, never the bank's raw string. "First charge at this
     *  payee" has to mean a payee: two spellings of one shop would each look
     *  like a first visit, and every unresolved capture would look like a
     *  payee nobody has ever paid. */
    private fun fraudTx(t: TransactionEntity) =
        Tx(t.id, t.amount, t.type, localTime(t.occurredAt), t.merchantName, t.categoryId)

    /**
     * The dedup key this message would be filed under, if the ledger already
     * holds it.
     *
     * Derived exactly as the capture path derives it — the same parse, the
     * same fallback to `now` for an undated message, the same hash — because
     * a key computed even slightly differently would answer "no" to a message
     * that is already there and hand back a duplicate.
     */
    private suspend fun alreadyBanked(body: String, receivedAt: LocalDateTime): String? {
        val parsed = Parsing.parse(body)
        if (parsed.amount == null || !parsed.matched) return null
        val key = sha256(Pipeline.dedupKey(parsed, parsed.occurredAt ?: receivedAt))
        return if (dao.idByDedupKey(key) != null) key else null
    }

    data class Resolved(val merchantName: String?, val categoryId: String?, val confidence: Int)

    /**
     * Name and category for a raw payee string, best evidence first:
     * what this user has taught the app, then the built-in seed table, then
     * the on-device categoriser, then nothing.
     */
    suspend fun resolveMerchant(rawMerchant: String?, amount: Double?,
                                occurredAt: LocalDateTime?): Resolved {
        val normalized = Engine.normalizeMerchant(rawMerchant)
        if (normalized.isEmpty()) return Resolved(null, null, 0)

        dao.learningFor(normalized)?.let { row ->
            val learning = LearningRow(
                row.rawName, row.merchantName, row.categoryId, row.confirmationCount,
                row.correctionCount, row.sampleCount, row.avgAmount, row.amountMin,
                row.amountMax, parseHistogram(row.hourHistogram))
            val b = Engine.score(learning, amount, row.categoryId, occurredAt)
            return Resolved(row.merchantName, row.categoryId, b.total)
        }

        Engine.seedLookup(normalized)?.let { (display, categoryName) ->
            val cat = dao.allCategories().firstOrNull { it.name == categoryName }
            return Resolved(display, cat?.id, Engine.SEED_CONFIDENCE)
        }

        // Nothing learned and not a seed: ask the categoriser trained on this
        // user's own corrections. It declines below its confidence floor,
        // which is why a wrong category is rare rather than merely unlikely.
        val suggestion = trainedModel()?.predict(normalized)
        return Resolved(titleCase(normalized), suggestion?.first,
            suggestion?.let { (it.second * 100).toInt().coerceAtMost(70) } ?: 0)
    }

    /** Retrained on demand from the user's own categorised transactions.
     *  Cached per call site rather than globally, so a category corrected a
     *  moment ago is reflected immediately instead of being stale. */
    private suspend fun trainedModel(): Categorizer.Model? {
        val rows = dao.all()
            .filter { it.categoryId != null }
            .map { (it.merchantName ?: it.rawMerchant ?: "") to it.categoryId }
        return Categorizer.train(rows)
    }

    /** Record that the user accepted (or corrected) a mapping. This is the
     *  only thing that ever teaches the engine — there is no other source. */
    suspend fun recordConfirmation(rawMerchant: String, merchantName: String,
                                   categoryId: String?, amount: Double?,
                                   occurredAt: LocalDateTime?,
                                   wasCorrection: Boolean) = withContext(Dispatchers.IO) {
        val key = Engine.normalizeMerchant(rawMerchant).ifEmpty { return@withContext }
        val existing = dao.learningFor(key)
        val hours = parseHistogram(existing?.hourHistogram ?: "").toMutableList()
        while (hours.size < 24) hours.add(0)
        occurredAt?.let { hours[it.hour % 24] = hours[it.hour % 24] + 1 }

        val samples = (existing?.sampleCount ?: 0) + if (amount != null) 1 else 0
        val avg = if (amount != null && samples > 0)
            ((existing?.avgAmount ?: 0.0) * (samples - 1) + amount) / samples
        else existing?.avgAmount ?: 0.0

        dao.upsertLearning(LearningEntity(
            id = existing?.id ?: newId(),
            rawName = key,
            merchantName = merchantName,
            categoryId = categoryId ?: existing?.categoryId,
            confirmationCount = (existing?.confirmationCount ?: 0) + if (wasCorrection) 0 else 1,
            correctionCount = (existing?.correctionCount ?: 0) + if (wasCorrection) 1 else 0,
            sampleCount = samples,
            avgAmount = avg,
            amountMin = minOfNonZero(existing?.amountMin, amount),
            amountMax = maxOf(existing?.amountMax ?: 0.0, amount ?: 0.0),
            hourHistogram = hours.joinToString(","),
            lastSeenAt = System.currentTimeMillis(),
        ))

        val merchantKey = Engine.normalizeMerchant(merchantName)
        if (merchantKey.isNotEmpty() && dao.merchantByName(merchantKey) == null) {
            dao.insertMerchant(MerchantEntity(newId(), merchantKey, categoryId))
        }
    }

    private suspend fun holdMessage(sender: String?, body: String, parsed: ParsedSms,
                                    verdict: RiskAssessment) {
        val hash = sha256(body)
        val existing = dao.quarantineByHash(hash)
        dao.upsertQuarantine(QuarantineEntity(
            id = existing?.id ?: newId(),
            sender = sender,
            body = body,
            bodyHash = hash,
            risk = verdict.risk,
            indicators = verdict.indicators.joinToString(","),
            reason = Senders.explain(verdict),
            amount = parsed.amount,
            type = parsed.type,
            rawMerchant = parsed.rawMerchant,
            occurredAt = parsed.occurredAt?.let { millis(it) },
            // Seeing the same scam repeatedly is itself information.
            seenCount = (existing?.seenCount ?: 0) + 1,
            status = existing?.status ?: "held",
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        ))
    }

    private suspend fun recordSender(v: RiskAssessment, captured: Boolean) {
        val key = v.sender.normalized.ifEmpty { return }
        val now = System.currentTimeMillis()
        val existing = dao.senderByName(key)
        dao.upsertSender(SenderEntity(
            id = existing?.id ?: newId(),
            sender = key,
            display = v.sender.bank ?: existing?.display,
            kind = v.sender.kind,
            entity = v.sender.entity,
            bank = v.sender.bank,
            // A user decision is never overwritten by a heuristic.
            trust = existing?.trust?.takeIf {
                it == Senders.TRUST_TRUSTED || it == Senders.TRUST_BLOCKED
            } ?: v.trust,
            messageCount = (existing?.messageCount ?: 0) + 1,
            capturedCount = (existing?.capturedCount ?: 0) + if (captured) 1 else 0,
            confirmedCount = existing?.confirmedCount ?: 0,
            quarantinedCount = (existing?.quarantinedCount ?: 0) + if (captured) 0 else 1,
            lastRisk = v.risk,
            firstSeenAt = existing?.firstSeenAt ?: now,
            lastSeenAt = now,
        ))
    }

    /** The ledger as the core module wants it. */
    suspend fun ledger(): List<Tx> = withContext(Dispatchers.IO) {
        dao.all().map {
            Tx(it.id, it.amount, it.type, localTime(it.occurredAt),
               it.merchantName ?: it.rawMerchant, it.categoryId)
        }
    }

    /**
     * Named outcomes rather than a boolean, because the two failures need
     * different sentences: a blank name is something the user can fix by
     * typing, a duplicate is them already having what they asked for.
     */
    enum class CategoryResult { CREATED, RENAMED, DUPLICATE, INVALID }

    /**
     * Seed the built-in categories, once, into an empty table.
     *
     * `Engine.SEED_MERCHANTS` files Swiggy under "Food & Dining" by NAME, and
     * `resolveMerchant` turns that name into an id by looking it up here — so
     * with no rows in this table the seed table silently resolves nothing and
     * every captured transaction lands uncategorised.
     *
     * Only into an empty table. Re-adding them whenever one is missing would
     * resurrect a category the user archived, and undo a restore that
     * deliberately carried a different set.
     */
    suspend fun ensureDefaultCategories() = withContext(Dispatchers.IO) {
        if (dao.allCategories().isNotEmpty()) return@withContext
        dao.insertCategories(DEFAULT_CATEGORIES.map { (name, type, color) ->
            CategoryEntity(id = newId(), name = name, type = type, color = color)
        })
    }

    suspend fun createCategory(name: String, type: String): CategoryResult =
        withContext(Dispatchers.IO) {
            val clean = name.trim()
            if (clean.isEmpty()) return@withContext CategoryResult.INVALID
            if (dao.categoryByName(clean) != null) return@withContext CategoryResult.DUPLICATE
            // IGNORE on the unique name index, so two taps on a slow device
            // cannot produce two categories with the same name.
            val rowId = dao.insertCategory(CategoryEntity(
                id = newId(), name = clean, type = type,
                color = DEFAULT_CATEGORIES.firstOrNull { it.first == clean }?.third
                    ?: Budgets.DEFAULT_COLOR,
            ))
            if (rowId == -1L) CategoryResult.DUPLICATE else CategoryResult.CREATED
        }

    /** Rename in place. The id is what transactions point at, so renaming
     *  never has to touch — or risk orphaning — a single row that uses it. */
    suspend fun renameCategory(id: String, name: String): CategoryResult =
        withContext(Dispatchers.IO) {
            val clean = name.trim()
            if (clean.isEmpty()) return@withContext CategoryResult.INVALID
            val row = dao.allCategories().firstOrNull { it.id == id }
                ?: return@withContext CategoryResult.INVALID
            if (clean == row.name) return@withContext CategoryResult.RENAMED
            val clash = dao.categoryByName(clean)
            if (clash != null && clash.id != id) return@withContext CategoryResult.DUPLICATE
            dao.updateCategory(row.copy(name = clean))
            CategoryResult.RENAMED
        }

    /** Null clears the budget, which is a different state from zero — see
     *  `Budget.amount` in :core. */
    suspend fun setBudget(categoryId: String, amount: Double?) =
        dao.setBudget(categoryId, amount)

    /** Read-modify-write of the one settings row, serialised against the
     *  restore path so neither can lose the other's change. */
    suspend fun updateSettings(mutate: (SettingsEntity) -> SettingsEntity) =
        withContext(Dispatchers.IO) {
            settingsGate.withLock {
                dao.putSettings(mutate(dao.settingsRow() ?: SettingsEntity()))
            }
        }

    /** Recorded so the introduction is shown once rather than every launch. */
    suspend fun markOnboardingSeen() = updateSettings { it.copy(onboardingSeen = true) }

    suspend fun clearUnreviewedCaptures(): Int = withContext(Dispatchers.IO) {
        dao.clearUnreviewedCaptures()
    }

    /** How many captures are waiting, for the badge that leads to them. A
     *  count query, so a screen that only shows the number does not load the
     *  rows behind it. */
    fun reviewCount(): Flow<Int> = dao.needingReviewCount()

    /**
     * The review queue as questions rather than rows.
     *
     * Grouping and the pre-filled suggestions are [Review] in :core, covered
     * by its own JVM checks; this only supplies the rows and the model. It is
     * a Flow so that answering one group removes it — the rows change, the
     * queue re-emits, and the screen has one fewer question on it without
     * anything having to remember to reload.
     */
    fun reviewQueue(): Flow<List<Review.Group>> = dao.needingReview().map { rows ->
        withContext(Dispatchers.IO) {
            val groups = Review.group(rows.map { r ->
                Review.Item(r.id, r.amount, r.type, localTime(r.occurredAt),
                    r.rawMerchant, r.merchantName, r.smsSender)
            })
            // Archived categories are excluded rather than merely unlikely:
            // the model can still name one, and the picker below the
            // suggestion cannot show it.
            Review.suggest(groups, trainedModel(),
                dao.allCategories().filterNot { it.isArchived }.map { it.id }.toSet())
        }
    }

    /**
     * One category for every capture from one payee, and the engine learns
     * from it.
     *
     * The learning is the half that makes this worth more than a fast way to
     * type the same thing twenty times: a confirmed group is that many
     * pieces of evidence about the payee's amounts and hours, so the next
     * message from them is filed without asking at all.
     *
     * @param newCategoryName used only when [categoryId] is null — the
     *   category the user typed instead of picking, created if this is the
     *   first time they have named it.
     * @return rows actually written, which is what the user is told.
     */
    suspend fun categoriseReviewGroup(
        group: Review.Group,
        categoryId: String? = null,
        newCategoryName: String? = null,
    ): Int = withContext(Dispatchers.IO) {
        val target = resolveCategory(categoryId, newCategoryName, group.type)
            ?: return@withContext 0
        // Blank means the captures resolved to no payee at all. Writing that
        // as a name would replace "unknown" with "", which is the same
        // absence spelled in a way the ledger cannot tell from a real name.
        val name = group.key.ifBlank { null }
        val written = dao.categoriseGroup(group.ids, target, name)
        for (row in Review.teachFrom(group)) {
            // teachFrom hands back only rows that carry one; the learning
            // table is keyed on it.
            val raw = row.rawMerchant ?: continue
            recordConfirmation(
                rawMerchant = raw,
                merchantName = name ?: raw,
                categoryId = target,
                amount = row.amount,
                occurredAt = row.occurredAt,
                // Nobody had categorised these rows, so there is no earlier
                // answer for this to be a correction of.
                wasCorrection = false,
            )
        }
        written
    }

    /** "Not a transaction", for a whole group of junk from one sender. Soft,
     *  like every other delete here. */
    suspend fun discardReviewGroup(group: Review.Group): Int = withContext(Dispatchers.IO) {
        dao.discardGroup(group.ids)
    }

    /** The category a review decision lands in: the one tapped, or the one
     *  typed — found if it already exists, created if it does not. */
    private suspend fun resolveCategory(id: String?, typed: String?, type: String): String? {
        if (id != null) return dao.allCategories().firstOrNull { it.id == id }?.id
        // Capped at the length the picker can show. A category whose name
        // does not fit on a chip is one the user cannot recognise later.
        val clean = typed?.trim()?.take(MAX_CATEGORY_NAME).orEmpty()
        if (clean.isEmpty()) return null
        createCategory(clean, type)
        return dao.categoryByName(clean)?.id
    }

    /**
     * The whole ledger as a backup file.
     *
     * Soft-deleted rows are included: dropping them would turn the undo
     * behind every delete into a one-way door the moment the file was
     * restored, and their ids are what stops a re-import resurrecting
     * something the user threw away.
     */
    suspend fun backupText(now: LocalDateTime = LocalDateTime.now()): String =
        withContext(Dispatchers.IO) {
            val prefs = dao.settingsRow() ?: SettingsEntity()
            Backup.write(Backup.Document(
                createdAt = Backup.stamp(now),
                categories = dao.allCategories().map { c ->
                    Backup.Category(c.id, c.name, c.type, c.icon, c.color,
                        c.budgetAmount, c.isArchived)
                },
                merchants = dao.allMerchants().map { Backup.Merchant(it.id, it.canonicalName, it.categoryId) },
                learning = dao.allLearning().map { l ->
                    Backup.Learned(l.id, l.rawName, l.merchantName, l.categoryId,
                        l.confirmationCount, l.correctionCount, l.sampleCount, l.avgAmount,
                        l.amountMin, l.amountMax, l.hourHistogram, l.lastSeenAt?.let { localTime(it) })
                },
                transactions = dao.everyTransaction().map { t ->
                    Backup.Txn(t.id, t.amount, t.type, localTime(t.occurredAt),
                        localTime(t.createdAt), t.categoryId, t.rawMerchant, t.merchantName,
                        t.notes, t.referenceNumber, t.source, t.confidence, t.status,
                        t.categoryPrompted, t.dedupKey, t.isDeleted)
                },
                senders = dao.allSenders().map { s ->
                    Backup.Sender(s.id, s.sender, localTime(s.firstSeenAt), localTime(s.lastSeenAt),
                        s.display, s.kind, s.entity, s.bank, s.trust, s.messageCount,
                        s.capturedCount, s.confirmedCount, s.quarantinedCount, s.lastRisk)
                },
                prefs = Backup.Prefs(prefs.currency, prefs.theme, prefs.autoSaveThreshold,
                    prefs.confirmThreshold, prefs.highValueAmount),
            ))
        }

    /** Validation only. Nothing is written until [applyRestore], so the user
     *  can be told what is in the file before deciding. */
    suspend fun readBackup(bytes: ByteArray): Backup.Read = withContext(Dispatchers.IO) {
        Backup.read(bytes)
    }

    /**
     * Land a validated backup.
     *
     * :core decides WHAT lands — [Backup.plan] filters out everything this
     * ledger already holds — and its KDoc says it cannot enforce HOW. That
     * guarantee is [SpendDao.applyBackup]: one Room `@Transaction`, in the
     * plan's own order, so a restore interrupted by a crash, a kill or a flat
     * battery leaves the ledger the user already had rather than half of two.
     *
     * The plan is returned so the caller can say what actually happened
     * instead of "done".
     */
    suspend fun applyRestore(doc: Backup.Document, replace: Boolean): Backup.Plan =
        withContext(Dispatchers.IO) {
            settingsGate.withLock {
                val plan = Backup.plan(doc, existingKeys(), replace)
                val current = dao.settingsRow() ?: SettingsEntity()
                dao.applyBackup(
                    clearFirst = plan.clearFirst,
                    categories = plan.categories.map { c ->
                        CategoryEntity(c.id, c.name, c.type, c.icon, c.color,
                            c.budgetAmount, c.isArchived)
                    },
                    merchants = plan.merchants.map { MerchantEntity(it.id, it.canonicalName, it.categoryId) },
                    learning = plan.learning.map { l ->
                        LearningEntity(l.id, l.rawName, l.merchantName, l.categoryId,
                            l.confirmationCount, l.correctionCount, l.sampleCount, l.avgAmount,
                            l.amountMin, l.amountMax, l.hourHistogram, l.lastSeenAt?.let { millis(it) })
                    },
                    transactions = plan.transactions.map { t ->
                        TransactionEntity(
                            id = t.id, amount = t.amount, type = t.type,
                            categoryId = t.categoryId, rawMerchant = t.rawMerchant,
                            merchantName = t.merchantName, notes = t.notes,
                            referenceNumber = t.referenceNumber,
                            occurredAt = millis(t.occurredAt), source = t.source,
                            confidence = t.confidence, status = t.status,
                            categoryPrompted = t.categoryPrompted, dedupKey = t.dedupKey,
                            // Not carried by the format: a restored row keeps
                            // its dedup key, which is what identifies the
                            // purchase, but not the header it arrived under.
                            smsSender = null,
                            isDeleted = t.isDeleted, createdAt = millis(t.createdAt),
                        )
                    },
                    senders = plan.senders.map { s ->
                        SenderEntity(s.id, s.sender, s.display, s.kind, s.entity, s.bank,
                            s.trust, s.messageCount, s.capturedCount, s.confirmedCount,
                            s.quarantinedCount, s.lastRisk, millis(s.firstSeenAt),
                            millis(s.lastSeenAt))
                    },
                    // A null field in the file means "the file carried no
                    // usable value", so the device keeps what it has. Whether
                    // onboarding was seen is never taken from a file: the
                    // person holding the phone has plainly been through it.
                    settings = plan.prefs?.let { p ->
                        current.copy(
                            currency = p.currency ?: current.currency,
                            theme = p.theme ?: current.theme,
                            autoSaveThreshold = p.autoSaveThreshold ?: current.autoSaveThreshold,
                            confirmThreshold = p.confirmThreshold ?: current.confirmThreshold,
                            highValueAmount = p.highValueAmount ?: current.highValueAmount,
                        )
                    },
                )
                plan
            }
        }

    /** The keys already here, so the plan can skip what would collide. The
     *  natural keys go in as well as the ids because the unique indexes
     *  refuse a duplicate name whatever id it arrives under. */
    private suspend fun existingKeys(): Backup.Existing {
        val txs = dao.everyTransaction()
        val cats = dao.allCategories()
        val merchants = dao.allMerchants()
        val learning = dao.allLearning()
        val senders = dao.allSenders()
        return Backup.Existing(
            transactionIds = txs.mapTo(HashSet()) { it.id },
            dedupKeys = txs.mapNotNullTo(HashSet()) { it.dedupKey },
            categoryIds = cats.mapTo(HashSet()) { it.id },
            categoryNames = cats.mapTo(HashSet()) { it.name },
            merchantIds = merchants.mapTo(HashSet()) { it.id },
            merchantNames = merchants.mapTo(HashSet()) { it.canonicalName },
            learningIds = learning.mapTo(HashSet()) { it.id },
            learningRawNames = learning.mapTo(HashSet()) { it.rawName },
            senderIds = senders.mapTo(HashSet()) { it.id },
            senderNames = senders.mapTo(HashSet()) { it.sender },
        )
    }

    /**
     * A spreadsheet, not a backup.
     *
     * Deliberately lossy — no learning, no senders, no dedup keys — which is
     * exactly why the settings screen calls it "a report for a spreadsheet"
     * and offers it beside the backup rather than instead of it.
     */
    suspend fun csvExport(): String = withContext(Dispatchers.IO) {
        val names = dao.allCategories().associateBy({ it.id }, { it.name })
        buildString {
            append("date,type,amount,merchant,category,notes,reference,source,status\n")
            for (t in dao.all()) {
                append(localTime(t.occurredAt).format(CsvDate)).append(',')
                append(t.type).append(',')
                append(csvAmount(t.amount)).append(',')
                append(csvCell(t.merchantName ?: t.rawMerchant)).append(',')
                append(csvCell(t.categoryId?.let { names[it] })).append(',')
                append(csvCell(t.notes)).append(',')
                append(csvCell(t.referenceNumber)).append(',')
                append(t.source).append(',')
                append(t.status).append('\n')
            }
        }
    }

    /**
     * The held messages, as text the user can send to whoever can fix the
     * parser.
     *
     * Nothing leaves the phone on its own — this produces a string, and the
     * only thing that moves it anywhere is the user choosing a destination in
     * the system file picker.
     */
    suspend fun heldExport(): String = withContext(Dispatchers.IO) {
        val rows = dao.allHeld()
        buildString {
            append("SpendWise — messages that could not be banked\n")
            append("${rows.size} message(s). Nothing here was added to your ledger.\n")
            for (q in rows) {
                append("\n--------------------------------\n")
                append("sender: ").append(q.sender ?: "unknown").append('\n')
                append("seen:   ").append(localTime(q.createdAt)).append('\n')
                append("times:  ").append(q.seenCount).append('\n')
                append("reason: ").append(q.reason ?: "-").append('\n')
                append("risk:   ").append(q.risk).append('\n')
                append("body:\n").append(q.body).append('\n')
            }
        }
    }

    private fun parseHistogram(raw: String): List<Int> =
        raw.split(",").mapNotNull { it.trim().toIntOrNull() }

    private fun minOfNonZero(existing: Double?, amount: Double?): Double = when {
        amount == null -> existing ?: 0.0
        existing == null || existing == 0.0 -> amount
        else -> minOf(existing, amount)
    }

    private fun titleCase(s: String): String = s.split(" ")
        .filter { it.isNotEmpty() }
        .joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
}
