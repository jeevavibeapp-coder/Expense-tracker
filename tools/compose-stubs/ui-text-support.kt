package androidx.compose.ui.text.font

class FontWeight(val weight: Int) : Comparable<FontWeight> {
    companion object {
        val Thin = FontWeight(100); val ExtraLight = FontWeight(200)
        val Light = FontWeight(300); val Normal = FontWeight(400)
        val Medium = FontWeight(500); val SemiBold = FontWeight(600)
        val Bold = FontWeight(700); val ExtraBold = FontWeight(800)
        val Black = FontWeight(900)
        val W100 = Thin; val W200 = ExtraLight; val W300 = Light
        val W400 = Normal; val W500 = Medium; val W600 = SemiBold
        val W700 = Bold; val W800 = ExtraBold; val W900 = Black
    }
    override fun compareTo(other: FontWeight): Int = weight.compareTo(other.weight)
}

class FontStyle private constructor(private val v: Int) {
    companion object { val Normal = FontStyle(0); val Italic = FontStyle(1) }
}

class FontSynthesis private constructor(private val v: Int) {
    companion object {
        val None = FontSynthesis(0); val All = FontSynthesis(1)
        val Weight = FontSynthesis(2); val Style = FontSynthesis(3)
    }
}

abstract class FontFamily {
    companion object {
        val Default: FontFamily = object : FontFamily() {}
        val SansSerif: FontFamily = object : FontFamily() {}
        val Serif: FontFamily = object : FontFamily() {}
        val Monospace: FontFamily = object : FontFamily() {}
        val Cursive: FontFamily = object : FontFamily() {}
    }
}
