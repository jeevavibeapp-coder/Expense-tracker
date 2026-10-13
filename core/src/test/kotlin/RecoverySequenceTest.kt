import com.jeevavibeapp.spendwise.core.Recovery
import com.jeevavibeapp.spendwise.core.Recovery.Failure
import com.jeevavibeapp.spendwise.core.Recovery.Outcome
import com.jeevavibeapp.spendwise.core.RecoverySequence
import com.jeevavibeapp.spendwise.core.RecoverySequence.Check
import com.jeevavibeapp.spendwise.core.RecoverySequence.Copy
import com.jeevavibeapp.spendwise.core.RecoverySequence.Opened
import com.jeevavibeapp.spendwise.core.RecoverySequence.Stopped
import java.time.LocalDateTime

private var passed = 0
private var failed = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else { failed++; println("  FAIL  $name${if (detail.isNotEmpty()) "  [$detail]" else ""}") }
}

/** Pinned clock: snapshot names, ages and set-aside names all come from it. */
private val NOW = LocalDateTime.of(2026, 6, 17, 9, 30, 0)

private const val NAME = "spendwise.db"
private const val TARGET = 3

/** As Android and Room raise them, flattened the way the app flattens a
 *  causal chain before classifying it. */
private const val LOCKED =
    "android.database.sqlite.SQLiteDatabaseLockedException: database is locked (code 5 SQLITE_BUSY)"
private const val MALFORMED =
    "android.database.sqlite.SQLiteDatabaseCorruptException: " +
        "database disk image is malformed (code 11 SQLITE_CORRUPT)"
private const val MISHANDLED =
    "java.lang.IllegalStateException: Migration didn't properly handle: transactions"
private const val DISK_FULL =
    "android.database.sqlite.SQLiteFullException: database or disk is full (code 13 SQLITE_FULL)"

/** A database file as far as the sequence can tell: how many entries it
 *  holds, the schema version it is at, and whether SQLite can read it. */
private data class DbFile(
    val rows: Int,
    val version: Int,
    val damaged: Boolean = false,
    val truncated: Boolean = false,
)

/**
 * The files, simulated. It answers the way SQLite and Room answer — a lock
 * before damage, damage before a pending migration — and records every
 * operation, so the ORDER the sequence acts in can be checked, not just
 * where it ends up.
 */
private class SimulatedDisk(var ledger: DbFile?) : RecoverySequence.Disk {
    val snapshots = sortedMapOf<String, DbFile>()
    val kept = linkedMapOf<String, DbFile>()
    var staged: DbFile? = null

    /** Checks and opens still to find another connection holding the lock. */
    var lockedFor = 0
    /** Opens still to fail inside the migration; Int.MAX_VALUE is a bug in it. */
    var migrationFailures = 0
    /** Opens still to fail with a message nothing recognises. */
    var unknownFailures = 0
    /** No copy can be written: neither a snapshot nor a restore. */
    var cannotCopy = false
    /** The ledger cannot be renamed: a fresh start has nowhere to go. */
    var cannotMove = false
    /** When set, answers successive opens in order, the last one for ever. */
    var openScript: List<String?>? = null
    /** When set, answers the check instead of the file. */
    var checkScript: Check? = null

    var opens = 0
    /** Attempts to rename onto a name already holding a kept file. */
    var collisions = 0
    val log = mutableListOf<String>()

    fun shelve(at: LocalDateTime, file: DbFile): String =
        Recovery.snapshotName(at).also { snapshots[it] = file }

    fun answered(): List<String?> {
        val script = openScript ?: return emptyList()
        return List(opens) { script[minOf(it, script.size - 1)] }
    }

    override fun ledgerExists() = ledger != null

    override fun checkLedger(): Check {
        log += "check"
        checkScript?.let { return it }
        val file = ledger!!
        if (lockedFor > 0) { lockedFor--; return Check("database is locked (code 5 SQLITE_BUSY)", null) }
        if (file.damaged || file.truncated) {
            return Check("database disk image is malformed (code 11 SQLITE_CORRUPT)", null)
        }
        return Check("ok", file.version)
    }

    // Whatever else lives in the directory, the sequence has to step over it.
    override fun snapshotNames(): List<String> =
        snapshots.keys.toList() + listOf("incoming.tmp", "last-recovery.properties")

    override fun inspectSnapshot(name: String): Copy {
        log += "inspect $name"
        return describe(snapshots[name])
    }

    override fun stageSnapshot(): Copy? {
        log += "stage"
        if (cannotCopy) return null
        staged = ledger
        return describe(staged)
    }

    override fun publishStaged(name: String): Boolean {
        val file = staged ?: return false
        snapshots[name] = file
        staged = null
        log += "publish $name"
        return true
    }

    override fun discardStaged() {
        staged = null
        log += "discard"
    }

    override fun deleteSnapshot(name: String) {
        snapshots.remove(name)
        log += "delete $name"
    }

    override fun restore(snapshot: String, keepAs: String): Boolean {
        log += "restore $snapshot"
        val copy = snapshots[snapshot]
        if (cannotCopy || copy == null) return false
        if (keepAs in kept) { collisions++; return false }
        ledger?.let { kept[keepAs] = it }
        ledger = copy
        return true
    }

    override fun setAside(keepAs: String): Boolean {
        log += "aside"
        if (cannotMove) return false
        if (keepAs in kept) { collisions++; return false }
        ledger?.let { kept[keepAs] = it }
        ledger = null
        return true
    }

    override fun open(): String? {
        opens++
        log += "open"
        val script = openScript
        val said = if (script != null) script[minOf(opens - 1, script.size - 1)] else answer()
        // Room creates a missing file and migrates an old one, all or nothing.
        if (said == null) ledger = (ledger ?: DbFile(rows = 0, version = 0)).copy(version = TARGET)
        return said
    }

    private fun answer(): String? {
        val file = ledger
        if (lockedFor > 0) { lockedFor--; return LOCKED }
        if (file != null && (file.damaged || file.truncated)) return MALFORMED
        if (file != null && file.version < TARGET && migrationFailures > 0) {
            migrationFailures--
            return MISHANDLED
        }
        if (unknownFailures > 0) { unknownFailures--; return DISK_FULL }
        return null
    }

    private fun describe(file: DbFile?): Copy = when (file) {
        null -> Copy(0, "unable to open database file (code 14 SQLITE_CANTOPEN)", emptySet())
        else -> Copy(
            sizeBytes = if (file.truncated) 200L else 64L * 1024 + file.rows * 128L,
            integrity = if (file.damaged) "row 3 missing from index ix_tx" else "ok",
            tables = if (file.version >= 1) setOf("transactions", "categories", "settings")
                     else emptySet(),
        )
    }
}

private fun open(disk: SimulatedDisk): RecoverySequence.Result =
    RecoverySequence.run(disk, NAME, TARGET, NOW)

private fun destructive(log: List<String>) = log.any { it.startsWith("restore") || it == "aside" }

/** Every sequence of open answers up to [length], the last one repeating. */
private fun scripts(answers: List<String?>, length: Int): List<List<String?>> {
    var level = answers.map { listOf(it) }
    val all = level.toMutableList()
    repeat(length - 1) {
        level = level.flatMap { prefix -> answers.map { prefix + it } }
        all += level
    }
    return all
}

fun main() {
    println("=== a damaged ledger with a good snapshot ends restored ===")
    run {
        val damaged = DbFile(rows = 120, version = 3, damaged = true)
        val good = DbFile(rows = 100, version = 3)
        val disk = SimulatedDisk(damaged)
        val name = disk.shelve(NOW.minusDays(3), good)
        val result = open(disk)
        check("it opens", result is Opened, result.toString())
        check("and says the ledger was restored", result.outcome == Outcome.RESTORED,
            result.outcome.toString())
        check("from that snapshot", result.restoredFrom == name, result.restoredFrom.toString())
        check("which is now the ledger", disk.ledger == good, disk.ledger.toString())
        check("the damaged file is kept, not deleted",
            disk.kept.values.toList() == listOf(damaged), disk.kept.toString())
        check("under the name the result reports", disk.kept.keys.toList() == result.keptAs,
            "${disk.kept.keys} vs ${result.keptAs}")
        check("Room is never handed the damaged file: the restore comes first",
            disk.log.indexOf("restore $name") in 0 until disk.log.indexOf("open"),
            disk.log.toString())
        check("one open was enough", disk.opens == 1, disk.opens.toString())
        check("a damaged file is never snapshotted", "stage" !in disk.log, disk.log.toString())
        check("and the user is told entries after the snapshot are missing",
            !(result as Opened).restoredWithoutLoss)
    }

    println("=== a lock leaves the ledger exactly as it was ===")
    run {
        val ledger = DbFile(rows = 200, version = 3)
        val disk = SimulatedDisk(ledger)
        disk.shelve(NOW.minusDays(2), DbFile(rows = 150, version = 3))
        disk.lockedFor = Int.MAX_VALUE
        val result = open(disk)
        check("a lock that never clears stops", result is Stopped, result.toString())
        check("as 'busy', not as damage", result.outcome == Outcome.BUSY, result.outcome.toString())
        check("classified as contention",
            (result as? Stopped)?.failure == Failure.CONTENTION, result.toString())
        check("the ledger is untouched", disk.ledger == ledger, disk.ledger.toString())
        check("nothing was moved, restored or kept",
            !destructive(disk.log) && disk.kept.isEmpty(), disk.log.toString())
        check("and nothing was copied from a file the check could not read",
            "stage" !in disk.log, disk.log.toString())
        check("one retry, then a stop", disk.opens == 2, disk.opens.toString())
    }
    run {
        val ledger = DbFile(rows = 200, version = 3)
        val disk = SimulatedDisk(ledger)
        disk.lockedFor = 1
        val result = open(disk)
        check("a lock seen only by the check does not stop the open",
            result is Opened && result.outcome == Outcome.NORMAL, result.toString())
        check("and the ledger it opened is the same one", disk.ledger == ledger,
            disk.ledger.toString())
        check("but a file the check never read is not copied, even with no snapshot at all",
            "stage" !in disk.log && disk.snapshots.isEmpty(), disk.log.toString())
    }
    run {
        // The race python_app found in production: an upgrade that loses a
        // lock is contention, so it is retried, not rolled back.
        val disk = SimulatedDisk(DbFile(rows = 200, version = 2))
        disk.openScript = listOf("IllegalStateException: migration to v3 failed: database is locked", null)
        val result = open(disk)
        check("a migration that lost a lock race is retried and opens",
            result is Opened && result.outcome == Outcome.NORMAL, result.toString())
        check("without touching the file", !destructive(disk.log), disk.log.toString())
    }

    println("=== a migration that fails once is retried from the copy taken before it ===")
    run {
        val disk = SimulatedDisk(DbFile(rows = 200, version = 2))
        disk.migrationFailures = 1
        val result = open(disk)
        val taken = Recovery.snapshotName(NOW)
        check("it opens", result is Opened, result.toString())
        check("a snapshot was taken before the first open",
            disk.log.indexOf("publish $taken") in 0 until disk.log.indexOf("open"),
            disk.log.toString())
        check("and is what the retry started from", result.restoredFrom == taken,
            result.restoredFrom.toString())
        check("every entry survived the upgrade",
            disk.ledger == DbFile(rows = 200, version = 3), disk.ledger.toString())
        check("so the user is not told anything is missing",
            (result as? Opened)?.restoredWithoutLoss == true)
        check("but is still told a restore happened", result.outcome == Outcome.RESTORED,
            result.outcome.toString())
    }

    println("=== a migration that always fails stops, with the ledger readable ===")
    run {
        val disk = SimulatedDisk(DbFile(rows = 200, version = 2))
        disk.migrationFailures = Int.MAX_VALUE
        val result = open(disk)
        check("it stops rather than crash or loop", result is Stopped, result.toString())
        check("in safe mode", result.outcome == Outcome.SAFE_MODE, result.outcome.toString())
        check("as a migration failure", (result as? Stopped)?.failure == Failure.MIGRATION)
        check("after exactly one retry", disk.opens == 2, disk.opens.toString())
        check("the ledger holds every entry, at the version the last build read",
            disk.ledger == DbFile(rows = 200, version = 2), disk.ledger.toString())
        check("the reason is kept for whoever has to diagnose it",
            (result as? Stopped)?.detail?.contains("Migration didn't properly handle") == true)
        check("and the screen can say a copy exists",
            (result as? Stopped)?.snapshots == listOf(Recovery.snapshotName(NOW)),
            (result as? Stopped)?.snapshots.toString())
    }

    println("=== a failed migration is never rolled back onto an older copy ===")
    run {
        val disk = SimulatedDisk(DbFile(rows = 200, version = 2))
        val old = disk.shelve(NOW.minusDays(3), DbFile(rows = 150, version = 2))
        disk.migrationFailures = Int.MAX_VALUE
        disk.cannotCopy = true
        val result = open(disk)
        check("with no copy from this run, it stops", result is Stopped, result.toString())
        check("without restoring the three-day-old snapshot",
            disk.log.none { it.startsWith("restore") }, disk.log.toString())
        check("so fifty entries were not thrown away",
            disk.ledger == DbFile(rows = 200, version = 2), disk.ledger.toString())
        check("and the old snapshot is still there", old in disk.snapshots)
        check("it was still retried once", disk.opens == 2, disk.opens.toString())
    }

    println("=== damage with no snapshot starts an empty ledger and keeps the old one ===")
    run {
        val damaged = DbFile(rows = 80, version = 3, damaged = true)
        val disk = SimulatedDisk(damaged)
        val result = open(disk)
        check("it opens", result is Opened, result.toString())
        check("and says it started fresh", result.outcome == Outcome.STARTED_FRESH,
            result.outcome.toString())
        check("the new ledger is empty and current",
            disk.ledger == DbFile(rows = 0, version = 3), disk.ledger.toString())
        check("the damaged one is kept for salvage", disk.kept.values.toList() == listOf(damaged))
        check("under the stamped name",
            result.keptAs == listOf(Recovery.asideName(NAME, NOW)), result.keptAs.toString())
    }

    println("=== a snapshot that does not verify is never restored ===")
    run {
        val disk = SimulatedDisk(DbFile(rows = 120, version = 3, damaged = true))
        val older = disk.shelve(NOW.minusDays(9), DbFile(rows = 90, version = 3))
        val newer = disk.shelve(NOW.minusDays(2), DbFile(rows = 110, version = 3, damaged = true))
        val result = open(disk)
        check("the newest snapshot is damaged, so the older good one is used",
            result.restoredFrom == older, result.restoredFrom.toString())
        check("and never the damaged one", disk.log.none { it == "restore $newer" },
            disk.log.toString())
    }
    run {
        val disk = SimulatedDisk(DbFile(rows = 120, version = 3, damaged = true))
        disk.shelve(NOW.minusDays(2), DbFile(rows = 110, version = 3, truncated = true))
        disk.shelve(NOW.minusDays(9), DbFile(rows = 90, version = 3, damaged = true))
        val result = open(disk)
        check("with no usable snapshot at all, nothing is restored",
            disk.log.none { it.startsWith("restore") }, disk.log.toString())
        check("and an empty ledger is started instead", result.outcome == Outcome.STARTED_FRESH,
            result.outcome.toString())
    }

    println("=== a restore that cannot be written leaves the ledger where it was ===")
    run {
        val damaged = DbFile(rows = 120, version = 3, damaged = true)
        val disk = SimulatedDisk(damaged)
        val name = disk.shelve(NOW.minusDays(3), DbFile(rows = 100, version = 3))
        disk.cannotCopy = true
        val result = open(disk)
        check("the failed restore is not reported as a restore", result.restoredFrom == null,
            result.restoredFrom.toString())
        check("the original is kept, not lost between two files",
            disk.kept.values.toList() == listOf(damaged), disk.kept.toString())
        check("the snapshot survives for a later attempt", name in disk.snapshots)
        check("and the user hears about the empty ledger", result.outcome == Outcome.STARTED_FRESH,
            result.outcome.toString())
    }

    println("=== two files set aside in one run do not overwrite each other ===")
    run {
        val damaged = DbFile(rows = 120, version = 3, damaged = true)
        val disk = SimulatedDisk(damaged)
        val name = disk.shelve(NOW.minusDays(3), DbFile(rows = 100, version = 3))
        // The snapshot verifies, but the restored copy still fails to open:
        // a phone whose storage is failing under it.
        disk.openScript = listOf(MALFORMED, null)
        val result = open(disk)
        check("it ends on an empty ledger", result.outcome == Outcome.STARTED_FRESH,
            result.outcome.toString())
        check("both files were kept", disk.kept.size == 2, disk.kept.toString())
        check("under different names, in the same second",
            result.keptAs.toSet().size == 2 && disk.collisions == 0, result.keptAs.toString())
        check("the original ledger is the first, untouched",
            disk.kept[result.keptAs.first()] == damaged, disk.kept.toString())
        check("and the snapshot itself is not consumed", name in disk.snapshots)
    }

    println("=== a ledger that cannot be moved aside ===")
    run {
        val damaged = DbFile(rows = 120, version = 3, damaged = true)
        val disk = SimulatedDisk(damaged)
        disk.cannotMove = true
        val result = open(disk)
        check("stops instead of trying to move it again", result.outcome == Outcome.SAFE_MODE,
            result.toString())
        check("after one open", disk.opens == 1, disk.opens.toString())
        check("and the ledger is still where it was", disk.ledger == damaged,
            disk.ledger.toString())
        check("with nothing claimed as kept", result.keptAs.isEmpty(), result.keptAs.toString())
    }
    run {
        // The check saw damage, the move failed, and then the file opened
        // after all: nothing changed, and claiming a fresh start would be a
        // lie about where the user's entries went.
        val ledger = DbFile(rows = 120, version = 3)
        val disk = SimulatedDisk(ledger)
        disk.checkScript = Check("database disk image is malformed (code 11 SQLITE_CORRUPT)", null)
        disk.cannotMove = true
        val result = open(disk)
        check("a move that failed is not reported as a fresh start",
            result is Opened && result.outcome == Outcome.NORMAL, result.toString())
        check("because the ledger never went anywhere", disk.ledger == ledger,
            disk.ledger.toString())
    }

    println("=== a failure nothing recognises never touches the file ===")
    run {
        val ledger = DbFile(rows = 200, version = 3)
        val disk = SimulatedDisk(ledger)
        disk.unknownFailures = Int.MAX_VALUE
        val result = open(disk)
        check("it stops in safe mode", result.outcome == Outcome.SAFE_MODE, result.toString())
        check("as unknown, not as damage", (result as? Stopped)?.failure == Failure.UNKNOWN)
        check("with the ledger as it was", disk.ledger == ledger, disk.ledger.toString())
        check("and nothing moved", !destructive(disk.log), disk.log.toString())
    }
    run {
        val ledger = DbFile(rows = 200, version = 3)
        val disk = SimulatedDisk(ledger)
        disk.unknownFailures = 1
        val result = open(disk)
        check("one that clears on the retry just opens",
            result is Opened && result.outcome == Outcome.NORMAL, result.toString())
    }

    println("=== a first launch has nothing to check or copy ===")
    run {
        val disk = SimulatedDisk(null)
        val result = open(disk)
        check("it opens a new ledger", result is Opened && result.outcome == Outcome.NORMAL,
            result.toString())
        check("without a check or a snapshot", disk.log == listOf("open"), disk.log.toString())
    }

    println("=== when a snapshot is taken ===")
    run {
        val disk = SimulatedDisk(DbFile(rows = 10, version = 3))
        val result = open(disk) as Opened
        check("a checked ledger with no snapshot gets one",
            result.snapshotTaken == Recovery.snapshotName(NOW), result.snapshotTaken.toString())
        check("and an ordinary open says nothing", result.outcome == Outcome.NORMAL)
    }
    run {
        val disk = SimulatedDisk(DbFile(rows = 10, version = 3))
        disk.shelve(NOW.minusDays(2), DbFile(rows = 9, version = 3))
        val result = open(disk) as Opened
        check("a two-day-old snapshot is recent enough", result.snapshotTaken == null,
            disk.log.toString())
    }
    run {
        val disk = SimulatedDisk(DbFile(rows = 10, version = 3))
        disk.shelve(NOW.minusDays(Recovery.REFRESH_DAYS), DbFile(rows = 9, version = 3))
        val result = open(disk) as Opened
        check("a week-old one is replaced", result.snapshotTaken == Recovery.snapshotName(NOW))
    }
    run {
        val disk = SimulatedDisk(DbFile(rows = 10, version = 2))
        disk.shelve(NOW.minusDays(1), DbFile(rows = 9, version = 2))
        val result = open(disk) as Opened
        check("a pending migration always takes one, however fresh the last",
            result.snapshotTaken == Recovery.snapshotName(NOW), disk.log.toString())
    }
    run {
        val disk = SimulatedDisk(DbFile(rows = 0, version = 0))
        val result = open(disk) as Opened
        check("a copy that does not hold a ledger is discarded, not published",
            result.snapshotTaken == null && "discard" in disk.log && disk.snapshots.isEmpty(),
            disk.log.toString())
    }
    run {
        val disk = SimulatedDisk(DbFile(rows = 10, version = 3))
        disk.cannotCopy = true
        val result = open(disk)
        check("a snapshot that cannot be written does not stop the open",
            result is Opened && result.outcome == Outcome.NORMAL, result.toString())
    }

    println("=== old snapshots are pruned, the new one never ===")
    run {
        val disk = SimulatedDisk(DbFile(rows = 10, version = 3))
        val oldest = disk.shelve(NOW.minusDays(30), DbFile(rows = 4, version = 3))
        disk.shelve(NOW.minusDays(20), DbFile(rows = 6, version = 3))
        disk.shelve(NOW.minusDays(10), DbFile(rows = 8, version = 3))
        open(disk)
        check("a fourth retires the oldest",
            disk.snapshots.size == Recovery.KEEP && oldest !in disk.snapshots,
            disk.snapshots.keys.toString())
        check("through Recovery.expired", "delete $oldest" in disk.log, disk.log.toString())
        check("and junk in the directory is left alone",
            disk.log.none { it.startsWith("delete") && !Recovery.isSnapshotName(it.removePrefix("delete ")) })
    }
    run {
        // A clock that ran backwards: three copies stamped in the "future".
        val disk = SimulatedDisk(DbFile(rows = 10, version = 2))
        repeat(3) { disk.shelve(NOW.plusDays(5L + it), DbFile(rows = 9, version = 2)) }
        val result = open(disk) as Opened
        check("the copy taken before a migration survives pruning even then",
            result.snapshotTaken != null && result.snapshotTaken in disk.snapshots,
            disk.snapshots.keys.toString())
    }
    run {
        val disk = SimulatedDisk(DbFile(rows = 120, version = 3, damaged = true))
        repeat(5) { disk.shelve(NOW.minusDays(10L + it), DbFile(rows = 100 - it, version = 3)) }
        open(disk)
        check("nothing is pruned while the ledger is damaged: an old copy may be the good one",
            disk.log.none { it.startsWith("delete") }, disk.log.toString())
    }

    println("=== every run ends, and only damage ever moves a file ===")
    val answers = listOf<String?>(null, LOCKED, MALFORMED, MISHANDLED, DISK_FULL)
    val checks = listOf<Check?>(
        null,
        Check("ok", 3),
        Check("ok", 2),
        Check("database is locked (code 5 SQLITE_BUSY)", null),
        Check("database disk image is malformed (code 11 SQLITE_CORRUPT)", null),
        Check(null, null),
    )
    val shelves = listOf(
        emptyList(),
        listOf(DbFile(rows = 100, version = 3)),
        listOf(DbFile(rows = 100, version = 3, damaged = true)),
        listOf(DbFile(rows = 100, version = 3, damaged = true), DbFile(rows = 90, version = 2)),
    )
    var runs = 0
    var longest = 0
    var tooLong: String? = null
    var wrongEnding: String? = null
    var overwrote: String? = null
    var misreported: String? = null
    var movedWithoutDamage: String? = null
    var olderRollback: String? = null
    var unverifiedRestore: String? = null
    var uncheckedCopy: String? = null
    for (c in checks) for (shelf in shelves) for (stuck in listOf(false, true))
    for (script in scripts(answers, 4)) {
        val disk = SimulatedDisk(if (c == null) null else DbFile(rows = 50, version = c.version ?: 3))
        disk.checkScript = c
        disk.openScript = script
        disk.cannotMove = stuck
        shelf.forEachIndexed { i, f -> disk.shelve(NOW.minusDays(3L + i), f) }
        val before = disk.snapshots.toMap()
        val result = open(disk)
        val seen = disk.answered()
        val case = "check=${c?.integrity} shelf=${shelf.size} stuck=$stuck script=$script " +
            "log=${disk.log}"
        runs++
        longest = maxOf(longest, disk.opens)

        if (disk.opens > 3 && tooLong == null) tooLong = case
        if ((result is Opened) != (seen.lastOrNull() == null && seen.isNotEmpty()) &&
            wrongEnding == null) wrongEnding = case
        if (disk.collisions > 0 && overwrote == null) overwrote = case
        if (result.keptAs != disk.kept.keys.toList() && misreported == null) misreported = case

        val damageSeen = c?.integrity?.contains("malformed") == true ||
            seen.any { it == MALFORMED || it == MISHANDLED }
        if (destructive(disk.log) && !damageSeen && movedWithoutDamage == null) {
            movedWithoutDamage = case
        }

        val restored = disk.log.filter { it.startsWith("restore ") }.map { it.removePrefix("restore ") }
        val corruptionSeen = c?.integrity?.contains("malformed") == true || MALFORMED in seen
        if (!corruptionSeen && restored.any { it != Recovery.snapshotName(NOW) } &&
            olderRollback == null) olderRollback = case
        if (restored.any { before[it]?.damaged == true } && unverifiedRestore == null) {
            unverifiedRestore = case
        }
        if ("stage" in disk.log && Recovery.classifyIntegrity(c?.integrity) != Failure.NONE &&
            uncheckedCopy == null) uncheckedCopy = case
    }
    check("every one of $runs runs stops within three opens", tooLong == null && longest <= 3,
        tooLong ?: "longest=$longest")
    check("and long before the backstop",
        longest < RecoverySequence.MAX_OPENS, "longest=$longest")
    check("it reports an open exactly when the last open succeeded", wrongEnding == null,
        wrongEnding ?: "")
    check("no file set aside is ever renamed over", overwrote == null, overwrote ?: "")
    check("the result names exactly the files that were kept", misreported == null,
        misreported ?: "")
    check("a lock, a full disk or a silent check never moves or replaces the ledger",
        movedWithoutDamage == null, movedWithoutDamage ?: "")
    check("a migration failure only ever rolls back to the copy taken this run",
        olderRollback == null, olderRollback ?: "")
    check("a damaged snapshot is never put in place of the ledger", unverifiedRestore == null,
        unverifiedRestore ?: "")
    check("only a ledger whose check came back clean is ever copied", uncheckedCopy == null,
        uncheckedCopy ?: "")

    println()
    println("=".repeat(60))
    println("$passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
