package com.jeevavibeapp.spendwise.core

import java.time.LocalDateTime
import kotlin.math.roundToInt

/**
 * The review queue, folded from rows into decisions.
 *
 * A week of capture leaves a list of individually-unfixable rows, but they
 * come from far fewer payees than there are rows — so grouping by payee turns
 * "categorise two hundred transactions" into "answer twenty questions", and
 * each answer teaches the engine enough that the payee never asks again.
 *
 * Nothing here reads or writes anything. The caller supplies the rows and the
 * trained model and decides what to persist, which is what lets the whole
 * grouping and suggestion behaviour be tested on a JVM in milliseconds.
 */
object Review {

    /**
     * How many rows of a confirmed group are fed back to the engine.
     *
     * A bulk confirmation is that many pieces of evidence about one payee's
     * amounts and hours, and learning from one of them would throw the rest
     * away — but a group can hold hundreds, and every row costs a
     * read-modify-write of the same learning row.
     */
    const val LEARN_LIMIT = 25

    /** One row awaiting review. */
    data class Item(
        val id: String,
        val amount: Double,
        val type: String,
        val occurredAt: LocalDateTime,
        val rawMerchant: String? = null,
        val merchantName: String? = null,
        val sender: String? = null,
    ) {
        /**
         * What this row is filed under: the resolved name if it has one, else
         * the raw payee string off the message, else nothing.
         *
         * Blank is a real group, not a dropped row. Captures that resolved to
         * no payee at all are exactly the ones most likely to be junk, and
         * leaving them out of the queue would leave the user no way to clear
         * them in bulk.
         */
        val key: String get() =
            merchantName?.trim().orEmpty().ifEmpty { rawMerchant?.trim().orEmpty() }
    }

    /** Every unreviewed row from one payee, as one decision. */
    data class Group(
        val key: String,
        val type: String,
        val items: List<Item>,
        val suggestedCategoryId: String? = null,
        /** Percent, so the screen prints a number rather than deriving one. */
        val suggestedConfidence: Int = 0,
        val because: List<String> = emptyList(),
    ) {
        /** Stable across re-groupings of the same queue, so a list can key
         *  rows on it without rebuilding them whenever a total changes. */
        val groupId: String get() = "$type\u0000$key"
        val count: Int get() = items.size
        val total: Double get() = items.sumOf { it.amount }
        val firstAt: LocalDateTime? get() = items.minOfOrNull { it.occurredAt }
        val lastAt: LocalDateTime? get() = items.maxOfOrNull { it.occurredAt }
        val ids: List<String> get() = items.map { it.id }

        /** The raw payee string off the message. Carries the tokens the
         *  resolved name has already had normalised away, which is signal the
         *  categoriser can use. */
        val rawSample: String? get() =
            items.firstNotNullOfOrNull { it.rawMerchant?.trim()?.ifEmpty { null } }

        val sender: String? get() =
            items.firstNotNullOfOrNull { it.sender?.trim()?.ifEmpty { null } }
    }

    /**
     * Group by payee AND type, biggest group first.
     *
     * Type is part of the key because one decision is applied to the whole
     * group: a refund and a purchase from the same shop are not the same
     * question, and answering them together would file income as spending.
     */
    fun group(items: List<Item>): List<Group> {
        val buckets = LinkedHashMap<String, MutableList<Item>>()
        for (item in items) {
            buckets.getOrPut(item.key + "\u0000" + item.type) { mutableListOf() }.add(item)
        }
        return buckets.values
            .map { rows -> Group(rows[0].key, rows[0].type, rows.toList()) }
            // Clearing the biggest group first is the most work the next tap
            // can do. The name breaks ties so the list does not reshuffle
            // between two identical groups on every recomposition.
            .sortedWith(compareByDescending<Group> { it.count }
                .thenByDescending { it.total }
                .thenBy { it.key })
    }

    /**
     * Pre-fill each group with a category learned from this user's own
     * history.
     *
     * This is the difference between picking a category for forty payees and
     * confirming forty pre-filled guesses. It is never applied on its own —
     * the group carries the suggestion, and the user still taps it — because
     * a silent auto-categorisation is one they cannot see to correct.
     *
     * @param knownCategoryIds the categories that still exist. A model
     *   trained before a category was archived can still name it, and
     *   suggesting a category the picker cannot show is a chip that does
     *   nothing when tapped.
     */
    fun suggest(
        groups: List<Group>,
        model: Categorizer.Model?,
        knownCategoryIds: Set<String>,
    ): List<Group> {
        if (model == null) return groups
        return groups.map { g ->
            // The resolved name and the raw string together: the ledger the
            // model was trained on stores the resolved name, and the raw
            // string is what carries the payee's own spelling. The Python
            // build also folds in a sample SMS body, which this schema does
            // not keep beside the transaction.
            val text = (g.key + " " + (g.rawSample ?: "")).trim()
            val hit = model.predict(text)
            if (hit == null || hit.first !in knownCategoryIds) g
            else g.copy(
                suggestedCategoryId = hit.first,
                suggestedConfidence = (hit.second * 100).roundToInt(),
                because = model.explain(text),
            )
        }
    }

    /**
     * The rows a confirmed group teaches the engine from.
     *
     * The cap is on rows examined, not on rows learned from: rows carrying no
     * raw payee string are dropped after it, because the learning table is
     * keyed on that string and a row without one has nothing to teach.
     */
    fun teachFrom(group: Group): List<Item> =
        group.items.take(LEARN_LIMIT).filter { !it.rawMerchant.isNullOrBlank() }
}
