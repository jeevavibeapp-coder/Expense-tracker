package com.jeevavibeapp.spendwise.core

import java.time.LocalDateTime

/**
 * The "which category?" question an automatic capture leaves behind.
 *
 * A message the app filed by itself and could not categorise is invisible to
 * every screen that groups by category: it is absent from the budget bars and
 * from the report's breakdown, so the totals it is part of quietly stop
 * adding up. Nothing else in the app ever asks about it — a capture is not an
 * edit the user opened — so unless it is raised as a question it stays
 * uncategorised for as long as the ledger exists.
 *
 * One question at a time, and the answer teaches the engine, so the same
 * payee is filed automatically from then on and never asks again.
 *
 * Nothing here reads or writes anything: the caller supplies the rows and the
 * categories and decides what to persist, which is what lets the selection
 * and the naming rules be tested on a JVM in milliseconds.
 */
object CategoryPrompt {

    /**
     * How many unanswered captures are queued at once.
     *
     * The queue is a nudge, not a backlog: past a screenful the honest answer
     * is the bulk-review screen, and counting every row of a year of capture
     * would turn a calm line into an accusation.
     */
    const val MAX_PENDING = 20

    /** Long enough for a real category name, short enough that a pasted SMS
     *  cannot become one. */
    const val NAME_LIMIT = 40

    /** One captured row, as the question it raises. */
    data class Row(
        val id: String,
        val amount: Double,
        val type: String,
        val occurredAt: LocalDateTime,
        val source: String,
        val categoryId: String?,
        val categoryPrompted: Boolean,
        val isDeleted: Boolean,
        val rawMerchant: String? = null,
        val merchantName: String? = null,
        val sender: String? = null,
    ) {
        /** What to call this on screen: the resolved name if the engine
         *  produced one, else the raw string off the message. */
        val payee: String? get() =
            merchantName?.trim()?.ifEmpty { null } ?: rawMerchant?.trim()?.ifEmpty { null }
    }

    /** A category the prompt may offer or match against. */
    data class Option(val id: String, val name: String, val type: String)

    /**
     * The captures still owed an answer, newest first.
     *
     * Only rows the app filed on its own. A manually entered row was created
     * on a screen with a category picker on it, so the question has already
     * been asked once and asking again would be the app arguing with a
     * deliberate choice — which is why [Row.categoryPrompted] is set when
     * such a row is saved, not only when a prompt is answered.
     */
    fun pending(rows: List<Row>, limit: Int = MAX_PENDING): List<Row> =
        rows.asSequence()
            .filter {
                it.source == "sms" && it.categoryId == null &&
                    !it.categoryPrompted && !it.isDeleted
            }
            // The id breaks ties so two captures from the same minute cannot
            // swap places between recompositions and move the question out
            // from under a finger already on its way down.
            .sortedWith(compareByDescending<Row> { it.occurredAt }.thenBy { it.id })
            .take(limit)
            .toList()

    /** What answering the prompt does. */
    sealed class Answer {
        /**
         * No category was chosen. The row is only marked as asked, so the
         * question is not raised again — declining to answer is an answer.
         */
        data object Skip : Answer()

        /**
         * @param merchantName the display name to store beside the row, or
         *   null to leave the stored one alone.
         * @param learnFrom the raw payee string the engine keys its learning
         *   on, or null when the message carried none — there is then
         *   nothing an incoming message could ever match, so teaching from
         *   it would write a row no capture reads.
         */
        data class Assign(
            val categoryId: String,
            val merchantName: String?,
            val learnFrom: String?,
        ) : Answer()
    }

    /**
     * @param assignable the categories this row may actually be filed under
     *   — the ones matching its own direction. A category id that is not one
     *   of them is treated as no answer rather than applied: filing income
     *   under a spending category would put it in a budget bar as if it had
     *   been spent, and the picker cannot show it either way.
     */
    fun answer(row: Row, categoryId: String?, assignable: Set<String>): Answer {
        if (categoryId == null || categoryId !in assignable) return Answer.Skip
        val learn = row.rawMerchant?.trim()?.ifEmpty { null }
        return Answer.Assign(
            categoryId = categoryId,
            merchantName = if (learn == null) null else row.payee,
            learnFrom = learn,
        )
    }

    /** What a name typed into the prompt resolves to. */
    sealed class Typed {
        data object Blank : Typed()
        data class Existing(val categoryId: String) : Typed()
        data class Create(val name: String, val type: String) : Typed()

        /**
         * The name is taken by a category of the other direction.
         *
         * The Python build creates the typed category as an expense whatever
         * the row is, and looks an existing one up across both directions —
         * so typing "Salary" on a purchase files it under income, and typing
         * an income category's name on an expense row is a silent
         * misfiling. Refusing is the honest answer: category names are
         * unique here, so there is no second "Salary" to create either.
         */
        data class WrongType(val name: String, val type: String) : Typed()
    }

    /**
     * Resolve a name typed into the prompt against the categories that exist.
     *
     * @param type the direction of the row being filed. A category invented
     *   here is created with it, so the next expense sees the chip too.
     */
    fun typedCategory(typed: String?, options: List<Option>, type: String): Typed {
        val clean = typed?.trim().orEmpty()
        if (clean.isEmpty()) return Typed.Blank
        val hit = options.firstOrNull { it.name.equals(clean, ignoreCase = true) }
        if (hit != null) {
            return if (hit.type == type) Typed.Existing(hit.id)
            else Typed.WrongType(hit.name, hit.type)
        }
        return Typed.Create(clean.take(NAME_LIMIT), type)
    }
}
