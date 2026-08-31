// Room annotation stubs. Annotations carry no behaviour, so stubbing them is
// exact rather than approximate: the compiler checks the same entity and DAO
// declarations it would with the real artifact. Room's generated code is not
// modelled — that is KSP's job at build time, and it cannot fail in a way
// these declarations would hide.
package androidx.room

@Target(AnnotationTarget.CLASS) annotation class Entity(
    val tableName: String = "", val indices: Array<Index> = [],
    val inheritSuperIndices: Boolean = false,
    val primaryKeys: Array<String> = [], val foreignKeys: Array<ForeignKey> = [],
    val ignoredColumns: Array<String> = [])
@Target(AnnotationTarget.CLASS) annotation class Index(
    val value: Array<String> = [], val unique: Boolean = false, val name: String = "")
@Target(AnnotationTarget.CLASS) annotation class ForeignKey(
    val entity: kotlin.reflect.KClass<*>, val parentColumns: Array<String>,
    val childColumns: Array<String>, val onDelete: Int = NO_ACTION,
    val onUpdate: Int = NO_ACTION, val deferred: Boolean = false) {
    companion object {
        const val NO_ACTION = 1; const val RESTRICT = 2; const val SET_NULL = 3
        const val SET_DEFAULT = 4; const val CASCADE = 5
    }
}
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class PrimaryKey(val autoGenerate: Boolean = false)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class ColumnInfo(
    val name: String = "[field-name]", val defaultValue: String = "[value-unspecified]",
    val typeAffinity: Int = 1, val index: Boolean = false, val collate: Int = 1)
@Target(AnnotationTarget.CLASS) annotation class Dao
@Target(AnnotationTarget.FUNCTION) annotation class Query(val value: String)
@Target(AnnotationTarget.FUNCTION) annotation class Insert(
    val entity: kotlin.reflect.KClass<*> = Any::class, val onConflict: Int = OnConflictStrategy.ABORT)
@Target(AnnotationTarget.FUNCTION) annotation class Upsert(
    val entity: kotlin.reflect.KClass<*> = Any::class)
@Target(AnnotationTarget.FUNCTION) annotation class RawQuery
@Target(AnnotationTarget.FUNCTION) annotation class Update(
    val entity: kotlin.reflect.KClass<*> = Any::class, val onConflict: Int = OnConflictStrategy.ABORT)
@Target(AnnotationTarget.FUNCTION) annotation class Delete
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS) annotation class Transaction
@Target(AnnotationTarget.CLASS) annotation class Database(
    val entities: Array<kotlin.reflect.KClass<*>>, val version: Int,
    val exportSchema: Boolean = true, val views: Array<kotlin.reflect.KClass<*>> = [],
    val autoMigrations: Array<AutoMigration> = [])
@Target() annotation class AutoMigration(
    val from: Int, val to: Int, val spec: kotlin.reflect.KClass<*> = Any::class)
@Target(AnnotationTarget.CLASS) annotation class TypeConverters(
    val value: Array<kotlin.reflect.KClass<*>> = [])
@Target(AnnotationTarget.FUNCTION) annotation class TypeConverter
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD) annotation class Embedded
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD) annotation class Ignore

object OnConflictStrategy {
    const val REPLACE = 1
    const val ROLLBACK = 2
    const val ABORT = 3
    const val FAIL = 4
    const val IGNORE = 5
}

abstract class RoomDatabase {
    open fun clearAllTables() {}
    open fun close() {}
    open val isOpen: Boolean get() = false
    open fun <R> runInTransaction(body: java.util.concurrent.Callable<R>): R = body.call()
    abstract class Callback {
        open fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
        open fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
    }
}

object Room {
    /**
     * Takes a real Context. Typed loosely it would accept an Activity where
     * an application Context is meant — the leak this call site exists to
     * avoid — and the harness would say nothing.
     */
    fun <T : RoomDatabase> databaseBuilder(
        context: android.content.Context,
        klass: Class<T>,
        name: String?,
    ): Builder<T> = Builder()

    fun <T : RoomDatabase> inMemoryDatabaseBuilder(
        context: android.content.Context,
        klass: Class<T>,
    ): Builder<T> = Builder()

    class Builder<T : RoomDatabase> internal constructor() {
        fun addMigrations(vararg migrations: androidx.room.migration.Migration): Builder<T> = this
        fun addCallback(callback: RoomDatabase.Callback): Builder<T> = this
        fun fallbackToDestructiveMigration(): Builder<T> = this
        fun fallbackToDestructiveMigrationFrom(vararg startVersions: Int): Builder<T> = this
        fun fallbackToDestructiveMigrationOnDowngrade(): Builder<T> = this
        fun allowMainThreadQueries(): Builder<T> = this
        fun createFromAsset(databaseFilePath: String): Builder<T> = this
        fun setJournalMode(mode: Any): Builder<T> = this
        fun build(): T = throw UnsupportedOperationException("stub")
    }
}
