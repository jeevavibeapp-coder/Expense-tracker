import com.jeevavibeapp.spendwise.core.Parsing

/**
 * The parser held to exactly the evidence the Python original was held to:
 * the same corpus of real bank messages, the same junk corpus, the same
 * edge cases. A port that passes fewer of these is not a port.
 */
private var passed = 0
private var failed = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { passed++ } else { failed++; println("  FAIL  $name${if (detail.isNotEmpty()) "  [$detail]" else ""}") }
}

/** Every one of these is a real transaction and MUST be captured. */
private val BANK_CORPUS = listOf(
    "Rs.450.00 debited from a/c **1234 on 05-Jan-24 to ZOMATO Ref 883120114455 UPI",
    "Dear UPI user A/C X1234 debited by 199.0 on date 08Jul26 trf to SWIGGY Refno 553201998877",
    "ICICI Bank Acct XX823 debited for Rs 320.00 on 08-Jul-26; SWIGGY credited. UPI:519023481234",
    "INR 460.00 debited A/c no. XX1234 08-07-26 UPI/P2M/519023481234/ZOMATO/pay",
    "Sent Rs.20.00 from Kotak Bank AC X1234 to swiggy8@ybl on 08-07-26. UPI Ref 519023481234",
    "Rs.1500.00 debited from A/c XX4567 on 08-07-26 to RAMESH STORE. UPI Ref 512345678901 -PNB",
    "Dear Customer, Rs.750.00 debited from A/c XX8899 on 08Jul26 UPI/512345678901/BIGBAZAAR",
    "INR 2,300.00 debited from YES BANK A/c XX3344 on 08-Jul-26 towards MYNTRA. Ref 883120114455",
    "Rs 899 debited from IDFC FIRST Bank A/c XX7788 on 08-07-26 to NETFLIX UPI Ref 519023481234",
    "Rs.340.00 spent using AU Bank Card xx5566 at DMART on 08-07-26",
    "Rs.120 paid to CHAIWALA from Paytm Payments Bank A/c XX2211. UPI Ref 553201998877",
    "Paid Rs.240 to SHARMA STORES via PhonePe. UPI Ref 553201998877",
    "You paid Rs.150 to Ramesh Kumar using Google Pay. UPI transaction ID: 519023481234",
    "Payment of Rs.349 made to CLOUDTAIL INDIA on 08-07-26 via Amazon Pay UPI. Ref 553201998877",
    "Rs.85 paid to teastall@upi via BHIM. UPI Ref No 512345678901",
    "Rs.99 spent on your HDFC Bank Card xx1234 at SPOTIFY on 08-07-25. Avl Lmt Rs 45,000",
    "Rs.2000.00 withdrawn from A/c XX1234 at ATM on 08-07-26. Avl Bal Rs 15,000.00",
    "INR 45,000.00 credited to A/c XX1234 by NEFT from ACME PVT LTD on 08-07-26 Ref N123456789012",
    "Rs.5,000.00 credited to your A/c XX9012 via IMPS Ref 512345678901 from RAHUL",
    "INR 62,500.00 credited to A/c XX1234 on 01-07-26 towards SALARY JUL26. Ref 998877665544",
    "Rs.599.00 credited to your A/c XX1234 on 08-07-26 from AMAZON refund. Ref 553201998877",
    "Rs.199.00 debited from A/c XX1234 for NETFLIX autopay on 08-07-26. UPI Ref 512345678901",
)

/** All of these carry an amount AND account evidence, and none of them is a
 *  transaction. This is the corpus that keeps junk out of the ledger. */
private val JUNK_WITH_ACCOUNT_EVIDENCE = listOf(
    "Your HDFC Card XX1234 statement: total amount due Rs 12,340.00 by 15-07-26. Ref 553201998877",
    "EMI of Rs 4,500 for loan A/c XX9988 is due on 15-07-26. Ref 512345678901",
    "Payment request of Rs.999 from netflix@icici on your UPI A/c. Ref 512345678901",
    "Rs.199 will be debited from A/c XX1234 on 15-07 for NETFLIX autopay. Ref 512345678901",
    "Txn of Rs.5000 on Card XX1234 at AMAZON was declined on 08-07-26. Ref 553201998877",
    "OTP 456789 for txn of Rs.2,500 on your A/c XX1234. Do not share. Ref 512345678901",
    "Pre-approved personal loan of Rs 5,00,000 on your A/c XX1234. Apply now Ref OFFER123456",
    "Your CIBIL score updated. Get credit card on A/c XX1234. Ref 553201998877",
    "URGENT: A/c XX1234 will be blocked. Verify KYC to receive Rs 10000. Ref 512345678901",
    "Congratulations! Rs 50000 credited to A/c XX1234. Claim now! Ref 553201998877",
    "Recharge of Rs 239 successful for 9876543210. Txn ID 512345678901. Plan validity 28 days",
    "Rs.500 transaction on A/c XX1234 has been reversed on 08-07-26. Ref 512345678901",
    "Your A/c XX1234 balance is Rs 15,230.50 as on 08-07-26",
    "Dear customer, minimum balance in A/c XX1234 is Rs 10,000. Maintain to avoid charges",
)

fun main() {
    println("=== bank corpus: every one must be captured ===")
    for (sms in BANK_CORPUS) {
        val r = Parsing.parse(sms)
        check("missed: ${sms.take(60)}", r.matched, "amount=${r.amount}")
    }

    println("=== junk corpus: none may reach the ledger ===")
    for (sms in JUNK_WITH_ACCOUNT_EVIDENCE) {
        val r = Parsing.parse(sms)
        check("leaked: ${sms.take(60)}", !r.matched, "amount=${r.amount}")
    }

    println("=== amounts ===")
    check("verb-anchored amount with no currency marker",
        Parsing.parse("Dear UPI user A/C X1234 debited by 199.0 on date 08Jul26 trf to SWIGGY Refno 553201998877").amount == 199.0)
    check("thousands separator",
        Parsing.parse("INR 25,000.00 credited to your account from ACME on 01/02/2024 Ref 553201998877").amount == 25000.0)
    check("a balance is not the transaction",
        Parsing.parse("Rs.2000.00 withdrawn from A/c XX1234 at ATM on 08-07-26. Avl Bal Rs 15,000.00").amount == 2000.0)
    check("rupee symbol",
        Parsing.parse("You paid ₹150 to Ramesh Kumar using Google Pay. UPI transaction ID: 519023481234").amount == 150.0)

    println("=== safeAmount rejects what is not money ===")
    check("NaN", Parsing.safeAmount(Double.NaN) == null)
    check("+Infinity", Parsing.safeAmount(Double.POSITIVE_INFINITY) == null)
    check("-Infinity", Parsing.safeAmount(Double.NEGATIVE_INFINITY) == null)
    check("zero", Parsing.safeAmount(0.0) == null)
    check("negative", Parsing.safeAmount(-5.0) == null)
    check("400-digit string does not become Infinity",
        Parsing.safeAmount("9".repeat(400)) == null)
    check("above the ceiling", Parsing.safeAmount(1e13) == null)
    check("a normal amount survives", Parsing.safeAmount("1,234.50") == 1234.50)

    println("=== direction ===")
    check("credit is income",
        Parsing.parse("INR 45,000.00 credited to A/c XX1234 by NEFT from ACME PVT LTD on 08-07-26 Ref N123456789012").type == "income")
    check("debit is expense",
        Parsing.parse("Rs.450.00 debited from a/c **1234 on 05-Jan-24 to ZOMATO Ref 883120114455 UPI").type == "expense")
    check("a message with both verbs is a debit",
        Parsing.parse("ICICI Bank Acct XX823 debited for Rs 320.00 on 08-Jul-26; SWIGGY credited. UPI:519023481234").type == "expense")

    println("=== merchants ===")
    val cases = listOf(
        "Rs.450.00 debited from a/c **1234 on 05-Jan-24 to ZOMATO Ref 883120114455 UPI" to "ZOMATO",
        // The Python original returned "SWIGGY Refno 553201998877" here. That
        // is a bug this port deliberately does NOT reproduce.
        "Dear UPI user A/C X1234 debited by 199.0 on date 08Jul26 trf to SWIGGY Refno 553201998877" to "SWIGGY",
        "INR 460.00 debited A/c no. XX1234 08-07-26 UPI/P2M/519023481234/ZOMATO/pay" to "ZOMATO",
        "Sent Rs.20.00 from Kotak Bank AC X1234 to swiggy8@ybl on 08-07-26. UPI Ref 519023481234" to "swiggy8",
        "Rs.340.00 spent using AU Bank Card xx5566 at DMART on 08-07-26" to "DMART",
        "Paid Rs.240 to SHARMA STORES via PhonePe. UPI Ref 553201998877" to "SHARMA STORES",
    )
    for ((sms, want) in cases) {
        val got = Parsing.parse(sms).rawMerchant
        check("merchant from: ${sms.take(45)}", got == want, "want=$want got=$got")
    }
    check("a date is never a merchant",
        Parsing.parse("Rs.100 debited from A/c XX1234 on 11-JUN-26 Ref 553201998877").rawMerchant != "11-JUN-26")

    println("=== references and dates ===")
    check("reference number",
        Parsing.parse("Paid Rs.240 to SHARMA STORES via PhonePe. UPI Ref 553201998877").referenceNumber == "553201998877")
    val d = Parsing.parse("Rs.450.00 debited from a/c **1234 on 05-Jan-24 to ZOMATO Ref 883120114455 UPI").occurredAt
    check("date 05-Jan-24", d != null && d.year == 2024 && d.monthValue == 1 && d.dayOfMonth == 5, "got=$d")
    val d2 = Parsing.parse("Dear UPI user A/C X1234 debited by 199.0 on date 08Jul26 trf to SWIGGY Refno 553201998877").occurredAt
    // "on date 08Jul26": the Python original returned null here and the
    // transaction was filed under the day it was received instead.
    check("date 08Jul26 after the word 'date'", d2 != null && d2.year == 2026 && d2.monthValue == 7 && d2.dayOfMonth == 8, "got=$d2")

    println("=== normalisation ===")
    check("Devanagari digits become an amount",
        Parsing.parse("Rs.४५० debited from A/c XX1234 to ZOMATO Ref 553201998877").amount == 450.0)
    check("zero-width marks do not defeat the pattern",
        Parsing.parse("Rs.​450.00 debited from A/c XX1234 to ZOMATO Ref 553201998877").amount == 450.0)
    check("full-width text folds to ASCII",
        Parsing.normalizeText("ｒｓ.450") == "rs.450")

    println("=== never captured ===")
    check("an OTP", !Parsing.parse("Your OTP is 4567.").matched)
    check("a personal message", !Parsing.parse("Hey, send me Rs.500 when you can").matched)
    check("empty input", !Parsing.parse("").matched)
    check("null input", !Parsing.parse(null).matched)
    check("an amount with no verb",
        !Parsing.parse("Your A/c XX1234 has Rs.500 Ref 553201998877").matched)
    check("a verb with no account evidence",
        !Parsing.parse("You spent Rs.500 today").matched)

    println()
    println("=".repeat(60))
    println("$passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
