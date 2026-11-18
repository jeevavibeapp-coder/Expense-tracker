import com.jeevavibeapp.spendwise.core.*
import java.time.LocalDateTime

/**
 * K3: when two bank messages are one payment.
 *
 * ALL SMS IN THIS FILE ARE SYNTHETIC: invented amounts, masked accounts and
 * made-up references, written in shapes the parser already reads. None is a
 * real person's message. The 6bd77fb5 fixture at the bottom was computed from
 * the same synthetic corpus by the parser at that commit.
 *
 * The 6bd77fb5 key, amount|payee|reference|day, filed two ₹500 Amazon debits
 * on one day as one row, and a debit and a credit of the same amount and
 * payee as one row. The new identity is the reference, scoped by account and
 * direction, when the message has one, and the message itself (sender, the
 * SMS's own timestamp, text) when it does not. Phones already hold rows
 * stored under the old key, so every identity also answers to that key, and
 * the invariant at the end proves a rescan after the upgrade adds nothing.
 */
private var passed = 0
private var failed = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else { failed++; println("  FAIL  $name${if (detail.isNotEmpty()) "  [$detail]" else ""}") }
}

private const val SENDER = "VM-HDFCBK"
private fun t(iso: String): LocalDateTime = LocalDateTime.parse(iso)

/** The identity the inbox scan and live receipt compute for a delivered SMS. */
private fun sms(body: String, receivedAt: LocalDateTime, sender: String = SENDER): Dedup.Identity {
    val p = Parsing.parse(body)
    return Dedup.identity(p, body, sender, receivedAt, p.occurredAt ?: receivedAt)
}

// Sol's K3 cases, bodies as in sol-harness/SolRegressionTest.kt.
private val SOL_NOW = LocalDateTime.of(2026, 11, 18, 12, 0)
private const val SOL_DEBIT = "INR 500.00 debited from account XX1234 to AMAZON on 18-Nov-2026 at 12:00."
private val SOL_EVENING = SOL_DEBIT.replace("12:00", "18:00")
private const val SOL_CREDIT = "INR 500.00 credited to account XX1234 from AMAZON on 18-Nov-2026 at 12:05."
private const val SOL_AXIS = "INR 500.00 debited from A/C XX1234 on 18-Nov-2026 UPI/P2M/519023481234/ZOMATO/UPI"

private fun capture(body: String, receivedAt: LocalDateTime = SOL_NOW): Pipeline.Outcome.Capture? =
    Pipeline.ingest(
        sender = SENDER, body = body, now = SOL_NOW, lookupSender = { null },
        resolve = { _, _, _ -> Pipeline.Resolution("Amazon", "shopping", 90) },
        receivedAt = receivedAt,
    ) as? Pipeline.Outcome.Capture

fun main() {
    println("=== Sol K3: same-day payments and a debit/credit pair each get a row ===")
    val debit = capture(SOL_DEBIT)
    val evening = capture(SOL_EVENING)
    val credit = capture(SOL_CREDIT)
    check("all three are captured", debit != null && evening != null && credit != null,
        "debit=$debit evening=$evening credit=$credit")
    val kd = debit?.identity?.key
    val ke = evening?.identity?.key
    val kc = credit?.identity?.key
    check("K3-two-purchases: the 12:00 and 18:00 debits have different keys", kd != ke,
        "12:00=$kd 18:00=$ke")
    check("K3-debit-credit: the debit and the credit have different keys", kd != kc,
        "debit=$kd credit=$kc")
    check("three messages, three keys", kd != null && setOf(kd, ke, kc).size == 3,
        "keys=${listOf(kd, ke, kc)}")
    // The scan treats a message as banked when ANY key it answers to is in the
    // ledger, so a different key is not enough: none may be another's alias.
    val three = listOfNotNull(debit, evening, credit).map { it.identity }
    check("and none answers to another's key",
        three.size == 3 && three.all { x -> three.all { y -> x === y || x.key !in y.all } },
        "identities=$three")
    check("K3-replay: the same message twice has the same key",
        kd != null && capture(SOL_DEBIT)?.identity?.key == kd)
    // Why K3 exists: the old key reads all three as one payment.
    val legacy = listOf(SOL_DEBIT, SOL_EVENING, SOL_CREDIT).map { b ->
        val p = Parsing.parse(b); Dedup.legacyKey(p, p.occurredAt ?: SOL_NOW)
    }
    check("the 6bd77fb5 key could not tell them apart",
        legacy.toSet() == setOf("500.0|AMAZON||2026-11-18"), "got=$legacy")

    println("=== no reference: the message itself, within a two-minute window ===")
    val card = "Rs.20.00 spent on Card XX5566 at CHAIPOINT on 08-07-26"
    check("precondition: the card alert carries no reference",
        Parsing.parse(card).referenceNumber == null, "got=${Parsing.parse(card).referenceNumber}")
    val nine = sms(card, t("2026-07-08T09:00"))
    val five = sms(card, t("2026-07-08T17:00"))
    check("a no-reference SMS is keyed as the message", nine.key.startsWith("v2|msg|"), "got=${nine.key}")
    check("the same tea bought at 09:00 and 17:00 gets two keys", nine.key != five.key,
        "09:00=${nine.key} 17:00=${five.key}")
    check("and neither answers to the other's key", nine.key !in five.all && five.key !in nine.all,
        "09:00=${nine.all} 17:00=${five.all}")
    val ten = sms(card, t("2026-07-08T10:00"))
    val quarter = sms(card, t("2026-07-08T10:15"))
    check("15 minutes apart is two payments",
        ten.key != quarter.key && ten.key !in quarter.all && quarter.key !in ten.all,
        "10:00=${ten.all} 10:15=${quarter.all}")
    check("the same delivery read twice has one key", sms(card, t("2026-07-08T09:00")).key == nine.key)
    val before = sms(card, t("2026-07-08T10:01:59"))
    val after = sms(card, t("2026-07-08T10:02:02"))
    check("precondition: 10:01:59 and 10:02:02 fall in different two-minute buckets",
        before.key != after.key, "both=${before.key}")
    check("3 s apart across the bucket edge, each answers to the other's key",
        after.key in before.all && before.key in after.all,
        "10:01:59=${before.all} 10:02:02=${after.all}")

    println("=== with a reference: scoped by account and direction ===")
    val noon = t("2026-07-08T12:00")
    val refDebit = "Rs.750.00 debited from A/c XX1234 on 08-07-26 to MYNTRA. UPI Ref 512345678901"
    val refCredit = "Rs.750.00 credited to A/c XX1234 on 08-07-26 from MYNTRA. UPI Ref 512345678901"
    val otherAccount = "Rs.750.00 debited from A/c XX9876 on 08-07-26 to MYNTRA. UPI Ref 512345678901"
    val pd = Parsing.parse(refDebit)
    val pc = Parsing.parse(refCredit)
    val po = Parsing.parse(otherAccount)
    check("precondition: the debit and the credit share reference, account and amount",
        pd.referenceNumber == "512345678901" && pc.referenceNumber == pd.referenceNumber &&
        pd.account == "1234" && pc.account == pd.account && pc.amount == pd.amount &&
        pd.type == "expense" && pc.type == "income", "debit=$pd credit=$pc")
    val idDebit = sms(refDebit, noon)
    val idCredit = sms(refCredit, noon)
    check("a debit and a credit under one reference are two rows",
        idDebit.key != idCredit.key && idDebit.key !in idCredit.all && idCredit.key !in idDebit.all,
        "debit=${idDebit.all} credit=${idCredit.all}")
    val oldDebit = Dedup.legacyKey(pd, pd.occurredAt ?: noon)
    val oldCredit = Dedup.legacyKey(pc, pc.occurredAt ?: noon)
    check("which the 6bd77fb5 key filed as one", oldDebit == oldCredit,
        "debit=$oldDebit credit=$oldCredit")
    check("precondition: the other debit has the same reference from account 9876",
        po.referenceNumber == pd.referenceNumber && po.account == "9876" && po.type == pd.type,
        "got=$po")
    val idOther = sms(otherAccount, noon)
    check("the same reference from another account is another row",
        idOther.key != idDebit.key && idOther.key !in idDebit.all && idDebit.key !in idOther.all,
        "1234=${idDebit.all} 9876=${idOther.all}")
    // The same payment worded by two rails (the pair in PipelineTest), which
    // arrive as separate SMS minutes apart.
    val viaUpi = sms("Rs.450.00 debited from a/c **1234 on 05-Jan-24 to ZOMATO Ref 883120114455 UPI", noon)
    val viaSms = sms("Rs.450.00 debited to ZOMATO from a/c **1234 on 05-Jan-24. Ref 883120114455",
        t("2026-07-08T12:07"))
    check("two rails describing one payment share a key", viaUpi.key == viaSms.key,
        "upi=${viaUpi.key} sms=${viaSms.key}")
    check("keyed by direction, account and reference",
        viaUpi.key.startsWith("v2|ref|expense|1234|883120114455|"), "got=${viaUpi.key}")
    val swiggy = sms("Rs.450.00 debited from a/c **1234 on 05-Jan-24 to SWIGGY Ref 999999999999 UPI", noon)
    check("a different reference is a different payment",
        swiggy.key != viaUpi.key && swiggy.key !in viaUpi.all && viaUpi.key !in swiggy.all,
        "zomato=${viaUpi.all} swiggy=${swiggy.all}")

    println("=== Axis UPI paths carry the reference (K3-axis-reference) ===")
    val axis = "INR 460.00 debited A/c no. XX1234 08-07-26 UPI/P2M/519023481234/ZOMATO/pay"
    val axisP2a = "INR 460.00 debited A/c no. XX1234 08-07-26 UPI/P2A/519023481234/RAMESH/pay"
    for (body in listOf(axis, SOL_AXIS, axisP2a)) {
        val p = Parsing.parse(body)
        check("the path's reference is the reference: ${body.take(60)}",
            p.referenceNumber == "519023481234", "got=${p.referenceNumber}")
        check("the key keeps 6bd77fb5's reading, none: ${body.take(60)}",
            p.keyReference == null, "got=${p.keyReference}")
    }
    val plain = Parsing.parse("Rs.450.00 debited from a/c **1234 on 05-Jan-24 to ZOMATO Ref 883120114455 UPI")
    check("a plain Ref is the same reference for the key",
        plain.referenceNumber == "883120114455" && plain.keyReference == "883120114455",
        "ref=${plain.referenceNumber} keyRef=${plain.keyReference}")

    println("=== the account a payment moved through ===")
    val accounts = listOf(
        "Rs.100.00 debited from a/c XX1234 on 08-07-26 to SWIGGY. UPI Ref 512345678901" to "1234",
        "Rs.450.00 debited from a/c **1234 on 05-Jan-24 to ZOMATO Ref 883120114455 UPI" to "1234",
        "Dear UPI user A/C X1234 debited by 199.0 on date 08Jul26 trf to SWIGGY Refno 553201998877" to "1234",
        "Rs.340.00 spent using Card xx5566 at DMART on 08-07-26" to "5566",
        "Rs 899 debited from A/c ending 7788 on 08-07-26 to NETFLIX UPI Ref 519023481234" to "7788",
        "Rs.500.00 debited from a/c no. 1234 on 08-07-26 to ZOMATO. Ref 512345678901" to "1234",
        axis to "1234",
        // No mask at all. The transaction ID is a reference, not an account.
        "You paid Rs.150 to Ramesh Kumar using Google Pay. UPI transaction ID: 519023481234" to "",
    )
    for ((body, want) in accounts) {
        val got = Parsing.parse(body).account
        check("account from: ${body.take(50)}", got == want, "want=$want got=$got")
    }

    println("=== a paste, then the inbox scan of the same message ===")
    val bare = "Rs.20.00 debited from A/c XX1234 to CHAIPOINT"
    val bareParsed = Parsing.parse(bare)
    check("precondition: it has no reference and no date",
        bareParsed.referenceNumber == null && bareParsed.occurredAt == null, "got=$bareParsed")
    val pasted = Dedup.identity(bareParsed, bare, null, null, t("2026-07-08T12:00"))
    val k = pasted.key
    check("a paste with no reference is keyed by its text and day",
        k.startsWith("v2|paste|") && k.endsWith("|2026-07-08"), "got=$k")
    val scanned = Dedup.identity(bareParsed, bare, SENDER, t("2026-07-08T12:00"), t("2026-07-08T12:00"))
    check("the scan of the same SMS answers to the paste's key", k in scanned.all,
        "paste=$k scan=${scanned.all}")
    check("a different message does not",
        k !in sms("Rs.30.00 debited from A/c XX1234 to CHAIPOINT", t("2026-07-08T12:00")).all)

    println("=== INVARIANT: rows banked under the 6bd77fb5 key are found after the upgrade ===")
    for (c in PRISTINE_6BD77FB5) {
        val p = Parsing.parse(c.body)
        val got = Dedup.legacyKey(p, p.occurredAt ?: c.receivedAt)
        check("legacy key unchanged: ${c.body.take(45)} @ ${c.receivedAt}", got == c.expected,
            "want=${c.expected} got=$got")
    }
    // The phone before the upgrade: one row per 6bd77fb5 key. The scan after
    // it files a message only when none of the keys it answers to is there.
    val ledger = PRISTINE_6BD77FB5.map { it.expected }.toSet()
    var newRows = 0
    for (c in PRISTINE_6BD77FB5) {
        val p = Parsing.parse(c.body)
        val all = Dedup.identity(p, c.body, SENDER, c.receivedAt, p.occurredAt ?: c.receivedAt).all
        val banked = all.any { it in ledger }
        if (!banked) newRows++
        check("the rescan finds it banked: ${c.body.take(45)} @ ${c.receivedAt}", banked, "all=$all")
    }
    check("a rescan after the upgrade adds zero rows", newRows == 0, "new rows=$newRows")
    println("  invariant: ${PRISTINE_6BD77FB5.size} messages checked against " +
        "${ledger.size} banked 6bd77fb5 keys, $newRows new rows")

    println()
    println("=".repeat(60))
    println("$passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}

/** One message as parser 2026.1.0-kt (6bd77fb5) saw it: the key it banked
 *  [body] under when received at [receivedAt], computed by that commit as
 *  Pipeline.dedupKey(p, p.occurredAt ?: receivedAt). Never edit [expected]. */
private class PristineLegacyKey(val body: String, val receivedAt: LocalDateTime, val expected: String)

private fun L(body: String, receivedAt: String, expected: String) =
    PristineLegacyKey(body, LocalDateTime.parse(receivedAt), expected)

// Synthetic corpus; keys computed at 6bd77fb5. Copied verbatim from
// legacy-fixture.kt.txt.
private val PRISTINE_6BD77FB5 = listOf(
    L("Rs.450.00 debited from a/c **1234 on 05-Jan-24 to ZOMATO Ref 883120114455 UPI", "2026-07-08T12:00", "450.0|ZOMATO|883120114455|2024-01-05"),
    L("Rs.450.00 debited from a/c **1234 on 05-Jan-24 to ZOMATO Ref 883120114455 UPI", "2026-07-09T23:59:59", "450.0|ZOMATO|883120114455|2024-01-05"),
    L("Dear UPI user A/C X1234 debited by 199.0 on date 08Jul26 trf to SWIGGY Refno 553201998877", "2026-07-08T12:00", "199.0|SWIGGY|553201998877|2026-07-08"),
    L("Dear UPI user A/C X1234 debited by 199.0 on date 08Jul26 trf to SWIGGY Refno 553201998877", "2026-07-09T23:59:59", "199.0|SWIGGY|553201998877|2026-07-08"),
    L("ICICI Bank Acct XX823 debited for Rs 320.00 on 08-Jul-26; SWIGGY credited. UPI:519023481234", "2026-07-08T12:00", "320.0|SWIGGY||2026-07-08"),
    L("ICICI Bank Acct XX823 debited for Rs 320.00 on 08-Jul-26; SWIGGY credited. UPI:519023481234", "2026-07-09T23:59:59", "320.0|SWIGGY||2026-07-08"),
    L("INR 460.00 debited A/c no. XX1234 08-07-26 UPI/P2M/519023481234/ZOMATO/pay", "2026-07-08T12:00", "460.0|ZOMATO||2026-07-08"),
    L("INR 460.00 debited A/c no. XX1234 08-07-26 UPI/P2M/519023481234/ZOMATO/pay", "2026-07-09T23:59:59", "460.0|ZOMATO||2026-07-09"),
    L("Sent Rs.20.00 from Kotak Bank AC X1234 to swiggy8@ybl on 08-07-26. UPI Ref 519023481234", "2026-07-08T12:00", "20.0|SWIGGY|519023481234|2026-07-08"),
    L("Sent Rs.20.00 from Kotak Bank AC X1234 to swiggy8@ybl on 08-07-26. UPI Ref 519023481234", "2026-07-09T23:59:59", "20.0|SWIGGY|519023481234|2026-07-08"),
    L("Rs.1500.00 debited from A/c XX4567 on 08-07-26 to RAMESH STORE. UPI Ref 512345678901 -PNB", "2026-07-08T12:00", "1500.0|RAMESH|512345678901|2026-07-08"),
    L("Rs.1500.00 debited from A/c XX4567 on 08-07-26 to RAMESH STORE. UPI Ref 512345678901 -PNB", "2026-07-09T23:59:59", "1500.0|RAMESH|512345678901|2026-07-08"),
    L("Dear Customer, Rs.750.00 debited from A/c XX8899 on 08Jul26 UPI/512345678901/BIGBAZAAR", "2026-07-08T12:00", "750.0|BIGBAZAAR||2026-07-08"),
    L("Dear Customer, Rs.750.00 debited from A/c XX8899 on 08Jul26 UPI/512345678901/BIGBAZAAR", "2026-07-09T23:59:59", "750.0|BIGBAZAAR||2026-07-08"),
    L("INR 2,300.00 debited from YES BANK A/c XX3344 on 08-Jul-26 towards MYNTRA. Ref 883120114455", "2026-07-08T12:00", "2300.0|MYNTRA|883120114455|2026-07-08"),
    L("INR 2,300.00 debited from YES BANK A/c XX3344 on 08-Jul-26 towards MYNTRA. Ref 883120114455", "2026-07-09T23:59:59", "2300.0|MYNTRA|883120114455|2026-07-08"),
    L("Rs 899 debited from IDFC FIRST Bank A/c XX7788 on 08-07-26 to NETFLIX UPI Ref 519023481234", "2026-07-08T12:00", "899.0|NETFLIX|519023481234|2026-07-08"),
    L("Rs 899 debited from IDFC FIRST Bank A/c XX7788 on 08-07-26 to NETFLIX UPI Ref 519023481234", "2026-07-09T23:59:59", "899.0|NETFLIX|519023481234|2026-07-08"),
    L("Rs.340.00 spent using AU Bank Card xx5566 at DMART on 08-07-26", "2026-07-08T12:00", "340.0|DMART||2026-07-08"),
    L("Rs.340.00 spent using AU Bank Card xx5566 at DMART on 08-07-26", "2026-07-09T23:59:59", "340.0|DMART||2026-07-08"),
    L("Rs.120 paid to CHAIWALA from Paytm Payments Bank A/c XX2211. UPI Ref 553201998877", "2026-07-08T12:00", "120.0|CHAIWALA|553201998877|2026-07-08"),
    L("Rs.120 paid to CHAIWALA from Paytm Payments Bank A/c XX2211. UPI Ref 553201998877", "2026-07-09T23:59:59", "120.0|CHAIWALA|553201998877|2026-07-09"),
    L("Paid Rs.240 to SHARMA STORES via PhonePe. UPI Ref 553201998877", "2026-07-08T12:00", "240.0|SHARMA|553201998877|2026-07-08"),
    L("Paid Rs.240 to SHARMA STORES via PhonePe. UPI Ref 553201998877", "2026-07-09T23:59:59", "240.0|SHARMA|553201998877|2026-07-09"),
    L("You paid Rs.150 to Ramesh Kumar using Google Pay. UPI transaction ID: 519023481234", "2026-07-08T12:00", "150.0|RAMESH KUMAR||2026-07-08"),
    L("You paid Rs.150 to Ramesh Kumar using Google Pay. UPI transaction ID: 519023481234", "2026-07-09T23:59:59", "150.0|RAMESH KUMAR||2026-07-09"),
    L("Payment of Rs.349 made to CLOUDTAIL INDIA on 08-07-26 via Amazon Pay UPI. Ref 553201998877", "2026-07-08T12:00", "349.0|CLOUDTAIL|553201998877|2026-07-08"),
    L("Payment of Rs.349 made to CLOUDTAIL INDIA on 08-07-26 via Amazon Pay UPI. Ref 553201998877", "2026-07-09T23:59:59", "349.0|CLOUDTAIL|553201998877|2026-07-08"),
    L("Rs.85 paid to teastall@upi via BHIM. UPI Ref No 512345678901", "2026-07-08T12:00", "85.0|TEASTALL|512345678901|2026-07-08"),
    L("Rs.85 paid to teastall@upi via BHIM. UPI Ref No 512345678901", "2026-07-09T23:59:59", "85.0|TEASTALL|512345678901|2026-07-09"),
    L("Rs.99 spent on your HDFC Bank Card xx1234 at SPOTIFY on 08-07-25. Avl Lmt Rs 45,000", "2026-07-08T12:00", "99.0|SPOTIFY||2025-07-08"),
    L("Rs.99 spent on your HDFC Bank Card xx1234 at SPOTIFY on 08-07-25. Avl Lmt Rs 45,000", "2026-07-09T23:59:59", "99.0|SPOTIFY||2025-07-08"),
    L("Rs.2000.00 withdrawn from A/c XX1234 at ATM on 08-07-26. Avl Bal Rs 15,000.00", "2026-07-08T12:00", "2000.0|ATM||2026-07-08"),
    L("Rs.2000.00 withdrawn from A/c XX1234 at ATM on 08-07-26. Avl Bal Rs 15,000.00", "2026-07-09T23:59:59", "2000.0|ATM||2026-07-08"),
    L("INR 45,000.00 credited to A/c XX1234 by NEFT from ACME PVT LTD on 08-07-26 Ref N123456789012", "2026-07-08T12:00", "45000.0|ACME|N123456789012|2026-07-08"),
    L("INR 45,000.00 credited to A/c XX1234 by NEFT from ACME PVT LTD on 08-07-26 Ref N123456789012", "2026-07-09T23:59:59", "45000.0|ACME|N123456789012|2026-07-08"),
    L("Rs.5,000.00 credited to your A/c XX9012 via IMPS Ref 512345678901 from RAHUL", "2026-07-08T12:00", "5000.0|RAHUL|512345678901|2026-07-08"),
    L("Rs.5,000.00 credited to your A/c XX9012 via IMPS Ref 512345678901 from RAHUL", "2026-07-09T23:59:59", "5000.0|RAHUL|512345678901|2026-07-09"),
    L("INR 62,500.00 credited to A/c XX1234 on 01-07-26 towards SALARY JUL26. Ref 998877665544", "2026-07-08T12:00", "62500.0|A C XX1234|998877665544|2026-07-01"),
    L("INR 62,500.00 credited to A/c XX1234 on 01-07-26 towards SALARY JUL26. Ref 998877665544", "2026-07-09T23:59:59", "62500.0|A C XX1234|998877665544|2026-07-01"),
    L("Rs.599.00 credited to your A/c XX1234 on 08-07-26 from AMAZON refund. Ref 553201998877", "2026-07-08T12:00", "599.0|AMAZON|553201998877|2026-07-08"),
    L("Rs.599.00 credited to your A/c XX1234 on 08-07-26 from AMAZON refund. Ref 553201998877", "2026-07-09T23:59:59", "599.0|AMAZON|553201998877|2026-07-08"),
    L("Rs.199.00 debited from A/c XX1234 for NETFLIX autopay on 08-07-26. UPI Ref 512345678901", "2026-07-08T12:00", "199.0||512345678901|2026-07-08"),
    L("Rs.199.00 debited from A/c XX1234 for NETFLIX autopay on 08-07-26. UPI Ref 512345678901", "2026-07-09T23:59:59", "199.0||512345678901|2026-07-08"),
    L("Rs.100.00 debited from A/c XX1234 on 08-07-26. UPI Ref 512345678901", "2026-07-08T12:00", "100.0|A C XX1234|512345678901|2026-07-08"),
    L("Rs.100.00 debited from A/c XX1234 on 08-07-26. UPI Ref 512345678901", "2026-07-09T23:59:59", "100.0|A C XX1234|512345678901|2026-07-08"),
    L("Rs.5,000.00 credited to your A/c XX9012 on 08-07-26. UPI Ref 512345678901", "2026-07-08T12:00", "5000.0|YOUR|512345678901|2026-07-08"),
    L("Rs.5,000.00 credited to your A/c XX9012 on 08-07-26. UPI Ref 512345678901", "2026-07-09T23:59:59", "5000.0|YOUR|512345678901|2026-07-08"),
    L("INR 62,500.00 credited to A/c XX1234 on 01-07-26 towards SALARY JUL26. Ref 998877665544", "2026-07-08T12:00", "62500.0|A C XX1234|998877665544|2026-07-01"),
    L("INR 62,500.00 credited to A/c XX1234 on 01-07-26 towards SALARY JUL26. Ref 998877665544", "2026-07-09T23:59:59", "62500.0|A C XX1234|998877665544|2026-07-01"),
    L("Rs.750.00 debited from Acct **4321 on 08-07-26. Ref 512345678901", "2026-07-08T12:00", "750.0||512345678901|2026-07-08"),
    L("Rs.750.00 debited from Acct **4321 on 08-07-26. Ref 512345678901", "2026-07-09T23:59:59", "750.0||512345678901|2026-07-08"),
    L("Refund of Rs.500.00 for your order paid via UPI has been credited to your A/c XX1234 on 08-07-26. Ref 512345678901", "2026-07-08T12:00", "500.0|YOUR|512345678901|2026-07-08"),
    L("Refund of Rs.500.00 for your order paid via UPI has been credited to your A/c XX1234 on 08-07-26. Ref 512345678901", "2026-07-09T23:59:59", "500.0|YOUR|512345678901|2026-07-08"),
    L("Rs.250.00 credited to A/c XX1234 on 08-07-26 as refund of the amount paid to AMAZON. UPI Ref 512345678901", "2026-07-08T12:00", "250.0|A C XX1234|512345678901|2026-07-08"),
    L("Rs.250.00 credited to A/c XX1234 on 08-07-26 as refund of the amount paid to AMAZON. UPI Ref 512345678901", "2026-07-09T23:59:59", "250.0|A C XX1234|512345678901|2026-07-08"),
    L("You have received a refund of Rs.120 for the bill paid at DMART. Credited to A/c XX1234 UPI Ref 512345678901", "2026-07-08T12:00", "120.0|DMART|512345678901|2026-07-08"),
    L("You have received a refund of Rs.120 for the bill paid at DMART. Credited to A/c XX1234 UPI Ref 512345678901", "2026-07-09T23:59:59", "120.0|DMART|512345678901|2026-07-09"),
    L("INR 500.00 credited to account XX1234 as refund for the amount paid to AMAZON. UTR 123456789012.", "2026-07-08T12:00", "500.0|ACCOUNT XX1234 AS|123456789012|2026-07-08"),
    L("INR 500.00 credited to account XX1234 as refund for the amount paid to AMAZON. UTR 123456789012.", "2026-07-09T23:59:59", "500.0|ACCOUNT XX1234 AS|123456789012|2026-07-09"),
    L("INR 500.00 credited to account XX1234 on 18-Nov-2026 as refund for an item purchased from AMAZON. UTR 123456789012.", "2026-07-08T12:00", "500.0|ACCOUNT XX1234|123456789012|2026-11-18"),
    L("INR 500.00 credited to account XX1234 on 18-Nov-2026 as refund for an item purchased from AMAZON. UTR 123456789012.", "2026-07-09T23:59:59", "500.0|ACCOUNT XX1234|123456789012|2026-11-18"),
    L("INR 600.00 paid to YOUR SALON on 18-Nov-2026 from account XX1234. UTR 123456789012.", "2026-07-08T12:00", "600.0|YOUR SALON|123456789012|2026-11-18"),
    L("INR 600.00 paid to YOUR SALON on 18-Nov-2026 from account XX1234. UTR 123456789012.", "2026-07-09T23:59:59", "600.0|YOUR SALON|123456789012|2026-11-18"),
    L("INR 500.00 debited from A/C XX1234 on 18-Nov-2026 UPI/P2M/519023481234/ZOMATO/UPI", "2026-07-08T12:00", "500.0|ZOMATO||2026-11-18"),
    L("INR 500.00 debited from A/C XX1234 on 18-Nov-2026 UPI/P2M/519023481234/ZOMATO/UPI", "2026-07-09T23:59:59", "500.0|ZOMATO||2026-11-18"),
    L("INR 500.00 debited from account XX1234 to AMAZON on 18-Nov-2026 at 12:00.", "2026-07-08T12:00", "500.0|AMAZON||2026-11-18"),
    L("INR 500.00 debited from account XX1234 to AMAZON on 18-Nov-2026 at 12:00.", "2026-07-09T23:59:59", "500.0|AMAZON||2026-11-18"),
    L("INR 500.00 debited from account XX1234 to AMAZON on 18-Nov-2026 at 18:00.", "2026-07-08T12:00", "500.0|AMAZON||2026-11-18"),
    L("INR 500.00 debited from account XX1234 to AMAZON on 18-Nov-2026 at 18:00.", "2026-07-09T23:59:59", "500.0|AMAZON||2026-11-18"),
    L("INR 500.00 credited to account XX1234 from AMAZON on 18-Nov-2026 at 12:05.", "2026-07-08T12:00", "500.0|AMAZON||2026-11-18"),
    L("INR 500.00 credited to account XX1234 from AMAZON on 18-Nov-2026 at 12:05.", "2026-07-09T23:59:59", "500.0|AMAZON||2026-11-18"),
    L("Rs.20.00 spent on Card XX5566 at CHAIPOINT on 08-07-26", "2026-07-08T12:00", "20.0|CHAIPOINT||2026-07-08"),
    L("Rs.20.00 spent on Card XX5566 at CHAIPOINT on 08-07-26", "2026-07-09T23:59:59", "20.0|CHAIPOINT||2026-07-08"),
    L("Rs.20.00 debited from A/c XX1234 to CHAIPOINT. UPI Ref 512345678901", "2026-07-08T12:00", "20.0|CHAIPOINT|512345678901|2026-07-08"),
    L("Rs.20.00 debited from A/c XX1234 to CHAIPOINT. UPI Ref 512345678901", "2026-07-09T23:59:59", "20.0|CHAIPOINT|512345678901|2026-07-09"),
    L("Rs.20.00 debited from A/c XX1234 to CHAIPOINT", "2026-07-08T12:00", "20.0|CHAIPOINT||2026-07-08"),
    L("Rs.20.00 debited from A/c XX1234 to CHAIPOINT", "2026-07-09T23:59:59", "20.0|CHAIPOINT||2026-07-09"),
    L("Rs.450.00 debited to ZOMATO from a/c **1234 on 05-Jan-24. Ref 883120114455", "2026-07-08T12:00", "450.0|ZOMATO|883120114455|2024-01-05"),
    L("Rs.450.00 debited to ZOMATO from a/c **1234 on 05-Jan-24. Ref 883120114455", "2026-07-09T23:59:59", "450.0|ZOMATO|883120114455|2024-01-05"),
)
