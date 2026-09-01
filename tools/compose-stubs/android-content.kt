package android.content

import android.content.pm.PackageManager
import android.content.res.Resources
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

abstract class Context {
    abstract val applicationContext: Context
    abstract val contentResolver: ContentResolver
    abstract val packageManager: PackageManager
    abstract val packageName: String
    abstract val resources: Resources
    open fun getString(resId: Int): String = ""
    open fun startActivity(intent: Intent) {}
    open fun checkSelfPermission(permission: String): Int = PackageManager.PERMISSION_DENIED
    open fun getSystemService(name: String): Any? = null
    open fun getExternalFilesDir(type: String?): java.io.File? = null
    open val filesDir: java.io.File get() = java.io.File(".")
    open val cacheDir: java.io.File get() = java.io.File(".")
}

open class ContextWrapper(base: Context?) : Context() {
    override val applicationContext: Context get() = this
    override val contentResolver: ContentResolver get() = throw stub()
    override val packageManager: PackageManager get() = throw stub()
    override val packageName: String get() = ""
    override val resources: Resources get() = throw stub()
}

internal fun stub(): Nothing = UnsupportedOperationException("android stub").let { throw it }

class Intent {
    constructor()
    constructor(action: String)
    constructor(packageContext: Context, cls: Class<*>)

    var action: String? = null
    val extras: Bundle? = null
    var data: Uri? = null
    var flags: Int = 0

    fun putExtra(name: String, value: String?): Intent = this
    fun putExtra(name: String, value: Int): Intent = this
    fun putExtra(name: String, value: Boolean): Intent = this
    fun putExtra(name: String, value: Long): Intent = this
    fun getStringExtra(name: String): String? = null
    fun getIntExtra(name: String, defaultValue: Int): Int = defaultValue
    fun getBooleanExtra(name: String, defaultValue: Boolean): Boolean = defaultValue
    fun setType(type: String): Intent = this
    fun addFlags(flags: Int): Intent = this

    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_SEND = "android.intent.action.SEND"
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
        const val EXTRA_STREAM = "android.intent.extra.STREAM"
        const val EXTRA_TEXT = "android.intent.extra.TEXT"
        fun createChooser(target: Intent, title: CharSequence?): Intent = Intent()
    }
}

abstract class BroadcastReceiver {
    abstract fun onReceive(context: Context, intent: Intent)

    /** Keeps the receiver alive past onReceive for the work started in it —
     *  the returned token must be finished exactly once. */
    fun goAsync(): PendingResult = PendingResult()

    class PendingResult internal constructor() {
        fun finish() {}
        fun setResultCode(code: Int) {}
    }
}

abstract class ContentResolver {
    abstract fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor?

    abstract fun openInputStream(uri: Uri): java.io.InputStream?
    abstract fun openOutputStream(uri: Uri): java.io.OutputStream?
}

class ContentValues {
    fun put(key: String, value: String?) {}
    fun put(key: String, value: Long?) {}
}

interface SharedPreferences
