package androidx.activity

import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

open class ComponentActivity : ContextWrapper(null), LifecycleOwner {
    override val lifecycle: Lifecycle = Lifecycle()

    override val packageManager: PackageManager = PackageManager()
    override val packageName: String = ""

    open fun onCreate(savedInstanceState: Bundle?) {}
    open fun onStart() {}
    open fun onResume() {}
    open fun onPause() {}
    open fun onStop() {}
    open fun onDestroy() {}
    open fun onNewIntent(intent: Intent) {}

    fun finish() {}
    val intent: Intent get() = Intent()

    /**
     * Must be called before the activity is STARTED — i.e. as a property
     * initialiser or in onCreate — which is why it is declared on the
     * activity rather than as something a composable can reach.
     */
    fun <I, O> registerForActivityResult(
        contract: ActivityResultContract<I, O>,
        callback: (O) -> Unit,
    ): ActivityResultLauncher<I> = ActivityResultLauncher()
}

fun ComponentActivity.enableEdgeToEdge() {}
