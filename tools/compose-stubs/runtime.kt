package androidx.compose.runtime

@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.TYPE,
    AnnotationTarget.TYPE_PARAMETER,
    AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.SOURCE)
annotation class Composable

interface State<out T> { val value: T }
interface MutableState<T> : State<T> { override var value: T }

fun <T> mutableStateOf(value: T): MutableState<T> = object : MutableState<T> {
    override var value: T = value
}

@Composable fun <T> remember(calculation: () -> T): T = calculation()
@Composable fun <T> remember(key1: Any?, calculation: () -> T): T = calculation()

operator fun <T> State<T>.getValue(thisObj: Any?, property: Any?): T = value
operator fun <T> MutableState<T>.setValue(thisObj: Any?, property: Any?, value: T) {
    this.value = value
}
