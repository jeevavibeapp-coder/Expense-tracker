package androidx.compose.material3

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@ExperimentalMaterial3Api
class DisplayMode private constructor(private val v: Int) {
    companion object { val Picker = DisplayMode(0); val Input = DisplayMode(1) }
}

@ExperimentalMaterial3Api
interface SelectableDates {
    fun isSelectableDate(utcTimeMillis: Long): Boolean = true
    fun isSelectableYear(year: Int): Boolean = true
}

@ExperimentalMaterial3Api
interface DatePickerState {
    /** Null until a day is picked, and UTC midnight for the day when it is —
     *  reading it in the device zone is a real off-by-one-day bug. */
    var selectedDateMillis: Long?
    var displayedMonthMillis: Long
    var displayMode: DisplayMode
    val yearRange: IntRange
    val selectableDates: SelectableDates
}

@ExperimentalMaterial3Api
class DatePickerColors internal constructor()

@ExperimentalMaterial3Api
class DatePickerFormatter internal constructor()

@ExperimentalMaterial3Api
object DatePickerDefaults {
    val YearRange: IntRange = IntRange(1900, 2100)
    val TonalElevation: Dp = 6.dp
    val shape: Shape @Composable get() = RoundedCornerShape(28.dp)
    val AllDates: SelectableDates = object : SelectableDates {}

    @Composable fun colors(): DatePickerColors = DatePickerColors()
    @Composable
    fun dateFormatter(
        yearSelectionSkeleton: String = "yMMMM",
        selectedDateSkeleton: String = "yMMMd",
        selectedDateDescriptionSkeleton: String = "yMMMMEEEEd",
    ): DatePickerFormatter = DatePickerFormatter()
}

@ExperimentalMaterial3Api
@Composable
fun rememberDatePickerState(
    initialSelectedDateMillis: Long? = null,
    initialDisplayedMonthMillis: Long? = initialSelectedDateMillis,
    yearRange: IntRange = DatePickerDefaults.YearRange,
    initialDisplayMode: DisplayMode = DisplayMode.Picker,
    selectableDates: SelectableDates = DatePickerDefaults.AllDates,
): DatePickerState {
    val years = yearRange
    val dates = selectableDates
    return object : DatePickerState {
        override var selectedDateMillis: Long? = initialSelectedDateMillis
        override var displayedMonthMillis: Long = initialDisplayedMonthMillis ?: 0L
        override var displayMode: DisplayMode = initialDisplayMode
        override val yearRange: IntRange = years
        override val selectableDates: SelectableDates = dates
    }
}

@ExperimentalMaterial3Api
@Composable
fun DatePicker(
    state: DatePickerState,
    modifier: Modifier = Modifier,
    dateFormatter: DatePickerFormatter = DatePickerDefaults.dateFormatter(),
    title: @Composable (() -> Unit)? = null,
    headline: @Composable (() -> Unit)? = null,
    showModeToggle: Boolean = true,
    colors: DatePickerColors = DatePickerDefaults.colors(),
) { title?.invoke(); headline?.invoke() }

@ExperimentalMaterial3Api
@Composable
fun DatePickerDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    shape: Shape = DatePickerDefaults.shape,
    tonalElevation: Dp = DatePickerDefaults.TonalElevation,
    colors: DatePickerColors = DatePickerDefaults.colors(),
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    content: @Composable ColumnScope.() -> Unit,
) {
    confirmButton()
    dismissButton?.invoke()
    androidx.compose.foundation.layout.ColumnScopeImpl.content()
}
