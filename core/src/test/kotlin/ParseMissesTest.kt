import com.jeevavibeapp.spendwise.core.*
import com.jeevavibeapp.spendwise.core.ParseMisses.Source
import java.time.LocalDateTime

/**
 * The unreadable-message log: what counts as a miss, and what of a miss is
 * allowed to exist anywhere.
 *
 * The redaction checks are written as an attacker would: every value class
 * a bank SMS carries, in every spelling seen in the wild, plus the tricks
 * that defeat naive masking — digits in other scripts, invisible marks
 * splitting a number, a name that happens to be an English word.
 */
private var passed = 0
private var failed = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else { failed++; println("  FAIL  $name${if (detail.isNotEmpty()) "  [$detail]" else ""}") }
}

private val NOW = LocalDateTime.of(2026, 7, 8, 12, 0)

private fun sms(sender: String?, body: String, history: ParseMisses.History? = null) =
    ParseMisses.judge(Source.SMS, sender, body, history)

private fun pasted(body: String) = ParseMisses.judge(Source.PASTE, null, body)

private val PLACEHOLDER = Regex("<(?:amount|account|upi|email|link|ref|name|word)>")
private val COMPOUND = Regex("(?i)(?<![\\p{L}\\p{N}])(?:a/c|m/s|w/d|p2[pma])(?![\\p{L}\\p{N}])")
private val LETTERS = Regex("[\\p{L}\\p{M}]+(?:['’][\\p{L}\\p{M}]+)*")

/** Words in a shape that are not ours and not on the allowlist. */
private fun strangers(shape: String): List<String> =
    LETTERS.findAll(COMPOUND.replace(PLACEHOLDER.replace(shape, " "), " "))
        .map { it.value }.filter { it.lowercase() !in ParseMisses.VOCABULARY }.toList()

/** Rail keywords such as P2M are fixed protocol words, not values. */
private fun anyDigit(s: String) = COMPOUND.replace(s, " ").any { Character.isDigit(it) ||
    Character.getType(it).toByte() == Character.OTHER_NUMBER ||
    Character.getType(it).toByte() == Character.LETTER_NUMBER }

private fun leaks(shape: String, secret: String): Boolean =
    Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(secret) + "(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE).containsMatchIn(shape)

/** Messages a bank really sends that this parser cannot read, each for a
 *  different one of its three gates. */
private val UNREADABLE = listOf(
    // amount in a form the parser has no pattern for
    Triple("AD-HDFCBK", "Your a/c no. XX1234 has been charged INR:236.00 towards SMS charges",
        ParseMisses.REASON_NO_AMOUNT),
    Triple("BZ-KARBNK", "A/c XX1234 debited 500.00 on 08-07-26. Avl Bal 12,000.00",
        ParseMisses.REASON_NO_AMOUNT),
    // a money verb the parser does not know
    Triple("VM-SBIINB", "Rs 500 deducted from your A/c XX1234 on 12-07-26 towards annual fee",
        ParseMisses.REASON_NO_VERB),
    Triple("AX-ICICIB", "Your transaction of INR 2,500.00 at AMAZON on card XX1234 is successful",
        ParseMisses.REASON_NO_VERB),
    // a wallet that cites no account at all
    Triple("VM-PAYTMB", "Rs.120 paid to CHAIWALA from your wallet. Thank you",
        ParseMisses.REASON_NO_ACCOUNT),
)

/** What a bank sender sends most, and none of it is a reading failure. */
private val NOT_MISSES = listOf(
    "OTP 456789 for txn of Rs.2,500 on your A/c XX1234. Do not share.",
    "123456 is the verification code for your transaction of INR 500.00 with card ending 1234",
    "Use 4455 as your OTP to pay Rs 999 at FLIPKART",
    "Pre-approved personal loan of Rs 5,00,000 on your A/c XX1234. Apply now",
    "Shop for Rs 5,000 with your HDFC Card XX1234 this weekend and win big",
    "Get 10% cashback up to Rs 150 when you pay with your card XX1234",
    "Rs.199 will be debited from A/c XX1234 on 15-07 for NETFLIX autopay",
    "Your credit card bill of Rs 5,000 is due on 15-07-26",
    "Txn of Rs.5000 on Card XX1234 at AMAZON was declined on 08-07-26",
    "Avl Bal in A/c XX1234 is Rs 15,230.50 as on 08-07-26",
    "Your debit card XX1234 expires on 07/26. A new card is on its way",
    "Your A/c XX1234 statement for July has been sent to your registered email",
    "Your cheque book for A/c XX1234 has been dispatched",
    "Dear Customer, minimum balance in A/c XX1234 is Rs 10,000. Maintain to avoid charges",
    "Rs.500 transaction on A/c XX1234 has been reversed on 08-07-26",
)

private val GENUINE = "Rs.450.00 debited from a/c **1234 on 05-Jan-24 to ZOMATO Ref 883120114455 UPI"
private val PHISH = "Rs.9999.00 debited from A/c XX1234 on 05-Jan-24. " +
                    "Not you? Call 9812345678 immediately to reverse. http://bit.ly/kyc-verify"

/** Body, then the things that must not survive it. */
private val ADVERSARIAL: List<Pair<String, List<String>>> = listOf(
    // account numbers, every shape
    "Rs.100 debited from A/c XX1234" to listOf("XX1234"),
    "Rs.100 debited from a/c **5678" to listOf("5678"),
    "Rs.100 debited from A/C X9218" to listOf("X9218", "9218"),
    "Rs.100 debited from a/c no. 50100123456789" to listOf("50100123456789"),
    "Rs.100 debited from Acct ending 4521" to listOf("4521"),
    "Rs.100 debited from account ending in 7788" to listOf("7788"),
    "Rs.100 debited from A/c ...3344" to listOf("3344"),
    "Rs.100 debited from A/c #6655" to listOf("6655"),
    "Rs.100 debited from A/c XX-9911 via IMPS" to listOf("9911"),
    "Rs.100 debited from savings account 000123456789 on 08-07-26" to listOf("000123456789"),
    // card numbers and last-4
    "Rs.100 spent on card no. 4111 1111 1111 1111" to listOf("4111", "1111"),
    "Rs.100 spent on Card XXXX XXXX XXXX 9876" to listOf("9876"),
    "Rs.100 spent on Credit Card 2233 at DMART" to listOf("2233", "DMART"),
    "Rs.100 spent on card xx5566" to listOf("xx5566", "5566"),
    "Rs.100 spent on card ending with 0042" to listOf("0042"),
    // UPI ids
    "Rs.100 sent to swiggy8@ybl" to listOf("swiggy8", "ybl", "swiggy8@ybl"),
    "Rs.100 sent to john.doe@okicici." to listOf("john", "doe", "okicici"),
    "Rs.100 received from 9812345678@paytm" to listOf("9812345678", "paytm"),
    "Rs.100 sent to VPA j_d-2@oksbi" to listOf("j_d-2", "oksbi"),
    "Rs.100 sent to ra***sh@okaxis" to listOf("okaxis", "ra***sh"),
    // names after the markers banks use
    "Rs.100 paid to RAMESH KUMAR on 08-07-26" to listOf("RAMESH", "KUMAR"),
    "Rs.100 received from Priya Sharma" to listOf("Priya", "Sharma"),
    "Rs.100 credited by Mr. A. K. Sharma via NEFT" to listOf("K", "Sharma", "A"),
    "Rs.100 paid to M/S SHARMA TRADERS & SONS" to listOf("SHARMA", "TRADERS", "SONS"),
    "Rs.100 paid to rahul" to listOf("rahul"),
    "Rs.100 paid to रमेश कुमार" to
        listOf("रमेश", "कुमार"),
    "Dear JUNE SMITH, Rs 500 credited to your a/c" to listOf("JUNE", "SMITH"),
    "Rs.100 sent to MRS SMT KAVITHA" to listOf("KAVITHA"),
    "Rs.100 paid towards RENT FOR FLAT" to listOf("RENT", "FLAT"),
    "Rs.100 paid to beneficiary ANAND SHARMA" to listOf("ANAND", "SHARMA"),
    // names that are also English words — the vocabulary alone would keep these
    "Rs 500 paid to APRIL MAY" to listOf("APRIL", "MAY"),
    "APRIL has sent you Rs 500" to listOf("APRIL"),
    "BILL GATES has sent you Rs 100" to listOf("BILL", "GATES"),
    "Rs 500 received from WILL SMITH" to listOf("WILL", "SMITH"),
    // names with no marker at all
    "RAMESH KUMAR has sent you Rs.500 on Google Pay" to listOf("RAMESH", "KUMAR", "Google"),
    "Info: UPI/P2A/512345678901/RAMESH KUMAR/HDFC" to listOf("RAMESH", "KUMAR", "HDFC"),
    "NEFT-HDFC0001234-SURESH NAIR-salary credit Rs 9000" to listOf("SURESH", "NAIR", "HDFC"),
    // amounts, with and without a currency, and balances
    "Rs.450.00 debited" to listOf("450"),
    "Rs 1,23,456.78 debited" to listOf("23", "456"),
    "INR 500 credited" to listOf("500"),
    "₹1,500.50 debited" to listOf("1,500"),
    "500 Rs paid" to listOf("500"),
    "750/- received" to listOf("750"),
    "rupees 900 spent" to listOf("900"),
    "debited by 199.0 for MILK" to listOf("199", "MILK"),
    "USD 25.00 spent abroad" to listOf("25"),
    "Avl Bal Rs 15,000.00" to listOf("15"),
    "Avl Bal: INR 1,234.56" to listOf("1,234"),
    "Available balance is Rs.12,430.50" to listOf("12"),
    "Bal 5000.00 after the debit" to listOf("5000"),
    "Avl Lmt Rs 45,000" to listOf("45"),
    // references, phones, mail, links
    "Ref No. ABCD12EF payment done" to listOf("ABCD", "EF"),
    "UTR SBIN0123456789 credited" to listOf("SBIN"),
    "RRN 512345678901 debited" to listOf("512345678901"),
    "Not you? Call 9812345678" to listOf("9812345678"),
    "Call +91 98123 45678 now" to listOf("98123"),
    "Call 1800-258-6161 now" to listOf("1800"),
    "Statement sent to john.doe@gmail.com" to listOf("john", "gmail"),
    "See https://hdfc.bank/x?id=ABC123 for details" to listOf("hdfc", "ABC123"),
    "See bit.ly/abc12 for details" to listOf("abc12"),
    "Visit HDFCBANK.COM" to listOf("HDFCBANK"),
    // digits that are not ASCII, and digits split by something invisible
    "Rs.৪৫০ debited" to listOf("৪৫০"),
    "card ending ১২৩৪" to listOf("১২৩৪"),
    "ref ٥١٢٣٤٥" to listOf("٥١٢"),
    "Rs.५०० debited" to listOf("५००", "500"),
    "call ９８１２３４５６７８" to listOf("981"),
    "call 98​12345678" to listOf("98"),
    "call 98­12345678" to listOf("98"),
    "call 98⁠123‍45678" to listOf("98"),
    // letters a pattern cannot see, spelled to slip past the vocabulary
    "Rs 5 paid to 𝐑𝐀𝐌" to listOf("RAM"),
    "Rs 5 paid 🇷🇦🇲" to listOf("🇷"),
    // text dressed up as our own placeholders must not read as one
    "<upi> and # and  paid Rs 5" to listOf("<upi>"),
)

fun main() {
    println("=== a bank message the parser cannot read is a miss ===")
    for ((sender, body, reason) in UNREADABLE) {
        val miss = sms(sender, body)
        check("missed: ${body.take(50)}", miss != null)
        check("  the parser really cannot read it", !Parsing.parse(body).matched)
        check("  for the reason it failed: $reason", miss?.reason == reason, "got=${miss?.reason}")
        check("  and the pipeline agrees it was not filed",
            Pipeline.ingest(sender, body, NOW, { null },
                { _, _, _ -> Pipeline.Resolution("X", "c", 90) }) is Pipeline.Outcome.NotFinancial)
    }
    val charged = sms("AD-HDFCBK", UNREADABLE[0].second)
    check("the sender is stored as the bank, without the route prefix",
        charged?.sender == "HDFCBK", "got=${charged?.sender}")
    check("and what is stored is a shape, not the message",
        charged?.shape == "Your a/c no. <account> has been charged INR:<amount> towards SMS charges",
        "got=${charged?.shape}")
    check("the de-duplication key is a hash, not text",
        charged?.bodyHash?.matches(Regex("[0-9a-f]{64}")) == true)

    println("=== an unlisted bank still counts ===")
    check("a header that reads as a bank (BNK)",
        sms("BZ-KARBNK", UNREADABLE[1].second)?.sender == "KARBNK")
    check("a header that reads as a bank (…BK)",
        sms("JD-IDBIBK", UNREADABLE[1].second) != null)
    val unknown = "VM-SARASW"
    check("an unrecognised header with no history does not",
        sms(unknown, UNREADABLE[1].second) == null)
    check("…until one of its messages has been captured",
        sms(unknown, UNREADABLE[1].second, ParseMisses.History(captured = 3)) != null)
    check("…or the user vouched for one",
        sms(unknown, UNREADABLE[1].second, ParseMisses.History(confirmed = 1)) != null)
    check("…or the user trusts it",
        sms(unknown, UNREADABLE[1].second, ParseMisses.History(trust = Senders.TRUST_TRUSTED)) != null)
    check("a sender the user blocked is never logged, even a real bank",
        sms("AD-HDFCBK", UNREADABLE[0].second,
            ParseMisses.History(trust = Senders.TRUST_BLOCKED, captured = 9)) == null)

    println("=== what is never a miss ===")
    for (body in NOT_MISSES) {
        check("not a miss: ${body.take(50)}", sms("AD-HDFCBK", body) == null)
    }
    check("a message that was captured", sms("AD-HDFCBK", GENUINE) == null)
    check("a message that was held as a scam", sms("AD-HDFCBK", PHISH) == null)
    check("anything from a personal mobile — no bank sends from one",
        sms("+919812345678", UNREADABLE[2].second) == null)
    check("anything from a shop's header",
        sms("AX-AMAZON", "Payment of Rs.1,299 received for your order") == null)
    check("anything from a short number", sms("57575", UNREADABLE[2].second) == null)
    check("a message whose sender the phone lost", sms(null, UNREADABLE[2].second) == null)
    check("an empty message", sms("AD-HDFCBK", "") == null)
    check("no message at all", sms("AD-HDFCBK", "   ") == null)

    println("=== pasted: the person pasting vouches for the sender ===")
    val p = pasted(UNREADABLE[2].second)
    check("a pasted unreadable message is a miss", p != null)
    check("  with no sender", p?.sender == null && p?.source == Source.PASTE)
    check("a pasted OTP is not", pasted(NOT_MISSES[0]) == null)
    check("a pasted personal message is not", pasted("Hey, send me Rs.500 when you can") == null)
    check("a pasted message that reads fine is not", pasted(GENUINE) == null)

    println("=== redaction keeps the format ===")
    val shapes = listOf(
        GENUINE to "Rs.<amount> debited from a/c <account> on #-Jan-# to <name> Ref <ref> UPI",
        "Dear UPI user A/C X1234 debited by 199.0 on date 08Jul26 trf to SWIGGY Refno 553201998877" to
            "Dear UPI user A/C <account> debited by <amount> on date #Jul# trf to <name> Refno <ref>",
        "INR 460.00 debited A/c no. XX1234 08-07-26 UPI/P2M/519023481234/ZOMATO/pay" to
            "INR <amount> debited A/c no. <account> #-#-# UPI/P2M/#/<word>/pay",
        "Sent Rs.20.00 from Kotak Bank AC X1234 to swiggy8@ybl on 08-07-26. UPI Ref 519023481234" to
            "Sent Rs.<amount> from <name> AC <account> to <upi> on #-#-#. UPI Ref <ref>",
        "Rs.2000.00 withdrawn from A/c XX1234 at ATM on 08-07-26. Avl Bal Rs 15,000.00" to
            "Rs.<amount> withdrawn from A/c <account> at ATM on #-#-#. Avl Bal Rs <amount>",
        "Rs 500 deducted from account ending in 4521 on 12/07/2026 13:45:10. Not you? Call 18002586161" to
            "Rs <amount> deducted from account ending in <account> on #/#/# #:#:#. Not you? Call #",
        "Dear RAMESH KUMAR, Rs.5,000 credited by Mr. A. K. Sharma via 9812345678@paytm. Bal 5000.00" to
            "Dear <name>, Rs.<amount> credited by Mr. <name> via <upi>. Bal <amount>",
    )
    for ((body, want) in shapes) {
        val got = ParseMisses.redact(body)
        check("shape of: ${body.take(45)}", got == want, "got=$got")
    }
    check("one format with different values is one shape",
        ParseMisses.redact("Rs.450.00 debited from a/c **1234 on 05-Jan-24 to ZOMATO Ref 883120114455 UPI") ==
        ParseMisses.redact("Rs.9,99,999.00 debited from a/c **8765 on 31-Jan-26 to PHARMACY Ref 112233445566 UPI"))
    check("the size of an amount is not given away by its separators",
        !ParseMisses.redact("Bal 1,23,45,678.90 now").contains("#,#"))
    check("a month beside a number is a date and stays",
        ParseMisses.redact("on 5 May 2026").contains("May"))
    check("a month alone is a possible name and goes",
        !ParseMisses.redact("MAY has sent you Rs 5").contains("MAY"))
    check("an empty message has an empty shape", ParseMisses.redact("") == "")
    check("no message has an empty shape", ParseMisses.redact(null) == "")

    println("=== redaction under attack ===")
    for ((body, secrets) in ADVERSARIAL) {
        val shape = ParseMisses.redact(body)
        check("no digit survives: ${body.take(40)}", !anyDigit(shape), shape)
        check("only allowlisted words survive: ${body.take(40)}", strangers(shape).isEmpty(),
            "strangers=${strangers(shape)} shape=$shape")
        for (secret in secrets) {
            check("'$secret' does not survive: ${body.take(40)}", !leaks(shape, secret), shape)
        }
        check("nothing outside text and placeholders: ${body.take(40)}",
            shape.all { it in ' '..'~' || it == '…' || Character.getType(it).toByte().let { t ->
                t == Character.CURRENCY_SYMBOL || t == Character.DASH_PUNCTUATION ||
                t == Character.OTHER_PUNCTUATION || t == Character.MATH_SYMBOL ||
                t == Character.START_PUNCTUATION || t == Character.END_PUNCTUATION ||
                t == Character.INITIAL_QUOTE_PUNCTUATION || t == Character.FINAL_QUOTE_PUNCTUATION ||
                t == Character.CONNECTOR_PUNCTUATION } },
            shape)
    }
    check("digits split by invisible marks become one number, not two",
        ParseMisses.redact("call 98­12345678") == "call #",
        ParseMisses.redact("call 98­12345678"))
    check("a private-use character in the input cannot become a placeholder",
        !ParseMisses.redact(" paid Rs 5").contains("<upi>"))
    val long = ("Rs.450.00 debited from a/c **1234 to ZOMATO. ").repeat(60)
    val cut = ParseMisses.redact(long)
    check("a long message is cut to the limit", cut.length <= ParseMisses.MAX_SHAPE + 1,
        "length=${cut.length}")
    check("  and says so", cut.endsWith("…"))
    check("  without cutting a placeholder in half",
        !Regex("<[a-z]*…$").containsMatchIn(cut), cut.takeLast(30))

    println("=== every shape a miss stores is clean ===")
    for ((sender, body, _) in UNREADABLE) {
        val shape = sms(sender, body)?.shape ?: ""
        check("stored shape has no digit: ${body.take(40)}", !anyDigit(shape), shape)
        check("stored shape has only allowlisted words: ${body.take(40)}",
            strangers(shape).isEmpty(), "${strangers(shape)}")
    }

    println("=== the de-duplication key ===")
    val h = ParseMisses.bodyHash(GENUINE)
    check("is stable", h == ParseMisses.bodyHash(GENUINE))
    check("ignores invisible marks the two delivery paths may differ in",
        h == ParseMisses.bodyHash(GENUINE.replace("debited", "deb​ited")))
    check("ignores doubled spaces", h == ParseMisses.bodyHash(GENUINE.replace(" on ", "  on ")))
    check("tells two messages apart", h != ParseMisses.bodyHash(GENUINE.replace("450", "451")))
    check("contains nothing of the message", !h.contains("450") || h.length == 64)

    println("=== storage stays bounded ===")
    check("a table with room admits anything",
        ParseMisses.admits(ParseMisses.MAX_ROWS - 1, 100L, 1L))
    check("an empty table admits anything", ParseMisses.admits(0, null, 1L))
    check("a full table refuses a message older than everything in it — the launch " +
          "scan would otherwise add and evict it on every launch",
        !ParseMisses.admits(ParseMisses.MAX_ROWS, 100L, 50L))
    check("a full table refuses one exactly as old as its oldest",
        !ParseMisses.admits(ParseMisses.MAX_ROWS, 100L, 100L))
    check("a full table admits a newer message, which evicts the oldest",
        ParseMisses.admits(ParseMisses.MAX_ROWS, 100L, 150L))
    check("rows expire after the age bound",
        ParseMisses.expiresBefore(NOW) == NOW.minusDays(ParseMisses.MAX_AGE_DAYS))
    check("the age bound outlasts the 90-day launch scan", ParseMisses.MAX_AGE_DAYS > 90)

    println("=== what the screen shows ===")
    val shapeA = ParseMisses.redact(UNREADABLE[0].second)
    val shapeB = ParseMisses.redact(UNREADABLE[2].second)
    val rows = listOf(
        ParseMisses.Row("HDFCBK", shapeA, ParseMisses.REASON_NO_AMOUNT, 1, NOW.minusDays(3)),
        ParseMisses.Row("HDFCBK", shapeA, ParseMisses.REASON_NO_AMOUNT, 2, NOW.minusDays(1)),
        ParseMisses.Row("HDFCBK", shapeA, ParseMisses.REASON_NO_AMOUNT, 1, NOW.minusDays(2)),
        ParseMisses.Row(null, shapeB, ParseMisses.REASON_NO_VERB, 1, NOW),
        ParseMisses.Row("SBIINB", shapeA, ParseMisses.REASON_NO_AMOUNT, 1, NOW),
    )
    val groups = ParseMisses.summarize(rows)
    check("one line per format per sender", groups.size == 3, "got=${groups.size}")
    check("the format behind most messages first", groups.first().sender == "HDFCBK")
    check("counting every arrival, repeats included", groups.first().messages == 4,
        "got=${groups.first().messages}")
    check("dated by the latest arrival", groups.first().lastSeen == NOW.minusDays(1))
    check("a known bank is named", groups.first().title == "HDFC Bank (HDFCBK)",
        groups.first().title)
    check("a pasted message says so", groups.any { it.sender == null && it.title == "Pasted by you" })
    check("an unknown header is shown as itself", ParseMisses.title("KARBNK") == "KARBNK")
    check("equal groups keep a stable order",
        ParseMisses.summarize(rows.reversed()) == groups)
    check("nothing to show is an empty list", ParseMisses.summarize(emptyList()).isEmpty())

    println("=== what sharing sends ===")
    val text = ParseMisses.export(groups)
    check("names the parser that failed", text.contains("Parser ${Parsing.PARSER_VERSION}."))
    check("counts formats and messages", text.contains("3 formats, 6 messages."), text.lines()[4])
    check("names the sender of each format", text.contains("HDFC Bank (HDFCBK) · 4 messages"))
    check("says why each one failed", text.contains(ParseMisses.reasonLabel(ParseMisses.REASON_NO_AMOUNT)))
    check("carries each shape", text.contains(shapeA) && text.contains(shapeB))
    check("says the messages are not in it", text.contains("The messages themselves are not in it"))
    check("an empty list still says what it is",
        ParseMisses.export(emptyList()).contains("0 formats, 0 messages."))
    val many = (1..ParseMisses.MAX_EXPORTED + 20).map {
        ParseMisses.Group("HDFCBK", "HDFC Bank (HDFCBK)", "shape $it", ParseMisses.REASON_NO_AMOUNT, 1, NOW)
    }
    val capped = ParseMisses.export(many)
    check("a very long list is capped", !capped.contains("shape ${ParseMisses.MAX_EXPORTED + 1}\n"))
    check("  and says how many were left out", capped.contains("…and 20 less frequent formats."))

    // The end-to-end privacy claim: real messages go in through the same
    // door the app uses, and nothing of them comes out of the share.
    val raw = ADVERSARIAL.map { it.first } + UNREADABLE.map { it.second }
    val logged = raw.mapNotNull { body -> pasted(body)?.let { body to it } }
    check("the adversarial corpus produces misses to test with", logged.size >= 10,
        "got=${logged.size}")
    val shared = ParseMisses.export(ParseMisses.summarize(logged.map { (_, m) ->
        ParseMisses.Row(m.sender, m.shape, m.reason, 1, NOW) }))
    for ((body, miss) in logged) {
        check("the share does not contain the message: ${body.take(40)}", !shared.contains(body))
        check("  nor its hash", !shared.contains(miss.bodyHash))
    }
    for ((body, secrets) in ADVERSARIAL) {
        if (logged.none { it.first == body }) continue
        // Numbers are covered line by line below; the share's own counts and
        // list numbering would otherwise match a secret like "23".
        for (secret in secrets.filter { s -> s.any { Character.isLetter(it) } }) {
            check("the share does not contain '$secret'", !leaks(shared, secret))
        }
    }
    val sharedBody = shared.lines().filter { line ->
        line.isNotBlank() && !Regex("^\\d+\\. ").containsMatchIn(line) &&
        !line.startsWith("Parser ") && !line.startsWith("Bank messages") &&
        !line.startsWith("Each line") }
    check("every shape line in the share is digit-free",
        sharedBody.none { anyDigit(it) }, sharedBody.firstOrNull { anyDigit(it) } ?: "")

    println()
    println("=".repeat(60))
    println("$passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
