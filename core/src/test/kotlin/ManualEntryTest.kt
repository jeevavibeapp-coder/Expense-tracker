import com.jeevavibeapp.spendwise.core.*
import com.jeevavibeapp.spendwise.core.ManualEntry.Capture
import com.jeevavibeapp.spendwise.core.ManualEntry.Route

private var passed = 0
private var failed = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else { failed++; println("  FAIL  $name${if (detail.isNotEmpty()) "  [$detail]" else ""}") }
}

/** Words that tell someone their messages are being read for them. */
private val PROMISES_CAPTURE = Regex("(?i)as they arrive|automatic|captured|fills itself")

fun main() {
    println("=== Home keeps a paste control only where pasting is the capture ===")
    check("the no-SMS build: pasting is how bank data arrives at all",
        ManualEntry.pasteOnHome(Capture.NONE))
    check("the SMS build with capture on keeps Home as it was",
        !ManualEntry.pasteOnHome(Capture.AUTOMATIC))
    // Off is a state of the SMS build the user can leave with one tap in
    // Settings; a permanent card would outlive the reason for it.
    check("the SMS build with capture off does too",
        !ManualEntry.pasteOnHome(Capture.OFF))

    println("=== an empty ledger never promises capture that is not happening ===")
    for (c in Capture.entries) {
        val body = ManualEntry.emptyLedger(c).body
        if (c == Capture.AUTOMATIC) {
            check("with capture on it says messages are filed as they arrive",
                PROMISES_CAPTURE.containsMatchIn(body), body)
        } else {
            check("$c: no sentence says messages are read for you",
                !PROMISES_CAPTURE.containsMatchIn(body), body)
        }
    }

    println("=== the sentence and the buttons under it agree ===")
    for (c in Capture.entries) {
        val empty = ManualEntry.emptyLedger(c)
        check("$c: typing one in is always offered — the form works in every build",
            Route.BY_HAND in empty.routes, "${empty.routes}")
        check("$c: no route is offered twice", empty.routes.distinct() == empty.routes,
            "${empty.routes}")
        check("$c: a button the sentence offers is one it names",
            (Route.PASTE !in empty.routes || empty.body.contains("paste", ignoreCase = true)) &&
                (Route.BY_HAND !in empty.routes || empty.body.contains("yourself")),
            empty.body)
        check("$c: and the sentence names none that is not there",
            Route.PASTE in empty.routes || !empty.body.contains("paste", ignoreCase = true),
            empty.body)
    }

    println("=== paste leads wherever nothing is captured for you ===")
    check("no-SMS build: paste is the primary button",
        ManualEntry.emptyLedger(Capture.NONE).routes.first() == Route.PASTE)
    check("capture off: so is it there",
        ManualEntry.emptyLedger(Capture.OFF).routes.first() == Route.PASTE)
    // The SMS build's add sheet carries paste; the empty card doubling it
    // would be two ways to the same page on one screen.
    check("capture on: the empty card offers the form alone",
        ManualEntry.emptyLedger(Capture.AUTOMATIC).routes == listOf(Route.BY_HAND))

    println("=== each route has one label, and the two cannot be confused ===")
    check("the labels differ", Route.PASTE.label != Route.BY_HAND.label)
    check("paste's button says paste", Route.PASTE.label.contains("Paste"))
    check("the form's button does not", !Route.BY_HAND.label.contains("paste", ignoreCase = true))
    check("no label is blank", Route.entries.all { it.label.isNotBlank() })

    println("=== in the no-SMS build paste is one tap from Home, empty or not ===")
    val none = ManualEntry.emptyLedger(Capture.NONE)
    check("empty: the card's first button", none.routes.first() == Route.PASTE)
    check("with a ledger: Home's own control", ManualEntry.pasteOnHome(Capture.NONE))

    println()
    println("=".repeat(60))
    println("$passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
