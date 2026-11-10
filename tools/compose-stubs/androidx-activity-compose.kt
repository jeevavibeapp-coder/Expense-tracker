package androidx.activity.compose

import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable

fun ComponentActivity.setContent(content: @Composable () -> Unit) {}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {}

/** The composable-side registration. Unlike the activity method it may be
 *  called during composition, and its callback parameter is named `onResult`. */
@Composable
fun <I, O> rememberLauncherForActivityResult(
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit,
): ActivityResultLauncher<I> = ActivityResultLauncher()
