package androidx.compose.foundation.shape

import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp

// Real RoundedCornerShape has a public constructor taking CornerSize values
// and a family of same-named factory functions. Only the factories are
// modelled, so `RoundedCornerShape(12.dp)` and `RoundedCornerShape(50)` both
// resolve while nothing else can be smuggled in.
class RoundedCornerShape private constructor() : Shape {
    internal companion object { fun make() = RoundedCornerShape() }
}

fun RoundedCornerShape(size: Dp): RoundedCornerShape = RoundedCornerShape.make()
fun RoundedCornerShape(percent: Int): RoundedCornerShape = RoundedCornerShape.make()
fun RoundedCornerShape(
    topStart: Dp = Dp(0f), topEnd: Dp = Dp(0f),
    bottomEnd: Dp = Dp(0f), bottomStart: Dp = Dp(0f),
): RoundedCornerShape = RoundedCornerShape.make()
fun RoundedCornerShape(
    topStartPercent: Int = 0, topEndPercent: Int = 0,
    bottomEndPercent: Int = 0, bottomStartPercent: Int = 0,
): RoundedCornerShape = RoundedCornerShape.make()

val CircleShape: RoundedCornerShape = RoundedCornerShape(50)

class CutCornerShape private constructor() : Shape {
    internal companion object { fun make() = CutCornerShape() }
}

fun CutCornerShape(size: Dp): CutCornerShape = CutCornerShape.make()
fun CutCornerShape(percent: Int): CutCornerShape = CutCornerShape.make()
