package androidx.compose.ui.text.style

class TextAlign private constructor(private val v: Int) {
    companion object {
        val Left = TextAlign(1); val Right = TextAlign(2); val Center = TextAlign(3)
        val Justify = TextAlign(4); val Start = TextAlign(5); val End = TextAlign(6)
    }
}
class TextOverflow private constructor(private val v: Int) {
    companion object { val Clip = TextOverflow(1); val Ellipsis = TextOverflow(2) }
}
