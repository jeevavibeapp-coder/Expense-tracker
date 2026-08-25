package androidx.compose.ui.text.input

class KeyboardType private constructor(val value: Int) {
    companion object {
        val Text = KeyboardType(0)
        val Number = KeyboardType(1)
        val Decimal = KeyboardType(2)
    }
}
