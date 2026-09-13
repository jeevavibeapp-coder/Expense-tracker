// android.database.sqlite, as the recovery path uses it: the platform SQLite
// API is how a database file can be inspected, checkpointed and verified
// WITHOUT handing it to Room — which matters because Room's open is also its
// migration, and by then it is too late to snapshot what the migration is
// about to change.
package android.database.sqlite

import android.database.Cursor

/** SQLiteClosable implements Closeable, which is what makes `use { }` close
 *  an open database on every path out. */
abstract class SQLiteClosable : java.io.Closeable {
    override fun close() {}
}

class SQLiteDatabase private constructor() : SQLiteClosable() {

    fun rawQuery(sql: String, selectionArgs: Array<String>?): Cursor =
        throw UnsupportedOperationException("android stub")

    fun execSQL(sql: String) {}

    /** `PRAGMA user_version` — the same integer Room stores its schema
     *  version in, which is how the version on disk is read before Room is
     *  allowed to change it. */
    var version: Int = 0

    val isOpen: Boolean get() = false

    interface CursorFactory

    companion object {
        const val OPEN_READWRITE = 0x00000000
        const val OPEN_READONLY = 0x00000001
        const val NO_LOCALIZED_COLLATORS = 0x00000010
        const val CREATE_IF_NECESSARY = 0x10000000

        /** Throws rather than creating when the file is absent, unless
         *  CREATE_IF_NECESSARY is passed — the distinction the recovery path
         *  depends on. */
        fun openDatabase(path: String, factory: CursorFactory?, flags: Int): SQLiteDatabase =
            throw UnsupportedOperationException("android stub")
    }
}
