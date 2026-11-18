package com.jeevavibeapp.spendwise.core

/**
 * Deleting a category, adding one back, and what may still be filed under a
 * deleted one.
 *
 * Delete archives; it never removes the row. The Python build archives a
 * category that live transactions still reference and removes one nothing
 * live points at, leaning on `ON DELETE SET NULL` to clear every other
 * reference — the soft-deleted rows an undo brings back, the learned payee
 * mappings, the merchant table. The native schema declares no foreign keys,
 * on purpose (see Db.kt), so a removed row there would leave each of those
 * holding an id that resolves to nothing. Removing rows costs something else
 * here too: the built-in categories are seeded into an EMPTY table on every
 * launch, so deleting the last few would bring all the defaults back the next
 * time the app opened. An archived row has neither problem, and keeps the
 * decision reversible — adding the name again brings the category back under
 * its old id, so everything filed under it returns with it.
 *
 * Nothing here reads or writes storage. The caller supplies the rows and
 * stores what comes back, which is what lets every rule below be checked on a
 * JVM.
 */
object Categories {

    /** One stored category, as these rules read it. */
    data class Row(
        val id: String,
        val name: String,
        val type: String = "expense",
        val budgetAmount: Double? = null,
        val isArchived: Boolean = false,
    )

    /**
     * The row as it should be stored once the user has deleted it, or null
     * when it is already archived and there is nothing to write.
     *
     * The budget goes with it, as it does in the Python build: a limit on a
     * category no screen shows can be neither seen nor cleared.
     */
    fun archive(row: Row): Row? =
        if (row.isArchived) null else row.copy(isArchived = true, budgetAmount = null)

    /** What adding a category by name does, decided before anything is written. */
    sealed class Claim {
        data class Create(val name: String) : Claim()

        /** A deleted category holds the name. [row] is that category as it
         *  should be stored to bring it back: the same id, so everything
         *  already filed under it returns with it, and its own name and type,
         *  because those are what its transactions were filed under. */
        data class Restore(val row: Row) : Claim()

        /** A category the user can see already has the name. */
        data class Taken(val id: String) : Claim()

        data object Blank : Claim()
    }

    /**
     * Who, if anyone, already holds [name] — and so what adding it does.
     *
     * Case is ignored, as the Python build ignores it: "groceries" beside
     * "Groceries" is a typo the user would then have to file around for as
     * long as the ledger exists. A visible holder wins over a deleted one, so
     * adding a name never brings back a second spelling of a category that is
     * already on screen.
     *
     * A deleted category's name brings that category back rather than being
     * refused. The Python build answers "already exists" about a category no
     * screen shows, which leaves the user nowhere to go; here, adding the name
     * again is how a deletion is undone.
     */
    fun claim(name: String, rows: List<Row>): Claim {
        val clean = name.trim()
        if (clean.isEmpty()) return Claim.Blank
        val holder = holder(clean, rows) ?: return Claim.Create(clean)
        if (!holder.isArchived) return Claim.Taken(holder.id)
        // Unbudgeted on the way back, for the reason [archive] clears it. A
        // backup is also a file anyone can edit, so an archived row can arrive
        // still carrying a limit nobody could see the whole time it was gone.
        return Claim.Restore(holder.copy(isArchived = false, budgetAmount = null))
    }

    /** What renaming a category does. */
    sealed class Rename {
        data class To(val name: String) : Rename()

        /** Already called exactly that, so there is nothing to write. */
        data object Unchanged : Rename()

        data object Blank : Rename()

        /** No category the user can see has that id. */
        data object Missing : Rename()

        /** Another visible category has the name. */
        data object Taken : Rename()

        /** A deleted category has the name. Two rows cannot share one — the
         *  unique index covers every row — and refusing with "already exists"
         *  about a category nobody can see is the dead end [claim] avoids, so
         *  the caller is told which kind of holder it is. */
        data object HeldByDeleted : Rename()
    }

    fun rename(id: String, name: String, rows: List<Row>): Rename {
        val clean = name.trim()
        if (clean.isEmpty()) return Rename.Blank
        val self = rows.firstOrNull { it.id == id && !it.isArchived } ?: return Rename.Missing
        if (clean == self.name) return Rename.Unchanged
        // The category's own row is left out, so a change of case alone is a
        // rename rather than a clash with itself.
        val other = holder(clean, rows.filter { it.id != id }) ?: return Rename.To(clean)
        return if (other.isArchived) Rename.HeldByDeleted else Rename.Taken
    }

    /**
     * [categoryId] if a new row may be filed under it, otherwise null — and
     * null is what sends a capture to the review queue to be asked about.
     *
     * Every source of a category for a fresh capture can outlive the category
     * it names: a payee mapping learned before the user deleted it, a seeded
     * merchant filed under a built-in name, a model trained on the old rows.
     * The mapping itself is kept, so bringing the category back brings its
     * payees back with it; it only stops deciding anything while the category
     * is gone. A capture filed there would be in no budget and on no chip, and
     * one confident enough to skip review would never be put in front of
     * anyone to fix. An id no row carries is refused for the same reason.
     */
    fun fileable(categoryId: String?, rows: List<Row>): String? =
        categoryId?.takeIf { id -> rows.any { it.id == id && !it.isArchived } }

    /**
     * Whether a picker offers [row] on a transaction currently filed under
     * [current] — null for a new one.
     *
     * A deleted category stays on the rows already filed under it. Leaving it
     * off their edit sheet would show no category selected, which reads as
     * uncategorised — the opposite of what deleting it promised — and invites
     * the user to "fix" a row that was never broken.
     */
    fun pickable(row: Row, current: String?): Boolean = !row.isArchived || row.id == current

    /** What the delete confirmation says, in the order the user worries. */
    data class DeleteNotice(
        /** Transactions the user can see that are filed under the category. */
        val filed: Int,
        /** What happens to them. First, because it is the fear behind the
         *  button: a category is a folder of the user's own history. */
        val kept: String,
        /** What changes from now on. */
        val hidden: String,
        /** The monthly budget that goes with it, or null when it had none. */
        val budget: String?,
        /** How to undo it. */
        val undo: String,
        /** What to say once it is done. */
        val done: String,
    )

    /**
     * @param ledger the live ledger, so the count is of transactions the user
     *   can still see rather than ones they already threw away.
     */
    fun deleteNotice(row: Row, ledger: List<Tx>): DeleteNotice {
        val n = ledger.count { it.categoryId == row.id }
        val name = row.name
        return DeleteNotice(
            filed = n,
            kept = when (n) {
                0 -> "Nothing is filed under $name."
                1 -> "Its 1 transaction is kept, still filed under $name."
                else -> "Its $n transactions are kept, still filed under $name."
            },
            hidden = "$name is hidden from new entries and from Budgets. Bank messages " +
                "that would have been filed there will ask you for a category instead.",
            // Zero is not a budget anywhere in this app (see Budget.amount),
            // so a stored zero is not one the user is losing.
            budget = row.budgetAmount?.takeIf { it > 0 }
                ?.let { "Its ${Insights.money(it)} monthly budget is removed." },
            undo = "Add $name again to bring it back.",
            done = when (n) {
                0 -> "Deleted $name."
                1 -> "Deleted $name. Its 1 transaction is still filed under it."
                else -> "Deleted $name. Its $n transactions are still filed under it."
            },
        )
    }

    /** A visible holder first, then a deleted one spelled exactly as asked,
     *  then any deleted one: see [claim] for why the order is this one. */
    private fun holder(name: String, rows: List<Row>): Row? {
        val same = rows.filter { it.name.equals(name, ignoreCase = true) }
        return same.firstOrNull { !it.isArchived }
            ?: same.firstOrNull { it.name == name }
            ?: same.firstOrNull()
    }
}
