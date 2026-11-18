package androidx.compose.ui.text.style

/**
 * TextAlign and TextDirection became non-nullable with an `Unspecified`
 * sentinel in Compose 1.7 (`TextStyle(textAlign = null)` no longer compiles).
 * Modelling them as nullable would hide exactly that migration mistake.
 */
class TextAlign private constructor(private val v: Int) {
    companion object {
        val Left = TextAlign(1); val Right = TextAlign(2); val Center = TextAlign(3)
        val Justify = TextAlign(4); val Start = TextAlign(5); val End = TextAlign(6)
        val Unspecified = TextAlign(Int.MIN_VALUE)
    }
}

class TextDirection private constructor(private val v: Int) {
    companion object {
        val Ltr = TextDirection(1); val Rtl = TextDirection(2)
        val Content = TextDirection(3); val ContentOrLtr = TextDirection(4)
        val ContentOrRtl = TextDirection(5); val Unspecified = TextDirection(Int.MIN_VALUE)
    }
}

class TextOverflow private constructor(private val v: Int) {
    companion object {
        val Clip = TextOverflow(1); val Ellipsis = TextOverflow(2)
        val Visible = TextOverflow(3)
    }
}

class TextDecoration private constructor(val mask: Int) {
    companion object {
        val None = TextDecoration(0)
        val Underline = TextDecoration(1)
        val LineThrough = TextDecoration(2)
    }
}

class BaselineShift(val multiplier: Float) {
    companion object {
        val Superscript = BaselineShift(0.5f)
        val Subscript = BaselineShift(-0.5f)
        val None = BaselineShift(0f)
    }
}

class TextGeometricTransform(val scaleX: Float = 1f, val skewX: Float = 0f)
class TextIndent(val firstLine: Any? = null, val restLine: Any? = null)
