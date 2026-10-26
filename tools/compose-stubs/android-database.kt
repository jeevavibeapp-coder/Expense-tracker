// android.database: what the recovery path needs to open a file WITHOUT the
// platform deciding its fate. A Java interface in the real SDK, so a lambda
// converts to it; `fun interface` is what makes the stub accept the same one.
package android.database

fun interface DatabaseErrorHandler {
    fun onCorruption(dbObj: android.database.sqlite.SQLiteDatabase)
}

/** Unchecked, as in the SDK: SQLite's refusals reach callers that never
 *  declared them, which is why the recovery path catches by this type. */
open class SQLException : RuntimeException {
    constructor() : super()
    constructor(error: String?) : super(error)
    constructor(error: String?, cause: Throwable?) : super(error, cause)
}
