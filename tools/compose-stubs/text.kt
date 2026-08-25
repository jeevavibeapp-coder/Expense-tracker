package androidx.compose.ui.text

import androidx.compose.ui.unit.TextUnit

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign

class TextStyle(
    val fontSize: TextUnit = TextUnit.Unspecified,
    val fontWeight: FontWeight? = null,
    val letterSpacing: TextUnit = TextUnit.Unspecified,
    val lineHeight: TextUnit = TextUnit.Unspecified,
    val textAlign: TextAlign? = null,
    val color: androidx.compose.ui.graphics.Color? = null,
) {
    fun copy(
        fontSize: TextUnit = this.fontSize,
        fontWeight: FontWeight? = this.fontWeight,
        letterSpacing: TextUnit = this.letterSpacing,
        lineHeight: TextUnit = this.lineHeight,
        textAlign: TextAlign? = this.textAlign,
        color: androidx.compose.ui.graphics.Color? = this.color,
    ) = TextStyle(fontSize, fontWeight, letterSpacing, lineHeight, textAlign, color)
}
