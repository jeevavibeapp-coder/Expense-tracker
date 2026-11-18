import com.jeevavibeapp.spendwise.core.*
import java.time.LocalDate
import java.time.LocalDateTime

private var passed = 0
private var failed = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else { failed++; println("  FAIL  $name${if (detail.isNotEmpty()) "  [$detail]" else ""}") }
}

private val NOW = LocalDateTime.of(2026, 6, 17, 12, 34)
private const val GENUINE =
    "Rs.450.00 debited from a/c **1234 on 05-Jan-24 to ZOMATO Ref 883120114455 UPI"
private const val UNDATED =
    "Rs.450.00 debited from a/c **1234 to ZOMATO Ref 883120114455 UPI"
/** Synthetic. Undated and with no reference: a paste of it is keyed by its
 *  text and the day it is filed under. */
private const val UNDATED_NO_REF = "Rs.20.00 debited from A/c XX1234 to CHAIPOINT"
private const val SALARY =
    "INR 62,500.00 credited to A/c XX1234 on 01-07-26 towards SALARY JUL26. Ref 998877665544"
private const val REFUND =
    "Rs.599.00 credited to your A/c XX1234 on 08-07-26 from AMAZON refund. Ref 553201998877"
/** Parses as a debit, worded as a scam. Pipeline holds this from an SMS. */
private const val PHISH = "Rs.9999.00 debited from A/c XX1234 on 05-Jan-24. " +
    "Not you? Call 9812345678 immediately to reverse. http://bit.ly/kyc-verify"
/** A pre-debit notice that also carries a link: transaction-shaped, not a
 *  transaction, with just enough content risk that Pipeline holds it. */
private const val NOTICE_WITH_LINK = "Rs.199 will be debited from A/c XX1234 on 15-07-26 " +
    "for NETFLIX autopay. Ref 512345678901. Details https://hdfc.example/x"

private val CATEGORY_TYPES = mapOf("food" to "expense", "shopping" to "expense",
                                   "salary" to "income", "other-income" to "income")

/**
 * A stand-in for the database, so "preview writes nothing" is a claim about
 * something that could have been written to, and "commit stores what was
 * shown" is checked by storing it and reading it back.
 */
private class FakeLedger {
    val rows = linkedMapOf<String, Paste.Banked>()
    var resolveCalls = 0
    var bankedCalls = 0

    /** What Repo.savePaste does with a Commit: one row per message. It is
     *  refused when the ledger holds the message under ANY key it answers to
     *  (its own, or one it was banked under before K3), and stored under its
     *  own key. */
    fun store(c: Paste.Commit): Boolean {
        if (c.identity.all.any { it in rows }) return false
        rows[c.identity.key] = Paste.Banked("t${rows.size}", c.amount, c.type,
            c.merchantName ?: c.rawMerchant, c.occurredAt, deleted = false)
        return true
    }

    fun preview(body: String, resolution: Pipeline.Resolution =
                    Pipeline.Resolution("Zomato", "food", 90),
                now: LocalDateTime = NOW): Paste.Preview =
        Paste.preview(
            body = body, now = now,
            resolve = { _, _, _ -> resolveCalls++; resolution },
            banked = { key -> bankedCalls++; rows[key] },
            categoryType = { CATEGORY_TYPES[it] },
        )
}

private fun Paste.Preview.tx(): Paste.Preview.Transaction? = this as? Paste.Preview.Transaction

fun main() {
    println("=== a clean message previews as the row Save would write ===")
    val ledger = FakeLedger()
    val p = ledger.preview(GENUINE).tx()
    check("reads as a transaction", p != null)
    val d = p!!.draft
    check("amount", d.amount == 450.0, "got=${d.amount}")
    check("direction", d.type == "expense")
    check("the bank's payee string is kept", d.rawMerchant == "ZOMATO", "got=${d.rawMerchant}")
    check("the engine's name for it", d.merchantName == "Zomato")
    check("the suggested category", d.categoryId == "food")
    check("the date the message carries", d.occurredAt == LocalDateTime.of(2024, 1, 5, 0, 0),
        "got=${d.occurredAt}")
    check("and says it came from the message", d.dated)
    check("the reference", d.referenceNumber == "883120114455")
    check("the confidence", d.confidence == 90)
    check("a confident payee is filed confirmed", d.status == Pipeline.STATUS_CONFIRMED)
    check("no warning on an ordinary alert", p.warning == null)
    check("nothing to duplicate in an empty ledger", p.duplicate == null)

    // The paste is the capture path's decision with no sender, not a second
    // parser: every field must be the one Pipeline would have stored.
    val captured = Pipeline.ingest(null, GENUINE, NOW, { null },
        { _, _, _ -> Pipeline.Resolution("Zomato", "food", 90) }) as Pipeline.Outcome.Capture
    check("the same amount the capture path stores", captured.parsed.amount == d.amount)
    check("the same payee", captured.merchantName == d.merchantName &&
        captured.parsed.rawMerchant == d.rawMerchant)
    check("the same confidence and status", captured.confidence == d.confidence &&
        captured.status == d.status)
    check("the same dedup key, so a later inbox scan recognises it",
        captured.identity.key == d.identity.key,
        "capture=${captured.identity.key} paste=${d.identity.key}")

    val undated = ledger.preview(UNDATED).tx()!!.draft
    check("an undated message is filed at the moment it was pasted", undated.occurredAt == NOW)
    check("and the screen is told the date is a guess", !undated.dated)

    val income = ledger.preview(SALARY, Pipeline.Resolution("Salary Jul26", "salary", 30)).tx()!!.draft
    check("a credit previews as income", income.type == "income")
    check("with its amount", income.amount == 62500.0)
    check("an income category is kept on an income row", income.categoryId == "salary")

    println("=== the suggestion must be a chip the card can show ===")
    val refund = ledger.preview(REFUND, Pipeline.Resolution("Amazon", "shopping", 90)).tx()!!.draft
    check("a refund is income", refund.type == "income")
    check("and does not arrive filed under the shop's spending category",
        refund.categoryId == null, "got=${refund.categoryId}")
    val archived = ledger.preview(GENUINE, Pipeline.Resolution("Zomato", "gone", 90)).tx()!!.draft
    check("a category the picker cannot offer is not suggested", archived.categoryId == null)

    println("=== previewing writes nothing ===")
    val quiet = FakeLedger()
    val first = quiet.preview(GENUINE)
    val again = quiet.preview(GENUINE)
    val scam = quiet.preview(PHISH)
    quiet.preview("Your OTP is 4567")
    check("the ledger is untouched after four previews", quiet.rows.isEmpty())
    check("previewing twice gives the same answer", first == again)
    // Since K3 a message answers to more than one key (its own and the one it
    // may have been banked under before), and each is a lookup. Nothing is
    // found in an empty ledger, so every one of them is asked.
    val keysAsked = listOf(first, again, scam).sumOf { it.tx()?.draft?.identity?.all?.size ?: 0 }
    check("it only asked about the payee and the keys the message answers to",
        quiet.resolveCalls == 3 && keysAsked >= 3 && quiet.bankedCalls == keysAsked,
        "resolve=${quiet.resolveCalls} banked=${quiet.bankedCalls} keys=$keysAsked")
    check("a scam paste is shown, not held — there is no quarantine outcome to write",
        quiet.preview(PHISH) is Paste.Preview.Transaction)

    println("=== committing stores exactly what was shown ===")
    val c = Paste.commit(d)!!
    check("amount", c.amount == d.amount)
    check("direction", c.type == d.type)
    check("payee, both spellings", c.rawMerchant == d.rawMerchant && c.merchantName == d.merchantName)
    check("category", c.categoryId == d.categoryId)
    check("reference", c.referenceNumber == d.referenceNumber)
    check("date and time", c.occurredAt == d.occurredAt, "got=${c.occurredAt}")
    check("confidence and status", c.confidence == d.confidence && c.status == d.status)
    check("dedup key", c.identity.key == d.identity.key)
    check("an untouched card teaches the engine nothing", c.learn == null)
    check("it is stored", ledger.store(c))
    val stored = ledger.rows[c.identity.key]!!
    check("and reads back as shown",
        stored.amount == 450.0 && stored.type == "expense" && stored.merchant == "Zomato" &&
        stored.occurredAt == d.occurredAt)

    val repeat = ledger.preview(GENUINE).tx()!!
    check("pasting it again says it is already in the ledger", repeat.duplicate == stored)
    val otherRail = ledger.preview(
        "Rs.450.00 debited to ZOMATO from a/c **1234 on 05-Jan-24. Ref 883120114455").tx()!!
    check("so does the same payment worded by another rail", otherRail.duplicate == stored)
    check("a different purchase is not a duplicate",
        ledger.preview("Rs.450.00 debited from a/c **1234 on 05-Jan-24 to SWIGGY Ref 999999999999 UPI")
            .tx()!!.duplicate == null)
    check("a second commit of the same message is refused by the key", !ledger.store(c))

    println("=== corrections are stored, the message keeps its identity ===")
    val shown = Paste.fields(d)
    check("the card starts on the draft's own values",
        shown.amount == 450.0 && shown.type == "expense" && shown.merchant == "Zomato" &&
        shown.categoryId == "food" && shown.date == LocalDate.of(2024, 1, 5))
    val fixedAmount = Paste.commit(d, shown.copy(amount = 540.0))!!
    check("a corrected amount is what is stored", fixedAmount.amount == 540.0)
    check("but the key stays the message's, so the misread copy cannot come in beside it",
        fixedAmount.identity.key == d.identity.key)
    check("correcting the amount is not naming the payee",
        fixedAmount.status == d.status && fixedAmount.learn == null)
    check("a flipped direction is stored", Paste.commit(d, shown.copy(type = "income"))!!.type == "income")
    val moved = Paste.commit(d, shown.copy(date = LocalDate.of(2024, 1, 6)))!!
    check("a corrected date is stored", moved.occurredAt.toLocalDate() == LocalDate.of(2024, 1, 6))
    check("with the time of day the message had", moved.occurredAt.toLocalTime() == d.occurredAt.toLocalTime())
    check("a dated message keeps its own key when the date is corrected",
        moved.identity.key == d.identity.key)
    // An undated message's day is not in the message, so the day the user
    // picks is the day it is filed under. The SMS arrived on that day, and the
    // inbox scan will compute that day's keys, so the commit is keyed by the
    // CHOSEN day (pre-K3 pastes were stored under it too). It also answers to
    // every key the preview showed for the paste day, so a row banked under
    // any of them (the same SMS captured on the paste day, before or after
    // K3) refuses it. Since K3-b a reference key holds a day as well.
    val chosenAt = LocalDateTime.of(2026, 6, 15, 12, 34)
    fun bankedUnder(key: String) = FakeLedger().apply {
        rows[key] = Paste.Banked("pre-k3", 450.0, "expense", "Zomato", NOW, deleted = false)
    }
    check("an untouched undated commit keeps the preview's key",
        Paste.commit(undated)!!.identity.key == undated.identity.key,
        "commit=${Paste.commit(undated)!!.identity.key} preview=${undated.identity.key}")
    // Today: "v2|ref|expense|1234|883120114455|450.00", no day.
    check("the preview of UNDATED is keyed by its reference on the paste day",
        undated.identity.key == "v2|ref|expense|1234|883120114455|450.00|2026-06-17",
        "got=${undated.identity.key}")
    val undatedMoved = Paste.commit(undated, Paste.fields(undated).copy(date = LocalDate.of(2026, 6, 15)))!!
    check("moved to another day, an undated message with a reference is keyed by the chosen day",
        undatedMoved.identity.key == "v2|ref|expense|1234|883120114455|450.00|2026-06-15",
        "got=${undatedMoved.identity.key}")
    check("and answers to every key the preview showed for the paste day",
        undated.identity.all.all { it in undatedMoved.identity.aliases },
        "preview=${undated.identity.all} moved=${undatedMoved.identity.aliases}")
    // UNDATED's 6bd77fb5 keys for the paste day and the chosen day, computed
    // at that commit.
    val preK3PasteDay = "450.0|ZOMATO|883120114455|2026-06-17"
    val preK3ChosenDay = "450.0|ZOMATO|883120114455|2026-06-15"
    check("precondition: those are legacyKey for the two days",
        Dedup.legacyKey(undated.parsed, NOW) == preK3PasteDay &&
        Dedup.legacyKey(undated.parsed, chosenAt) == preK3ChosenDay,
        "paste day=${Dedup.legacyKey(undated.parsed, NOW)} chosen=${Dedup.legacyKey(undated.parsed, chosenAt)}")
    check("it answers to the pre-K3 key for the day the user chose",
        preK3ChosenDay in undatedMoved.identity.aliases, "got=${undatedMoved.identity.aliases}")
    check("and to the pre-K3 key for the day it was pasted",
        preK3PasteDay in undatedMoved.identity.all, "got=${undatedMoved.identity.all}")
    check("so a row banked before the upgrade under the paste day refuses the moved commit",
        !bankedUnder(preK3PasteDay).store(undatedMoved), "all=${undatedMoved.identity.all}")
    check("as does one banked under the chosen day",
        !bankedUnder(preK3ChosenDay).store(undatedMoved), "all=${undatedMoved.identity.all}")
    check("and one banked under the paste day's reference key",
        !bankedUnder(undated.identity.key).store(undatedMoved), "all=${undatedMoved.identity.all}")
    check("and keeps the paste's time of day", undatedMoved.occurredAt == chosenAt)
    // With no reference the message is its own identity, and a paste has no
    // sender or SMS timestamp, so the day is what places it: the chosen day IS
    // in the key (v2|paste|<hash>|<day>).
    val bare = ledger.preview(UNDATED_NO_REF).tx()!!.draft
    val bareMoved = Paste.commit(bare, Paste.fields(bare).copy(date = LocalDate.of(2026, 6, 15)))!!
    check("an undated message with no reference is keyed by the day the user chose",
        bareMoved.identity.key.startsWith("v2|paste|") && bareMoved.identity.key.endsWith("|2026-06-15"),
        "got=${bareMoved.identity.key}")
    check("not by the day it was pasted",
        bare.identity.key.endsWith("|2026-06-17") && bareMoved.identity.key != bare.identity.key,
        "pasted=${bare.identity.key} moved=${bareMoved.identity.key}")
    check("the key a paste of it filed on that day has",
        bareMoved.identity.key ==
            Dedup.identity(bare.parsed, UNDATED_NO_REF, null, null, chosenAt).key,
        "got=${bareMoved.identity.key}")
    check("and it answers to every key the preview showed for the paste day",
        bare.identity.all.all { it in bareMoved.identity.aliases },
        "preview=${bare.identity.all} moved=${bareMoved.identity.aliases}")
    check("the paste-day paste key among them", bare.identity.key in bareMoved.identity.aliases,
        "pasted=${bare.identity.key} moved=${bareMoved.identity.aliases}")
    // UNDATED_NO_REF's 6bd77fb5 keys for the two days, computed at that commit.
    val bareK3PasteDay = "20.0|CHAIPOINT||2026-06-17"
    val bareK3ChosenDay = "20.0|CHAIPOINT||2026-06-15"
    check("precondition: those are legacyKey for the two days",
        Dedup.legacyKey(bare.parsed, NOW) == bareK3PasteDay &&
        Dedup.legacyKey(bare.parsed, chosenAt) == bareK3ChosenDay,
        "paste day=${Dedup.legacyKey(bare.parsed, NOW)} chosen=${Dedup.legacyKey(bare.parsed, chosenAt)}")
    check("and the pre-K3 keys for both days",
        bareK3PasteDay in bareMoved.identity.aliases && bareK3ChosenDay in bareMoved.identity.aliases,
        "got=${bareMoved.identity.aliases}")
    check("so a row banked before the upgrade under the paste day refuses it",
        !bankedUnder(bareK3PasteDay).store(bareMoved), "all=${bareMoved.identity.all}")

    println("=== naming the payee on the card is the confirmation ===")
    val unknown = ledger.preview(GENUINE, Pipeline.Resolution(null, null, 5)).tx()!!.draft
    check("a payee the engine has never seen waits for review", unknown.status == Pipeline.STATUS_REVIEW)
    check("the card shows the bank's string for it", Paste.fields(unknown).merchant == "ZOMATO")
    val untouched = Paste.commit(unknown)!!
    check("saved untouched, it is still for review", untouched.status == Pipeline.STATUS_REVIEW)
    check("and keeps no name the engine did not give it", untouched.merchantName == null)
    val named = Paste.commit(unknown, Paste.fields(unknown).copy(merchant = " Zomato ", categoryId = "food"))!!
    check("named and categorised, it is confirmed", named.status == Pipeline.STATUS_CONFIRMED)
    check("with a person's confidence", named.confidence == Pipeline.CONFIRMED_CONFIDENCE)
    check("under the name as typed, without its spacing", named.merchantName == "Zomato")
    check("and the engine learns it under the bank's string",
        named.learn == Paste.Learning("ZOMATO", "Zomato", "food", isCorrection = false),
        "got=${named.learn}")
    val pending = ledger.preview(GENUINE, Pipeline.Resolution("Zomatoo", "food", 55)).tx()!!.draft
    check("a middling score waits for confirmation", pending.status == Pipeline.STATUS_PENDING)
    val renamed = Paste.commit(pending, Paste.fields(pending).copy(merchant = "Zomato"))!!
    check("renaming the engine's payee is a correction", renamed.learn?.isCorrection == true)
    check("and confirms the row", renamed.status == Pipeline.STATUS_CONFIRMED)
    val sameInCase = Paste.commit(pending, Paste.fields(pending).copy(merchant = "zomatoo"))!!
    check("agreeing in different case confirms without counting as a correction",
        sameInCase.status == Pipeline.STATUS_CONFIRMED && sameInCase.learn?.isCorrection == false)
    val recategorised = Paste.commit(pending, Paste.fields(pending).copy(categoryId = "shopping"))!!
    check("picking a different category confirms the payee", recategorised.status == Pipeline.STATUS_CONFIRMED)
    check("and replacing the engine's category is a correction", recategorised.learn?.isCorrection == true)
    check("keeping the engine's name", recategorised.merchantName == "Zomatoo")
    val cleared = Paste.commit(pending, Paste.fields(pending).copy(categoryId = null))!!
    check("clearing the category is stored as Uncategorised", cleared.categoryId == null)
    check("but is not the user vouching for the payee",
        cleared.status == Pipeline.STATUS_PENDING && cleared.learn == null)
    val blanked = Paste.commit(pending, Paste.fields(pending).copy(merchant = "   "))!!
    check("an emptied name stores no name, so the row shows the bank's string",
        blanked.merchantName == null && blanked.rawMerchant == "ZOMATO")
    check("and confirms nothing", blanked.status == Pipeline.STATUS_PENDING && blanked.learn == null)

    println("=== the last check before storage ===")
    for (bad in listOf(0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY, 1e13)) {
        check("refuses an amount of $bad", Paste.commit(d, shown.copy(amount = bad)) == null)
    }
    check("refuses a direction the ledger has no side for",
        Paste.commit(d, shown.copy(type = "transfer")) == null)

    println("=== a suspicious message is shown with the reason, never held ===")
    val phish = FakeLedger().preview(PHISH, Pipeline.Resolution(null, null, 0)).tx()
    check("it is still a transaction the user can judge", phish != null)
    check("with a warning", !phish?.warning.isNullOrBlank())
    check("that names what is wrong with it", phish?.warning?.contains("shortener") == true,
        "got=${phish?.warning}")
    check("and never talks about a sender a paste does not have",
        phish?.warning?.contains("sender", ignoreCase = true) == false)
    check("saving it anyway is the user vouching, so it is filed confirmed",
        phish?.draft?.status == Pipeline.STATUS_CONFIRMED)
    check("and the amount is the one it claims", phish?.draft?.amount == 9999.0)
    val callback = FakeLedger().preview("Rs.9999.00 debited from A/c XX1234 on 05-Jan-24. " +
        "Call 9812345678 immediately to reverse it. Details at http://hdfc-help.example").tx()
    check("a mobile callback number is named as the problem",
        callback?.warning?.contains("personal mobile") == true, "got=${callback?.warning}")
    val notice = FakeLedger().preview(NOTICE_WITH_LINK).tx()
    check("a pre-debit notice with a link is shown as not money that has moved",
        notice?.warning == Paste.NOT_MOVED_YET, "got=${notice?.warning}")

    println("=== not a transaction, and why ===")
    fun reason(body: String) = (FakeLedger().preview(body) as? Paste.Preview.NotATransaction)?.reason
    check("nothing pasted", reason("   ") == Paste.NOTHING_PASTED)
    check("an OTP has no amount", reason("Your OTP is 4567") == Paste.NO_AMOUNT)
    check("a zero amount is no amount", reason("Rs.0.00 debited from A/c XX1234 to GLITCH Ref 553201998877") ==
        Paste.NO_AMOUNT)
    check("a personal message does not say money moved",
        reason("Hey, send me Rs.500 when you can") == Paste.NO_MOVEMENT)
    check("half a copied message is told to paste all of it",
        reason("Rs.450.00 debited to ZOMATO")?.contains("paste all of it") == true)
    check("an autopay reminder has not moved money yet",
        reason("Rs.199 will be debited from A/c XX1234 on 15-07 for NETFLIX autopay. Ref 512345678901") ==
            Paste.NOT_MOVED_YET)
    check("a payment request has not moved money either",
        reason("Payment request of Rs.999 from netflix@icici on your UPI A/c. Ref 512345678901") ==
            Paste.NOT_MOVED_YET)
    check("the three reasons say three different things",
        setOf(Paste.NO_AMOUNT, Paste.NO_MOVEMENT, Paste.NOT_MOVED_YET).size == 3)

    println()
    println("=".repeat(60))
    println("$passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
