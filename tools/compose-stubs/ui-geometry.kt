package androidx.compose.ui.geometry

class Offset(val x: Float, val y: Float) {
    companion object {
        val Zero = Offset(0f, 0f)
        val Infinite = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        val Unspecified = Offset(Float.NaN, Float.NaN)
    }
    operator fun plus(other: Offset) = Offset(x + other.x, y + other.y)
    operator fun minus(other: Offset) = Offset(x - other.x, y - other.y)
    operator fun times(operand: Float) = Offset(x * operand, y * operand)
    operator fun div(operand: Float) = Offset(x / operand, y / operand)
    fun copy(x: Float = this.x, y: Float = this.y) = Offset(x, y)
}

class Size(val width: Float, val height: Float) {
    companion object {
        val Zero = Size(0f, 0f)
        val Unspecified = Size(Float.NaN, Float.NaN)
    }
    val minDimension: Float get() = minOf(width, height)
    val maxDimension: Float get() = maxOf(width, height)
    val center: Offset get() = Offset(width / 2f, height / 2f)
    fun copy(width: Float = this.width, height: Float = this.height) = Size(width, height)
}

class CornerRadius(val x: Float, val y: Float = x) {
    companion object { val Zero = CornerRadius(0f, 0f) }
}

class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object { val Zero = Rect(0f, 0f, 0f, 0f) }
}
