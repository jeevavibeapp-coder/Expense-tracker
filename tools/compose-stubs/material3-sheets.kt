package androidx.compose.material3

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@ExperimentalMaterial3Api
enum class SheetValue { Hidden, Expanded, PartiallyExpanded }

@ExperimentalMaterial3Api
class SheetState internal constructor(
    val skipPartiallyExpanded: Boolean,
    val currentValue: SheetValue = SheetValue.Hidden,
) {
    val isVisible: Boolean get() = currentValue != SheetValue.Hidden
    val targetValue: SheetValue get() = currentValue
    suspend fun show() {}
    suspend fun hide() {}
    suspend fun expand() {}
    suspend fun partialExpand() {}
}

@ExperimentalMaterial3Api
object BottomSheetDefaults {
    val SheetMaxWidth: Dp = 640.dp
    val Elevation: Dp = 1.dp
    val ExpandedShape: Shape @Composable get() = RoundedCornerShape(28.dp)
    val ContainerColor: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerLow
    val ScrimColor: Color @Composable get() = Color.Black.copy(alpha = 0.32f)
    val windowInsets: WindowInsets @Composable get() = object : WindowInsets {}

    @Composable fun DragHandle() {}
}

@ExperimentalMaterial3Api
class ModalBottomSheetProperties internal constructor()

@ExperimentalMaterial3Api
object ModalBottomSheetDefaults {
    val properties: ModalBottomSheetProperties = ModalBottomSheetProperties()
}

@ExperimentalMaterial3Api
@Composable
fun rememberModalBottomSheetState(
    skipPartiallyExpanded: Boolean = false,
    confirmValueChange: (SheetValue) -> Boolean = { true },
): SheetState = SheetState(skipPartiallyExpanded)

@ExperimentalMaterial3Api
@Composable
fun ModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    properties: ModalBottomSheetProperties = ModalBottomSheetDefaults.properties,
    content: @Composable ColumnScope.() -> Unit,
) { androidx.compose.foundation.layout.ColumnScopeImpl.content() }
