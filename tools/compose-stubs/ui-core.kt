package androidx.compose.ui

/** Real Modifier is an interface whose companion is the empty modifier, which
 *  is why `Modifier.padding(...)` and `modifier.padding(...)` are the same
 *  call. Anything else would let `Modifier` resolve where a value is wanted. */
interface Modifier {
    companion object : Modifier
    infix fun then(other: Modifier): Modifier = other
}

/**
 * Alignment is a type, not a namespace: `Box(contentAlignment = ...)` takes an
 * `Alignment`, while `Row(verticalAlignment = ...)` takes the nested
 * `Alignment.Vertical`. Flattening the three into one object would let a
 * horizontal alignment be passed where a vertical one is required.
 */
interface Alignment {
    interface Vertical
    interface Horizontal

    companion object {
        val TopStart: Alignment = object : Alignment {}
        val TopCenter: Alignment = object : Alignment {}
        val TopEnd: Alignment = object : Alignment {}
        val CenterStart: Alignment = object : Alignment {}
        val Center: Alignment = object : Alignment {}
        val CenterEnd: Alignment = object : Alignment {}
        val BottomStart: Alignment = object : Alignment {}
        val BottomCenter: Alignment = object : Alignment {}
        val BottomEnd: Alignment = object : Alignment {}

        val Top: Vertical = object : Vertical {}
        val CenterVertically: Vertical = object : Vertical {}
        val Bottom: Vertical = object : Vertical {}

        val Start: Horizontal = object : Horizontal {}
        val CenterHorizontally: Horizontal = object : Horizontal {}
        val End: Horizontal = object : Horizontal {}
    }
}

class ExperimentalComposeUiApi
