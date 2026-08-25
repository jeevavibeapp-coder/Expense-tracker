package androidx.compose.foundation.layout

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

interface PaddingValues

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
    val Top: Vertical = object : Vertical {}
    val Start: Horizontal = object : Horizontal {}
    fun spacedBy(space: Dp): HorizontalOrVertical = object : HorizontalOrVertical {}
    val Center: HorizontalOrVertical = object : HorizontalOrVertical {}
}

interface ColumnScope {
    fun Modifier.weight(weight: Float, fill: Boolean = true): Modifier = this
    fun Modifier.align(alignment: Alignment.Horizontal): Modifier = this
}

interface RowScope {
    fun Modifier.weight(weight: Float, fill: Boolean = true): Modifier = this
    fun Modifier.align(alignment: Alignment.Vertical): Modifier = this
}

private object ColumnScopeImpl : ColumnScope
private object RowScopeImpl : RowScope

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
fun Box(modifier: Modifier = Modifier) {}

@Composable
fun Spacer(modifier: Modifier) {}

fun Modifier.fillMaxWidth(fraction: Float = 1f): Modifier = this
fun Modifier.fillMaxSize(fraction: Float = 1f): Modifier = this
fun Modifier.height(height: Dp): Modifier = this
fun Modifier.width(width: Dp): Modifier = this
fun Modifier.size(size: Dp): Modifier = this
fun Modifier.heightIn(min: Dp = Dp.Unspecified, max: Dp = Dp.Unspecified): Modifier = this
fun Modifier.padding(all: Dp): Modifier = this
fun Modifier.padding(horizontal: Dp = 0.dp, vertical: Dp = 0.dp): Modifier = this
fun Modifier.padding(
    start: Dp = 0.dp, top: Dp = 0.dp, end: Dp = 0.dp, bottom: Dp = 0.dp,
): Modifier = this
