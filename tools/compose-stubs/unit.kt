package androidx.compose.ui.unit

class Dp(val value: Float) {
    companion object { val Unspecified = Dp(Float.NaN) }
}
val Int.dp: Dp get() = Dp(this.toFloat())
val Double.dp: Dp get() = Dp(this.toFloat())
val Float.dp: Dp get() = Dp(this)

class TextUnit(val value: Float) {
    companion object { val Unspecified = TextUnit(Float.NaN) }
}
val Int.sp: TextUnit get() = TextUnit(this.toFloat())
val Double.sp: TextUnit get() = TextUnit(this.toFloat())
