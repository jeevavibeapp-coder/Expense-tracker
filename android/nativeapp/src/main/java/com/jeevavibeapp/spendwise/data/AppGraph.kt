package com.jeevavibeapp.spendwise.data

import android.content.Context
import androidx.room.Room
import com.jeevavibeapp.spendwise.sms.InboxScanner

/**
 * Wiring. Deliberately a plain object rather than a DI framework: this app
 * has one database, one repository and one scanner, and a container would be
 * more machinery than the thing it manages.
 */
object AppGraph {
    @Volatile private var db: SpendDatabase? = null
    @Volatile private var repoRef: Repo? = null

    fun database(context: Context): SpendDatabase =
        db ?: synchronized(this) {
            db ?: Room.databaseBuilder(
                context.applicationContext, SpendDatabase::class.java, "spendwise.db")
                // Declaring MIGRATION_1_2 is not enough — Room only runs the
                // migrations it is handed, and refuses to open a database it
                // has no path for. Without this line a v1 install crashes on
                // first open instead of upgrading.
                .addMigrations(MIGRATION_1_2)
                // No destructive fallback. Losing a ledger to a schema change
                // is not an acceptable outcome for a finance app, and there
                // is no cloud copy to restore from — every future version
                // must ship a real migration.
                .build().also { db = it }
        }

    fun repo(context: Context): Repo =
        repoRef ?: synchronized(this) {
            repoRef ?: Repo(database(context).dao()).also { repoRef = it }
        }

    fun inboxScanner(context: Context): InboxScanner =
        InboxScanner(context.applicationContext, repo(context))
}
