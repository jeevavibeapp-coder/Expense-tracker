package androidx.lifecycle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class Lifecycle {
    enum class State { DESTROYED, INITIALIZED, CREATED, STARTED, RESUMED }
    val currentState: State = State.INITIALIZED
}

interface LifecycleOwner { val lifecycle: Lifecycle }

/** Cancelled when the owner is destroyed, which is the whole reason to use it
 *  instead of a bare CoroutineScope. */
val LifecycleOwner.lifecycleScope: CoroutineScope get() = CoroutineScope(Dispatchers.Main)

open class ViewModel {
    protected open fun onCleared() {}
}

val ViewModel.viewModelScope: CoroutineScope get() = CoroutineScope(Dispatchers.Main)
