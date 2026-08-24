package com.jeevavibeapp.spendwise.data

import com.jeevavibeapp.spendwise.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
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
        fun newId(): String = UUID.randomUUID().toString()

        fun millis(t: LocalDateTime): Long =
            t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        fun localTime(ms: Long): LocalDateTime =
            LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())

        fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
                .joinToString("") { "%02x".format(it) }
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
     */
    suspend fun ingestSms(sender: String?, body: String): Ingest = withContext(Dispatchers.IO) {
        // The decision sequence itself lives in :core and is covered by 24
        // JVM checks. This function only supplies the lookups and writes the
        // result — if a rule appears to be decided here, it is in the wrong
        // file.
        val outcome = Pipeline.ingest(
            sender = sender,
            body = body,
            now = LocalDateTime.now(),
            lookupSender = { key ->
                dao.senderByName(key)?.let {
                    SenderRegistry(it.trust, it.confirmedCount, it.quarantinedCount)
                }
            },
            resolve = { raw, amount, at ->
                val r = resolveMerchant(raw, amount, at)
                Pipeline.Resolution(r.merchantName, r.categoryId, r.confidence)
            },
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
                        dao.senderByName(Senders.normalizeSender(it))?.let { r ->
                            SenderRegistry(r.trust, r.confirmedCount, r.quarantinedCount) }),
                        captured = true)
                }
                val at = outcome.parsed.occurredAt ?: LocalDateTime.now()
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
                else Ingest.Captured(tx.id, outcome.needsCategory)
            }
        }
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
