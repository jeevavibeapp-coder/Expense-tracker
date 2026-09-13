package androidx.compose.ui.draw

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun Modifier.clip(shape: Shape): Modifier = this
fun Modifier.clipToBounds(): Modifier = this
fun Modifier.alpha(alpha: Float): Modifier = this
fun Modifier.rotate(degrees: Float): Modifier = this
fun Modifier.scale(scale: Float): Modifier = this
fun Modifier.scale(scaleX: Float, scaleY: Float): Modifier = this
fun Modifier.shadow(
    elevation: Dp,
    shape: Shape = androidx.compose.ui.graphics.RectangleShape,
    clip: Boolean = elevation > 0.dp,
    ambientColor: Color = Color.Black,
    spotColor: Color = Color.Black,
): Modifier = this
fun Modifier.drawBehind(onDraw: DrawScope.() -> Unit): Modifier = this
fun Modifier.drawWithContent(onDraw: ContentDrawScope.() -> Unit): Modifier = this
