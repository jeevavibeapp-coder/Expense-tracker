import com.jeevavibeapp.spendwise.core.*

private var passed = 0
private var failed = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else { failed++; println("  FAIL  $name${if (detail.isNotEmpty()) "  [$detail]" else ""}") }
}

private fun row(
    sender: String,
    kind: String = "dlt",
    bank: String? = null,
    display: String? = null,
    trust: String = Senders.TRUST_UNKNOWN,
    messages: Int = 1,
    held: Int = 0,
) = SenderBook.Row(sender, display, kind, bank, trust, messages, held)

private fun entryFor(r: SenderBook.Row): SenderBook.Entry = SenderBook.list(listOf(r))[0]

fun main() {
    println("=== the list puts the decisions worth revisiting first ===")
    val mixed = listOf(
        row("VM-HDFCBK", bank = "HDFC Bank", messages = 40),
        row("9812345678", kind = "mobile", trust = Senders.TRUST_BLOCKED, messages = 2),
        row("VM-AXISBK", bank = "Axis Bank", messages = 9),
    )
    check("a blocked sender outranks a far chattier one",
        SenderBook.list(mixed).first().sender == "9812345678",
        SenderBook.list(mixed).map { it.sender }.toString())
    check("below it the chattiest sender comes first",
        SenderBook.list(mixed).map { it.sender }.drop(1) == listOf("VM-HDFCBK", "VM-AXISBK"))

    // A list that reshuffles between redraws makes the row under a finger
    // move as it lands, which on this screen means blocking the wrong sender.
    val tied = listOf(row("VM-ZZZBNK", messages = 5), row("VM-AAABNK", messages = 5))
    check("senders on an equal count are ordered by name",
        SenderBook.list(tied).map { it.sender } == listOf("VM-AAABNK", "VM-ZZZBNK"))
    check("and that order does not depend on the order they arrived in",
        SenderBook.list(tied).map { it.sender } ==
            SenderBook.list(tied.reversed()).map { it.sender })

    // A restore can carry a row the current sanitiser would reject. A blank
    // key addresses no sender, so a button beside it would write nothing.
    check("a row with no sender key is dropped",
        SenderBook.list(listOf(row(""), row("VM-HDFCBK"))).map { it.sender } ==
            listOf("VM-HDFCBK"))
    check("an empty registry lists nothing", SenderBook.list(emptyList()).isEmpty())

    println("=== a row says who the sender is, in words ===")
    check("the bank's name is the title when the app knows it",
        entryFor(row("VM-HDFCBK", bank = "HDFC Bank")).title == "HDFC Bank")
    check("a stored display name is next best",
        entryFor(row("VM-QQQBNK", display = "Some Bank")).title == "Some Bank")
    check("otherwise the sender key is the title",
        entryFor(row("VM-QQQBNK")).title == "VM-QQQBNK")
    check("a blank display name does not become a blank title",
        entryFor(row("VM-QQQBNK", display = "   ")).title == "VM-QQQBNK")

    check("the detail line carries the key the title replaced",
        entryFor(row("VM-HDFCBK", bank = "HDFC Bank", messages = 4)).detail ==
            "VM-HDFCBK · bank header · 4 messages",
        entryFor(row("VM-HDFCBK", bank = "HDFC Bank", messages = 4)).detail)
    check("and does not repeat a key that is already the title",
        entryFor(row("VM-QQQBNK", messages = 4)).detail == "bank header · 4 messages",
        entryFor(row("VM-QQQBNK", messages = 4)).detail)
    check("one message is not '1 messages'",
        entryFor(row("VM-QQQBNK", messages = 1)).detail.endsWith("1 message"))
    check("held messages are counted only when there are some",
        !entryFor(row("VM-QQQBNK", messages = 4, held = 0)).detail.contains("held"))
    check("and named when there are",
        entryFor(row("VM-QQQBNK", messages = 4, held = 3)).detail.endsWith("4 messages · 3 held"))

    // The whole structural argument of this module, said out loud on the one
    // screen where a user decides whether to believe a sender.
    check("a personal mobile is described as one",
        entryFor(row("9812345678", kind = "mobile")).detail.contains("personal mobile"))
    check("every shape the identifier can return has words for it",
        listOf("dlt", "header", "mobile", "shortcode", "missing", "other")
            .map { SenderBook.kindLabel(it) }.toSet().size == 6)
    check("an unrecognised shape is not printed raw",
        SenderBook.kindLabel("wharrgarbl") == "unrecognised")

    println("=== the pill names the state a user can act on ===")
    check("a trusted sender says so", SenderBook.statusLabel(Senders.TRUST_TRUSTED) == "Trusted")
    check("a blocked sender says so", SenderBook.statusLabel(Senders.TRUST_BLOCKED) == "Blocked")
    check("a heuristic verdict is plain language, not a schema value",
        SenderBook.statusLabel(Senders.TRUST_SUSPICIOUS) == "Looks unsafe")
    check("a sender never judged still gets a label",
        SenderBook.statusLabel(null) == "Unverified")

    println("=== what a tap stores ===")
    val fresh = SenderBook.choices(Senders.TRUST_UNKNOWN)
    check("an unjudged sender can be trusted or blocked",
        fresh.map { it.trust } == listOf(Senders.TRUST_TRUSTED, Senders.TRUST_BLOCKED))
    check("so can one the heuristics called suspicious",
        SenderBook.choices(Senders.TRUST_SUSPICIOUS).map { it.trust } ==
            fresh.map { it.trust })
    check("so can one they recognised",
        SenderBook.choices(Senders.TRUST_KNOWN).map { it.trust } == fresh.map { it.trust })

    val blocked = SenderBook.choices(Senders.TRUST_BLOCKED)
    check("a blocked sender offers exactly one way out", blocked.size == 1)
    check("and it clears the decision rather than trusting",
        blocked[0].trust == Senders.TRUST_UNKNOWN)
    check("blocked to trusted is never one tap",
        blocked.none { it.trust == Senders.TRUST_TRUSTED })

    val trusted = SenderBook.choices(Senders.TRUST_TRUSTED)
    check("a trusted sender can be stepped back or blocked outright",
        trusted.map { it.trust } == listOf(Senders.TRUST_UNKNOWN, Senders.TRUST_BLOCKED))
    check("every offered choice is one the registry will accept",
        listOf(null, Senders.TRUST_UNKNOWN, Senders.TRUST_KNOWN, Senders.TRUST_SUSPICIOUS,
               Senders.TRUST_TRUSTED, Senders.TRUST_BLOCKED)
            .flatMap { SenderBook.choices(it) }.all { SenderBook.isUserDecision(it.trust) })
    check("no choice is offered without a label",
        listOf(null, Senders.TRUST_UNKNOWN, Senders.TRUST_TRUSTED, Senders.TRUST_BLOCKED)
            .flatMap { SenderBook.choices(it) }.all { it.label.isNotBlank() })

    check("a heuristic verdict cannot be written as a user decision",
        !SenderBook.isUserDecision(Senders.TRUST_KNOWN) &&
        !SenderBook.isUserDecision(Senders.TRUST_SUSPICIOUS))
    check("nor can a value from nowhere",
        !SenderBook.isUserDecision("") && !SenderBook.isUserDecision(null) &&
        !SenderBook.isUserDecision("Trusted"))

    println("=== the stored decision is the one assess obeys ===")
    // These tie the buttons to the behaviour they promise. Without them the
    // screen could offer a block that changes nothing about the next message.
    val bankBody = "Rs.2,340.00 debited from A/c XX4412 on 04-06-26 to SWIGGY. Avl Bal Rs.11,204.10"
    val blockChoice = SenderBook.choices(Senders.TRUST_UNKNOWN)
        .first { it.trust == Senders.TRUST_BLOCKED }
    val afterBlock = Senders.assess("VM-HDFCBK", bankBody,
        SenderRegistry(trust = blockChoice.trust))
    check("blocking a sender holds its next message even though it parses",
        afterBlock.action == Senders.ACTION_QUARANTINE, afterBlock.action)
    check("and the held message says the user blocked it, not that it looked wrong",
        Senders.explain(afterBlock) == "You blocked this sender.")

    // The pre-trust case from the other side: a short number the heuristics
    // will send to review forever, until someone vouches for it once.
    val shortcodeBefore = Senders.assess("56767", bankBody, null)
    check("an unvouched short number is reviewed rather than banked",
        shortcodeBefore.action == Senders.ACTION_REVIEW, shortcodeBefore.action)
    val trustChoice = SenderBook.choices(Senders.TRUST_UNKNOWN)
        .first { it.trust == Senders.TRUST_TRUSTED }
    check("trusting it stops the app asking again",
        Senders.assess("56767", bankBody, SenderRegistry(trust = trustChoice.trust))
            .action == Senders.ACTION_ACCEPT)

    // Trust is not a bypass, and the screen must not imply it is.
    val scam = "Your KYC will be suspended. Download http://x.co/a.apk and confirm your OTP now"
    check("a trusted sender sending a scam is still held",
        Senders.assess("56767", scam, SenderRegistry(trust = Senders.TRUST_TRUSTED))
            .action == Senders.ACTION_QUARANTINE)

    check("unblocking restores the heuristics rather than trusting the sender",
        Senders.assess("VM-HDFCBK", bankBody,
            SenderRegistry(trust = blocked[0].trust)).action ==
            Senders.assess("VM-HDFCBK", bankBody, null).action)

    println()
    println("=".repeat(60))
    println("$passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
