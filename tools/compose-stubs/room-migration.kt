package androidx.room.migration

import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration is an abstract CLASS taking the version pair, which is why
 *  `object : Migration(1, 2)` is the idiom and an interface stub would not
 *  type-check the same way. */
abstract class Migration(val startVersion: Int, val endVersion: Int) {
    abstract fun migrate(db: SupportSQLiteDatabase)
}
