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

interface SupportSQLiteOpenHelper
