import com.jeevavibeapp.spendwise.core.Categorizer
import com.jeevavibeapp.spendwise.core.Engine
import com.jeevavibeapp.spendwise.core.LearningRow
import com.jeevavibeapp.spendwise.core.SenderRegistry
import com.jeevavibeapp.spendwise.core.Senders
import java.time.LocalDateTime

private var passed = 0
private var failed = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else { failed++; println("  FAIL  $name${if (detail.isNotEmpty()) "  [$detail]" else ""}") }
}

private fun row(
    conf: Int = 0, corr: Int = 0, samples: Int = 0, cat: String? = null,
    avg: Double = 0.0, lo: Double = 0.0, hi: Double = 0.0,
    hours: List<Int> = emptyList(),
) = LearningRow("raw", "Merchant", cat, conf, corr, samples, avg, lo, hi, hours)

fun main() {
    println("=== merchant normalisation: every spelling folds to one key ===")
    val variants = listOf("SWIGGY", "swiggy", "Swiggy8", "swiggy8@ybl",
                          "UPI/P2M/SWIGGY", "SWIGGY PVT LTD", "SWIGGY-123456")
    for (v in variants) {
        check("$v -> SWIGGY", Engine.normalizeMerchant(v) == "SWIGGY",
              "got=${Engine.normalizeMerchant(v)}")
    }
    // "STORES" is a deliberate noise token, so "SHARMA" and "SHARMA STORES"
    // fold to one key and share the learning the user did once. The trailing
    // reference number is dropped for the same reason.
    check("a trailing reference is not part of the name",
        Engine.normalizeMerchant("SHARMA STORES 553201998877") == "SHARMA",
        Engine.normalizeMerchant("SHARMA STORES 553201998877"))
    check("a merchant and its 'stores' spelling share one key",
        Engine.normalizeMerchant("SHARMA") == Engine.normalizeMerchant("SHARMA STORES"))
    check("empty stays empty", Engine.normalizeMerchant("") == "")
    check("null stays empty", Engine.normalizeMerchant(null) == "")

    println("=== seeds: a fresh install already knows the obvious ones ===")
    check("SWIGGY is food", Engine.seedLookup("SWIGGY")?.second == "Food & Dining")
    check("UBER is transport", Engine.seedLookup("UBER")?.second == "Transport")
    check("multi-token seed", Engine.seedLookup("BIG BASKET")?.first == "BigBasket")
    check("an unknown merchant is not invented", Engine.seedLookup("RANDOMSHOP") == null)

    println("=== scoring ===")
    val fresh = Engine.score(row(), 100.0, null, null)
    check("an unseen payee scores low", fresh.total < 30, "total=${fresh.total}")
    val trusted = Engine.score(row(conf = 5, samples = 4, cat = "c1", avg = 250.0,
                                   lo = 200.0, hi = 300.0), 250.0, "c1", null)
    check("a well-learned payee scores high", trusted.total >= 80, "total=${trusted.total}")
    check("score is never above 100", trusted.total <= 100)

    val corrected = Engine.score(row(conf = 1, corr = 4, samples = 2), 100.0, null, null)
    val uncorrected = Engine.score(row(conf = 1, corr = 0, samples = 2), 100.0, null, null)
    check("corrections lower confidence", corrected.total < uncorrected.total,
          "corrected=${corrected.total} clean=${uncorrected.total}")

    val near = Engine.score(row(samples = 3, avg = 250.0, lo = 200.0, hi = 300.0), 250.0, null, null)
    val far = Engine.score(row(samples = 3, avg = 250.0, lo = 200.0, hi = 300.0), 9000.0, null, null)
    check("a familiar amount scores above a wild one", near.total > far.total,
          "near=${near.total} far=${far.total}")

    val wrongCat = Engine.score(row(conf = 3, samples = 3, cat = "c1"), null, "c2", null)
    val rightCat = Engine.score(row(conf = 3, samples = 3, cat = "c1"), null, "c1", null)
    check("the right category scores above the wrong one", rightCat.total > wrongCat.total)

    val hours = MutableList(24) { 0 }.also { it[13] = 10 }
    val onTime = Engine.score(row(samples = 3, hours = hours), null, null,
                              LocalDateTime.of(2026, 1, 1, 13, 0))
    val offTime = Engine.score(row(samples = 3, hours = hours), null, null,
                               LocalDateTime.of(2026, 1, 1, 3, 0))
    check("the usual hour scores above an odd one", onTime.total > offTime.total)

    println("=== decisions ===")
    check("auto above the threshold", Engine.decide(95, 80, 50) == Engine.DECISION_AUTO)
    check("confirm in between", Engine.decide(60, 80, 50) == Engine.DECISION_CONFIRM)
    check("manual below", Engine.decide(20, 80, 50) == Engine.DECISION_MANUAL)
    check("exactly at auto is auto", Engine.decide(80, 80, 50) == Engine.DECISION_AUTO)

    println("=== token overlap finds learning done under another spelling ===")
    check("SWIGGY INSTAMART overlaps SWIGGY",
        Engine.tokenOverlap("SWIGGY INSTAMART", "SWIGGY") > 0.0)
    check("unrelated names do not overlap",
        Engine.tokenOverlap("SWIGGY", "NETFLIX") == 0.0)

    println("=== explanations are for a person, not a log ===")
    val reasons = Engine.explain(trusted, row(conf = 5, samples = 4, cat = "c1"))
    check("a learned payee explains itself", reasons.isNotEmpty())
    check("an unseen payee says so",
        Engine.explain(fresh, row()).any { it.contains("No history") })
    check("a seed says it is built in",
        Engine.explain(fresh, null, seeded = true).any { it.contains("built-in") })

    println("=== sender identity ===")
    val hdfc = Senders.identify("AD-HDFCBK")
    check("a DLT header is recognised", hdfc.kind == "dlt" && hdfc.bank == "HDFC Bank")
    check("and scores high", hdfc.base >= 90)
    val unknownDlt = Senders.identify("AD-NEWBNK")
    check("an unlisted DLT header is still accept-worthy", unknownDlt.base >= 70,
          "base=${unknownDlt.base}")
    val mobile = Senders.identify("+919812345678")
    check("a personal mobile is the weakest sender", mobile.kind == "mobile" && mobile.base <= 10)
    check("mobile forms normalise to one sender",
        Senders.normalizeSender("+919812345678") == Senders.normalizeSender("09812345678") &&
        Senders.normalizeSender("9812345678") == Senders.normalizeSender("+919812345678"))
    check("invisible characters cannot fork a sender",
        Senders.normalizeSender("AD-HDFC​BK") == "AD-HDFCBK")
    check("a missing sender is not treated as hostile",
        Senders.identify(null).base >= 60)

    println("=== phishing: the toll-free false positive that broke real alerts ===")
    val genuine = "Rs.450.00 debited from a/c **1234 on 05-Jan-24 to ZOMATO. " +
                  "Not you? Call 18002586161"
    val (genuineRisk, _) = Senders.phishingIndicators(genuine)
    check("a real alert with a toll-free callback stays low risk", genuineRisk < 45,
          "risk=$genuineRisk")
    val fraud = "URGENT: Your a/c will be blocked. Call 9812345678 immediately to reverse."
    val (fraudRisk, fraudFlags) = Senders.phishingIndicators(fraud)
    check("a mobile callback is caught", fraudRisk >= 45, "risk=$fraudRisk")
    check("and named", "callback_mobile_number" in fraudFlags)
    val (otpRisk, otpFlags) = Senders.phishingIndicators("Share your OTP to verify KYC")
    check("credential harvesting is caught", otpRisk >= 55 && "credential_request" in otpFlags)
    check("a shortener is caught",
        "url_shortener" in Senders.phishingIndicators("Click http://bit.ly/x").second)

    println("=== verdicts ===")
    check("a genuine bank alert is accepted",
        Senders.assess("AD-HDFCBK", genuine).action == Senders.ACTION_ACCEPT)
    check("a phishing message is quarantined",
        Senders.assess("VM-KYCUPD", fraud).action == Senders.ACTION_QUARANTINE)
    check("a bank claim from a mobile is quarantined",
        Senders.assess("9812345678", "Your bank a/c is blocked, call now").action
            == Senders.ACTION_QUARANTINE)
    check("a blocked sender stays blocked whatever it says",
        Senders.assess("AD-HDFCBK", genuine, SenderRegistry(trust = Senders.TRUST_BLOCKED))
            .action == Senders.ACTION_QUARANTINE)
    check("trusting a sender is not a blanket bypass of phishing detection",
        Senders.assess("AD-HDFCBK", "Share your OTP and PIN to verify KYC now. " +
            "Click http://bit.ly/x or your account will be blocked",
            SenderRegistry(trust = Senders.TRUST_TRUSTED)).action == Senders.ACTION_QUARANTINE)
    check("an explanation is offered for a held message",
        Senders.explain(Senders.assess("VM-KYCUPD", fraud)).isNotEmpty())

    println("=== categoriser ===")
    check("too little data trains nothing",
        Categorizer.train(listOf("swiggy" to "food", "uber" to "travel")) == null)
    val corpus = buildList {
        repeat(8) { add("swiggy order $it" to "food") }
        repeat(8) { add("uber ride $it" to "travel") }
    }
    val model = Categorizer.train(corpus)
    check("enough data trains a model", model != null)
    if (model != null) {
        check("it predicts a seen merchant", model.predict("swiggy")?.first == "food",
              "got=${model.predict("swiggy")}")
        check("and the other one", model.predict("uber")?.first == "travel")
        check("a probability, not a score",
            (model.predict("swiggy")?.second ?: 0.0) in 0.0..1.0)
        check("it declines when it has nothing to go on",
            model.predict("") == null && model.predict("zzz qqq") == null)
        check("it can say which words decided it",
            model.explain("swiggy order").isNotEmpty())
    }
    check("one category cannot discriminate",
        Categorizer.train(List(20) { "swiggy $it" to "food" }) == null)
    check("digits and stopwords are not features",
        Categorizer.tokens("Paid to SWIGGY ref 553201998877").let {
            "553201998877" !in it && "to" !in it && "swiggy" in it
        })

    println()
    println("=".repeat(60))
    println("$passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
