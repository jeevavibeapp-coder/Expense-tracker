package com.jeevavibeapp.spendwise.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The design system, carried over from the web build.
 *
 * The rule that produced it: premium in this category is restraint plus
 * hierarchy, not decoration. One thing per screen is loud — the balance —
 * and everything else is whispered until you look at it directly. Money is
 * set large with tight tracking; labels shrink to uppercase micro-type with
 * wide tracking. The contrast between those two does most of the work.
 */

private val Ink = Color(0xFF07070B)
private val Surface1 = Color(0xFF101017)
private val Surface2 = Color(0xFF17171F)
private val Surface3 = Color(0xFF1F1F29)
private val TextHi = Color(0xFFF6F6F9)
private val TextMid = Color(0xFF9A9AAB)
private val TextLow = Color(0xFF63636F)

private val LightBg = Color(0xFFF4F4F7)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurface2 = Color(0xFFF7F7FA)
private val LightTextHi = Color(0xFF0D0D14)
private val LightTextMid = Color(0xFF5F5F70)
private val LightTextLow = Color(0xFF8B8B9C)

val Primary = Color(0xFF7C5CFF)
val PrimaryBright = Color(0xFF9B86FF)
val Income = Color(0xFF36D39A)
val Expense = Color(0xFFFF6B81)
val Warn = Color(0xFFFBBF24)

val BrandGradient = Brush.linearGradient(listOf(Color(0xFF8B5CFF), Color(0xFF5B8CFF)))

private val DarkScheme = darkColorScheme(
    primary = Primary, onPrimary = Color.White,
    secondary = PrimaryBright, background = Ink, onBackground = TextHi,
    surface = Surface1, onSurface = TextHi,
    surfaceVariant = Surface2, onSurfaceVariant = TextMid,
    outline = Color(0x14FFFFFF), error = Expense,
)

private val LightScheme = lightColorScheme(
    primary = Primary, onPrimary = Color.White,
    secondary = PrimaryBright, background = LightBg, onBackground = LightTextHi,
    surface = LightSurface, onSurface = LightTextHi,
    surfaceVariant = LightSurface2, onSurfaceVariant = LightTextMid,
    outline = Color(0x120C0C14), error = Expense,
)

/** Money is always tabular — `tnum`, not a promise in a comment. Roboto's
 *  default figures are proportional, so a column of amounts whose digits do
 *  not line up is the fastest way for an app to look unfinished, and the
 *  ledger is a column of amounts. */
private const val TABULAR = "tnum"

val MoneyLarge = TextStyle(
    fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.4).sp,
    fontFeatureSettings = TABULAR)
val MoneyMedium = TextStyle(
    fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp,
    fontFeatureSettings = TABULAR)
val MoneyRow = TextStyle(
    fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.15).sp,
    fontFeatureSettings = TABULAR)

/** Uppercase micro-type with wide tracking. This is what turns a small label
 *  into a considered one, and it is half the reason the screens read as
 *  organised rather than as more content. */
val MicroLabel = TextStyle(
    fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.3.sp)

val SectionHeading = MicroLabel.copy(textAlign = TextAlign.Start)

object Tokens {
    val screenPadding = 18.dp
    val cardRadius = 26.dp
    val rowRadius = 13.dp
    /** Material and WCAG 2.5.5 both put the floor here, and every control in
     *  the app is held to it. */
    val minTouchTarget = 48.dp
    val gutter = 16.dp
}

@Composable
fun SpendWiseTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = Typography(
            headlineLarge = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.6).sp),
            titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            bodyMedium = TextStyle(fontSize = 14.sp),
            bodySmall = TextStyle(fontSize = 12.sp),
            labelSmall = MicroLabel,
        ),
        content = content,
    )
}
