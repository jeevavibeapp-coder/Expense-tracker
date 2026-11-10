package androidx.compose.material3

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

class FabPosition private constructor(private val v: Int) {
    companion object {
        val Start = FabPosition(0); val Center = FabPosition(1)
        val End = FabPosition(2); val EndOverlay = FabPosition(3)
    }
}

object ScaffoldDefaults {
    val contentWindowInsets: WindowInsets @Composable get() = object : WindowInsets {}
}

/**
 * `content` takes the PaddingValues the scaffold reserved for its bars — a
 * screen that ignores the parameter draws under the navigation bar, so the
 * signature has to force it to be named or received.
 */
@Composable
fun Scaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = contentColorFor(containerColor),
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable (PaddingValues) -> Unit,
) {
    topBar(); bottomBar(); snackbarHost(); floatingActionButton()
    content(PaddingValues(0.dp))
}

class FloatingActionButtonElevation internal constructor()

object FloatingActionButtonDefaults {
    val shape: Shape @Composable get() = RoundedCornerShape(16.dp)
    val smallShape: Shape @Composable get() = RoundedCornerShape(12.dp)
    val largeShape: Shape @Composable get() = RoundedCornerShape(28.dp)
    val extendedFabShape: Shape @Composable get() = RoundedCornerShape(16.dp)

    @Composable
    fun elevation(
        defaultElevation: Dp = 6.dp,
        pressedElevation: Dp = 6.dp,
        focusedElevation: Dp = 6.dp,
        hoveredElevation: Dp = 8.dp,
    ): FloatingActionButtonElevation = FloatingActionButtonElevation()
}

@Composable
fun FloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = FloatingActionButtonDefaults.shape,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = contentColorFor(containerColor),
    elevation: FloatingActionButtonElevation = FloatingActionButtonDefaults.elevation(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) { content() }

@Composable
fun SmallFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = FloatingActionButtonDefaults.smallShape,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = contentColorFor(containerColor),
    elevation: FloatingActionButtonElevation = FloatingActionButtonDefaults.elevation(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) { content() }

@Composable
fun ExtendedFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = FloatingActionButtonDefaults.extendedFabShape,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = contentColorFor(containerColor),
    elevation: FloatingActionButtonElevation = FloatingActionButtonDefaults.elevation(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) { androidx.compose.foundation.layout.RowScopeImpl.content() }

class NavigationBarItemColors internal constructor()

object NavigationBarDefaults {
    val Elevation: Dp = 3.dp
    val containerColor: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainer
    val windowInsets: WindowInsets @Composable get() = object : WindowInsets {}
}

object NavigationBarItemDefaults {
    @Composable
    fun colors(
        selectedIconColor: Color = Color.Unspecified,
        selectedTextColor: Color = Color.Unspecified,
        indicatorColor: Color = Color.Unspecified,
        unselectedIconColor: Color = Color.Unspecified,
        unselectedTextColor: Color = Color.Unspecified,
        disabledIconColor: Color = Color.Unspecified,
        disabledTextColor: Color = Color.Unspecified,
    ): NavigationBarItemColors = NavigationBarItemColors()
}

@Composable
fun NavigationBar(
    modifier: Modifier = Modifier,
    containerColor: Color = NavigationBarDefaults.containerColor,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = NavigationBarDefaults.Elevation,
    windowInsets: WindowInsets = NavigationBarDefaults.windowInsets,
    content: @Composable RowScope.() -> Unit,
) { androidx.compose.foundation.layout.RowScopeImpl.content() }

/** `icon` is required and `label` is optional — the reverse of what code
 *  usually assumes, and a stub that defaulted `icon` would hide that. */
@Composable
fun RowScope.NavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable (() -> Unit)? = null,
    alwaysShowLabel: Boolean = true,
    colors: NavigationBarItemColors = NavigationBarItemDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) { icon(); label?.invoke() }

class TopAppBarColors internal constructor()
class TopAppBarScrollBehavior internal constructor()

@ExperimentalMaterial3Api
object TopAppBarDefaults {
    val windowInsets: WindowInsets @Composable get() = object : WindowInsets {}

    @Composable
    fun topAppBarColors(
        containerColor: Color = Color.Unspecified,
        scrolledContainerColor: Color = Color.Unspecified,
        navigationIconContentColor: Color = Color.Unspecified,
        titleContentColor: Color = Color.Unspecified,
        actionIconContentColor: Color = Color.Unspecified,
    ): TopAppBarColors = TopAppBarColors()

    @Composable
    fun pinnedScrollBehavior(): TopAppBarScrollBehavior = TopAppBarScrollBehavior()

    @Composable
    fun enterAlwaysScrollBehavior(): TopAppBarScrollBehavior = TopAppBarScrollBehavior()
}

@ExperimentalMaterial3Api
@Composable
fun TopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    expandedHeight: Dp = 64.dp,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
    scrollBehavior: TopAppBarScrollBehavior? = null,
) { title(); navigationIcon(); androidx.compose.foundation.layout.RowScopeImpl.actions() }

@ExperimentalMaterial3Api
@Composable
fun CenterAlignedTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    expandedHeight: Dp = 64.dp,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
    scrollBehavior: TopAppBarScrollBehavior? = null,
) { title(); navigationIcon(); androidx.compose.foundation.layout.RowScopeImpl.actions() }

class SnackbarHostState {
    suspend fun showSnackbar(
        message: String,
        actionLabel: String? = null,
        withDismissAction: Boolean = false,
        duration: SnackbarDuration =
            if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Indefinite,
    ): SnackbarResult = SnackbarResult.Dismissed
}

enum class SnackbarDuration { Short, Long, Indefinite }
enum class SnackbarResult { Dismissed, ActionPerformed }
interface SnackbarData { val visuals: Any }

@Composable
fun SnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    snackbar: @Composable (SnackbarData) -> Unit = {},
) {}

@Composable
fun Snackbar(
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
    dismissAction: @Composable (() -> Unit)? = null,
    actionOnNewLine: Boolean = false,
    shape: Shape = RoundedCornerShape(4.dp),
    containerColor: Color = MaterialTheme.colorScheme.inverseSurface,
    contentColor: Color = MaterialTheme.colorScheme.inverseOnSurface,
    actionContentColor: Color = MaterialTheme.colorScheme.inversePrimary,
    dismissActionContentColor: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) { content() }
