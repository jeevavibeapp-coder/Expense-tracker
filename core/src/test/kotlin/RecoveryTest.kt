import com.jeevavibeapp.spendwise.core.Recovery
import com.jeevavibeapp.spendwise.core.Recovery.Failure
import com.jeevavibeapp.spendwise.core.Recovery.Outcome
import com.jeevavibeapp.spendwise.core.Recovery.Step
import java.time.LocalDateTime

private var passed = 0
private var failed = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else { failed++; println("  FAIL  $name${if (detail.isNotEmpty()) "  [$detail]" else ""}") }
}

/** Pinned clock: ages are measured against it, so a real one would make the
 *  staleness checks answer differently every week. */
private val NOW = LocalDateTime.of(2026, 6, 17, 9, 30, 0)

/** Messages as Android and Room actually raise them, class name included —
 *  the caller flattens the whole causal chain into the string handed to
 *  classify, because Room wraps the real cause and the class name is often a
 *  stronger statement than the text. */
private const val MALFORMED =
    "android.database.sqlite.SQLiteDatabaseCorruptException: " +
        "database disk image is malformed (code 11 SQLITE_CORRUPT)"
private const val NOT_A_DB =
    "android.database.sqlite.SQLiteException: file is not a database (code 26 SQLITE_NOTADB)"
private const val LOCKED =
    "android.database.sqlite.SQLiteDatabaseLockedException: " +
        "database is locked (code 5 SQLITE_BUSY)"
private const val NO_PATH =
    "java.lang.IllegalStateException: A migration from 2 to 3 was required but not found."
private const val MISHANDLED =
    "java.lang.IllegalStateException: Migration didn't properly handle: transactions"
private const val CHANGED_SCHEMA =
    "java.lang.IllegalStateException: Room cannot verify the data integrity. " +
        "Looks like you've changed schema but forgot to update the version number."
private const val DISK_FULL =
    "android.database.sqlite.SQLiteFullException: database or disk is full (code 13 SQLITE_FULL)"

/**
 * Drive the policy with a stream of failures, exactly as the caller does:
 * each step is applied and the next attempt reports the next failure.
 * Returns the steps taken, which is what makes "does this terminate"
 * answerable rather than a matter of reading the `when`.
 */
private fun drive(reported: List<Failure>, hasSnapshot: Boolean): List<Step> {
    var retried = false
    var movedAside = false
    val steps = mutableListOf<Step>()
    for (f in reported) {
        val step = Recovery.next(f, hasSnapshot, retried, movedAside)
        steps += step
        when (step) {
            Step.RETRY, Step.RESTORE_AND_RETRY -> retried = true
            Step.START_FRESH -> movedAside = true
            Step.OPEN, Step.SAFE_MODE -> return steps
        }
    }
    return steps
}

fun main() {
    println("=== reading what went wrong ===")
    check("a malformed disk image is corruption", Recovery.classify(MALFORMED) == Failure.CORRUPTION)
    check("so is a file that is not a database", Recovery.classify(NOT_A_DB) == Failure.CORRUPTION)
    check("a missing migration path is a migration failure",
        Recovery.classify(NO_PATH) == Failure.MIGRATION)
    check("so is a migration that left the wrong shape behind",
        Recovery.classify(MISHANDLED) == Failure.MIGRATION)
    check("and so is Room refusing a schema it cannot verify",
        Recovery.classify(CHANGED_SCHEMA) == Failure.MIGRATION)
    check("a column the code expects and the file lacks is a migration failure",
        Recovery.classify("no such column: highValueAmount (code 1 SQLITE_ERROR)") ==
            Failure.MIGRATION)
    check("a lock is contention", Recovery.classify(LOCKED) == Failure.CONTENTION)
    check("so is a busy database",
        Recovery.classify("SQLiteException: database is busy") == Failure.CONTENTION)
    check("a full disk is not damage and not a migration bug",
        Recovery.classify(DISK_FULL) == Failure.UNKNOWN)
    check("neither is a failure with no message at all",
        Recovery.classify(null) == Failure.UNKNOWN)
    check("nor one whose message is blank", Recovery.classify("   ") == Failure.UNKNOWN)

    // The Python app learned this one in production: a migration that loses a
    // lock race reports BOTH words, and reading it as a deterministic
    // migration bug put a healthy ledger into safe mode.
    check("a migration that lost a lock race is contention, not a migration bug",
        Recovery.classify(
            "IllegalStateException: migration to v3 failed: database is locked") ==
            Failure.CONTENTION)
    // And the other direction: corruption is never softened into a lock.
    check("a corrupt schema is corruption even though it says 'schema'",
        Recovery.classify("malformed database schema (index_transactions_dedupKey)") ==
            Failure.CORRUPTION)

    println("=== reading integrity_check ===")
    check("\"ok\" is the only clean answer", Recovery.classifyIntegrity("ok") == Failure.NONE)
    check("and it is read case- and space-insensitively",
        Recovery.classifyIntegrity("  OK  ") == Failure.NONE)
    check("a complaint nobody can parse is still damage",
        Recovery.classifyIntegrity("*** in database main *** row 12 missing from index ix") ==
            Failure.CORRUPTION)
    check("a check that could not run because of a lock is contention",
        Recovery.classifyIntegrity("database is locked") == Failure.CONTENTION)
    check("no answer at all is unknown, not damage",
        Recovery.classifyIntegrity(null) == Failure.UNKNOWN)

    println("=== what may be opened as it stands ===")
    check("a clean file", Recovery.fitToOpen(Failure.NONE))
    check("a locked one — a lock is not damage", Recovery.fitToOpen(Failure.CONTENTION))
    check("one whose verdict could not be read: let the real open decide",
        Recovery.fitToOpen(Failure.UNKNOWN))
    check("a damaged one must be recovered first", !Recovery.fitToOpen(Failure.CORRUPTION))
    check("and so must one the code cannot migrate", !Recovery.fitToOpen(Failure.MIGRATION))

    println("=== the policy, failure by failure ===")
    check("nothing wrong means open it",
        Recovery.next(Failure.NONE, hasSnapshot = true, retried = false, movedAside = false) ==
            Step.OPEN)
    check("damage with a snapshot is restored and retried",
        Recovery.next(Failure.CORRUPTION, hasSnapshot = true, retried = false,
            movedAside = false) == Step.RESTORE_AND_RETRY)
    check("damage with no snapshot starts fresh rather than crash-looping",
        Recovery.next(Failure.CORRUPTION, hasSnapshot = false, retried = false,
            movedAside = false) == Step.START_FRESH)
    check("damage that survived a restore starts fresh",
        Recovery.next(Failure.CORRUPTION, hasSnapshot = true, retried = true,
            movedAside = false) == Step.START_FRESH)
    check("damage that survived even that stops and explains",
        Recovery.next(Failure.CORRUPTION, hasSnapshot = true, retried = true,
            movedAside = true) == Step.SAFE_MODE)
    check("a failed migration is rolled back and tried once",
        Recovery.next(Failure.MIGRATION, hasSnapshot = true, retried = false,
            movedAside = false) == Step.RESTORE_AND_RETRY)
    // A full disk or an OOM kill leaves nothing to restore, and is still
    // worth one more attempt — which is what python_app does.
    check("a failed migration with no snapshot is still tried once more",
        Recovery.next(Failure.MIGRATION, hasSnapshot = false, retried = false,
            movedAside = false) == Step.RESTORE_AND_RETRY)
    check("a migration that fails twice is deterministic: stop",
        Recovery.next(Failure.MIGRATION, hasSnapshot = true, retried = true,
            movedAside = false) == Step.SAFE_MODE)
    check("a lock is retried, never recovered from",
        Recovery.next(Failure.CONTENTION, hasSnapshot = true, retried = false,
            movedAside = false) == Step.RETRY)
    check("a lock that is still held stops rather than spinning",
        Recovery.next(Failure.CONTENTION, hasSnapshot = true, retried = true,
            movedAside = false) == Step.SAFE_MODE)
    check("an unreadable failure is retried once",
        Recovery.next(Failure.UNKNOWN, hasSnapshot = true, retried = false,
            movedAside = false) == Step.RETRY)
    check("and then stops",
        Recovery.next(Failure.UNKNOWN, hasSnapshot = true, retried = true,
            movedAside = false) == Step.SAFE_MODE)

    println("=== the rule that cost a ledger the last time it was broken ===")
    // Recovering on "locked" moved a live database aside and started empty:
    // total data loss caused entirely by the recovery. No combination of
    // flags may reach a step that replaces or moves the user's file.
    val destructive = setOf(Step.RESTORE_AND_RETRY, Step.START_FRESH)
    var safe = true
    for (snapshot in listOf(false, true)) {
        for (retried in listOf(false, true)) {
            for (aside in listOf(false, true)) {
                if (Recovery.next(Failure.CONTENTION, snapshot, retried, aside) in destructive)
                    safe = false
                if (Recovery.next(Failure.UNKNOWN, snapshot, retried, aside) in destructive)
                    safe = false
            }
        }
    }
    check("contention and unreadable failures never touch the file, in any state", safe)

    println("=== the policy always ends ===")
    // A launch that keeps retrying is the failure this whole file exists to
    // prevent, so every kind of permanent failure must reach a stop.
    for (kind in listOf(Failure.CORRUPTION, Failure.MIGRATION, Failure.CONTENTION,
                        Failure.UNKNOWN)) {
        for (snapshot in listOf(false, true)) {
            val steps = drive(List(8) { kind }, snapshot)
            check("$kind (snapshot=$snapshot) stops",
                steps.last() == Step.SAFE_MODE, steps.toString())
            check("$kind (snapshot=$snapshot) stops within four attempts",
                steps.size <= 3, steps.size.toString())
        }
    }
    check("a file that opens on the retry never reaches safe mode",
        drive(listOf(Failure.MIGRATION, Failure.NONE), hasSnapshot = true) ==
            listOf(Step.RESTORE_AND_RETRY, Step.OPEN))
    check("damage recovered from a snapshot opens on the second attempt",
        drive(listOf(Failure.CORRUPTION, Failure.NONE), hasSnapshot = true) ==
            listOf(Step.RESTORE_AND_RETRY, Step.OPEN))
    check("damage with no snapshot opens an empty ledger on the second attempt",
        drive(listOf(Failure.CORRUPTION, Failure.NONE), hasSnapshot = false) ==
            listOf(Step.START_FRESH, Step.OPEN))

    println("=== what the user is told ===")
    check("an ordinary open says nothing",
        Recovery.outcomeOf(restored = false, movedAside = false) == Outcome.NORMAL)
    check("a restore is never silent — entries after the snapshot are gone",
        Recovery.outcomeOf(restored = true, movedAside = false) == Outcome.RESTORED)
    check("an empty ledger is never silent either",
        Recovery.outcomeOf(restored = false, movedAside = true) == Outcome.STARTED_FRESH)
    check("a restore that ended in an empty ledger reports the empty ledger",
        Recovery.outcomeOf(restored = true, movedAside = true) == Outcome.STARTED_FRESH)
    check("giving up on a lock is 'busy', not 'your database is damaged'",
        Recovery.outcomeOf(Step.SAFE_MODE, Failure.CONTENTION) == Outcome.BUSY)
    check("giving up on damage is safe mode",
        Recovery.outcomeOf(Step.SAFE_MODE, Failure.CORRUPTION) == Outcome.SAFE_MODE)
    check("and so is giving up on a migration",
        Recovery.outcomeOf(Step.SAFE_MODE, Failure.MIGRATION) == Outcome.SAFE_MODE)

    println("=== naming and ordering snapshots ===")
    val first = Recovery.snapshotName(LocalDateTime.of(2026, 6, 10, 9, 0, 0))
    val second = Recovery.snapshotName(LocalDateTime.of(2026, 6, 14, 23, 59, 59))
    val third = Recovery.snapshotName(NOW)
    check("a snapshot is named for the moment it was taken",
        first == "spendwise-20260610-090000.bak.db", first)
    check("and the name parses back to that moment",
        Recovery.takenAt(third) == NOW, Recovery.takenAt(third).toString())
    check("a stamp that is not a time is not a snapshot",
        !Recovery.isSnapshotName("spendwise-not-a-date.bak.db"))
    check("nor is the live database", !Recovery.isSnapshotName("spendwise.db"))
    check("nor a half-written temporary file",
        !Recovery.isSnapshotName("spendwise-20260610-090000.bak.db.tmp"))
    check("newest first, regardless of the order they were listed in",
        Recovery.newestFirst(listOf(second, first, third)) == listOf(third, second, first))
    check("and anything that is not a snapshot is left out of the ordering",
        Recovery.newestFirst(listOf("spendwise.db", "readme.txt", first)) == listOf(first))
    check("three are kept", Recovery.expired(listOf(first, second, third)).isEmpty())
    check("a fourth retires the oldest",
        Recovery.expired(listOf(first, second, third,
            Recovery.snapshotName(LocalDateTime.of(2026, 6, 16, 8, 0, 0)))) == listOf(first))
    check("and a caller that wants fewer gets fewer",
        Recovery.expired(listOf(first, second, third), keep = 1) == listOf(second, first))

    println("=== when another snapshot is due ===")
    check("today's snapshot is one day old tomorrow",
        Recovery.ageInDays(third, NOW.plusDays(1)) == 1L)
    check("a partial day does not count as one",
        Recovery.ageInDays(third, NOW.plusHours(23)) == 0L)
    check("a stamp from the future is treated as current, not as negative age",
        Recovery.ageInDays(third, NOW.minusDays(3)) == 0L)
    check("a name that is not a snapshot has no age",
        Recovery.ageInDays("spendwise.db", NOW) == null)
    check("a pending migration always takes one first",
        Recovery.snapshotDue(migrationPending = true, newestSnapshotAgeDays = 0))
    check("so does having none at all",
        Recovery.snapshotDue(migrationPending = false, newestSnapshotAgeDays = null))
    check("a week-old snapshot is replaced",
        Recovery.snapshotDue(migrationPending = false,
            newestSnapshotAgeDays = Recovery.REFRESH_DAYS))
    check("a fresh one is not",
        !Recovery.snapshotDue(migrationPending = false, newestSnapshotAgeDays = 1))
    check("a database Room has only just created is not worth snapshotting",
        !Recovery.migrationPending(onDiskVersion = 0, targetVersion = 3))
    check("one behind the code is",
        Recovery.migrationPending(onDiskVersion = 2, targetVersion = 3))
    check("one already at the target is not",
        !Recovery.migrationPending(onDiskVersion = 3, targetVersion = 3))
    // Forward-only: an older build must not migrate a newer file, and must
    // certainly not snapshot it as though it were about to.
    check("one written by a newer build is not",
        !Recovery.migrationPending(onDiskVersion = 4, targetVersion = 3))

    println("=== a snapshot is verified, never trusted ===")
    val tables = setOf("transactions", "categories", "settings", "merchants")
    check("a healthy copy is usable",
        Recovery.usableSnapshot(64 * 1024, "ok", tables))
    check("a truncated one is not",
        !Recovery.usableSnapshot(200, "ok", tables))
    check("nor is one that fails its own integrity check",
        !Recovery.usableSnapshot(64 * 1024, "row 3 missing from index ix_tx", tables))
    check("nor one that could not be opened to check",
        !Recovery.usableSnapshot(64 * 1024, null, tables))
    check("nor one with no ledger in it",
        !Recovery.usableSnapshot(64 * 1024, "ok", setOf("categories", "settings")))
    // The snapshot a failed migration needs is the one taken BEFORE it ran,
    // so it cannot be required to contain what that migration was adding.
    check("a copy from before the settings table existed is still usable",
        Recovery.usableSnapshot(64 * 1024, "ok", setOf("transactions", "categories")))

    println("=== nothing is ever deleted ===")
    check("a file that could not be opened is moved aside, stamped",
        Recovery.asideName("spendwise.db", NOW) == "spendwise.db.corrupt-20260617-093000",
        Recovery.asideName("spendwise.db", NOW))
    check("and two failures on the same day do not overwrite each other",
        Recovery.asideName("spendwise.db", NOW) !=
            Recovery.asideName("spendwise.db", NOW.plusSeconds(1)))

    println()
    println("=".repeat(60))
    println("$passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
