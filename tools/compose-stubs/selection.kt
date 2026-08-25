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
