import com.jeevavibeapp.spendwise.core.*
import java.time.LocalDateTime

private var passed = 0
private var failed = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else { failed++; println("  FAIL  $name${if (detail.isNotEmpty()) "  [$detail]" else ""}") }
}

private val NOW = LocalDateTime.of(2026, 6, 17, 12, 0)
private val GENUINE = "Rs.450.00 debited from a/c **1234 on 05-Jan-24 to ZOMATO Ref 883120114455 UPI"
/** The dangerous shape: a message that PARSES as a transaction and would
 *  enter the ledger, wrapped in a scam. A phishing message with no amount is
 *  merely spam — the parser drops it and quarantine never sees it. */
private val PHISH = "Rs.9999.00 debited from A/c XX1234 on 05-Jan-24. " +
                    "Not you? Call 9812345678 immediately to reverse. http://bit.ly/kyc-verify"

private fun run(
    sender: String?, body: String,
    registry: SenderRegistry? = null,
    resolution: Pipeline.Resolution = Pipeline.Resolution("Zomato", "food", 90),
) = Pipeline.ingest(sender, body, NOW, { registry }, { _, _, _ -> resolution })

fun main() {
    println("=== a genuine bank message ===")
    val ok = run("AD-HDFCBK", GENUINE)
    check("is captured", ok is Pipeline.Outcome.Capture, "got=${ok::class.simpleName}")
    if (ok is Pipeline.Outcome.Capture) {
        check("with the amount", ok.parsed.amount == 450.0)
        check("and the merchant", ok.merchantName == "Zomato")
        check("saved without asking when confidence is high",
            ok.status == Pipeline.STATUS_CONFIRMED, "status=${ok.status}")
        check("and does not need a category", !ok.needsCategory)
    }

    println("=== what must never reach the ledger ===")
    check("an OTP", run("AD-HDFCBK", "Your OTP is 4567") is Pipeline.Outcome.NotFinancial)
    check("a personal message",
        run("9812345678", "Hey send me Rs.500") is Pipeline.Outcome.NotFinancial)
    check("a scam with no amount is spam, not a held transaction",
        run("VM-KYCUPD", "URGENT: a/c will be blocked. Verify KYC. Call 9812345678")
            is Pipeline.Outcome.NotFinancial)
    check("a promo", run("AD-HDFCBK",
        "Pre-approved loan of Rs 5,00,000 on A/c XX1234. Apply now Ref OFFER123456")
        is Pipeline.Outcome.NotFinancial)
    val held = run("VM-KYCUPD", PHISH)
    check("a phishing message is HELD, not discarded", held is Pipeline.Outcome.Hold,
        "got=${held::class.simpleName}")
    if (held is Pipeline.Outcome.Hold) {
        check("with a reason a person can act on", held.reason.isNotEmpty())
        check("and the risk that caused it", held.verdict.risk >= 70, "risk=${held.verdict.risk}")
    }

    println("=== a spoofed header cannot inject a transaction ===")
    // The parse gate runs BEFORE the sender is consulted, so a perfect DLT
    // header cannot turn a scam into a ledger entry. It is HELD rather than
    // dropped: it claims Rs 50,000 was credited, which is a scam aimed at
    // this person, and the quarantine screen exists to show them exactly
    // that. What matters is that it never becomes a transaction.
    val spoofed = run("AD-HDFCBK",
        "Congratulations! Rs 50000 credited to A/c XX1234. Claim now! Ref 553201998877")
    check("a prize scam from a perfect header never becomes a transaction",
        spoofed !is Pipeline.Outcome.Capture, "got=${spoofed::class.simpleName}")
    check("and is shown to the user rather than silently dropped",
        spoofed is Pipeline.Outcome.Hold)

    // A pre-debit notice is noise, not an attack: nothing has moved and
    // nobody is being targeted, so it is dropped without bothering anyone.
    check("an autopay pre-debit notice is neither banked nor held",
        run("AD-HDFCBK", "Rs.199 will be debited from A/c XX1234 on 15-07 for NETFLIX autopay. Ref 512345678901")
            is Pipeline.Outcome.NotFinancial)

    println("=== user decisions win ===")
    check("a blocked sender is held even with a clean message",
        run("AD-HDFCBK", GENUINE, SenderRegistry(trust = Senders.TRUST_BLOCKED))
            is Pipeline.Outcome.Hold)
    check("trusting a sender is not a bypass of phishing detection",
        run("AD-HDFCBK", PHISH, SenderRegistry(trust = Senders.TRUST_TRUSTED))
            is Pipeline.Outcome.Hold)

    println("=== an unverified sender makes the app ask more, not less ===")
    val fromMobile = run("9812345678",
        "Rs.450.00 debited from a/c **1234 to ZOMATO Ref 883120114455 UPI")
    val fromBank = run("AD-HDFCBK",
        "Rs.450.00 debited from a/c **1234 to ZOMATO Ref 883120114455 UPI")
    if (fromMobile is Pipeline.Outcome.Capture && fromBank is Pipeline.Outcome.Capture) {
        check("a mobile sender scores below a bank header",
            fromMobile.confidence < fromBank.confidence,
            "mobile=${fromMobile.confidence} bank=${fromBank.confidence}")
    } else {
        // Held is also an acceptable outcome for a bank claim from a mobile.
        check("a bank message from a mobile is at least not auto-saved",
            fromMobile !is Pipeline.Outcome.Capture ||
            (fromMobile as Pipeline.Outcome.Capture).status != Pipeline.STATUS_CONFIRMED)
    }

    println("=== low confidence asks instead of burying ===")
    val unsure = run("AD-HDFCBK", GENUINE, resolution = Pipeline.Resolution(null, null, 55))
    check("a middling score asks for confirmation",
        unsure is Pipeline.Outcome.Capture && unsure.status == Pipeline.STATUS_PENDING,
        "status=${(unsure as? Pipeline.Outcome.Capture)?.status}")
    val unknown = run("AD-HDFCBK", GENUINE, resolution = Pipeline.Resolution(null, null, 5))
    check("an unknown payee goes to review",
        unknown is Pipeline.Outcome.Capture && unknown.status == Pipeline.STATUS_REVIEW)
    check("and is flagged as needing a category",
        unknown is Pipeline.Outcome.Capture && unknown.needsCategory)

    println("=== dedup: one purchase, one row ===")
    val a = run("AD-HDFCBK", GENUINE)
    val b = run("AD-HDFCBK", GENUINE)
    check("the same message twice yields the same key",
        (a as Pipeline.Outcome.Capture).dedupKey == (b as Pipeline.Outcome.Capture).dedupKey)
    // The same payment, worded differently by two rails.
    val viaUpi = run("AD-HDFCBK",
        "Rs.450.00 debited from a/c **1234 on 05-Jan-24 to ZOMATO Ref 883120114455 UPI")
    val viaSms = run("AD-HDFCBK",
        "Rs.450.00 debited to ZOMATO from a/c **1234 on 05-Jan-24. Ref 883120114455")
    check("two rails describing one payment share a key",
        (viaUpi as Pipeline.Outcome.Capture).dedupKey ==
        (viaSms as Pipeline.Outcome.Capture).dedupKey,
        "upi=${viaUpi.dedupKey} sms=${viaSms.dedupKey}")
    val other = run("AD-HDFCBK",
        "Rs.450.00 debited from a/c **1234 on 05-Jan-24 to SWIGGY Ref 999999999999 UPI")
    check("a different purchase does not collide",
        (other as Pipeline.Outcome.Capture).dedupKey != viaUpi.dedupKey)

    println()
    println("=".repeat(60))
    println("$passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
