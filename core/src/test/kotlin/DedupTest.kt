import com.jeevavibeapp.spendwise.core.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

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
 * payee as one row. The new identity is the reference, scoped by account,
 * direction, amount and day, when the message has one (a reference has a
 * digit in it), and the message itself (sender, the SMS's own timestamp in
 * epoch milliseconds, text) when it does not. Phones already hold rows
 * stored under the old key, so every identity also answers to that key, and
 * the invariant at the end proves a rescan after the upgrade adds nothing.
 */
private var passed = 0
private var failed = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else { failed++; println("  FAIL  $name${if (detail.isNotEmpty()) "  [$detail]" else ""}") }
}

private const val SENDER = "VM-HDFCBK"
private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
private fun t(iso: String): LocalDateTime = LocalDateTime.parse(iso)

/** Epoch milliseconds of the wall-clock time [local] in [zone]: when the phone
 *  received a message, as Dedup.identity and Pipeline.ingest take it. */
private fun ms(local: String, zone: ZoneId = IST): Long =
    LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

/** The identity the inbox scan and live receipt compute for an SMS received
 *  at [receivedAtMs] by a phone whose clock is set to [zone]. */
private fun sms(body: String, receivedAtMs: Long, zone: ZoneId = IST,
                sender: String = SENDER): Dedup.Identity {
    val p = Parsing.parse(body)
    val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(receivedAtMs), zone)
    return Dedup.identity(p, body, sender, receivedAtMs, p.occurredAt ?: local)
}

/** The bucket a message key holds: v2|msg|<sender>|<bucket>|<text>. */
private fun bucketOf(key: String): String? =
    key.split("|").takeIf { it.size == 5 && it[0] == "v2" && it[1] == "msg" }?.get(3)

/** The bucket [receivedAtMs] falls in, as K3-b defines it. */
private fun bucket(receivedAtMs: Long): String =
    Math.floorDiv(receivedAtMs, Dedup.WINDOW_MINUTES * 60_000L).toString()

/** Synthetic. No reference and no date: an SMS of it is identified by sender,
 *  receipt time and text, and the day comes from the phone's clock. */
private const val NO_DATE_NO_REF = "Rs.20.00 debited from A/c XX1234 to CHAIPOINT"

// Sol's K3 cases, bodies as in sol-harness/SolRegressionTest.kt.
private val SOL_NOW = LocalDateTime.of(2026, 11, 18, 12, 0)
private val SOL_NOW_MS = SOL_NOW.atZone(IST).toInstant().toEpochMilli()
private const val SOL_DEBIT = "INR 500.00 debited from account XX1234 to AMAZON on 18-Nov-2026 at 12:00."
private val SOL_EVENING = SOL_DEBIT.replace("12:00", "18:00")
private const val SOL_CREDIT = "INR 500.00 credited to account XX1234 from AMAZON on 18-Nov-2026 at 12:05."
private const val SOL_AXIS = "INR 500.00 debited from A/C XX1234 on 18-Nov-2026 UPI/P2M/519023481234/ZOMATO/UPI"

private fun capture(body: String, receivedAtMs: Long = SOL_NOW_MS): Pipeline.Outcome.Capture? =
    Pipeline.ingest(
        sender = SENDER, body = body, now = SOL_NOW, lookupSender = { null },
        resolve = { _, _, _ -> Pipeline.Resolution("Amazon", "shopping", 90) },
        receivedAtMs = receivedAtMs,
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
    // Times below are IST wall-clock readings, passed as epoch milliseconds.
    val nine = sms(card, ms("2026-07-08T09:00"))
    val five = sms(card, ms("2026-07-08T17:00"))
    check("a no-reference SMS is keyed as the message", nine.key.startsWith("v2|msg|"), "got=${nine.key}")
    check("the same tea bought at 09:00 and 17:00 gets two keys", nine.key != five.key,
        "09:00=${nine.key} 17:00=${five.key}")
    check("and neither answers to the other's key", nine.key !in five.all && five.key !in nine.all,
        "09:00=${nine.all} 17:00=${five.all}")
    val ten = sms(card, ms("2026-07-08T10:00"))
    val quarter = sms(card, ms("2026-07-08T10:15"))
    check("15 minutes apart is two payments",
        ten.key != quarter.key && ten.key !in quarter.all && quarter.key !in ten.all,
        "10:00=${ten.all} 10:15=${quarter.all}")
    check("the same delivery read twice has one key", sms(card, ms("2026-07-08T09:00")).key == nine.key)
    // 10:02:00 IST is 04:32:00Z, a multiple of two minutes since the epoch.
    val before = sms(card, ms("2026-07-08T10:01:59"))
    val after = sms(card, ms("2026-07-08T10:02:02"))
    check("precondition: 10:01:59 and 10:02:02 fall in different two-minute buckets",
        before.key != after.key, "both=${before.key}")
    check("3 s apart across the bucket edge, each answers to the other's key",
        after.key in before.all && before.key in after.all,
        "10:01:59=${before.all} 10:02:02=${after.all}")
    // Readings two buckets apart never meet: from the start of a bucket,
    // 4 min 1 s later is two buckets on.
    val edge = ms("2026-07-08T10:02:00")
    val atEdge = sms(card, edge)
    val past = sms(card, edge + 241_000L)
    check("precondition: 4 min 1 s after a bucket starts is two buckets on",
        bucketOf(atEdge.key) == bucket(edge) && bucketOf(past.key) == bucket(edge + 240_000L),
        "edge=${atEdge.key} later=${past.key}")
    check("more than 4 minutes apart, neither answers to the other's key",
        atEdge.key !in past.all && past.key !in atEdge.all,
        "10:02:00=${atEdge.all} 10:06:01=${past.all}")

    println("=== the window is counted in epoch time, not the phone's clock (K3-b) ===")
    // bucket = floorDiv(receivedAtMs, WINDOW_MINUTES * 60 000): the stored key
    // holds exactly that number, whatever zone the phone is set to.
    check("the key holds the epoch bucket of the receipt time",
        bucketOf(nine.key) == bucket(ms("2026-07-08T09:00")),
        "key=${nine.key} want bucket=${bucket(ms("2026-07-08T09:00"))}")
    val instant = Instant.parse("2026-07-08T04:30:00Z").toEpochMilli()
    val kolkata = ZoneId.of("Asia/Kolkata")
    val dubai = ZoneId.of("Asia/Dubai")
    check("precondition: that instant reads 10:00 in Kolkata and 08:30 in Dubai",
        LocalDateTime.ofInstant(Instant.ofEpochMilli(instant), kolkata) == t("2026-07-08T10:00") &&
        LocalDateTime.ofInstant(Instant.ofEpochMilli(instant), dubai) == t("2026-07-08T08:30"))
    // Same SMS, same receipt time; only the zone `at` was computed in differs.
    // Before K3-b the bucket came from the local time, so these were two keys.
    val inKolkata = sms(NO_DATE_NO_REF, instant, kolkata)
    val inDubai = sms(NO_DATE_NO_REF, instant, dubai)
    check("an undated SMS received once has one key in any zone", inKolkata.key == inDubai.key,
        "Kolkata=${inKolkata.key} Dubai=${inDubai.key}")
    check("and that key holds the epoch bucket", bucketOf(inKolkata.key) == bucket(instant),
        "key=${inKolkata.key} want bucket=${bucket(instant)}")
    val cardKolkata = sms(card, instant, kolkata)
    val cardDubai = sms(card, instant, dubai)
    check("so does a dated one", cardKolkata.key == cardDubai.key,
        "Kolkata=${cardKolkata.key} Dubai=${cardDubai.key}")
    // The other way round: on 25 Oct 2026 London's clock reads 01:30 twice,
    // an hour apart. Two identical debits then are two payments.
    val london = ZoneId.of("Europe/London")
    val bst = Instant.parse("2026-10-25T00:30:00Z").toEpochMilli()
    val gmt = Instant.parse("2026-10-25T01:30:00Z").toEpochMilli()
    check("precondition: London reads 01:30 at both instants",
        LocalDateTime.ofInstant(Instant.ofEpochMilli(bst), london) == t("2026-10-25T01:30") &&
        LocalDateTime.ofInstant(Instant.ofEpochMilli(gmt), london) == t("2026-10-25T01:30"))
    val firstHalfPast = sms(NO_DATE_NO_REF, bst, london)
    val secondHalfPast = sms(NO_DATE_NO_REF, gmt, london)
    check("an hour apart is two payments though the clock read 01:30 both times",
        firstHalfPast.key != secondHalfPast.key &&
        firstHalfPast.key !in secondHalfPast.all && secondHalfPast.key !in firstHalfPast.all,
        "BST=${firstHalfPast.all} GMT=${secondHalfPast.all}")

    println("=== with a reference: scoped by account and direction ===")
    val noon = ms("2026-07-08T12:00")
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
    val oldDebit = Dedup.legacyKey(pd, pd.occurredAt ?: t("2026-07-08T12:00"))
    val oldCredit = Dedup.legacyKey(pc, pc.occurredAt ?: t("2026-07-08T12:00"))
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
        ms("2026-07-08T12:07"))
    check("two rails describing one payment share a key", viaUpi.key == viaSms.key,
        "upi=${viaUpi.key} sms=${viaSms.key}")
    // v2|ref|<type>|<account>|<reference>|<amount>|<day>, day = at's date.
    check("keyed by direction, account, reference, amount and day",
        viaUpi.key == "v2|ref|expense|1234|883120114455|450.00|2024-01-05", "got=${viaUpi.key}")
    val swiggy = sms("Rs.450.00 debited from a/c **1234 on 05-Jan-24 to SWIGGY Ref 999999999999 UPI", noon)
    check("a different reference is a different payment",
        swiggy.key != viaUpi.key && swiggy.key !in viaUpi.all && viaUpi.key !in swiggy.all,
        "zomato=${viaUpi.all} swiggy=${swiggy.all}")

    println("=== a reference names a payment on one day (K3-b) ===")
    // Synthetic. A loan reference repeats on every instalment: the same EMI of
    // the same amount from the same account a month apart is two payments.
    val emiJul = "Rs.4,500.00 debited from A/c XX1234 on 08-07-26 towards EMI Ref LN00123456"
    val emiAug = emiJul.replace("08-07-26", "08-08-26")
    val pj = Parsing.parse(emiJul)
    val pa = Parsing.parse(emiAug)
    check("precondition: both carry LN00123456 from 1234, dated 8 Jul and 8 Aug",
        pj.referenceNumber == "LN00123456" && pa.referenceNumber == pj.referenceNumber &&
        pj.account == "1234" && pa.account == "1234" && pj.type == "expense" && pa.type == "expense" &&
        pj.amount == 4500.0 && pa.amount == 4500.0 &&
        pj.occurredAt == t("2026-07-08T00:00") && pa.occurredAt == t("2026-08-08T00:00"),
        "jul=$pj aug=$pa")
    val idJul = sms(emiJul, ms("2026-07-08T09:00"))
    val idAug = sms(emiAug, ms("2026-08-08T09:00"))
    check("the July and August instalments are two rows",
        idJul.key != idAug.key && idJul.key !in idAug.all && idAug.key !in idJul.all,
        "jul=${idJul.all} aug=${idAug.all}")
    check("the July key ends with its day",
        idJul.key == "v2|ref|expense|1234|LN00123456|4500.00|2026-07-08", "got=${idJul.key}")
    check("the August key ends with its day",
        idAug.key == "v2|ref|expense|1234|LN00123456|4500.00|2026-08-08", "got=${idAug.key}")
    check("a dated referenced SMS read again days later keeps its key",
        sms(emiJul, ms("2026-07-10T23:00")).key == idJul.key)
    // 6bd77fb5's keys for these two, computed at that commit.
    check("their 6bd77fb5 keys are unchanged",
        Dedup.legacyKey(pj, pj.occurredAt!!) == "4500.0|EMI|LN00123456|2026-07-08" &&
        Dedup.legacyKey(pa, pa.occurredAt!!) == "4500.0|EMI|LN00123456|2026-08-08",
        "jul=${Dedup.legacyKey(pj, pj.occurredAt!!)} aug=${Dedup.legacyKey(pa, pa.occurredAt!!)}")
    check("and each still answers to its own",
        "4500.0|EMI|LN00123456|2026-07-08" in idJul.all && "4500.0|EMI|LN00123456|2026-08-08" in idAug.all,
        "jul=${idJul.all} aug=${idAug.all}")

    println("=== a reference has a digit in it (K3-b) ===")
    // Synthetic. "Txn amount" is a label: REF reads "txn" and takes the next
    // run of six or more letters, so today referenceNumber == "amount" and the
    // key is v2|ref|expense|1234|amount|500.00 for every such debit.
    val txn = "Txn amount Rs.500.00 debited from A/c XX1234 to SHOP on 08-07-26"
    val ptx = Parsing.parse(txn)
    check("precondition: a matched debit of 500.00 from 1234 to SHOP on 8 Jul",
        ptx.amount == 500.0 && ptx.type == "expense" && ptx.matched && ptx.account == "1234" &&
        ptx.rawMerchant == "SHOP" && ptx.occurredAt == t("2026-07-08T00:00"), "got=$ptx")
    check("a word with no digit in it is not a reference", ptx.referenceNumber == null,
        "got=${ptx.referenceNumber}")
    check("the key keeps 6bd77fb5's reading of it", ptx.keyReference == "amount",
        "got=${ptx.keyReference}")
    // Computed at 6bd77fb5.
    check("so its 6bd77fb5 key is unchanged",
        Dedup.legacyKey(ptx, ptx.occurredAt!!) == "500.0|SHOP|amount|2026-07-08",
        "got=${Dedup.legacyKey(ptx, ptx.occurredAt!!)}")
    val txnNoon = sms(txn, ms("2026-07-08T12:00"))
    check("it is identified as the message", txnNoon.key.startsWith("v2|msg|"), "got=${txnNoon.key}")
    check("and answers to no reference key", txnNoon.all.none { it.startsWith("v2|ref|") },
        "all=${txnNoon.all}")
    check("but still to its 6bd77fb5 key", "500.0|SHOP|amount|2026-07-08" in txnNoon.all,
        "all=${txnNoon.all}")
    val txnPaste = Dedup.identity(ptx, txn, null, null, ptx.occurredAt!!)
    check("a paste of it is keyed by its text and day",
        txnPaste.key.startsWith("v2|paste|") && txnPaste.key.endsWith("|2026-07-08"), "got=${txnPaste.key}")
    val txnEvening = sms(txn, ms("2026-07-08T18:00"))
    check("the same debit at 12:00 and 18:00 is two rows",
        txnNoon.key != txnEvening.key && txnNoon.key !in txnEvening.all && txnEvening.key !in txnNoon.all,
        "12:00=${txnNoon.all} 18:00=${txnEvening.all}")
    // A reference with letters still counts when it has a digit.
    for ((body, want) in listOf(
        "INR 45,000.00 credited to A/c XX1234 by NEFT from ACME PVT LTD on 08-07-26 Ref N123456789012" to
            "N123456789012",
        emiJul to "LN00123456",
        "Rs.450.00 debited from a/c **1234 on 05-Jan-24 to ZOMATO Ref 883120114455 UPI" to "883120114455",
    )) {
        val p = Parsing.parse(body)
        check("still a reference: $want", p.referenceNumber == want && p.keyReference == want,
            "ref=${p.referenceNumber} keyRef=${p.keyReference}")
        check("and keyed by it: $want", sms(body, noon).key.startsWith("v2|ref|${p.type}|${p.account}|$want|"),
            "got=${sms(body, noon).key}")
    }

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
    val bare = NO_DATE_NO_REF
    val bareParsed = Parsing.parse(bare)
    check("precondition: it has no reference and no date",
        bareParsed.referenceNumber == null && bareParsed.occurredAt == null, "got=$bareParsed")
    val pasted = Dedup.identity(bareParsed, bare, null, null, t("2026-07-08T12:00"))
    val k = pasted.key
    check("a paste with no reference is keyed by its text and day",
        k.startsWith("v2|paste|") && k.endsWith("|2026-07-08"), "got=$k")
    val scanned = Dedup.identity(bareParsed, bare, SENDER, ms("2026-07-08T12:00"), t("2026-07-08T12:00"))
    check("the scan of the same SMS answers to the paste's key", k in scanned.all,
        "paste=$k scan=${scanned.all}")
    check("a different message does not",
        k !in sms("Rs.30.00 debited from A/c XX1234 to CHAIPOINT", ms("2026-07-08T12:00")).all)

    println("=== INVARIANT: rows banked under the 6bd77fb5 key are found after the upgrade ===")
    for (c in PRISTINE_6BD77FB5) {
        val p = Parsing.parse(c.body)
        val got = Dedup.legacyKey(p, p.occurredAt ?: c.receivedAt)
        check("legacy key unchanged: ${c.body.take(45)} @ ${c.receivedAt}", got == c.expected,
            "want=${c.expected} got=$got")
    }
    // The phone before the upgrade: one row per 6bd77fb5 key. The scan after
    // it files a message only when none of the keys it answers to is there.
    // receivedAt is the IST wall clock the key was banked under; the scan
    // reads the same moment as epoch milliseconds.
    val ledger = PRISTINE_6BD77FB5.map { it.expected }.toSet()
    var newRows = 0
    for (c in PRISTINE_6BD77FB5) {
        val p = Parsing.parse(c.body)
        val receivedAtMs = c.receivedAt.atZone(IST).toInstant().toEpochMilli()
        val all = Dedup.identity(p, c.body, SENDER, receivedAtMs, p.occurredAt ?: c.receivedAt).all
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
