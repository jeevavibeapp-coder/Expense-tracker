package androidx.compose.material3

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle

@RequiresOptIn("This material3 API is experimental and is likely to change or be removed.")
annotation class ExperimentalMaterial3Api

/**
 * The full M3 role set. A screen that reaches for a role this scheme does not
 * carry — `surfaceContainerHigh`, say — has to fail here, or the harness
 * would let a typo through as an unresolved reference nobody sees until
 * Gradle runs.
 */
class ColorScheme(
    val primary: Color = Color.Unspecified,
    val onPrimary: Color = Color.Unspecified,
    val primaryContainer: Color = Color.Unspecified,
    val onPrimaryContainer: Color = Color.Unspecified,
    val inversePrimary: Color = Color.Unspecified,
    val secondary: Color = Color.Unspecified,
    val onSecondary: Color = Color.Unspecified,
    val secondaryContainer: Color = Color.Unspecified,
    val onSecondaryContainer: Color = Color.Unspecified,
    val tertiary: Color = Color.Unspecified,
    val onTertiary: Color = Color.Unspecified,
    val tertiaryContainer: Color = Color.Unspecified,
    val onTertiaryContainer: Color = Color.Unspecified,
    val background: Color = Color.Unspecified,
    val onBackground: Color = Color.Unspecified,
    val surface: Color = Color.Unspecified,
    val onSurface: Color = Color.Unspecified,
    val surfaceVariant: Color = Color.Unspecified,
    val onSurfaceVariant: Color = Color.Unspecified,
    val surfaceTint: Color = Color.Unspecified,
    val inverseSurface: Color = Color.Unspecified,
    val inverseOnSurface: Color = Color.Unspecified,
    val error: Color = Color.Unspecified,
    val onError: Color = Color.Unspecified,
    val errorContainer: Color = Color.Unspecified,
    val onErrorContainer: Color = Color.Unspecified,
    val outline: Color = Color.Unspecified,
    val outlineVariant: Color = Color.Unspecified,
    val scrim: Color = Color.Unspecified,
    val surfaceBright: Color = Color.Unspecified,
    val surfaceDim: Color = Color.Unspecified,
    val surfaceContainer: Color = Color.Unspecified,
    val surfaceContainerHigh: Color = Color.Unspecified,
    val surfaceContainerHighest: Color = Color.Unspecified,
    val surfaceContainerLow: Color = Color.Unspecified,
    val surfaceContainerLowest: Color = Color.Unspecified,
)

fun lightColorScheme(
    primary: Color = Color.Unspecified,
    onPrimary: Color = Color.Unspecified,
    primaryContainer: Color = Color.Unspecified,
    onPrimaryContainer: Color = Color.Unspecified,
    inversePrimary: Color = Color.Unspecified,
    secondary: Color = Color.Unspecified,
    onSecondary: Color = Color.Unspecified,
    secondaryContainer: Color = Color.Unspecified,
    onSecondaryContainer: Color = Color.Unspecified,
    tertiary: Color = Color.Unspecified,
    onTertiary: Color = Color.Unspecified,
    tertiaryContainer: Color = Color.Unspecified,
    onTertiaryContainer: Color = Color.Unspecified,
    background: Color = Color.Unspecified,
    onBackground: Color = Color.Unspecified,
    surface: Color = Color.Unspecified,
    onSurface: Color = Color.Unspecified,
    surfaceVariant: Color = Color.Unspecified,
    onSurfaceVariant: Color = Color.Unspecified,
    surfaceTint: Color = Color.Unspecified,
    inverseSurface: Color = Color.Unspecified,
    inverseOnSurface: Color = Color.Unspecified,
    error: Color = Color.Unspecified,
    onError: Color = Color.Unspecified,
    errorContainer: Color = Color.Unspecified,
    onErrorContainer: Color = Color.Unspecified,
    outline: Color = Color.Unspecified,
    outlineVariant: Color = Color.Unspecified,
    scrim: Color = Color.Unspecified,
    surfaceBright: Color = Color.Unspecified,
    surfaceDim: Color = Color.Unspecified,
    surfaceContainer: Color = Color.Unspecified,
    surfaceContainerHigh: Color = Color.Unspecified,
    surfaceContainerHighest: Color = Color.Unspecified,
    surfaceContainerLow: Color = Color.Unspecified,
    surfaceContainerLowest: Color = Color.Unspecified,
): ColorScheme = ColorScheme(
    primary, onPrimary, primaryContainer, onPrimaryContainer, inversePrimary,
    secondary, onSecondary, secondaryContainer, onSecondaryContainer,
    tertiary, onTertiary, tertiaryContainer, onTertiaryContainer,
    background, onBackground, surface, onSurface, surfaceVariant, onSurfaceVariant,
    surfaceTint, inverseSurface, inverseOnSurface, error, onError,
    errorContainer, onErrorContainer, outline, outlineVariant, scrim,
    surfaceBright, surfaceDim, surfaceContainer, surfaceContainerHigh,
    surfaceContainerHighest, surfaceContainerLow, surfaceContainerLowest,
)

fun darkColorScheme(
    primary: Color = Color.Unspecified,
    onPrimary: Color = Color.Unspecified,
    primaryContainer: Color = Color.Unspecified,
    onPrimaryContainer: Color = Color.Unspecified,
    inversePrimary: Color = Color.Unspecified,
    secondary: Color = Color.Unspecified,
    onSecondary: Color = Color.Unspecified,
    secondaryContainer: Color = Color.Unspecified,
    onSecondaryContainer: Color = Color.Unspecified,
    tertiary: Color = Color.Unspecified,
    onTertiary: Color = Color.Unspecified,
    tertiaryContainer: Color = Color.Unspecified,
    onTertiaryContainer: Color = Color.Unspecified,
    background: Color = Color.Unspecified,
    onBackground: Color = Color.Unspecified,
    surface: Color = Color.Unspecified,
    onSurface: Color = Color.Unspecified,
    surfaceVariant: Color = Color.Unspecified,
    onSurfaceVariant: Color = Color.Unspecified,
    surfaceTint: Color = Color.Unspecified,
    inverseSurface: Color = Color.Unspecified,
    inverseOnSurface: Color = Color.Unspecified,
    error: Color = Color.Unspecified,
    onError: Color = Color.Unspecified,
    errorContainer: Color = Color.Unspecified,
    onErrorContainer: Color = Color.Unspecified,
    outline: Color = Color.Unspecified,
    outlineVariant: Color = Color.Unspecified,
    scrim: Color = Color.Unspecified,
    surfaceBright: Color = Color.Unspecified,
    surfaceDim: Color = Color.Unspecified,
    surfaceContainer: Color = Color.Unspecified,
    surfaceContainerHigh: Color = Color.Unspecified,
    surfaceContainerHighest: Color = Color.Unspecified,
    surfaceContainerLow: Color = Color.Unspecified,
    surfaceContainerLowest: Color = Color.Unspecified,
): ColorScheme = ColorScheme(
    primary, onPrimary, primaryContainer, onPrimaryContainer, inversePrimary,
    secondary, onSecondary, secondaryContainer, onSecondaryContainer,
    tertiary, onTertiary, tertiaryContainer, onTertiaryContainer,
    background, onBackground, surface, onSurface, surfaceVariant, onSurfaceVariant,
    surfaceTint, inverseSurface, inverseOnSurface, error, onError,
    errorContainer, onErrorContainer, outline, outlineVariant, scrim,
    surfaceBright, surfaceDim, surfaceContainer, surfaceContainerHigh,
    surfaceContainerHighest, surfaceContainerLow, surfaceContainerLowest,
)

class Typography(
    val displayLarge: TextStyle = TextStyle.Default,
    val displayMedium: TextStyle = TextStyle.Default,
    val displaySmall: TextStyle = TextStyle.Default,
    val headlineLarge: TextStyle = TextStyle.Default,
    val headlineMedium: TextStyle = TextStyle.Default,
    val headlineSmall: TextStyle = TextStyle.Default,
    val titleLarge: TextStyle = TextStyle.Default,
    val titleMedium: TextStyle = TextStyle.Default,
    val titleSmall: TextStyle = TextStyle.Default,
    val bodyLarge: TextStyle = TextStyle.Default,
    val bodyMedium: TextStyle = TextStyle.Default,
    val bodySmall: TextStyle = TextStyle.Default,
    val labelLarge: TextStyle = TextStyle.Default,
    val labelMedium: TextStyle = TextStyle.Default,
    val labelSmall: TextStyle = TextStyle.Default,
)

class Shapes(
    val extraSmall: Shape = RoundedCornerShape(4),
    val small: Shape = RoundedCornerShape(8),
    val medium: Shape = RoundedCornerShape(12),
    val large: Shape = RoundedCornerShape(16),
    val extraLarge: Shape = RoundedCornerShape(28),
)

object MaterialTheme {
    val colorScheme: ColorScheme @Composable @ReadOnlyComposable get() = ColorScheme()
    val typography: Typography @Composable @ReadOnlyComposable get() = Typography()
    val shapes: Shapes @Composable @ReadOnlyComposable get() = Shapes()
}

@Composable
fun MaterialTheme(
    colorScheme: ColorScheme = MaterialTheme.colorScheme,
    shapes: Shapes = MaterialTheme.shapes,
    typography: Typography = MaterialTheme.typography,
    content: @Composable () -> Unit,
) { content() }

@Composable
@ReadOnlyComposable
fun contentColorFor(backgroundColor: Color): Color = Color.Unspecified

object LocalContentColor {
    val current: Color @Composable @ReadOnlyComposable get() = Color.Unspecified
}

object LocalTextStyle {
    val current: TextStyle @Composable @ReadOnlyComposable get() = TextStyle.Default
}
