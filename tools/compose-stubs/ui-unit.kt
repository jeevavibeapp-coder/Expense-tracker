package androidx.compose.ui.unit

// Real Dp/TextUnit are inline value classes over Float/Long. Modelled as
// ordinary classes here: the arithmetic and comparisons the app performs on
// them type-check identically, and nothing in the app depends on the packing.
class Dp(val value: Float) : Comparable<Dp> {
    companion object {
        val Unspecified = Dp(Float.NaN)
        val Hairline = Dp(0f)
        val Infinity = Dp(Float.POSITIVE_INFINITY)
    }
    operator fun plus(other: Dp) = Dp(value + other.value)
    operator fun minus(other: Dp) = Dp(value - other.value)
    operator fun times(other: Float) = Dp(value * other)
    operator fun div(other: Float) = Dp(value / other)
    operator fun unaryMinus() = Dp(-value)
    override fun compareTo(other: Dp): Int = value.compareTo(other.value)
}

val Int.dp: Dp get() = Dp(this.toFloat())
val Double.dp: Dp get() = Dp(this.toFloat())
val Float.dp: Dp get() = Dp(this)

class TextUnit(val value: Float) {
    companion object { val Unspecified = TextUnit(Float.NaN) }
    operator fun times(other: Float) = TextUnit(value * other)
    operator fun unaryMinus() = TextUnit(-value)
}

val Int.sp: TextUnit get() = TextUnit(this.toFloat())
val Double.sp: TextUnit get() = TextUnit(this.toFloat())
val Float.sp: TextUnit get() = TextUnit(this)

val Int.em: TextUnit get() = TextUnit(this.toFloat())

class IntSize(val width: Int, val height: Int) {
    companion object { val Zero = IntSize(0, 0) }
}

class IntOffset(val x: Int, val y: Int) {
    companion object { val Zero = IntOffset(0, 0) }
}

/** `Dp.toPx()` lives on Density, which is why DrawScope can call it and an
 *  ordinary composable cannot. Keeping that split is what makes a `toPx()`
 *  written outside a draw scope fail here the way it does on device. */
interface Density {
    val density: Float get() = 1f
    val fontScale: Float get() = 1f
    fun Dp.toPx(): Float = value * density
    fun Dp.roundToPx(): Int = toPx().toInt()
    fun Float.toDp(): Dp = Dp(this / density)
    fun Int.toDp(): Dp = Dp(this / density)
    fun TextUnit.toPx(): Float = value * density * fontScale
    fun TextUnit.toDp(): Dp = Dp(value * fontScale)
}

class LayoutDirection private constructor(private val v: Int) {
    companion object { val Ltr = LayoutDirection(0); val Rtl = LayoutDirection(1) }
}
