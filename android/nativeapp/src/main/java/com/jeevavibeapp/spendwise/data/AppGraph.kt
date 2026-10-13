package com.jeevavibeapp.spendwise.data

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.jeevavibeapp.spendwise.core.Recovery
import com.jeevavibeapp.spendwise.core.RecoverySequence
import com.jeevavibeapp.spendwise.sms.InboxScanner
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDateTime
import java.util.Properties

/**
 * Wiring. Deliberately a plain object rather than a DI framework: this app
 * has one database, one repository and one scanner, and a container would be
 * more machinery than the thing it manages.
 *
 * Every way into the ledger — the launcher, the SMS receiver, the retry
 * button — opens it through [RecoverySequence], because the alternative was
 * an app that crashed on every launch after one bad write, with the user's
 * whole history still on the phone and no way to be told so.
 */
object AppGraph {

    /** A result rather than a database or an exception, so a caller cannot
     *  reach the ledger without first deciding what to do when there is none. */
    sealed interface Ledger {
        class Ready(val repo: Repo) : Ledger
        class Unavailable(val stopped: RecoverySequence.Stopped, val where: Where) : Ledger
    }

    /** Absolute paths, because the one thing a person facing a failure screen
     *  must be able to pass on is where their data still is. */
    class Where(val ledger: String, val snapshots: String, val kept: List<String>)

    /** Read back from disk, not passed along in memory, because whoever
     *  recovered may have had no screen to say it on. */
    class Notice(
        val outcome: Recovery.Outcome,
        val restoredFrom: String?,
        val withoutLoss: Boolean,
        val where: Where,
    )

    class LedgerUnavailable(val stopped: RecoverySequence.Stopped) :
        IllegalStateException("the ledger could not be opened: ${stopped.outcome}")

    @Volatile private var db: SpendDatabase? = null
    @Volatile private var repoRef: Repo? = null
    @Volatile private var safeMode: Ledger.Unavailable? = null

    /** Blocks for as long as a recovery takes, so never on the main thread. */
    fun open(context: Context): Ledger {
        repoRef?.let { return Ledger.Ready(it) }
        return synchronized(this) {
            repoRef?.let { Ledger.Ready(it) } ?: safeMode ?: openNow(context.applicationContext)
        }
    }

    /** The failure screen's button. Safe mode is otherwise held until this is
     *  pressed — see [openNow] for why. */
    fun retry(context: Context): Ledger = synchronized(this) {
        safeMode = null
        open(context)
    }

    fun database(context: Context): SpendDatabase {
        val ledger = open(context)
        if (ledger is Ledger.Unavailable) throw LedgerUnavailable(ledger.stopped)
        return checkNotNull(db)
    }

    /** Throws [LedgerUnavailable] rather than handing out a repository over a
     *  database that never opened; the SMS receiver already catches it, and
     *  the message stays in the inbox for the next launch's scan. */
    fun repo(context: Context): Repo = when (val ledger = open(context)) {
        is Ledger.Ready -> ledger.repo
        is Ledger.Unavailable -> throw LedgerUnavailable(ledger.stopped)
    }

    fun inboxScanner(context: Context): InboxScanner =
        InboxScanner(context.applicationContext, repo(context))

    fun notice(context: Context): Notice? = LedgerDisk(context.applicationContext).readNotice()

    fun dismissNotice(context: Context) = LedgerDisk(context.applicationContext).clearNotice()

    private fun openNow(app: Context): Ledger {
        val disk = LedgerDisk(app)
        val result = try {
            RecoverySequence.run(disk, LEDGER, SCHEMA, LocalDateTime.now())
        } catch (t: Throwable) {
            // A bug on the Android side of the sequence. Thrown from here it
            // would be the crash on every launch this whole path replaces.
            Log.e(TAG, "recovery sequence failed", t)
            RecoverySequence.Stopped(Recovery.Outcome.SAFE_MODE, Recovery.Failure.UNKNOWN,
                describe(t), null, emptyList(), emptyList())
        }
        return when (result) {
            is RecoverySequence.Opened -> {
                // Written down rather than held: the SMS receiver can be the
                // one that recovered, with no screen up and a process that
                // may be gone before the user next opens the app.
                if (result.outcome != Recovery.Outcome.NORMAL) disk.recordNotice(result)
                val opened = checkNotNull(disk.opened)
                db = opened
                Ledger.Ready(Repo(opened.dao()).also { repoRef = it })
            }
            is RecoverySequence.Stopped -> {
                val unavailable = Ledger.Unavailable(result, disk.where(result.keptAs))
                // Busy clears by itself, so the next caller simply tries
                // again. Anything else waits for the button: each run of a
                // failing migration sets another full copy of the ledger
                // aside, and an SMS every few minutes would fill the phone.
                if (result.outcome == Recovery.Outcome.SAFE_MODE) safeMode = unavailable
                unavailable
            }
        }
    }
}

private const val LEDGER = "spendwise.db"
private const val TAG = "SpendWiseLedger"

/** Declaring a migration is not enough — Room only runs the ones it is
 *  handed, and refuses to open a database it has no path for. Without these
 *  a v1 install crashes on first open instead of upgrading. There is no
 *  destructive fallback: there is no cloud copy to restore from, so every
 *  version must ship a real migration. */
private val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

/** The schema this build writes, read off the migrations rather than
 *  restated: Room's own number cannot be read until the open it is needed to
 *  prepare for, and a second copy of it would drift. */
private val SCHEMA = MIGRATIONS.maxOf { it.endVersion }

/** A journal or log belongs to the file it sits beside, and is moved or
 *  removed with it. */
private val SIDE_FILES = listOf("-wal", "-shm", "-journal")

/** Android's default handler deletes a database it finds corrupt. Here the
 *  file is the only copy of someone's finances, so damage only raises, and
 *  what happens to the file is RecoverySequence's decision. */
private val KEEP_FILE = DatabaseErrorHandler {
    Log.e(TAG, "SQLite reported corruption; the file is kept for recovery")
}

/**
 * Room's answer to a corrupt file is SupportSQLiteOpenHelper.Callback's
 * default onCorruption: delete it, and carry on with an empty database.
 * That is silent total loss on exactly the event recovery exists for, and it
 * would happen inside Room before RecoverySequence ever saw a failure. So
 * every callback goes through to Room unchanged except that one.
 */
private object KeepDamagedFiles : SupportSQLiteOpenHelper.Factory {
    override fun create(
        configuration: SupportSQLiteOpenHelper.Configuration,
    ): SupportSQLiteOpenHelper {
        val room = configuration.callback
        val keeping = object : SupportSQLiteOpenHelper.Callback(room.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = room.onConfigure(db)
            override fun onCreate(db: SupportSQLiteDatabase) = room.onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                room.onUpgrade(db, oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                room.onDowngrade(db, oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = room.onOpen(db)
            override fun onCorruption(db: SupportSQLiteDatabase) {
                Log.e(TAG, "Room reported corruption; the file is kept for recovery")
            }
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(NoDeletes(configuration.context))
                .name(configuration.name)
                .callback(keeping)
                .noBackupDirectory(configuration.useNoBackupDirectory)
                .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
                .build()
        )
    }
}

/**
 * The Context the open helper is given. androidx.sqlite has a second way to
 * lose a ledger: when Room's onOpen fails twice with any SQLite error —
 * damage, or merely a lock — it calls Context.deleteDatabase and starts again
 * empty, whatever allowDataLossOnRecovery says. Refused here, the failure
 * surfaces instead, RecoverySequence classifies it, and the file is kept.
 */
private class NoDeletes(base: Context) : ContextWrapper(base) {
    override fun deleteDatabase(name: String): Boolean {
        Log.e(TAG, "refused to delete $name; recovery decides what happens to it")
        return false
    }
}

/**
 * The files behind [RecoverySequence.Disk], and nothing more: every decision
 * about them is made in :core, where the order they are made in is tested.
 */
private class LedgerDisk(private val app: Context) : RecoverySequence.Disk {
    private val live = app.getDatabasePath(LEDGER).absoluteFile
    private val dir = checkNotNull(live.parentFile)
    private val shelf = File(dir, Recovery.DIR)
    private val staging = File(shelf, "incoming.tmp")
    private val incoming = File(dir, "$LEDGER.restoring")
    private val noticeFile = File(shelf, "last-recovery.properties")

    /** Kept rather than built again: this instance is the one whose open
     *  RecoverySequence saw succeed. */
    var opened: SpendDatabase? = null
        private set

    fun where(keptAs: List<String>) =
        AppGraph.Where(live.path, shelf.path, keptAs.map { File(dir, it).path })

    override fun ledgerExists() = live.exists()

    override fun checkLedger(): RecoverySequence.Check = try {
        withDatabase(live) { RecoverySequence.Check(firstRow(it, "PRAGMA integrity_check"), it.version) }
    } catch (e: SQLiteException) {
        RecoverySequence.Check(describe(e), null)
    } catch (t: Throwable) {
        // Not SQLite's verdict on the file, so not evidence of damage either:
        // the real open decides instead.
        Log.w(TAG, "ledger check did not run", t)
        RecoverySequence.Check(null, null)
    }

    override fun snapshotNames(): List<String> = shelf.list()?.toList().orEmpty()

    override fun inspectSnapshot(name: String) = inspect(File(shelf, name))

    override fun stageSnapshot(): RecoverySequence.Copy? = try {
        shelf.mkdirs()
        remove(staging)
        // The copy takes the main file alone, so whatever is still in the
        // write-ahead log is folded into it first.
        withDatabase(live) { firstRow(it, "PRAGMA wal_checkpoint(TRUNCATE)") }
        copy(live, staging)
        inspect(staging)
    } catch (t: Throwable) {
        Log.w(TAG, "snapshot not taken", t)
        null
    }

    override fun publishStaged(name: String) = staging.renameTo(File(shelf, name))

    override fun discardStaged() = remove(staging)

    override fun deleteSnapshot(name: String) {
        if (Recovery.isSnapshotName(name)) remove(File(shelf, name))
    }

    override fun restore(snapshot: String, keepAs: String): Boolean {
        // Copied before the ledger moves, so a full disk fails here with the
        // ledger still in place rather than with nothing where it was.
        try {
            remove(incoming)
            copy(File(shelf, snapshot), incoming)
        } catch (t: Throwable) {
            Log.w(TAG, "restore copy failed", t)
            remove(incoming)
            return false
        }
        // Even with no ledger there, a stray log beside where it was would be
        // replayed into the copy, so the move runs either way.
        if (!setAside(keepAs)) {
            remove(incoming)
            return false
        }
        if (incoming.renameTo(live)) return true
        File(dir, keepAs).renameTo(live)
        remove(incoming)
        return false
    }

    override fun setAside(keepAs: String): Boolean {
        val kept = File(dir, keepAs)
        if (kept.exists() || (live.exists() && !live.renameTo(kept))) return false
        for (suffix in SIDE_FILES) {
            val side = File(dir, LEDGER + suffix)
            // Left behind, it would be replayed into whatever takes the
            // ledger's place: it goes with its file, or it goes.
            if (side.exists() && !side.renameTo(File(dir, keepAs + suffix))) side.delete()
        }
        return !live.exists()
    }

    override fun open(): String? {
        var built: SpendDatabase? = null
        return try {
            built = Room.databaseBuilder(app, SpendDatabase::class.java, LEDGER)
                .addMigrations(*MIGRATIONS)
                .openHelperFactory(KeepDamagedFiles)
                .build()
            // Room opens lazily, on whichever query comes first, which would
            // put this failure inside a screen or the SMS receiver. Forced
            // here, it can be caught, classified and answered.
            built.openHelper.writableDatabase
            opened = built
            null
        } catch (t: Throwable) {
            Log.w(TAG, "ledger did not open", t)
            try { built?.close() } catch (ignored: Throwable) { }
            describe(t)
        }
    }

    fun recordNotice(result: RecoverySequence.Opened) {
        try {
            shelf.mkdirs()
            val notice = Properties()
            notice.setProperty("outcome", result.outcome.name)
            notice.setProperty("restoredFrom", result.restoredFrom.orEmpty())
            notice.setProperty("withoutLoss", result.restoredWithoutLoss.toString())
            notice.setProperty("keptAs", result.keptAs.joinToString("/"))
            FileOutputStream(noticeFile).use { notice.store(it, null) }
        } catch (t: Throwable) {
            Log.w(TAG, "recovery notice not written", t)
        }
    }

    fun readNotice(): AppGraph.Notice? = try {
        if (!noticeFile.exists()) null else {
            val saved = Properties()
            FileInputStream(noticeFile).use { saved.load(it) }
            AppGraph.Notice(
                outcome = Recovery.Outcome.valueOf(saved.getProperty("outcome")),
                restoredFrom = saved.getProperty("restoredFrom")?.takeIf { it.isNotEmpty() },
                withoutLoss = saved.getProperty("withoutLoss") == "true",
                where = where(saved.getProperty("keptAs").orEmpty().split('/').filter { it.isNotEmpty() }),
            )
        }
    } catch (t: Throwable) {
        Log.w(TAG, "recovery notice unreadable", t)
        null
    }

    fun clearNotice() {
        noticeFile.delete()
    }

    private fun inspect(file: File): RecoverySequence.Copy {
        val size = file.length()
        return try {
            withDatabase(file) { RecoverySequence.Copy(size, firstRow(it, "PRAGMA integrity_check"), tables(it)) }
        } catch (e: SQLiteException) {
            RecoverySequence.Copy(size, describe(e), emptySet())
        } catch (t: Throwable) {
            RecoverySequence.Copy(size, null, emptySet())
        }
    }
}

/** Read-write, because a read-only connection cannot roll back a hot journal
 *  and would report a healthy ledger that crashed mid-write as unreadable. */
private inline fun <T> withDatabase(file: File, block: (SQLiteDatabase) -> T): T =
    SQLiteDatabase.openDatabase(
        file.path, null,
        SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        KEEP_FILE,
    ).use(block)

private fun firstRow(db: SQLiteDatabase, sql: String): String? =
    db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getString(0) else null }

private fun tables(db: SQLiteDatabase): Set<String> =
    db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { rows ->
        buildSet { while (rows.moveToNext()) rows.getString(0)?.let(::add) }
    }

private fun copy(from: File, to: File) {
    FileInputStream(from).use { input ->
        FileOutputStream(to).use { output ->
            input.copyTo(output)
            // On the disk before anything is renamed on the strength of it,
            // not in a cache that a power cut would empty.
            output.fd.sync()
        }
    }
    if (to.length() != from.length()) {
        throw IOException("copied ${to.length()} of ${from.length()} bytes")
    }
}

private fun remove(file: File) {
    file.delete()
    for (suffix in SIDE_FILES) File(file.path + suffix).delete()
}

/** The whole causal chain, class names included: Room wraps the SQLite error
 *  that says what actually happened, and SQLiteDatabaseLockedException says
 *  more in its name than in its message. */
private fun describe(t: Throwable): String =
    generateSequence(t) { it.cause }.take(8)
        .joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }
