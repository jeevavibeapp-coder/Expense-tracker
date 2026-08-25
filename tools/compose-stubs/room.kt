// Room annotation stubs. Annotations carry no behaviour, so stubbing them is
// exact rather than approximate: the compiler checks the same entity and DAO
// declarations it would with the real artifact. Room's generated code is not
// modelled — that is KSP's job at build time, and it cannot fail in a way
// these declarations would hide.
package androidx.room

@Target(AnnotationTarget.CLASS) annotation class Entity(
    val tableName: String = "", val indices: Array<Index> = [],
    val primaryKeys: Array<String> = [], val foreignKeys: Array<ForeignKey> = [])
@Target(AnnotationTarget.CLASS) annotation class Index(
    val value: Array<String> = [], val unique: Boolean = false, val name: String = "")
@Target(AnnotationTarget.CLASS) annotation class ForeignKey(
    val entity: kotlin.reflect.KClass<*>, val parentColumns: Array<String>,
    val childColumns: Array<String>, val onDelete: Int = 1)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class PrimaryKey(val autoGenerate: Boolean = false)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class ColumnInfo(val name: String = "", val defaultValue: String = "")
@Target(AnnotationTarget.CLASS) annotation class Dao
@Target(AnnotationTarget.FUNCTION) annotation class Query(val value: String)
@Target(AnnotationTarget.FUNCTION) annotation class Insert(val onConflict: Int = 1)
@Target(AnnotationTarget.FUNCTION) annotation class Update
@Target(AnnotationTarget.FUNCTION) annotation class Delete
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS) annotation class Transaction
@Target(AnnotationTarget.CLASS) annotation class Database(
    val entities: Array<kotlin.reflect.KClass<*>>, val version: Int,
    val exportSchema: Boolean = true)
@Target(AnnotationTarget.CLASS) annotation class TypeConverters(
    val value: Array<kotlin.reflect.KClass<*>> = [])
@Target(AnnotationTarget.FUNCTION) annotation class TypeConverter
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD) annotation class Embedded
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD) annotation class Ignore

object OnConflictStrategy { const val REPLACE = 1; const val IGNORE = 5; const val ABORT = 3 }
abstract class RoomDatabase { open fun clearAllTables() {} }

object Room {
    fun <T : RoomDatabase> databaseBuilder(context: Any?, klass: Class<T>, name: String):
        Builder<T> = Builder()
    class Builder<T : RoomDatabase> {
        fun addMigrations(vararg m: Any?): Builder<T> = this
        fun fallbackToDestructiveMigration(): Builder<T> = this
        fun build(): T = throw UnsupportedOperationException("stub")
    }
}
