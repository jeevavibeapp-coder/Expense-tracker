package androidx.lifecycle.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

object LocalLifecycleOwner {
    val current: LifecycleOwner @Composable get() = throw UnsupportedOperationException("stub")
}

/**
 * Note the parameter is `initialValue` here, while the plain
 * `collectAsState` in compose-runtime calls it `initial`. Getting that wrong
 * is one of the commonest named-argument mistakes in Compose code.
 */
@Composable
fun <T> StateFlow<T>.collectAsStateWithLifecycle(
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    context: CoroutineContext = EmptyCoroutineContext,
): State<T> = mutableStateOf(this.value)

@Composable
fun <T> StateFlow<T>.collectAsStateWithLifecycle(
    lifecycle: Lifecycle,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    context: CoroutineContext = EmptyCoroutineContext,
): State<T> = mutableStateOf(this.value)

@Composable
fun <T : R, R> Flow<T>.collectAsStateWithLifecycle(
    initialValue: R,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    context: CoroutineContext = EmptyCoroutineContext,
): State<R> = mutableStateOf(initialValue)

@Composable
fun <T : R, R> Flow<T>.collectAsStateWithLifecycle(
    initialValue: R,
    lifecycle: Lifecycle,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    context: CoroutineContext = EmptyCoroutineContext,
): State<R> = mutableStateOf(initialValue)
