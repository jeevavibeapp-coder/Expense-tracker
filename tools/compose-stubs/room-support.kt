package androidx.sqlite.db

interface SupportSQLiteDatabase {
    fun execSQL(sql: String)
    fun execSQL(sql: String, bindArgs: Array<out Any?>)
    fun beginTransaction()
    fun endTransaction()
    fun setTransactionSuccessful()
    fun query(query: String): android.database.Cursor
    val version: Int
}

/**
 * androidx.sqlite 2.4.0, the version Room 2.6.1 depends on: Kotlin since
 * 2.3.0, so the open methods are properties and Factory is a fun interface.
 * Transcribed for the recovery path, which has to replace exactly one
 * callback — onCorruption, whose default deletes the database — and pass
 * every other one through to Room untouched.
 */
interface SupportSQLiteOpenHelper : java.io.Closeable {
    val databaseName: String?
    fun setWriteAheadLoggingEnabled(enabled: Boolean)
    val writableDatabase: SupportSQLiteDatabase
    val readableDatabase: SupportSQLiteDatabase
    override fun close()

    abstract class Callback(@JvmField val version: Int) {
        open fun onConfigure(db: SupportSQLiteDatabase) {}
        abstract fun onCreate(db: SupportSQLiteDatabase)
        abstract fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int)
        open fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        open fun onOpen(db: SupportSQLiteDatabase) {}
        open fun onCorruption(db: SupportSQLiteDatabase) {}
    }

    class Configuration(
        @JvmField val context: android.content.Context,
        @JvmField val name: String?,
        @JvmField val callback: Callback,
        @JvmField val useNoBackupDirectory: Boolean = false,
        @JvmField val allowDataLossOnRecovery: Boolean = false,
    ) {
        open class Builder internal constructor(context: android.content.Context) {
            open fun build(): Configuration = throw UnsupportedOperationException("stub")
            open fun name(name: String?): Builder = this
            open fun callback(callback: Callback): Builder = this
            open fun noBackupDirectory(useNoBackupDirectory: Boolean): Builder = this
            open fun allowDataLossOnRecovery(allowDataLossOnRecovery: Boolean): Builder = this
        }

        companion object {
            @JvmStatic
            fun builder(context: android.content.Context): Builder = Builder(context)
        }
    }

    fun interface Factory {
        fun create(configuration: Configuration): SupportSQLiteOpenHelper
    }
}
