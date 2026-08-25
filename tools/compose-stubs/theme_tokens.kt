// Mirrors the real declarations in ui/Theme.kt (read, not guessed) so the
// screen file can be compiled without the Material3 machinery Theme.kt needs.
package com.jeevavibeapp.spendwise.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

val Primary = Color(0xFF7C5CFF)
val PrimaryBright = Color(0xFF9B86FF)
val Income = Color(0xFF36D39A)
val Expense = Color(0xFFFF6B81)
val Warn = Color(0xFFFBBF24)

val MoneyLarge = TextStyle()
val MoneyMedium = TextStyle()
val MoneyRow = TextStyle()
val MicroLabel = TextStyle()
val SectionHeading = MicroLabel

object Tokens {
    val screenPadding = 18.dp
    val cardRadius = 26.dp
    val rowRadius = 13.dp
    val minTouchTarget = 48.dp
    val gutter = 16.dp
}
