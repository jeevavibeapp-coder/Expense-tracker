package androidx.compose.ui.graphics

class Brush {
    companion object {
        fun linearGradient(colors: List<Color>): Brush = Brush()
        fun verticalGradient(colors: List<Color>): Brush = Brush()
        fun horizontalGradient(colors: List<Color>): Brush = Brush()
        fun radialGradient(colors: List<Color>): Brush = Brush()
    }
}
