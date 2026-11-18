import com.jeevavibeapp.spendwise.core.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * Probes for the findings of the independent audit of main @ 2fa8dc0, run
 * against the native port. Each section names the finding it answers. A
 * finding the port already fixes stays here as a regression check; one it
 * does not fix gets its failing case here first, then its fix.
 */
private var passed = 0
private var failed = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else { failed++; println("  FAIL  $name${if (detail.isNotEmpty()) "  [$detail]" else ""}") }
}

private val NOW = LocalDateTime.of(2026, 7, 8, 12, 0)

private fun ingest(body: String, now: LocalDateTime = NOW) = Pipeline.ingest(
    "AD-HDFCBK", body, now, { null }, { _, _, _ -> Pipeline.Resolution("Payee", "food", 90) })

private fun identity(body: String, now: LocalDateTime = NOW): Dedup.Identity? =
    (ingest(body, now) as? Pipeline.Outcome.Capture)?.identity

/** The key parser 2026.1.0-kt (6bd77fb5) banked [body] under. Since K3 it is
 *  an alias rather than the key a new row stores, but rows a phone already
 *  holds are stored under it, so the string must not move. */
private fun legacyKey(body: String, now: LocalDateTime = NOW): String {
    val p = Parsing.parse(body)
    return Dedup.legacyKey(p, p.occurredAt ?: now)
}

fun main() {
    println("=== OTP / failed / reversed / declined: money that did not move ===")
    val notMoved = listOf(
        "OTP 456789 for txn of Rs.2,500 on your A/c XX1234. Do not share. Ref 512345678901",
        "Use 4455 as OTP to pay Rs 999 at FLIPKART from A/c XX1234",
        "Txn of Rs.1,200.00 from A/c XX1234 to SWIGGY failed on 08-07-26. UPI Ref 512345678901",
        "Your payment of Rs.350.00 to ZOMATO has failed. A/c XX1234 UPI Ref 512345678901",
        "Rs.500.00 debited from A/c XX1234 on 08-07-26 has been reversed. Ref 512345678901",
        "Txn of Rs.5000 on Card XX1234 at AMAZON was declined on 08-07-26. Ref 553201998877",
    )
    for (sms in notMoved) {
        val r = ingest(sms)
        check("not banked: ${sms.take(55)}", r !is Pipeline.Outcome.Capture,
            "got=${r::class.simpleName}")
    }

    println("=== account masks are never the amount or the payee ===")
    data class Mask(val sms: String, val amount: Double, val merchant: String?)
    val masks = listOf(
        Mask("Rs.450.00 debited from a/c **1234 on 05-Jan-24 to ZOMATO Ref 883120114455 UPI", 450.0, "ZOMATO"),
        Mask("Dear UPI user A/C X1234 debited by 199.0 on date 08Jul26 trf to SWIGGY Refno 553201998877", 199.0, "SWIGGY"),
        Mask("INR 2,300.00 debited from YES BANK A/c XX3344 on 08-Jul-26 towards MYNTRA. Ref 883120114455", 2300.0, "MYNTRA"),
        Mask("Rs 899 debited from A/c ending 7788 on 08-07-26 to NETFLIX UPI Ref 519023481234", 899.0, "NETFLIX"),
        Mask("Rs.340.00 spent using Card xx5566 at DMART on 08-07-26", 340.0, "DMART"),
        // No payee in the message at all. The payer side ("from A/c XX1234")
        // or the payee side ("to your A/c") must not stand in for one: every
        // payee-less debit from the account would share that name, and one
        // confirmation would teach the engine to file all of them the same way.
        Mask("Rs.100.00 debited from A/c XX1234 on 08-07-26. UPI Ref 512345678901", 100.0, null),
        Mask("Rs.5,000.00 credited to your A/c XX9012 on 08-07-26. UPI Ref 512345678901", 5000.0, null),
        Mask("INR 62,500.00 credited to A/c XX1234 on 01-07-26 towards SALARY JUL26. Ref 998877665544", 62500.0, null),
        Mask("Rs.750.00 debited from Acct **4321 on 08-07-26. Ref 512345678901", 750.0, null),
    )
    for (m in masks) {
        val p = Parsing.parse(m.sms)
        check("amount from: ${m.sms.take(45)}", p.amount == m.amount, "want=${m.amount} got=${p.amount}")
        check("payee from: ${m.sms.take(45)}", p.rawMerchant == m.merchant,
            "want=${m.merchant} got=${p.rawMerchant}")
    }
    // Keys these messages were banked under before the payee fix, computed
    // with the parser at 6bd77fb5. The launch scan finds banked rows by
    // recomputing exactly this string, so it must not move.
    val bankedBefore = listOf(
        "Rs.100.00 debited from A/c XX1234 on 08-07-26. UPI Ref 512345678901" to
            "100.0|A C XX1234|512345678901|2026-07-08",
        "Rs.5,000.00 credited to your A/c XX9012 on 08-07-26. UPI Ref 512345678901" to
            "5000.0|YOUR|512345678901|2026-07-08",
        "INR 62,500.00 credited to A/c XX1234 on 01-07-26 towards SALARY JUL26. Ref 998877665544" to
            "62500.0|A C XX1234|998877665544|2026-07-01",
    )
    for ((sms, want) in bankedBefore) {
        check("dedup key unchanged: ${sms.take(45)}", legacyKey(sms) == want, "want=$want got=${legacyKey(sms)}")
        check("and the capture still answers to it: ${sms.take(45)}",
            identity(sms)?.all?.contains(want) == true, "all=${identity(sms)?.all}")
    }

    println("=== #9: a refund that also says 'paid' is money coming in ===")
    // The debit verb in these describes the original payment, not this
    // message. Filed as an expense, a refund doubles the spend it undoes.
    val refunds = listOf(
        "Refund of Rs.500.00 for your order paid via UPI has been credited to your A/c XX1234 on 08-07-26. Ref 512345678901" to
            "500.0|YOUR|512345678901|2026-07-08",
        "Rs.250.00 credited to A/c XX1234 on 08-07-26 as refund of the amount paid to AMAZON. UPI Ref 512345678901" to
            "250.0|A C XX1234|512345678901|2026-07-08",
        "You have received a refund of Rs.120 for the bill paid at DMART. Credited to A/c XX1234 UPI Ref 512345678901" to
            "120.0|DMART|512345678901|2026-07-08",
    )
    for ((sms, banked) in refunds) {
        val p = Parsing.parse(sms)
        check("captured: ${sms.take(50)}", p.matched)
        check("income: ${sms.take(50)}", p.type == "income", "got=${p.type}")
        check("dedup key unchanged: ${sms.take(45)}", legacyKey(sms) == banked,
            "want=$banked got=${legacyKey(sms)}")
        check("and the capture still answers to it: ${sms.take(45)}",
            identity(sms)?.all?.contains(banked) == true, "all=${identity(sms)?.all}")
    }
    check("the refund keeps its payee", Parsing.parse(refunds[2].first).rawMerchant == "DMART")
    // The rule must not swallow debits that merely mention a credit.
    check("ICICI 'debited …; SWIGGY credited' is still a debit",
        Parsing.parse("ICICI Bank Acct XX823 debited for Rs 320.00 on 08-Jul-26; SWIGGY credited. UPI:519023481234").type == "expense")
    check("a debit that mentions an earlier refund is still a debit",
        Parsing.parse("Rs.300.00 debited from A/c XX1234 on 08-07-26 to MYNTRA, refund of Rs.300 credited earlier. UPI Ref 512345678901").type == "expense")
    check("a plain payment is still a debit",
        Parsing.parse("You paid Rs.150 to Ramesh Kumar using Google Pay. UPI transaction ID: 519023481234").type == "expense")
    check("a refund with no 'paid' was already income",
        Parsing.parse("Rs.599.00 credited to your A/c XX1234 on 08-07-26 from AMAZON refund. Ref 553201998877").type == "income")

    println("=== #11: times are the bank's wall clock, never shifted to UTC ===")
    val late = Parsing.parse("Rs.450.00 debited from A/c XX1234 on 08-07-26 00:30:15 to ZOMATO Ref 883120114455")
    check("00:30 on the 8th stays 00:30 on the 8th",
        late.occurredAt == LocalDateTime.of(2026, 7, 8, 0, 30), "got=${late.occurredAt}")
    // Since K3 a referenced message's own key holds no day; the day-bearing
    // key is the 6bd77fb5 one it still answers to.
    check("and its dedup key is filed under the 8th",
        legacyKey("Rs.450.00 debited from A/c XX1234 on 08-07-26 00:30:15 to ZOMATO Ref 883120114455")
            .endsWith("2026-07-08"))
    val firstOfMonth = Tx("t1", 100.0, "expense", LocalDateTime.of(2026, 7, 1, 0, 10), "Zomato", "food")
    check("a spend at 00:10 on the 1st counts toward the new month",
        Budgets.monthCategorySpend(listOf(firstOfMonth), YearMonth.of(2026, 7),
            LocalDate.of(2026, 7, 1))["food"] == 100.0)
    check("and toward today, not yesterday",
        Budgets.dailySeries(listOf(firstOfMonth), LocalDate.of(2026, 7, 1), days = 1)
            .single().amount == 100.0)

    println("=== #12: one payee name, one answer ===")
    // Learning is one row per raw name (unique index on learning.rawName),
    // so two learned mappings cannot tie. A dead heat in the categoriser is a
    // 50/50 guess, below its floor, so it declines rather than picking one.
    val tie = Categorizer.train(listOf("ALPHA SHOP" to "food", "ALPHA SHOP" to "travel"))
    check("an exact categoriser tie declines instead of guessing",
        tie?.predict("ALPHA SHOP") == null, "got=${tie?.predict("ALPHA SHOP")}")

    println()
    println("=".repeat(60))
    println("$passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
