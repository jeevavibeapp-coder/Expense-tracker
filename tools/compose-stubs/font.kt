// Transcribed from Compose 1.7.x. Real weights so any arithmetic or
// comparison on them behaves as it would on device.
package androidx.compose.ui.text.font

class FontWeight(val weight: Int) {
    companion object {
        val Thin = FontWeight(100); val ExtraLight = FontWeight(200)
        val Light = FontWeight(300); val Normal = FontWeight(400)
        val Medium = FontWeight(500); val SemiBold = FontWeight(600)
        val Bold = FontWeight(700); val ExtraBold = FontWeight(800)
        val Black = FontWeight(900)
        val W400 = Normal; val W500 = Medium; val W600 = SemiBold
        val W700 = Bold; val W800 = ExtraBold
    }
}
