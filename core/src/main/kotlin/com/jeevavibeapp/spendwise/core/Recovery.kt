package com.jeevavibeapp.spendwise.core

import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * What to do when the database will not open.
 *
 * This app is the only copy of the user's financial history: no cloud, and no
 * Android auto-backup. SQLite corruption on low-end flash is a routine event
 * at scale — power loss mid-write, OEM filesystem bugs — and a schema upgrade
 * that throws is a second way to arrive at the same place. Without a policy
 * for either, the first launch after the damage crashes, and so does every
 * launch after that: the ledger is still on the phone, and the user has no
 * way to be told so.
 *
 * The policy is the one python_app/spendwise/db.py and maintenance.py already
 * implement, moved here where it can be tested without a device:
 *
 *  1. Check the file before opening it.
 *  2. Snapshot it before a migration can change it.
 *  3. On failure, restore the newest verified snapshot and try exactly once
 *     more.
 *  4. If that fails too, stop trying and say so — with the path to the data.
 *
 * Two rules are absolute, and both were learned the expensive way in the
 * Python app (see tests/test_recovery.py):
 *
 *  * A locked or busy database is NOT damage. It is another connection
 *    holding a write — the SMS receiver banking a message while the app
 *    launches. Treating that as corruption moves a live ledger aside and
 *    starts empty, which is total data loss caused entirely by the recovery.
 *  * A failure that repeats is never answered by raising. Raising is what
 *    turns one bad file into an app that cannot be opened again; the answer
 *    is a screen that explains where the data is.
 *
 * Nothing here touches a file. It decides; the caller acts.
 */
object Recovery {

    /** Why an open failed, as far as the message can say. */
    enum class Failure { NONE, CONTENTION, CORRUPTION, MIGRATION, UNKNOWN }

    /** What the caller should do next. */
    enum class Step {
        /** The file is fit to open. Open it. */
        OPEN,

        /** Try again without touching the file — the cause may not be damage. */
        RETRY,

        /** Put the newest verified snapshot in place (keeping the damaged
         *  file for salvage) and open once more. */
        RESTORE_AND_RETRY,

        /** Move the unusable file aside and start an empty ledger. Last
         *  resort, and only when there is no snapshot to go back to. */
        START_FRESH,

        /** Stop. Show the failure screen rather than loop or crash. */
        SAFE_MODE,
    }

    /** What the user needs to be told once the dust settles. */
    enum class Outcome {
        /** Opened normally. Say nothing. */
        NORMAL,

        /** Opened from a snapshot: everything recorded after it was taken is
         *  gone, so this cannot be silent. */
        RESTORED,

        /** Opened empty, with the damaged file kept. Also cannot be silent —
         *  it is the user's whole history apparently vanishing. */
        STARTED_FRESH,

        /** Busy, not broken. Worth another attempt now or on the next launch. */
        BUSY,

        /** Could not be opened at all. The data is still on the device. */
        SAFE_MODE,
    }

    // ── Classification ───────────────────────────────────────────────────

    // Matched against the exception's whole causal chain — class names as
    // well as messages, since `SQLiteDatabaseCorruptException` is a stronger
    // statement than anything in its text, and Room wraps the real cause.

    /** Contention is checked FIRST and wins over every other marker: the cost
     *  of reading a lock as damage is a destroyed ledger, and the cost of
     *  reading damage as a lock is one more launch before it is found. */
    private val CONTENTION = listOf("locked", "busy", "sqlite_busy")

    private val CORRUPTION = listOf(
        "malformed", "not a database", "corrupt", "sqlite_corrupt", "sqlite_notadb",
        "encrypted",
    )

    /** "no such table"/"no such column" belongs here rather than with
     *  corruption: it is what a half-applied or skipped migration looks like,
     *  and the difference matters because the migration path never moves the
     *  user's file aside. */
    private val MIGRATION = listOf(
        "migration", "identity hash", "room cannot verify", "schema",
        "no such table", "no such column",
    )

    fun classify(message: String?): Failure {
        val text = message?.lowercase() ?: return Failure.UNKNOWN
        if (text.isBlank()) return Failure.UNKNOWN
        return when {
            CONTENTION.any { it in text } -> Failure.CONTENTION
            CORRUPTION.any { it in text } -> Failure.CORRUPTION
            MIGRATION.any { it in text } -> Failure.MIGRATION
            else -> Failure.UNKNOWN
        }
    }

    /**
     * The first line of `PRAGMA integrity_check`, read as a verdict.
     *
     * SQLite answers "ok" or a list of complaints, and raises instead when it
     * cannot read the file at all — the caller passes that exception text in
     * the same way, so "database is locked" arrives here and is classified as
     * contention rather than as a damaged file.
     */
    fun classifyIntegrity(result: String?): Failure {
        val text = result?.trim()?.lowercase() ?: return Failure.UNKNOWN
        if (text == "ok") return Failure.NONE
        if (text.isBlank()) return Failure.UNKNOWN
        return when (val kind = classify(text)) {
            // A complaint we cannot parse is still a complaint: integrity_check
            // said something other than "ok", which is damage by definition.
            Failure.UNKNOWN, Failure.NONE -> Failure.CORRUPTION
            else -> kind
        }
    }

    // ── The policy ───────────────────────────────────────────────────────

    /**
     * Whether a pre-open verdict lets the database be opened as it stands.
     *
     * Only actual damage is worth acting on before the open. A lock is not
     * damage, and a verdict we could not read is not evidence of damage — in
     * both cases the honest move is to let the real open decide, because the
     * alternative is moving a user's ledger aside on a guess.
     */
    fun fitToOpen(verdict: Failure): Boolean =
        verdict != Failure.CORRUPTION && verdict != Failure.MIGRATION

    /**
     * @param failure      what the last attempt reported
     * @param hasSnapshot  a verified snapshot exists to go back to
     * @param retried      the one extra attempt has already been spent
     * @param movedAside   the file has already been set aside once
     */
    fun next(
        failure: Failure,
        hasSnapshot: Boolean,
        retried: Boolean,
        movedAside: Boolean,
    ): Step = when (failure) {
        Failure.NONE -> Step.OPEN

        // Never touch the file. Another attempt — now, or on the next launch
        // when the user taps the button — is the whole remedy, and anything
        // else destroys data to solve a problem that was about to solve
        // itself. Stopping after one retry rather than spinning: the lock is
        // held by something that needs the CPU to finish with it.
        Failure.CONTENTION -> if (!retried) Step.RETRY else Step.SAFE_MODE

        Failure.CORRUPTION -> when {
            hasSnapshot && !retried -> Step.RESTORE_AND_RETRY
            // No snapshot, or restoring one did not help. Starting fresh
            // keeps the app usable and keeps the damaged file for salvage;
            // crash-looping keeps neither.
            !movedAside -> Step.START_FRESH
            else -> Step.SAFE_MODE
        }

        // One retry, whether or not there is a snapshot to roll back to: the
        // ordinary causes of a failed upgrade — a full disk, an OOM kill, a
        // lock lost at the wrong moment — are transient, and a second attempt
        // costs nothing. A migration failure never moves the ledger aside,
        // because the file is intact; it is the code that could not read it.
        Failure.MIGRATION -> if (!retried) Step.RESTORE_AND_RETRY else Step.SAFE_MODE

        // Unrecognised. Try once more, then explain — but do not conclude
        // damage from a message we could not read, and so never destroy
        // anything on this path. A full disk lands here.
        Failure.UNKNOWN -> if (!retried) Step.RETRY else Step.SAFE_MODE
    }

    /** What to tell the user after an open that eventually succeeded. */
    fun outcomeOf(restored: Boolean, movedAside: Boolean): Outcome = when {
        movedAside -> Outcome.STARTED_FRESH
        restored -> Outcome.RESTORED
        else -> Outcome.NORMAL
    }

    /**
     * Where giving up leaves the user.
     *
     * A database that was busy both times is a different screen from one that
     * could not be read: "something is still finishing, try again" is true
     * and fixable, while showing the safe-mode explanation for a lock would
     * frighten someone whose data is in no danger at all.
     */
    fun outcomeOf(step: Step, failure: Failure): Outcome = when (step) {
        Step.SAFE_MODE ->
            if (failure == Failure.CONTENTION) Outcome.BUSY else Outcome.SAFE_MODE
        Step.RETRY -> Outcome.BUSY
        Step.OPEN, Step.RESTORE_AND_RETRY, Step.START_FRESH -> Outcome.NORMAL
    }

    // ── Snapshots ────────────────────────────────────────────────────────

    const val DIR = "backups"
    const val PREFIX = "spendwise-"
    const val SUFFIX = ".bak.db"

    /** Three is enough to survive a bad snapshot and its predecessor without
     *  spending a phone's storage on copies of a ledger. */
    const val KEEP = 3

    /** Below this, a "snapshot" is a truncated write, never a database:
     *  SQLite's own header plus one page cannot fit in less. */
    const val MIN_VALID_BYTES = 4096L

    /** How stale the newest snapshot may be before another is taken.
     *
     *  python_app only snapshots ahead of a migration, which leaves a user
     *  who never upgrades with nothing to restore from — and corruption, not
     *  migration, is the common failure. A weekly copy bounds what a
     *  corrupted ledger can cost to seven days of entries, for one file copy
     *  a week off the launch path. */
    const val REFRESH_DAYS = 7L

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun snapshotName(at: LocalDateTime): String = PREFIX + STAMP.format(at) + SUFFIX

    fun isSnapshotName(name: String): Boolean = takenAt(name) != null

    fun takenAt(name: String): LocalDateTime? {
        if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) return null
        val stamp = name.substring(PREFIX.length, name.length - SUFFIX.length)
        return try {
            LocalDateTime.parse(stamp, STAMP)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Newest first, by the timestamp in the NAME rather than the file's mtime.
     *
     * Restoring a snapshot copies it, and a copy carries a new mtime — so
     * ordering by mtime would promote the snapshot that was used to recover
     * above the newer one that was not, and hand the next recovery older data
     * than it had to.
     *
     * The stamp is fixed-width and big-endian, so sorting the names as text
     * is sorting them by time.
     */
    fun newestFirst(names: List<String>): List<String> =
        names.filter(::isSnapshotName).sortedDescending()

    /** The ones past [keep], oldest included, for deletion. */
    fun expired(names: List<String>, keep: Int = KEEP): List<String> =
        newestFirst(names).drop(maxOf(keep, 0))

    /** Whole days since [name] was taken, or null if it is not a snapshot. */
    fun ageInDays(name: String, now: LocalDateTime): Long? {
        val taken = takenAt(name) ?: return null
        // A snapshot stamped in the future is a clock that moved backwards,
        // not a fresh copy. Age zero rather than negative, so it is treated as
        // current and not needlessly replaced.
        return maxOf(0L, Duration.between(taken, now).toDays())
    }

    /** True when the file on disk is behind the schema the code expects.
     *  Version 0 is a database SQLite has just created and nothing has
     *  migrated yet — there is nothing in it worth snapshotting. */
    fun migrationPending(onDiskVersion: Int, targetVersion: Int): Boolean =
        onDiskVersion in 1 until targetVersion

    /** @param newestSnapshotAgeDays null when there is no snapshot at all. */
    fun snapshotDue(migrationPending: Boolean, newestSnapshotAgeDays: Long?): Boolean =
        migrationPending || newestSnapshotAgeDays == null ||
            newestSnapshotAgeDays >= REFRESH_DAYS

    /**
     * The tables a snapshot must contain to be worth restoring.
     *
     * Only the two that have existed since v1. Requiring a table a later
     * migration added would reject exactly the snapshot a failed migration
     * needs — the one taken before it ran.
     */
    val REQUIRED_TABLES: Set<String> = setOf("transactions", "categories")

    /**
     * Whether a snapshot may be restored over a live ledger.
     *
     * Verified, not trusted: restoring garbage over a working database is a
     * worse outcome than failing to restore at all, so a snapshot has to
     * open, pass its own integrity check and hold the expected tables before
     * it is allowed to replace anything.
     */
    fun usableSnapshot(sizeBytes: Long, integrity: String?, tables: Set<String>): Boolean =
        sizeBytes >= MIN_VALID_BYTES &&
            classifyIntegrity(integrity) == Failure.NONE &&
            REQUIRED_TABLES.all { it in tables }

    /** Where a file that could not be opened is kept. Moved, never deleted:
     *  a later salvage is the only thing left that can return the entries
     *  made since the newest snapshot. */
    fun asideName(fileName: String, at: LocalDateTime): String =
        "$fileName.corrupt-${STAMP.format(at)}"
}
