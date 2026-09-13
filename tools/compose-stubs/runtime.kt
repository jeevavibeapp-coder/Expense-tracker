package androidx.compose.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The annotation only. Everything @Composable *means* is enforced by the
 * Compose compiler plugin, which is not on this classpath — so calling a
 * composable from a non-composable context compiles here and fails in Gradle.
 * See 00-README.md.
 */
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.TYPE,
    AnnotationTarget.TYPE_PARAMETER,
    AnnotationTarget.PROPERTY_GETTER,
)
annotation class Composable

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
annotation class ReadOnlyComposable

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class Stable

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class Immutable

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class LayoutScopeMarker

@RequiresOptIn("Experimental Compose runtime API.")
annotation class ExperimentalComposeApi

interface State<out T> { val value: T }
interface MutableState<T> : State<T> {
    override var value: T
    operator fun component1(): T
    operator fun component2(): (T) -> Unit
}
interface IntState : State<Int> { val intValue: Int }
interface MutableIntState : IntState, MutableState<Int> { override var intValue: Int }
interface FloatState : State<Float> { val floatValue: Float }
interface MutableFloatState : FloatState, MutableState<Float> { override var floatValue: Float }

interface SnapshotMutationPolicy<T>
fun <T> structuralEqualityPolicy(): SnapshotMutationPolicy<T> = object : SnapshotMutationPolicy<T> {}
fun <T> referentialEqualityPolicy(): SnapshotMutationPolicy<T> = object : SnapshotMutationPolicy<T> {}
fun <T> neverEqualPolicy(): SnapshotMutationPolicy<T> = object : SnapshotMutationPolicy<T> {}

fun <T> mutableStateOf(
    value: T,
    policy: SnapshotMutationPolicy<T> = structuralEqualityPolicy(),
): MutableState<T> {
    val initial = value
    return object : MutableState<T> {
        override var value: T = initial
        override fun component1(): T = this.value
        override fun component2(): (T) -> Unit = { this.value = it }
    }
}

fun mutableIntStateOf(value: Int): MutableIntState {
    val initial = value
    return object : MutableIntState {
        override var intValue: Int = initial
        override var value: Int
            get() = intValue
            set(v) { intValue = v }
        override fun component1(): Int = intValue
        override fun component2(): (Int) -> Unit = { intValue = it }
    }
}

fun mutableFloatStateOf(value: Float): MutableFloatState {
    val initial = value
    return object : MutableFloatState {
        override var floatValue: Float = initial
        override var value: Float
            get() = floatValue
            set(v) { floatValue = v }
        override fun component1(): Float = floatValue
        override fun component2(): (Float) -> Unit = { floatValue = it }
    }
}

fun <T> mutableStateListOf(vararg elements: T): MutableList<T> = elements.toMutableList()
fun <K, V> mutableStateMapOf(vararg pairs: Pair<K, V>): MutableMap<K, V> = mutableMapOf(*pairs)

// `by` delegation on state. Real Compose declares these as inline operators in
// the same package, so an `import androidx.compose.runtime.getValue` in app
// code has something to bind to.
operator fun <T> State<T>.getValue(thisObj: Any?, property: Any?): T = value
operator fun <T> MutableState<T>.setValue(thisObj: Any?, property: Any?, value: T) {
    this.value = value
}
operator fun MutableIntState.getValue(thisObj: Any?, property: Any?): Int = intValue
operator fun MutableIntState.setValue(thisObj: Any?, property: Any?, value: Int) {
    this.intValue = value
}
operator fun MutableFloatState.getValue(thisObj: Any?, property: Any?): Float = floatValue
operator fun MutableFloatState.setValue(thisObj: Any?, property: Any?, value: Float) {
    this.floatValue = value
}

@Composable fun <T> remember(calculation: () -> T): T = calculation()
@Composable fun <T> remember(key1: Any?, calculation: () -> T): T = calculation()
@Composable fun <T> remember(key1: Any?, key2: Any?, calculation: () -> T): T = calculation()
@Composable fun <T> remember(key1: Any?, key2: Any?, key3: Any?, calculation: () -> T): T =
    calculation()
@Composable fun <T> remember(vararg keys: Any?, calculation: () -> T): T = calculation()

@Composable fun <T> rememberUpdatedState(newValue: T): State<T> = mutableStateOf(newValue)

fun <T> derivedStateOf(calculation: () -> T): State<T> = mutableStateOf(calculation())
fun <T> derivedStateOf(
    policy: SnapshotMutationPolicy<T>,
    calculation: () -> T,
): State<T> = mutableStateOf(calculation())

@Composable fun rememberCoroutineScope(
    getContext: () -> CoroutineContext = { EmptyCoroutineContext },
): CoroutineScope = CoroutineScope(getContext())

@Composable fun LaunchedEffect(key1: Any?, block: suspend CoroutineScope.() -> Unit) {}
@Composable fun LaunchedEffect(key1: Any?, key2: Any?, block: suspend CoroutineScope.() -> Unit) {}
@Composable fun LaunchedEffect(
    key1: Any?, key2: Any?, key3: Any?, block: suspend CoroutineScope.() -> Unit,
) {}
@Composable fun LaunchedEffect(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {}

class DisposableEffectScope {
    inline fun onDispose(crossinline onDisposeEffect: () -> Unit): DisposableEffectResult =
        object : DisposableEffectResult { override fun dispose() { onDisposeEffect() } }
}
interface DisposableEffectResult { fun dispose() }

@Composable fun DisposableEffect(
    key1: Any?, effect: DisposableEffectScope.() -> DisposableEffectResult,
) {}
@Composable fun DisposableEffect(
    key1: Any?, key2: Any?, effect: DisposableEffectScope.() -> DisposableEffectResult,
) {}
@Composable fun DisposableEffect(
    vararg keys: Any?, effect: DisposableEffectScope.() -> DisposableEffectResult,
) {}

@Composable fun SideEffect(effect: () -> Unit) {}

@Composable fun <T> key(vararg keys: Any?, block: @Composable () -> T): T = block()

fun <T> snapshotFlow(block: () -> T): Flow<T> = throw UnsupportedOperationException("stub")

@Composable
fun <T> produceState(
    initialValue: T,
    producer: suspend ProduceStateScope<T>.() -> Unit,
): State<T> = mutableStateOf(initialValue)

@Composable
fun <T> produceState(
    initialValue: T,
    key1: Any?,
    producer: suspend ProduceStateScope<T>.() -> Unit,
): State<T> = mutableStateOf(initialValue)

interface ProduceStateScope<T> : MutableState<T>, CoroutineScope {
    suspend fun awaitDispose(onDispose: () -> Unit): Nothing
}

/**
 * Flow collection. `initial` is the parameter name on the Flow overload and
 * there is no initial value on the StateFlow one — getting that wrong is a
 * common miss, so both are declared exactly as shipped.
 */
@Composable
fun <T : R, R> Flow<T>.collectAsState(
    initial: R,
    context: CoroutineContext = EmptyCoroutineContext,
): State<R> = mutableStateOf(initial)

@Composable
fun <T> StateFlow<T>.collectAsState(
    context: CoroutineContext = EmptyCoroutineContext,
): State<T> = mutableStateOf(this.value)

abstract class CompositionLocal<T> internal constructor(
    internal val defaultFactory: () -> T,
) {
    val current: T @Composable get() = defaultFactory()
}
abstract class ProvidableCompositionLocal<T> internal constructor(
    defaultFactory: () -> T,
) : CompositionLocal<T>(defaultFactory) {
    infix fun provides(value: T): ProvidedValue<T> = ProvidedValue(value)
}
class ProvidedValue<T> internal constructor(val value: T)

fun <T> compositionLocalOf(
    policy: SnapshotMutationPolicy<T> = structuralEqualityPolicy(),
    defaultFactory: () -> T,
): ProvidableCompositionLocal<T> = object : ProvidableCompositionLocal<T>(defaultFactory) {}

fun <T> staticCompositionLocalOf(defaultFactory: () -> T): ProvidableCompositionLocal<T> =
    object : ProvidableCompositionLocal<T>(defaultFactory) {}

@Composable
fun CompositionLocalProvider(vararg values: ProvidedValue<*>, content: @Composable () -> Unit) {
    content()
}
