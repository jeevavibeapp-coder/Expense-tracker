package androidx.compose.foundation.text

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/**
 * Foundation 1.7 renamed `autoCorrect` to `autoCorrectEnabled` (nullable) and
 * moved every default to the `Unspecified` sentinels. Code written against
 * the 1.6 shape must fail here, which is the point of keeping this exact.
 */
class KeyboardOptions(
    val capitalization: KeyboardCapitalization = KeyboardCapitalization.Unspecified,
    val autoCorrectEnabled: Boolean? = null,
    val keyboardType: KeyboardType = KeyboardType.Unspecified,
    val imeAction: ImeAction = ImeAction.Unspecified,
    val platformImeOptions: Any? = null,
    val showKeyboardOnFocus: Boolean? = null,
    val hintLocales: Any? = null,
) {
    companion object { val Default = KeyboardOptions() }
}

class KeyboardActionScope internal constructor() {
    fun defaultKeyboardAction(imeAction: ImeAction) {}
}

class KeyboardActions(
    val onDone: (KeyboardActionScope.() -> Unit)? = null,
    val onGo: (KeyboardActionScope.() -> Unit)? = null,
    val onNext: (KeyboardActionScope.() -> Unit)? = null,
    val onPrevious: (KeyboardActionScope.() -> Unit)? = null,
    val onSearch: (KeyboardActionScope.() -> Unit)? = null,
    val onSend: (KeyboardActionScope.() -> Unit)? = null,
) {
    companion object { val Default = KeyboardActions() }
}
