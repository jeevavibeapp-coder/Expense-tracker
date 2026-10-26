package androidx.compose.foundation.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LayoutScopeMarker
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

interface PaddingValues {
    interface Absolute : PaddingValues
}

fun PaddingValues(all: Dp): PaddingValues = object : PaddingValues {}
fun PaddingValues(horizontal: Dp = 0.dp, vertical: Dp = 0.dp): PaddingValues =
    object : PaddingValues {}
fun PaddingValues(
    start: Dp = 0.dp, top: Dp = 0.dp, end: Dp = 0.dp, bottom: Dp = 0.dp,
): PaddingValues = object : PaddingValues {}

object Arrangement {
    interface Horizontal
    interface Vertical
    interface HorizontalOrVertical : Horizontal, Vertical

    val Start: Horizontal = object : Horizontal {}
    val End: Horizontal = object : Horizontal {}
    val Top: Vertical = object : Vertical {}
    val Bottom: Vertical = object : Vertical {}

    val Center: HorizontalOrVertical = object : HorizontalOrVertical {}
    val SpaceBetween: HorizontalOrVertical = object : HorizontalOrVertical {}
    val SpaceEvenly: HorizontalOrVertical = object : HorizontalOrVertical {}
    val SpaceAround: HorizontalOrVertical = object : HorizontalOrVertical {}

    fun spacedBy(space: Dp): HorizontalOrVertical = object : HorizontalOrVertical {}
    fun spacedBy(space: Dp, alignment: Alignment.Horizontal): Horizontal = object : Horizontal {}
    fun spacedBy(space: Dp, alignment: Alignment.Vertical): Vertical = object : Vertical {}

    fun aligned(alignment: Alignment.Horizontal): Horizontal = object : Horizontal {}
    fun aligned(alignment: Alignment.Vertical): Vertical = object : Vertical {}

    object Absolute {
        val Left: Horizontal = object : Horizontal {}
        val Center: Horizontal = object : Horizontal {}
        val Right: Horizontal = object : Horizontal {}
        val SpaceBetween: Horizontal = object : Horizontal {}
        val SpaceEvenly: Horizontal = object : Horizontal {}
        val SpaceAround: Horizontal = object : Horizontal {}
        fun spacedBy(space: Dp): HorizontalOrVertical = object : HorizontalOrVertical {}
    }
}

@LayoutScopeMarker
interface ColumnScope {
    fun Modifier.weight(weight: Float, fill: Boolean = true): Modifier = this
    fun Modifier.align(alignment: Alignment.Horizontal): Modifier = this
    fun Modifier.alignBy(alignmentLine: Any): Modifier = this
}

@LayoutScopeMarker
interface RowScope {
    fun Modifier.weight(weight: Float, fill: Boolean = true): Modifier = this
    fun Modifier.align(alignment: Alignment.Vertical): Modifier = this
    fun Modifier.alignBy(alignmentLine: Any): Modifier = this
    fun Modifier.alignByBaseline(): Modifier = this
}

@LayoutScopeMarker
interface BoxScope {
    fun Modifier.align(alignment: Alignment): Modifier = this
    fun Modifier.matchParentSize(): Modifier = this
}

internal object ColumnScopeImpl : ColumnScope
internal object RowScopeImpl : RowScope
internal object BoxScopeImpl : BoxScope

@Composable
fun Column(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) { ColumnScopeImpl.content() }

@Composable
fun Row(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable RowScope.() -> Unit,
) { RowScopeImpl.content() }

@Composable
fun Box(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    propagateMinConstraints: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) { BoxScopeImpl.content() }

/** The content-less overload really does exist; without it `Box(Modifier...)`
 *  as a bare coloured rectangle would look like a missing trailing lambda. */
@Composable
fun Box(modifier: Modifier) {}

@Composable
fun Spacer(modifier: Modifier) {}

fun Modifier.fillMaxWidth(fraction: Float = 1f): Modifier = this
fun Modifier.fillMaxHeight(fraction: Float = 1f): Modifier = this
fun Modifier.fillMaxSize(fraction: Float = 1f): Modifier = this
fun Modifier.wrapContentWidth(
    align: Alignment.Horizontal = Alignment.CenterHorizontally,
    unbounded: Boolean = false,
): Modifier = this
fun Modifier.wrapContentHeight(
    align: Alignment.Vertical = Alignment.CenterVertically,
    unbounded: Boolean = false,
): Modifier = this
fun Modifier.wrapContentSize(
    align: Alignment = Alignment.Center,
    unbounded: Boolean = false,
): Modifier = this

fun Modifier.width(width: Dp): Modifier = this
fun Modifier.height(height: Dp): Modifier = this
fun Modifier.size(size: Dp): Modifier = this
fun Modifier.size(width: Dp, height: Dp): Modifier = this
fun Modifier.widthIn(min: Dp = Dp.Unspecified, max: Dp = Dp.Unspecified): Modifier = this
fun Modifier.heightIn(min: Dp = Dp.Unspecified, max: Dp = Dp.Unspecified): Modifier = this
fun Modifier.sizeIn(
    minWidth: Dp = Dp.Unspecified, minHeight: Dp = Dp.Unspecified,
    maxWidth: Dp = Dp.Unspecified, maxHeight: Dp = Dp.Unspecified,
): Modifier = this
fun Modifier.defaultMinSize(
    minWidth: Dp = Dp.Unspecified, minHeight: Dp = Dp.Unspecified,
): Modifier = this
fun Modifier.aspectRatio(ratio: Float, matchHeightConstraintsFirst: Boolean = false): Modifier = this

fun Modifier.padding(all: Dp): Modifier = this
fun Modifier.padding(horizontal: Dp = 0.dp, vertical: Dp = 0.dp): Modifier = this
fun Modifier.padding(
    start: Dp = 0.dp, top: Dp = 0.dp, end: Dp = 0.dp, bottom: Dp = 0.dp,
): Modifier = this
fun Modifier.padding(paddingValues: PaddingValues): Modifier = this

fun Modifier.offset(x: Dp = 0.dp, y: Dp = 0.dp): Modifier = this

interface WindowInsets
object WindowInsetsSides
fun Modifier.windowInsetsPadding(insets: WindowInsets): Modifier = this
fun Modifier.imePadding(): Modifier = this
fun Modifier.navigationBarsPadding(): Modifier = this
fun Modifier.statusBarsPadding(): Modifier = this
fun Modifier.systemBarsPadding(): Modifier = this
fun Modifier.safeDrawingPadding(): Modifier = this
fun Modifier.consumeWindowInsets(insets: WindowInsets): Modifier = this

object WindowInsetsHolder {
    val ime: WindowInsets = object : WindowInsets {}
    val navigationBars: WindowInsets = object : WindowInsets {}
    val systemBars: WindowInsets = object : WindowInsets {}
}
