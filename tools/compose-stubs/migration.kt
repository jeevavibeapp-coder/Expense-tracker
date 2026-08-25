package androidx.room.migration

import androidx.sqlite.db.SupportSQLiteDatabase

// Room's Migration is an abstract class taking the version pair, so a stub
// has to be a class rather than an interface for `object : Migration(1, 2)`
// to type-check the same way it does on device.
abstract class Migration(val startVersion: Int, val endVersion: Int) {
    abstract fun migrate(db: SupportSQLiteDatabase)
}
