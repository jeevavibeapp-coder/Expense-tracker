package com.jeevavibeapp.spendwise.core

import com.jeevavibeapp.spendwise.core.Recovery.Failure
import com.jeevavibeapp.spendwise.core.Recovery.Outcome
import com.jeevavibeapp.spendwise.core.Recovery.Step
import java.time.LocalDateTime

/**
 * Opening the ledger, with [Recovery]'s decisions applied in order.
 *
 * Recovery answers "what now?" for one failure at a time. Whether a ledger
 * survives depends just as much on the order those answers are acted on: a
 * snapshot taken of a file nobody checked, a restore that moves the ledger
 * aside before the copy replacing it exists, a failed migration "rolled back"
 * onto a week-old copy, a retry loop that never ends. A type-checker sees none
 * of that, so the sequence lives here, where the tests run it against a
 * simulated [Disk], and the Android side only implements [Disk]: files and
 * SQLite, and no decisions of its own.
 */
object RecoverySequence {

    /** Read with plain SQLite, because Room's open is also its migration: by
     *  the time Room has looked at the file, it is too late to copy it first. */
    data class Check(
        /** The first line of `PRAGMA integrity_check`, or the text of what
         *  SQLite raised instead. Null when there is no verdict at all. */
        val integrity: String?,
        /** `PRAGMA user_version`, which is where Room keeps its schema
         *  version. Null when it could not be read. */
        val version: Int?,
    )

    /** Facts about a copy rather than a verdict on it, so that whether it may
     *  replace a ledger is decided by Recovery.usableSnapshot and nowhere else. */
    data class Copy(val sizeBytes: Long, val integrity: String?, val tables: Set<String>)

    interface Disk {
        /** False on a first launch, which has nothing to check or snapshot. */
        fun ledgerExists(): Boolean

        /** A refusal from SQLite itself belongs in [Check.integrity], because
         *  "database is locked" is an answer that must reach the classifier.
         *  Anything else that goes wrong is no evidence about the file, and
         *  comes back as a null verdict rather than as damage. */
        fun checkLedger(): Check

        /** Unfiltered and unordered: which names are snapshots, and which is
         *  newest, is Recovery's call, so this side cannot get it wrong. */
        fun snapshotNames(): List<String>

        fun inspectSnapshot(name: String): Copy

        /** Copy the ledger somewhere that is not yet a snapshot, and describe
         *  the COPY. The original passing its check proves nothing about a
         *  copy that ran out of disk halfway. Null if no copy could be made. */
        fun stageSnapshot(): Copy?

        fun publishStaged(name: String): Boolean

        fun discardStaged()

        fun deleteSnapshot(name: String)

        /** Put [snapshot] where the ledger is and keep the file it replaces
         *  as [keepAs]. All or nothing: false must mean the ledger is still
         *  where it was, never that it was moved and nothing took its place. */
        fun restore(snapshot: String, keepAs: String): Boolean

        /** Move the ledger to [keepAs], so the next open creates an empty one. */
        fun setAside(keepAs: String): Boolean

        /** The real open, migrations included. Null when it opened; otherwise
         *  everything the failure said, class names and causes included,
         *  because Room wraps the SQLite error that names what happened. */
        fun open(): String?
    }

    sealed interface Result {
        val outcome: Outcome

        /** Its stamp is the moment after which entries may be missing, which
         *  is the one thing a restored user most needs to be told. */
        val restoredFrom: String?

        /** Files this run moved out of the way, oldest first. Nothing here is
         *  ever deleted: these are what a salvage would start from. */
        val keptAs: List<String>
    }

    data class Opened(
        override val outcome: Outcome,
        override val restoredFrom: String?,
        override val keptAs: List<String>,
        val snapshotTaken: String?,
    ) : Result {
        /** The copy put back is the one this run took before its first open,
         *  and Room's failed attempts roll back, so nothing was written after
         *  it: a migration undone, not a ledger rewound. Still not silent,
         *  but telling this user entries are missing would be false. */
        val restoredWithoutLoss: Boolean
            get() = restoredFrom != null && restoredFrom == snapshotTaken
    }

    data class Stopped(
        override val outcome: Outcome,
        val failure: Failure,
        /** What the last failure said: for whoever has to diagnose it. */
        val detail: String?,
        override val restoredFrom: String?,
        override val keptAs: List<String>,
        /** Newest first, so the screen can say what is still on the phone. */
        val snapshots: List<String>,
    ) : Result

    /** Recovery.next already stops within three opens. This is the floor
     *  under it, because a launch that never ends is the failure this whole
     *  module exists to prevent, and the policy may be edited one day. */
    const val MAX_OPENS = 8

    fun run(disk: Disk, ledgerName: String, targetVersion: Int, now: LocalDateTime): Result {
        val kept = mutableListOf<String>()
        val verified = mutableMapOf<String, Boolean>()

        fun usable(name: String): Boolean = verified.getOrPut(name) {
            val copy = disk.inspectSnapshot(name)
            Recovery.usableSnapshot(copy.sizeBytes, copy.integrity, copy.tables)
        }

        // A restore and then a fresh start in one run share a clock reading.
        // Without the bump, the restored copy would be moved onto the name
        // the ORIGINAL ledger was just kept under, and the original is the
        // only file holding what was written since the snapshot.
        fun keepName(): String {
            var at = now
            while (Recovery.asideName(ledgerName, at) in kept) at = at.plusSeconds(1)
            return Recovery.asideName(ledgerName, at)
        }

        val check = if (disk.ledgerExists()) disk.checkLedger() else null
        val verdict = if (check == null) Failure.NONE else Recovery.classifyIntegrity(check.integrity)

        // Only a file that has just passed its check is copied. A snapshot of
        // a damaged ledger would later be restored as though it were good,
        // and a lock means the check never looked at the file at all.
        val taken = if (check != null && verdict == Failure.NONE)
            snapshotIfDue(disk, check.version, targetVersion, now) else null

        // A verdict that is not fit to open is acted on before Room is ever
        // handed the file.
        var fromCheck: Failure? = if (Recovery.fitToOpen(verdict)) null else verdict
        var detail: String? = if (fromCheck != null) check?.integrity else null
        var last = verdict
        var retried = false
        var movedAside = false
        var restoredFrom: String? = null
        var startedFresh = false
        var opens = 0

        while (true) {
            val current: Failure
            val pending = fromCheck
            if (pending != null) {
                current = pending
                fromCheck = null
            } else {
                if (opens >= MAX_OPENS) {
                    return Stopped(Recovery.outcomeOf(Step.SAFE_MODE, last), last, detail,
                        restoredFrom, kept.toList(), Recovery.newestFirst(disk.snapshotNames()))
                }
                opens++
                val said = disk.open() ?: return Opened(
                    Recovery.outcomeOf(restored = restoredFrom != null, movedAside = startedFresh),
                    restoredFrom, kept.toList(), taken)
                detail = said
                current = Recovery.classify(said)
            }
            last = current

            // Damage goes back to the newest copy that still verifies. A
            // migration goes back only to the copy taken this run: the file
            // is intact, so rolling it onto an older snapshot would throw away
            // real entries to undo a step that never committed.
            val candidate = when (current) {
                Failure.CORRUPTION -> Recovery.newestFirst(disk.snapshotNames()).firstOrNull(::usable)
                Failure.MIGRATION -> taken?.takeIf(::usable)
                else -> null
            }

            when (val step = Recovery.next(current, candidate != null, retried, movedAside)) {
                Step.OPEN -> Unit
                Step.RETRY -> retried = true
                Step.RESTORE_AND_RETRY -> {
                    retried = true
                    if (candidate != null) {
                        val had = disk.ledgerExists()
                        val keepAs = keepName()
                        if (disk.restore(candidate, keepAs)) {
                            restoredFrom = candidate
                            if (had) kept += keepAs
                        }
                    }
                }
                Step.START_FRESH -> {
                    // Spent even when the move fails, so the next damaged
                    // open stops instead of trying to move it again forever.
                    movedAside = true
                    val had = disk.ledgerExists()
                    val keepAs = keepName()
                    if (disk.setAside(keepAs)) {
                        startedFresh = true
                        if (had) kept += keepAs
                    }
                }
                Step.SAFE_MODE -> return Stopped(
                    Recovery.outcomeOf(step, current), current, detail, restoredFrom,
                    kept.toList(), Recovery.newestFirst(disk.snapshotNames()))
            }
        }
    }

    private fun snapshotIfDue(disk: Disk, version: Int?, targetVersion: Int, now: LocalDateTime): String? {
        val newest = Recovery.newestFirst(disk.snapshotNames()).firstOrNull()
        val pending = version != null && Recovery.migrationPending(version, targetVersion)
        val age = newest?.let { Recovery.ageInDays(it, now) }
        if (!Recovery.snapshotDue(pending, age)) return null

        val copy = disk.stageSnapshot()
        val name = Recovery.snapshotName(now)
        if (copy == null || !Recovery.usableSnapshot(copy.sizeBytes, copy.integrity, copy.tables) ||
            !disk.publishStaged(name)) {
            disk.discardStaged()
            return null
        }
        // The copy just taken is never pruned, even if a clock that ran
        // backwards makes it look older than the rest: before a migration it
        // is the one copy a rollback is allowed to use.
        for (old in Recovery.expired(disk.snapshotNames())) {
            if (old != name) disk.deleteSnapshot(old)
        }
        return name
    }
}
