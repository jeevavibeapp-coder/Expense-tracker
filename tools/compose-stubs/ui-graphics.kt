package androidx.compose.ui.graphics

import androidx.compose.ui.geometry.Offset

/**
 * Real Color is a value class over a packed ULong, constructed through
 * top-level functions rather than a constructor. Keeping the functions
 * (rather than a public constructor) is what makes `Color(0xFF7C5CFF)` and
 * `Color(r, g, b)` both resolve here exactly as they do on device.
 */
class Color internal constructor(val value: ULong) {
    companion object {
        internal fun of(v: Long) = Color(v.toULong())
        val Black = of(0xFF000000)
        val DarkGray = of(0xFF444444)
        val Gray = of(0xFF888888)
        val LightGray = of(0xFFCCCCCC)
        val White = of(0xFFFFFFFF)
        val Red = of(0xFFFF0000)
        val Green = of(0xFF00FF00)
        val Blue = of(0xFF0000FF)
        val Yellow = of(0xFFFFFF00)
        val Cyan = of(0xFF00FFFF)
        val Magenta = of(0xFFFF00FF)
        val Transparent = of(0x00000000)
        val Unspecified = of(0L)
    }

    val red: Float get() = 0f
    val green: Float get() = 0f
    val blue: Float get() = 0f
    val alpha: Float get() = 1f

    fun copy(
        alpha: Float = this.alpha,
        red: Float = this.red,
        green: Float = this.green,
        blue: Float = this.blue,
    ): Color = Color(value)
}

fun Color(color: Long): Color = Color(color.toULong())
fun Color(color: Int): Color = Color.of(color.toLong())
fun Color(red: Float, green: Float, blue: Float, alpha: Float = 1f): Color = Color.of(0)
fun Color(red: Int, green: Int, blue: Int, alpha: Int = 0xFF): Color = Color.of(0)

interface Shape

object RectangleShape : Shape

abstract class Brush {
    companion object {
        fun linearGradient(
            colors: List<Color>,
            start: Offset = Offset.Zero,
            end: Offset = Offset.Infinite,
            tileMode: TileMode = TileMode.Clamp,
        ): Brush = object : Brush() {}

        fun linearGradient(
            vararg colorStops: Pair<Float, Color>,
            start: Offset = Offset.Zero,
            end: Offset = Offset.Infinite,
            tileMode: TileMode = TileMode.Clamp,
        ): Brush = object : Brush() {}

        fun verticalGradient(
            colors: List<Color>,
            startY: Float = 0f,
            endY: Float = Float.POSITIVE_INFINITY,
            tileMode: TileMode = TileMode.Clamp,
        ): Brush = object : Brush() {}

        fun horizontalGradient(
            colors: List<Color>,
            startX: Float = 0f,
            endX: Float = Float.POSITIVE_INFINITY,
            tileMode: TileMode = TileMode.Clamp,
        ): Brush = object : Brush() {}

        fun radialGradient(
            colors: List<Color>,
            center: Offset = Offset.Unspecified,
            radius: Float = Float.POSITIVE_INFINITY,
            tileMode: TileMode = TileMode.Clamp,
        ): Brush = object : Brush() {}

        fun sweepGradient(colors: List<Color>, center: Offset = Offset.Unspecified): Brush =
            object : Brush() {}
    }
}

class SolidColor(val value: Color) : Brush()

class TileMode private constructor(private val v: Int) {
    companion object {
        val Clamp = TileMode(0); val Repeated = TileMode(1)
        val Mirror = TileMode(2); val Decal = TileMode(3)
    }
}

class StrokeCap private constructor(private val v: Int) {
    companion object { val Butt = StrokeCap(0); val Round = StrokeCap(1); val Square = StrokeCap(2) }
}

class StrokeJoin private constructor(private val v: Int) {
    companion object { val Miter = StrokeJoin(0); val Round = StrokeJoin(1); val Bevel = StrokeJoin(2) }
}

class BlendMode private constructor(private val v: Int) {
    companion object { val SrcOver = BlendMode(0); val Clear = BlendMode(1) }
}

class ColorFilter
class PathEffect
interface Path
