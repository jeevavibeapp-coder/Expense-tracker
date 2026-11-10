package android.database

/** Cursor is Closeable, which is what makes `query(...)?.use { }` work. */
interface Cursor : java.io.Closeable {
    fun moveToNext(): Boolean
    fun moveToFirst(): Boolean
    fun getCount(): Int
    /** -1 when the column is absent; every read has to be guarded on it. */
    fun getColumnIndex(columnName: String): Int
    fun getColumnIndexOrThrow(columnName: String): Int
    fun getString(columnIndex: Int): String?
    fun getLong(columnIndex: Int): Long
    fun getInt(columnIndex: Int): Int
    fun getDouble(columnIndex: Int): Double
    fun isNull(columnIndex: Int): Boolean
    override fun close()
}
