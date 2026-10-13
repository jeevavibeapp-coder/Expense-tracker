package androidx.compose.ui.graphics.drawscope

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp

sealed class DrawStyle
object Fill : DrawStyle()
class Stroke(
    val width: Float = 0f,
    val miter: Float = DefaultMiter,
    val cap: StrokeCap = StrokeCap.Butt,
    val join: StrokeJoin = StrokeJoin.Miter,
    val pathEffect: PathEffect? = null,
) : DrawStyle() {
    companion object {
        const val HairlineWidth = 0f
        const val DefaultMiter = 4f
        val DefaultCap = StrokeCap.Butt
        val DefaultJoin = StrokeJoin.Miter
    }
}

/** DrawScope extends Density, which is the only reason `2.dp.toPx()` resolves
 *  inside a Canvas block and nowhere else. */
interface DrawScope : Density {
    val size: Size
    val center: Offset

    fun drawLine(
        color: Color,
        start: Offset,
        end: Offset,
        strokeWidth: Float = Stroke.HairlineWidth,
        cap: StrokeCap = Stroke.DefaultCap,
        pathEffect: PathEffect? = null,
        alpha: Float = 1.0f,
        colorFilter: ColorFilter? = null,
        blendMode: BlendMode = BlendMode.SrcOver,
    ) {}

    fun drawLine(
        brush: Brush,
        start: Offset,
        end: Offset,
        strokeWidth: Float = Stroke.HairlineWidth,
        cap: StrokeCap = Stroke.DefaultCap,
        pathEffect: PathEffect? = null,
        alpha: Float = 1.0f,
        colorFilter: ColorFilter? = null,
        blendMode: BlendMode = BlendMode.SrcOver,
    ) {}

    fun drawCircle(
        color: Color,
        radius: Float = size.minDimension / 2.0f,
        center: Offset = this.center,
        alpha: Float = 1.0f,
        style: DrawStyle = Fill,
        colorFilter: ColorFilter? = null,
        blendMode: BlendMode = BlendMode.SrcOver,
    ) {}

    fun drawCircle(
        brush: Brush,
        radius: Float = size.minDimension / 2.0f,
        center: Offset = this.center,
        alpha: Float = 1.0f,
        style: DrawStyle = Fill,
        colorFilter: ColorFilter? = null,
        blendMode: BlendMode = BlendMode.SrcOver,
    ) {}

    fun drawRect(
        color: Color,
        topLeft: Offset = Offset.Zero,
        size: Size = this.size,
        alpha: Float = 1.0f,
        style: DrawStyle = Fill,
        colorFilter: ColorFilter? = null,
        blendMode: BlendMode = BlendMode.SrcOver,
    ) {}

    fun drawRect(
        brush: Brush,
        topLeft: Offset = Offset.Zero,
        size: Size = this.size,
        alpha: Float = 1.0f,
        style: DrawStyle = Fill,
        colorFilter: ColorFilter? = null,
        blendMode: BlendMode = BlendMode.SrcOver,
    ) {}

    fun drawRoundRect(
        color: Color,
        topLeft: Offset = Offset.Zero,
        size: Size = this.size,
        cornerRadius: CornerRadius = CornerRadius.Zero,
        style: DrawStyle = Fill,
        alpha: Float = 1.0f,
        colorFilter: ColorFilter? = null,
        blendMode: BlendMode = BlendMode.SrcOver,
    ) {}

    fun drawArc(
        color: Color,
        startAngle: Float,
        sweepAngle: Float,
        useCenter: Boolean,
        topLeft: Offset = Offset.Zero,
        size: Size = this.size,
        alpha: Float = 1.0f,
        style: DrawStyle = Fill,
        colorFilter: ColorFilter? = null,
        blendMode: BlendMode = BlendMode.SrcOver,
    ) {}

    fun drawPath(
        path: Path,
        color: Color,
        alpha: Float = 1.0f,
        style: DrawStyle = Fill,
        colorFilter: ColorFilter? = null,
        blendMode: BlendMode = BlendMode.SrcOver,
    ) {}

    fun drawPoints(
        points: List<Offset>,
        pointMode: Int = 0,
        color: Color,
        strokeWidth: Float = Stroke.HairlineWidth,
        cap: StrokeCap = StrokeCap.Butt,
        pathEffect: PathEffect? = null,
        alpha: Float = 1.0f,
        colorFilter: ColorFilter? = null,
        blendMode: BlendMode = BlendMode.SrcOver,
    ) {}

    fun inset(left: Float = 0f, top: Float = 0f, right: Float = 0f, bottom: Float = 0f,
              block: DrawScope.() -> Unit) { block() }

    fun translate(left: Float = 0f, top: Float = 0f, block: DrawScope.() -> Unit) { block() }

    fun rotate(degrees: Float, pivot: Offset = center, block: DrawScope.() -> Unit) { block() }
}

interface ContentDrawScope : DrawScope { fun drawContent() }

internal object DrawScopeImpl : DrawScope {
    override val size: Size = Size.Zero
    override val center: Offset = Offset.Zero
}

/** Used by the stubs that take a `DrawScope.() -> Unit`, so the lambda body
 *  is genuinely type-checked instead of being left unresolved. */
internal fun runDraw(block: DrawScope.() -> Unit) { DrawScopeImpl.block() }

/** `Dp.toPx()` is reachable from any Density; DrawScope inherits it. */
internal fun Density.px(dp: Dp): Float = dp.toPx()
