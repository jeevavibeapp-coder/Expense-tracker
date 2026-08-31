package androidx.compose.ui.text.input

class KeyboardType private constructor(private val v: Int) {
    companion object {
        val Unspecified = KeyboardType(0)
        val Text = KeyboardType(1)
        val Ascii = KeyboardType(2)
        val Number = KeyboardType(3)
        val Phone = KeyboardType(4)
        val Uri = KeyboardType(5)
        val Email = KeyboardType(6)
        val Password = KeyboardType(7)
        val NumberPassword = KeyboardType(8)
        val Decimal = KeyboardType(9)
    }
}

class ImeAction private constructor(private val v: Int) {
    companion object {
        val Unspecified = ImeAction(-1)
        val None = ImeAction(0)
        val Default = ImeAction(1)
        val Go = ImeAction(2)
        val Search = ImeAction(3)
        val Send = ImeAction(4)
        val Previous = ImeAction(5)
        val Next = ImeAction(6)
        val Done = ImeAction(7)
    }
}

class KeyboardCapitalization private constructor(private val v: Int) {
    companion object {
        val Unspecified = KeyboardCapitalization(-1)
        val None = KeyboardCapitalization(0)
        val Characters = KeyboardCapitalization(1)
        val Words = KeyboardCapitalization(2)
        val Sentences = KeyboardCapitalization(3)
    }
}

interface VisualTransformation {
    companion object { val None: VisualTransformation = object : VisualTransformation {} }
}

class PasswordVisualTransformation(val mask: Char = '•') : VisualTransformation

class TextFieldValue(
    val text: String = "",
    val selection: androidx.compose.ui.text.TextRange = androidx.compose.ui.text.TextRange.Zero,
)
