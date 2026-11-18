package androidx.compose.ui.focus

import androidx.compose.ui.Modifier

class FocusRequester {
    companion object { val Default = FocusRequester() }
    fun requestFocus() {}
    fun freeFocus(): Boolean = false
    fun captureFocus(): Boolean = false
}

fun Modifier.focusRequester(focusRequester: FocusRequester): Modifier = this
fun Modifier.focusable(enabled: Boolean = true, interactionSource: Any? = null): Modifier = this
fun Modifier.onFocusChanged(onFocusChanged: (FocusState) -> Unit): Modifier = this

interface FocusState {
    val isFocused: Boolean
    val hasFocus: Boolean
    val isCaptured: Boolean
}
