package androidx.compose.ui.graphics

class Color(val argb: Long) {
    companion object {
        val Unspecified = Color(0)
        val Transparent = Color(0)
        val White = Color(-1)
    }
}

interface Shape
