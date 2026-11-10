package com.jeevavibeapp.spendwise.core

import kotlin.math.exp
import kotlin.math.ln

/**
 * Multinomial Naive Bayes over the user's OWN corrections, trained on device.
 *
 * There is no downloaded model and no server call: the only training data is
 * what this person has already categorised, which is both the privacy
 * property and the reason it works — a merchant string means whatever this
 * user has decided it means.
 */
object Categorizer {

    const val MODEL_VERSION = "2026.08.1-kt"

    /** Below this there is not enough signal to beat guessing, and a wrong
     *  confident suggestion costs more trust than no suggestion. */
    const val MIN_TRAINING_ROWS = 12
    const val MIN_ROWS_PER_CATEGORY = 2
    const val MIN_CONFIDENCE = 0.62
    private const val ALPHA = 1.0

    private val TOKEN = Regex("[^\\W_]+")

    /** Tokens carrying no category signal that appear in almost every
     *  SMS-derived merchant string. Left in, they dominate the vocabulary and
     *  flatten every posterior toward the prior. */
    private val STOPWORDS = setOf(
        "upi", "p2a", "p2m", "pvt", "ltd", "limited", "india", "indian", "in",
        "the", "and", "for", "from", "via", "ref", "no", "txn", "transaction",
        "payment", "paid", "debit", "debited", "credit", "credited", "account",
        "acct", "ac", "bank", "card", "inr", "rs", "to", "at", "on", "by", "of",
        "a", "an", "it", "com", "www", "http", "https")

    fun tokens(text: String?): List<String> =
        TOKEN.findAll((text ?: "").lowercase())
            .map { it.value }
            // Pure digits are account/reference fragments: unique per
            // transaction, so they only ever add noise.
            .filter { it.length >= 2 && !it.all { c -> c.isDigit() } && it !in STOPWORDS }
            .toList()

    /** Fit from (text, categoryId) pairs. Null when there is too little. */
    fun train(rows: List<Pair<String, String?>>): Model? {
        val docs = rows.mapNotNull { (text, cat) ->
            if (cat.isNullOrBlank()) null else tokens(text).takeIf { it.isNotEmpty() }?.let { it to cat }
        }
        if (docs.size < MIN_TRAINING_ROWS) return null

        val counts = mutableMapOf<String, MutableMap<String, Int>>()
        val totals = mutableMapOf<String, Int>()
        val docCounts = mutableMapOf<String, Int>()
        val vocab = mutableSetOf<String>()
        for ((toks, cat) in docs) {
            docCounts[cat] = (docCounts[cat] ?: 0) + 1
            val bucket = counts.getOrPut(cat) { mutableMapOf() }
            for (t in toks) {
                bucket[t] = (bucket[t] ?: 0) + 1
                totals[cat] = (totals[cat] ?: 0) + 1
                vocab += t
            }
        }

        // Drop classes with too few examples rather than letting one win on a
        // single coincidental token.
        val keep = docCounts.filterValues { it >= MIN_ROWS_PER_CATEGORY }.keys
        if (keep.size < 2) return null                 // nothing to discriminate

        val nDocs = keep.sumOf { docCounts[it]!! }
        val v = vocab.size
        val logPrior = mutableMapOf<String, Double>()
        val logLikelihood = mutableMapOf<String, Map<String, Double>>()
        val defaultLogl = mutableMapOf<String, Double>()
        for (cat in keep) {
            logPrior[cat] = ln(docCounts[cat]!!.toDouble() / nDocs)
            val denom = (totals[cat] ?: 0) + ALPHA * (v + 1)
            logLikelihood[cat] = counts[cat]!!.mapValues { (_, n) -> ln((n + ALPHA) / denom) }
            // Unseen token: the smoothed probability with a zero numerator.
            defaultLogl[cat] = ln(ALPHA / denom)
        }
        return Model(logPrior, logLikelihood, v, keep.sorted(), defaultLogl, nDocs)
    }

    class Model(
        val logPrior: Map<String, Double>,
        val logLikelihood: Map<String, Map<String, Double>>,
        val vocabSize: Int,
        val categories: List<String>,
        val defaultLogl: Map<String, Double>,
        val rows: Int,
    ) {
        /** (categoryId, probability), or null when unsure. */
        fun predict(text: String?): Pair<String, Double>? {
            val toks = tokens(text)
            if (toks.isEmpty()) return null
            val scores = logPrior.mapValues { (cat, prior) ->
                val likelihood = logLikelihood[cat] ?: emptyMap()
                val fallback = defaultLogl[cat] ?: 0.0
                prior + toks.sumOf { likelihood[it] ?: fallback }
            }
            if (scores.isEmpty()) return null
            val best = scores.maxByOrNull { it.value }!!.key
            // Log-sum-exp, so the number shown to the user is a real
            // probability rather than an arbitrary score.
            val top = scores[best]!!
            val denom = scores.values.sumOf { exp(it - top) }
            val probability = 1.0 / denom
            if (probability < MIN_CONFIDENCE) return null
            return best to probability
        }

        /** The tokens that pushed hardest toward the winning category. */
        fun explain(text: String?, limit: Int = 3): List<String> {
            val cat = predict(text)?.first ?: return emptyList()
            val likelihood = logLikelihood[cat] ?: emptyMap()
            val fallback = defaultLogl[cat] ?: 0.0
            val others = categories.filter { it != cat }
            return tokens(text).toSet().map { tok ->
                val mine = likelihood[tok] ?: fallback
                val rest = others.maxOfOrNull {
                    logLikelihood[it]?.get(tok) ?: defaultLogl[it] ?: fallback
                } ?: fallback
                (mine - rest) to tok
            }.filter { it.first > 0 }
                .sortedByDescending { it.first }
                .take(limit)
                .map { it.second }
        }
    }
}
