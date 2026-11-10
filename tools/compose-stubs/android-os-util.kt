package android.os

class Bundle {
    fun get(key: String): Any? = null
    fun getString(key: String): String? = null
    fun getString(key: String, defaultValue: String): String = defaultValue
    fun getInt(key: String, defaultValue: Int = 0): Int = defaultValue
    fun getLong(key: String, defaultValue: Long = 0L): Long = defaultValue
    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean = defaultValue
    fun putString(key: String, value: String?) {}
    fun putInt(key: String, value: Int) {}
    val isEmpty: Boolean get() = true
}

object Build {
    object VERSION { @JvmField val SDK_INT: Int = 34 }
    object VERSION_CODES {
        const val M = 23; const val O = 26; const val Q = 29
        const val S = 31; const val TIRAMISU = 33; const val UPSIDE_DOWN_CAKE = 34
    }
}
