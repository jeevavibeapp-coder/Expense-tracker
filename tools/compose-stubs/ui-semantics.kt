package androidx.compose.ui.semantics

import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString

class Role private constructor(private val v: Int) {
    companion object {
        val Button = Role(0)
        val Checkbox = Role(1)
        val Switch = Role(2)
        val RadioButton = Role(3)
        val Tab = Role(4)
        val Image = Role(5)
        val DropdownList = Role(6)
    }
}

interface SemanticsPropertyReceiver

// Semantics properties are `var`s on the receiver, and the accessibility
// actions are functions taking (label, action) with the action returning
// Boolean — TalkBack uses the return to decide whether it was handled.
var SemanticsPropertyReceiver.contentDescription: String
    get() = throw UnsupportedOperationException("write-only semantics property")
    set(_) {}

var SemanticsPropertyReceiver.stateDescription: String
    get() = throw UnsupportedOperationException("write-only semantics property")
    set(_) {}

var SemanticsPropertyReceiver.testTag: String
    get() = throw UnsupportedOperationException("write-only semantics property")
    set(_) {}

var SemanticsPropertyReceiver.text: AnnotatedString
    get() = throw UnsupportedOperationException("write-only semantics property")
    set(_) {}

var SemanticsPropertyReceiver.selected: Boolean
    get() = throw UnsupportedOperationException("write-only semantics property")
    set(_) {}

var SemanticsPropertyReceiver.role: Role
    get() = throw UnsupportedOperationException("write-only semantics property")
    set(_) {}

fun SemanticsPropertyReceiver.disabled() {}
fun SemanticsPropertyReceiver.heading() {}

fun SemanticsPropertyReceiver.expand(label: String? = null, action: (() -> Boolean)? = null) {}
fun SemanticsPropertyReceiver.collapse(label: String? = null, action: (() -> Boolean)? = null) {}
fun SemanticsPropertyReceiver.onClick(label: String? = null, action: (() -> Boolean)? = null) {}
fun SemanticsPropertyReceiver.dismiss(label: String? = null, action: (() -> Boolean)? = null) {}

internal object SemanticsReceiverImpl : SemanticsPropertyReceiver

/** The properties lambda is invoked so its body is type-checked. */
fun Modifier.semantics(
    mergeDescendants: Boolean = false,
    properties: SemanticsPropertyReceiver.() -> Unit,
): Modifier {
    SemanticsReceiverImpl.properties()
    return this
}

fun Modifier.clearAndSetSemantics(
    properties: SemanticsPropertyReceiver.() -> Unit,
): Modifier {
    SemanticsReceiverImpl.properties()
    return this
}
