package com.jeevavibeapp.spendwise.core

import kotlin.math.min

/**
 * Sender identity, trust and content-based phishing detection. Pure Kotlin.
 *
 * The premise is that in India the SENDER SHAPE carries real information.
 * TRAI's DLT rails mean a bank cannot legally send a transactional SMS from a
 * personal mobile number, so "bank alert from a 10-digit mobile" is a forgery
 * or, at best, someone forwarding one. That single structural fact does more
 * work than any amount of content analysis.
 */
object Senders {

    const val SENDER_MODEL_VERSION = "2026.08.1-kt"

    const val TRUST_TRUSTED = "trusted"
    const val TRUST_KNOWN = "known"
    const val TRUST_UNKNOWN = "unknown"
    const val TRUST_SUSPICIOUS = "suspicious"
    const val TRUST_BLOCKED = "blocked"

    const val ACTION_ACCEPT = "accept"
    const val ACTION_REVIEW = "review"
    const val ACTION_QUARANTINE = "quarantine"

    /** DLT transactional header: two-letter operator prefix, hyphen, then the
     *  principal entity. Some circles add a trailing route tag. */
    private val DLT = Regex("^([A-Z]{2})-([A-Z0-9]{5,8})(?:-[A-Z])?$")
    private val BARE_HEADER = Regex("^([A-Z]{5,8})$")
    private val MOBILE = Regex("^(?:\\+?91|0)?([6-9]\\d{9})$")
    private val SHORTCODE = Regex("^\\d{3,8}$")

    /** Zero-width and bidi controls that spoofed senders use to defeat exact
     *  matching. Written as escapes so the class is reviewable in a diff —
     *  the entire point is that these characters are invisible. */
    private val SENDER_NOISE = Regex("[\\s\\u200b-\\u200f\\u202a-\\u202e\\u2060\\ufeff]")

    val KNOWN_ENTITIES: Map<String, String> = mapOf(
        // Public sector banks
        "SBIINB" to "State Bank of India", "SBIUPI" to "State Bank of India",
        "SBICRD" to "SBI Card", "SBIPSG" to "State Bank of India",
        "PNBSMS" to "Punjab National Bank", "CANBNK" to "Canara Bank",
        "UNIONB" to "Union Bank of India", "BOIIND" to "Bank of India",
        "BOBTXN" to "Bank of Baroda", "BOBSMS" to "Bank of Baroda",
        "IOBCHN" to "Indian Overseas Bank", "CBSSBI" to "State Bank of India",
        "INDBNK" to "Indian Bank", "UCOBNK" to "UCO Bank",
        // Private sector banks
        "HDFCBK" to "HDFC Bank", "HDFCBN" to "HDFC Bank",
        "ICICIB" to "ICICI Bank", "ICICIT" to "ICICI Bank",
        "AXISBK" to "Axis Bank", "AXISBN" to "Axis Bank",
        "KOTAKB" to "Kotak Mahindra Bank", "INDUSB" to "IndusInd Bank",
        "YESBNK" to "Yes Bank", "IDFCFB" to "IDFC First Bank",
        "RBLBNK" to "RBL Bank", "FEDBNK" to "Federal Bank",
        "SIBSMS" to "South Indian Bank", "KVBANK" to "Karur Vysya Bank",
        "CITIBK" to "Citibank", "SCBANK" to "Standard Chartered",
        "HSBCIN" to "HSBC India", "DBSBNK" to "DBS Bank",
        "AUBANK" to "AU Small Finance Bank", "BANDHN" to "Bandhan Bank",
        // Payments banks / wallets / UPI apps
        "PYTMBK" to "Paytm Payments Bank", "PAYTMB" to "Paytm",
        "PHONPE" to "PhonePe", "PHNPAY" to "PhonePe",
        "GPAYIN" to "Google Pay", "GOOGPY" to "Google Pay",
        "AMZNPY" to "Amazon Pay", "BHIMUP" to "BHIM UPI",
        "AIRTLB" to "Airtel Payments Bank", "JIOPAY" to "Jio Payments Bank",
        "MOBIKW" to "MobiKwik", "FREECH" to "Freecharge",
        // Cards / NBFCs
        "ONECRD" to "OneCard", "SLICEI" to "Slice", "BAJFIN" to "Bajaj Finance",
        "HDFCCC" to "HDFC Credit Card", "AMEXIN" to "American Express",
    )

    /** Canonical registry key. Mobile numbers reduce to their ten national
     *  digits, so +919812345678, 09812345678 and 9812345678 are one sender
     *  rather than three. The hyphen is deliberately kept: it separates the
     *  DLT operator prefix from the entity header, and removing it would
     *  destroy the very shape this module classifies on. */
    fun normalizeSender(raw: String?): String {
        val s = SENDER_NOISE.replace((raw ?: "").trim().uppercase(), "")
        return MOBILE.find(s)?.groupValues?.get(1) ?: s
    }

    /** Classify by shape alone — no user history involved. */
    fun identify(raw: String?): SenderIdentity {
        val norm = normalizeSender(raw)
        if (norm.isEmpty()) {
            // Some OEM stacks lose the originating address on multipart
            // messages. Crucially this is NOT attacker-controlled — an
            // attacker sends from some number and cannot make the field
            // vanish — so absence carries no hostile signal and must not
            // downgrade an otherwise-clean message.
            return SenderIdentity(raw ?: "", norm, "missing",
                base = 70, reasons = listOf("sender_missing"))
        }

        DLT.find(norm)?.let { m ->
            val entity = m.groupValues[2]
            val bank = KNOWN_ENTITIES[entity]
            // Unrecognised but correctly-shaped headers score as
            // accept-worthy: India has roughly 1,500 registered entities and
            // this table holds about 50, so an unknown header is
            // overwhelmingly a real bank we simply do not list. Treating them
            // all as suspect floods the review queue with real transactions.
            return SenderIdentity(raw ?: "", norm, "dlt", entity, bank,
                if (bank != null) 90 else 75,
                listOf(if (bank != null) "dlt_known_entity" else "dlt_unknown_entity"))
        }

        BARE_HEADER.find(norm)?.let { m ->
            val entity = m.groupValues[1]
            val bank = KNOWN_ENTITIES[entity]
            return SenderIdentity(raw ?: "", norm, "header", entity, bank,
                if (bank != null) 75 else 55,
                listOf(if (bank != null) "header_known_entity" else "header_unknown_entity"))
        }

        if (MOBILE.matches(norm) || Regex("^[6-9]\\d{9}$").matches(norm)) {
            // The strongest structural signal available. DLT forbids banks
            // from sending transactional SMS from a personal number.
            return SenderIdentity(raw ?: "", norm, "mobile",
                base = 10, reasons = listOf("personal_mobile_number"))
        }

        if (SHORTCODE.matches(norm)) {
            return SenderIdentity(raw ?: "", norm, "shortcode",
                base = 30, reasons = listOf("numeric_shortcode"))
        }

        return SenderIdentity(raw ?: "", norm, "other",
            base = 35, reasons = listOf("unrecognised_sender_format"))
    }

    /** Content indicators. Weights are additive, capped at 100, and chosen so
     *  no single indicator can quarantine a message on its own: it takes one
     *  severe signal plus a weak sender, or two independent signals. */
    private val INDICATORS: List<Triple<String, Regex, Int>> = listOf(
        // A genuine bank alert DOES say "Not you? Call 18002586161" — a
        // toll-free line. The fraud says "call 9812345678". The discriminator
        // is the number FORM, not the instruction to call. The lookarounds
        // matter: without them the toll-free 18002586161 matches [6-9]\d{9}
        // on the substring 8002586161, and every real HDFC alert scores 95.
        Triple("callback_mobile_number", Regex(
            "\\b(?:call|contact|dial|whatsapp|sms)\\b[^.\\n]{0,40}?" +
            "(?<!\\d)(?:\\+?91[\\-\\s]?)?[6-9]\\d{9}(?!\\d)", RegexOption.IGNORE_CASE), 45),
        // URL shorteners: the defining feature of SMS phishing — they hide
        // the destination inside a 160-character limit.
        Triple("url_shortener", Regex(
            "\\b(?:bit\\.ly|tinyurl|t\\.co|goo\\.gl|rb\\.gy|cutt\\.ly|is\\.gd|" +
            "ow\\.ly|shorturl|tiny\\.cc|rebrand\\.ly)\\b", RegexOption.IGNORE_CASE), 40),
        Triple("apk_download", Regex(
            "\\.apk\\b|download.{0,20}app.{0,20}http", RegexOption.IGNORE_CASE), 60),
        Triple("credential_request", Regex(
            "\\b(?:enter|share|send|confirm|update|verify)\\b[^.\\n]{0,30}" +
            "\\b(?:otp|pin|cvv|password|mpin|upi\\s*pin|card\\s*(?:no|number)|" +
            "aadha?ar|pan\\s*card|kyc)\\b", RegexOption.IGNORE_CASE), 55),
        Triple("account_block_threat", Regex(
            "\\b(?:account|a/?c|card|sim|kyc)\\b[^.\\n]{0,30}" +
            "\\b(?:will\\s+be\\s+)?(?:block|suspend|deactivat|freez|expir|clos)\\w*",
            RegexOption.IGNORE_CASE), 40),
        Triple("urgency_pressure", Regex(
            "\\b(?:immediately|within\\s+\\d+\\s*(?:hour|hrs|minute|min)|" +
            "urgent(?:ly)?|last\\s+chance|act\\s+now|expires\\s+today)\\b",
            RegexOption.IGNORE_CASE), 20),
        // "Not you? Reverse it" — the hook that makes a victim call. Weighted
        // LOW on purpose: HDFC, ICICI and SBI all ship "Not you? Call
        // <toll-free>" in genuine alerts, so it is only meaningful alongside a
        // mobile callback. Weighted as a strong signal it quarantined every
        // real HDFC message in testing.
        Triple("reversal_bait", Regex(
            "\\b(?:if\\s+)?not\\s+(?:you|done\\s+by\\s+you)\\b|" +
            "\\breverse\\s+(?:this|the)?\\s*(?:transaction|txn|payment)\\b|" +
            "\\bto\\s+cancel\\b[^.\\n]{0,30}\\bcall\\b", RegexOption.IGNORE_CASE), 15),
        Triple("prize_or_refund_lure", Regex(
            "\\b(?:you\\s+have\\s+won|lucky\\s+winner|cash\\s*prize|lottery|" +
            "claim\\s+your\\s+(?:refund|reward|cashback)|congratulations)\\b",
            RegexOption.IGNORE_CASE), 45),
        // A bare link is mild alone — real bank messages link to statements —
        // but it compounds with everything above.
        Triple("contains_link", Regex("https?://|\\bwww\\.", RegexOption.IGNORE_CASE), 12),
        Triple("suspicious_domain", Regex(
            "https?://[^\\s]*\\.(?:xyz|top|club|online|site|icu|buzz|link|" +
            "tk|ml|ga|cf|gq|ru|cn)\\b", RegexOption.IGNORE_CASE), 45),
        // An alert never asks you to send money out.
        Triple("payment_request", Regex(
            "\\b(?:pay|send|transfer|deposit)\\b[^.\\n]{0,25}" +
            "(?:rs\\.?|inr|₹)\\s*[\\d,]+[^.\\n]{0,25}" +
            "\\b(?:to\\s+(?:this|the\\s+following)|immediately|now)\\b",
            RegexOption.IGNORE_CASE), 40),
    )

    /** A message claiming to be from a bank while arriving from a personal
     *  mobile is a compound indicator: neither half is conclusive alone. */
    private val BANK_CLAIM = Regex(
        "\\b(?:bank|a/?c|account|upi|atm|debit\\s*card|credit\\s*card|netbanking)\\b",
        RegexOption.IGNORE_CASE)

    fun phishingIndicators(body: String?): Pair<Int, List<String>> {
        val text = body ?: ""
        var risk = 0
        val found = mutableListOf<String>()
        for ((name, pattern, weight) in INDICATORS) {
            if (pattern.containsMatchIn(text)) { found += name; risk += weight }
        }
        return min(risk, 100) to found
    }

    /** Combine sender shape, learned trust and content risk into one verdict.
     *
     * A user decision always wins over the heuristics. They can see things
     * the patterns cannot, and overriding them makes the app untrustworthy. */
    fun assess(sender: String?, body: String?, registry: SenderRegistry? = null): RiskAssessment {
        val ident = identify(sender)
        var (risk, indicators) = phishingIndicators(body)
        val mutableIndicators = indicators.toMutableList()
        val reasons = ident.reasons.toMutableList()

        if (ident.kind == "mobile" && BANK_CLAIM.containsMatchIn(body ?: "")) {
            risk = min(100, risk + 35)
            mutableIndicators += "bank_claim_from_mobile"
        }

        val storedTrust = registry?.trust
        val confirmed = registry?.confirmedCount ?: 0

        // 1. Explicit user decisions are final.
        if (storedTrust == TRUST_BLOCKED) {
            return RiskAssessment(ident, risk, mutableIndicators, TRUST_BLOCKED,
                ACTION_QUARANTINE, -100, reasons + "user_blocked_sender")
        }
        if (storedTrust == TRUST_TRUSTED) {
            // Still quarantine on a severe signal: a trusted header can be
            // spoofed by an SMS gateway, and "trusted once" must not become a
            // blanket bypass of phishing detection.
            if (risk >= 85) {
                return RiskAssessment(ident, risk, mutableIndicators, TRUST_TRUSTED,
                    ACTION_QUARANTINE, -60, reasons + "trusted_sender_severe_content_risk")
            }
            return RiskAssessment(ident, risk, mutableIndicators, TRUST_TRUSTED,
                if (risk >= 45) ACTION_REVIEW else ACTION_ACCEPT,
                if (risk >= 45) -20 else 0, reasons + "user_trusted_sender")
        }

        // 2. Structural prior plus observed history.
        val score = ident.base + min(confirmed * 5, 25)
        if (confirmed >= 3) reasons += "history_confirmed_transactions"

        // 3. Content risk pulls effective trust down.
        val effective = score - risk

        return when {
            risk >= 70 || (risk >= 50 && ident.base < 60) -> RiskAssessment(
                ident, risk, mutableIndicators, TRUST_SUSPICIOUS, ACTION_QUARANTINE,
                -100, reasons + "high_content_risk")
            effective >= 70 -> RiskAssessment(
                ident, risk, mutableIndicators,
                if (ident.bank != null) TRUST_KNOWN else TRUST_UNKNOWN,
                ACTION_ACCEPT, 0, reasons)
            effective >= 30 -> RiskAssessment(
                ident, risk, mutableIndicators, TRUST_UNKNOWN, ACTION_REVIEW,
                -25, reasons + "unverified_sender_needs_review")
            else -> RiskAssessment(
                ident, risk, mutableIndicators, TRUST_SUSPICIOUS, ACTION_QUARANTINE,
                -100, reasons + "low_sender_trust")
        }
    }

    /** One line the user can act on, shown beside a held message. */
    fun explain(a: RiskAssessment): String = when {
        "user_blocked_sender" in a.reasons -> "You blocked this sender."
        a.indicators.contains("credential_request") ->
            "This message asks for an OTP, PIN or card detail. A bank never does."
        a.indicators.contains("apk_download") -> "This message links to an app download."
        a.indicators.contains("url_shortener") ->
            "This message hides its link behind a shortener."
        a.indicators.contains("bank_claim_from_mobile") ->
            "This claims to be from a bank but came from a personal mobile number."
        a.indicators.contains("account_block_threat") ->
            "This threatens to block your account to make you act quickly."
        a.indicators.contains("prize_or_refund_lure") ->
            "This offers a prize or refund to get you to respond."
        a.trust == TRUST_SUSPICIOUS -> "We could not verify this sender."
        a.action == ACTION_REVIEW -> "We have not seen this sender before."
        else -> "Looks fine."
    }
}

data class SenderIdentity(
    val raw: String,
    val normalized: String,
    val kind: String,                 // dlt | header | mobile | shortcode | missing | other
    val entity: String? = null,
    val bank: String? = null,
    val base: Int = 0,                // 0..100 prior trust from shape alone
    val reasons: List<String> = emptyList(),
)

/** What the app has stored about this sender, or null if never seen. */
data class SenderRegistry(
    val trust: String? = null,
    val confirmedCount: Int = 0,
    val quarantinedCount: Int = 0,
)

data class RiskAssessment(
    val sender: SenderIdentity,
    val risk: Int,                    // 0..100 content risk
    val indicators: List<String>,
    val trust: String,
    val action: String,               // accept | review | quarantine
    val confidenceDelta: Int,         // added to merchant confidence (<= 0)
    val reasons: List<String>,
)
