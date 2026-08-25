package androidx.compose.ui.semantics

import androidx.compose.ui.Modifier

class Role private constructor(val value: Int) {
    companion object {
        val Button = Role(0)
        val Checkbox = Role(1)
        val RadioButton = Role(3)
    }
}

interface SemanticsPropertyReceiver

var SemanticsPropertyReceiver.stateDescription: String
    get() = ""
    set(_) {}

fun SemanticsPropertyReceiver.expand(label: String? = null, action: (() -> Boolean)? = null) {}
fun SemanticsPropertyReceiver.collapse(label: String? = null, action: (() -> Boolean)? = null) {}

fun Modifier.semantics(
    mergeDescendants: Boolean = false,
    properties: SemanticsPropertyReceiver.() -> Unit,
): Modifier = this
