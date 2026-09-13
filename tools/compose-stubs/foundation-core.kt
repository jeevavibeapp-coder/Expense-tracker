package androidx.compose.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.runDraw
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp

@RequiresOptIn("This foundation API is experimental and is likely to change.")
annotation class ExperimentalFoundationApi

/** Real BorderStroke holds a Brush; the Color form is a factory function, so
 *  `BorderStroke(1.dp, someColor)` resolves through it rather than through a
 *  constructor that silently accepts the wrong type. */
class BorderStroke internal constructor(val width: Dp, val brush: Brush)

fun BorderStroke(width: Dp, color: Color): BorderStroke =
    BorderStroke(width, androidx.compose.ui.graphics.SolidColor(color))

fun Modifier.background(color: Color, shape: Shape = RectangleShape): Modifier = this
fun Modifier.background(
    brush: Brush,
    shape: Shape = RectangleShape,
    alpha: Float = 1.0f,
): Modifier = this

fun Modifier.border(width: Dp, color: Color, shape: Shape = RectangleShape): Modifier = this
fun Modifier.border(width: Dp, brush: Brush, shape: Shape): Modifier = this
fun Modifier.border(border: BorderStroke, shape: Shape = RectangleShape): Modifier = this

fun Modifier.clickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = this

fun Modifier.combinedClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = this

class ScrollState(val initial: Int = 0) {
    val value: Int = initial
    val maxValue: Int = Int.MAX_VALUE
    suspend fun scrollTo(value: Int): Float = 0f
    suspend fun animateScrollTo(value: Int) {}
}

@Composable
fun rememberScrollState(initial: Int = 0): ScrollState = remember { ScrollState(initial) }

fun Modifier.verticalScroll(
    state: ScrollState,
    enabled: Boolean = true,
    flingBehavior: Any? = null,
    reverseScrolling: Boolean = false,
): Modifier = this

fun Modifier.horizontalScroll(
    state: ScrollState,
    enabled: Boolean = true,
    flingBehavior: Any? = null,
    reverseScrolling: Boolean = false,
): Modifier = this

@Composable
@ReadOnlyComposable
fun isSystemInDarkTheme(): Boolean = true

/** The lambda is invoked so its body is genuinely type-checked; a Canvas
 *  whose stub swallowed the block would hide every error inside it. */
@Composable
fun Canvas(modifier: Modifier, onDraw: DrawScope.() -> Unit) { runDraw(onDraw) }

@Composable
fun Canvas(modifier: Modifier, contentDescription: String, onDraw: DrawScope.() -> Unit) {
    runDraw(onDraw)
}

@Composable
fun Image(
    painter: Any,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {}
