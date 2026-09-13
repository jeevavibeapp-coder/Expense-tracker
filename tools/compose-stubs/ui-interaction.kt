package androidx.compose.foundation.interaction

interface Interaction
interface InteractionSource
interface MutableInteractionSource : InteractionSource

fun MutableInteractionSource(): MutableInteractionSource = object : MutableInteractionSource {}
