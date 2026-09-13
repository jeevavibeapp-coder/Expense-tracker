package androidx.core.content

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

object ContextCompat {
    fun checkSelfPermission(context: Context, permission: String): Int =
        android.content.pm.PackageManager.PERMISSION_DENIED
    fun startActivity(context: Context, intent: Intent, options: android.os.Bundle?) {}
    fun getColor(context: Context, id: Int): Int = 0
}

object FileProvider {
    fun getUriForFile(context: Context, authority: String, file: File): Uri =
        Uri.fromFile(file)
}
