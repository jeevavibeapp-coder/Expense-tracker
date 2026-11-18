package androidx.compose.foundation.selection

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

fun Modifier.selectable(
    selected: Boolean,
    enabled: Boolean = true,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = this

fun Modifier.selectableGroup(): Modifier = this

fun Modifier.toggleable(
    value: Boolean,
    enabled: Boolean = true,
    role: Role? = null,
    onValueChange: (Boolean) -> Unit,
): Modifier = this

fun Modifier.triStateToggleable(
    state: Any,
    enabled: Boolean = true,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = this
