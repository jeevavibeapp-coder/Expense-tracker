package androidx.compose.ui

interface Modifier {
    companion object : Modifier
    fun then(other: Modifier): Modifier = other
}

object Alignment {
    interface Vertical
    interface Horizontal
    val Top: Vertical = object : Vertical {}
    val CenterVertically: Vertical = object : Vertical {}
    val Bottom: Vertical = object : Vertical {}
    val Start: Horizontal = object : Horizontal {}
    val CenterHorizontally: Horizontal = object : Horizontal {}
}
