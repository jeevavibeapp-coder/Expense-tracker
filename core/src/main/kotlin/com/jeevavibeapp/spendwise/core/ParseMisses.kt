package com.jeevavibeapp.spendwise.core

import java.security.MessageDigest
import java.time.LocalDateTime

/**
 * Bank messages the parser could not read, and the only form this app keeps
 * them in.
 *
 * Without this a bank that changes its wording is silent: transactions stop
 * appearing, Settings says "0 captured", and the person has nothing to show
 * anyone that would get the format supported.
 *
 * The Python build stored and exported the first 600 characters of each
 * message verbatim — account digits, UPI ids, names, balances and all. Here
 * the message itself is never kept. What is kept is its SHAPE: the wording
 * the parser needs to be taught, with every value replaced on the phone
 * before anything is written. [redact] is fail-closed: a word survives only
 * if it is on [VOCABULARY], a fixed list anyone can review, so a name in a
 * position nobody anticipated is masked by default rather than by luck.
 */
object ParseMisses {

    /** Rows kept, newest first. A bank whose whole format is unsupported
     *  produces a miss for every transaction, so without a ceiling this
     *  table would grow as fast as the ledger it failed to fill. */
    const val MAX_ROWS = 200

    /** Longer than the launch scan looks back (90 days), so a rescan never
     *  re-records a message that the age bound would discard straight away. */
    const val MAX_AGE_DAYS = 180L

    /** Characters of shape kept. Two SMS segments; a format is recognisable
     *  long before that, and it keeps a shared list well under what a share
     *  intent can carry. */
    const val MAX_SHAPE = 400

    /** Groups in one export, for the same reason. */
    const val MAX_EXPORTED = 100

    private const val MAX_INPUT = 1000

    /** Which of the parser's gates the message failed, first in the order
     *  [Parsing.parse] applies them. Stored, so a fix can start with the
     *  commonest. */
    const val REASON_NO_AMOUNT = "no_amount"
    const val REASON_NO_VERB = "no_verb"
    const val REASON_NO_ACCOUNT = "no_account"

    /** A pasted message has no sender, and the person pasting it into "Paste
     *  a bank message" is the one vouching that a bank sent it. */
    enum class Source { SMS, PASTE }

    /** What the sender registry knows, as far as this decision cares. */
    data class History(
        val trust: String? = null,
        val captured: Int = 0,
        val confirmed: Int = 0,
    )

    /** What gets stored. No field here can hold the message. */
    data class Miss(
        val source: Source,
        /** The sender's DLT entity ("HDFCBK"), or null when pasted. The
         *  operator prefix is dropped so one bank arriving over two routes is
         *  one sender, not two. */
        val sender: String?,
        val shape: String,
        val reason: String,
        /** For de-duplication only: a rescan meets the same message on every
         *  launch. Never shown and never exported. */
        val bodyHash: String,
    )

    /**
     * Whether a message counts as one the parser failed to read.
     *
     * Meant for what [Pipeline.ingest] returned NotFinancial for, and it
     * checks that itself: anything with the full shape of a transaction was
     * either banked, held, or rejected on purpose by the parser's own
     * non-transaction vocabulary, and none of those is a reading failure.
     *
     * The rest must clear three gates, each there to keep the table from
     * filling with what banks send most — OTPs and offers:
     *  - the sender is a bank (or the user pasted it as one);
     *  - the wording is not an OTP, a promotion, a reminder or a decline —
     *    the parser's own NON_TXN list, so "not understood" and "understood
     *    as not a transaction" can never disagree;
     *  - it still reads like money moved: a completed money verb AND an
     *    amount, each recognised more loosely than the parser does, because
     *    the loose forms are exactly the ones the parser is missing.
     */
    fun judge(source: Source, sender: String?, body: String?, history: History? = null): Miss? {
        val from = when (source) {
            Source.PASTE -> null
            Source.SMS -> bankSender(sender, history) ?: return null
        }
        val text = Parsing.normalizeText(body)
        if (text.isEmpty()) return null
        val parsed = Parsing.parse(text)
        if (parsed.looksTransactional) return null
        if (Parsing.NON_TXN.containsMatchIn(text) || OTP_LIKE.containsMatchIn(text)) return null

        val parserVerb = Parsing.TXN_VERB.containsMatchIn(text)
        val verb = parserVerb || MORE_VERBS.containsMatchIn(text)
        val amount = parsed.amount != null || LOOSE_AMOUNT.containsMatchIn(text)
        if (!verb || !amount) return null

        val reason = when {
            parsed.amount == null -> REASON_NO_AMOUNT
            !parserVerb -> REASON_NO_VERB
            else -> REASON_NO_ACCOUNT
        }
        return Miss(source, from, redact(text), reason, bodyHash(body))
    }

    /** An entity name that reads as a bank. The registry knows about fifty
     *  entities and India has well over a thousand; a small bank whose format
     *  is wholly unsupported has no captures to vouch for it, and is exactly
     *  the user this log exists for. */
    private val BANKISH = Regex("BNK|BANK|BK$|SFB")

    /**
     * The sender key to store, or null when the sender is not a bank.
     *
     * A registered header only. A bank cannot legally send a transactional
     * SMS from a personal number, so a mobile's unreadable "Rs.500 sent" is
     * not a format this app failed to learn; and a missing sender could be
     * anyone. A blocked sender is the user saying they want to hear nothing
     * more from it.
     */
    private fun bankSender(sender: String?, history: History?): String? {
        val id = Senders.identify(sender)
        if (id.kind != "dlt" && id.kind != "header") return null
        if (history?.trust == Senders.TRUST_BLOCKED) return null
        val entity = id.entity ?: return null
        val vouched = history != null && (history.trust == Senders.TRUST_TRUSTED ||
            history.captured > 0 || history.confirmed > 0)
        return if (id.bank != null || BANKISH.containsMatchIn(entity) || vouched) entity else null
    }

    private val I = RegexOption.IGNORE_CASE
    private const val NUMBER = "\\p{N}[\\p{N},]*(?:\\.\\p{N}+)?"
    private const val CURRENCY = "rs\\.?|re\\.?|inr|rupees?|usd|eur|gbp|aed|sgd|₹|\\u0024|€|£"

    /** OTP wordings the parser's own list does not name. A code for a
     *  payment carries an amount and a card, and would otherwise look like
     *  the very thing this log is for. */
    private val OTP_LIKE = Regex(
        "\\b(?:verification|security|secure|confirmation|login)\\s+code\\b|" +
        "\\bpass\\s?code\\b|\\b(?:is|as)\\s+(?:your|the)\\s+(?:otp|code|pin)\\b|" +
        "\\bcode\\s*(?:is|:)\\s*\\p{N}{4,8}\\b", I)

    /** Money verbs the parser does not know — the usual reason a new format
     *  fails. "Debit card" and "credit limit" are things, not movements. */
    private val MORE_VERBS = Regex(
        "\\b(?:deducted|charged|withdrawal|transfer|txn|transaction|remitted|" +
        "refunded|dr|cr)\\b|\\bdebit\\b(?!\\s*cards?\\b)|" +
        "\\bcredit\\b(?!\\s*(?:cards?|limit|score)\\b)", I)

    /** Amount shapes the parser's two patterns miss: "INR:500", "500 Rs",
     *  "500/-", "Amt 500", "debited 500.00". */
    private val LOOSE_AMOUNT = Regex(
        "(?<![\\p{L}\\p{N}])(?:$CURRENCY)\\s*[.:\\-]?\\s*\\p{N}|" +
        "\\p{N}\\s*(?:rs\\.?|inr|rupees?)(?![\\p{L}])|\\p{N}\\s*/-|" +
        "\\b(?:amt|amount)\\s*(?:of)?\\s*[:.\\-]?\\s*\\p{N}|" +
        "\\b(?:debited|credited|deducted|charged|withdrawn|transferred|paid|spent)\\s+\\p{N}", I)

    // ── Redaction ─────────────────────────────────────────────────────────

    // Placeholders travel through the string as private-use code points,
    // which [sanitize] strips from the input first: so every one present is
    // ours, and no later pattern can read one as a letter or a digit.
    private const val S_AMOUNT = '\uE001'
    private const val S_ACCOUNT = '\uE002'
    private const val S_UPI = '\uE003'
    private const val S_EMAIL = '\uE004'
    private const val S_LINK = '\uE005'
    private const val S_REF = '\uE006'

    private val PLACEHOLDERS = mapOf(
        S_AMOUNT to "<amount>", S_ACCOUNT to "<account>", S_UPI to "<upi>",
        S_EMAIL to "<email>", S_LINK to "<link>", S_REF to "<ref>",
    )
    private const val NAME = "<name>"
    private const val WORD = "<word>"
    private const val DIGITS = "#"

    private val EMAIL = Regex(
        "[\\p{L}\\p{N}._%+\\-]+@[\\p{L}\\p{N}\\-]+(?:\\.[\\p{L}\\p{N}\\-]+)*\\.\\p{L}{2,}")
    /** handle@bank. The local part is often a phone number or a full name.
     *  The handle never ends in a dot, so a full stop after one stays a full
     *  stop. */
    private val VPA = Regex("[\\p{L}\\p{N}._*\\-]+@\\p{L}(?:[\\p{L}\\p{N}.\\-]*[\\p{L}\\p{N}])?")
    private val LINK = Regex(
        "\\b(?:https?://|www\\.)\\S+|" +
        "(?<![\\p{L}\\p{N}])[\\p{L}\\p{N}][\\p{L}\\p{N}\\-]{2,}(?:\\.[\\p{L}\\p{N}\\-]+)*" +
        "\\.(?:com|in|co|net|org|ly|me|io|app|info|gl|gd|cc|xyz|top|bank|link|site|" +
        "online|club|icu|buzz|tk|ml|ga|cf|gq|ru|cn)(?![\\p{L}\\p{N}])(?:/\\S*)?", I)
    /** "4111 1111 1111 1111", "XXXX XXXX XXXX 1234". */
    private val CARD_GROUPS = Regex(
        "(?<!\\p{N})(?:[\\p{N}xX*•]{4}[ \\-]){3}\\p{N}{2,4}(?!\\p{N})")
    /** "XX1234", "**1234", "X1234", "...1234". */
    private val MASKED_NUMBER = Regex(
        "(?<![\\p{L}\\p{N}])(?:[xX*•]+|\\.{2,})[ \\-]?\\p{N}{2,}(?!\\p{N})")
    /** "A/c no. 50100123456789", "card ending 4521", "account ending in 4521". */
    private val KEYWORD_ACCOUNT = Regex(
        "(?<![\\p{L}\\p{N}])(a/c|acct|account|acc|ac|card|cc|dc)(?![\\p{L}\\p{N}])" +
        "(\\s*(?:no|number|num)?\\.?\\s*(?:ending(?:\\s+(?:with|in))?|ends?\\s+(?:with|in))?" +
        "\\s*[:.\\-]?\\s*)([.\\-]*\\p{N}[\\p{N}\\-]*)", I)
    private val REFERENCE = Regex(
        "(?<![\\p{L}\\p{N}])(ref(?:erence)?(?:\\s*(?:no|number|num|id))?|utr(?:\\s*no)?|rrn|" +
        "txn\\s*(?:id|no|ref)|transaction\\s*(?:id|no|ref(?:erence)?)|order\\s*(?:id|no)|" +
        "approval\\s*code|auth(?:ori[sz]ation)?\\s*code|ch(?:e)?q(?:ue)?\\s*no)(?![\\p{L}])" +
        "(\\.?\\s*[:.\\-]?\\s*)([\\p{L}\\p{N}]*\\p{N}[\\p{L}\\p{N}/\\-]*)", I)
    private val AMOUNT_BEFORE = Regex(
        "(?<![\\p{L}\\p{N}])($CURRENCY)(\\s*[.:\\-]?\\s*)($NUMBER)", I)
    /** "500 Rs", "500/-" — but not the year in "26 Rs.<amount>", whose
     *  currency already belongs to the amount after it. */
    private val AMOUNT_AFTER = Regex(
        "(?<![\\p{L}\\p{N}])($NUMBER)(\\s*)(rs\\.?|inr|rupees?|/-)(?![\\p{L}\\p{N}])" +
        "(?!\\s*[.:\\-]?\\s*[\\p{N}\\uE000-\\uE0FF])", I)
    private val VERB_AMOUNT = Regex(
        "\\b(debited|credited|deducted|charged|paid|spent|withdrawn|transferred|received|" +
        "sent|deposited|refunded)(\\s+(?:by|with|for|of)\\s*|\\s+)($NUMBER)(?![\\p{L}])", I)
    /** Balances and limits are amounts too, marked or not: "Avl Bal 15,000". */
    private val LABELLED_AMOUNT = Regex(
        "(?<![\\p{L}\\p{N}])(amt|amount|bal|balance|lmt|limit)" +
        "(\\s*(?:is|of)?\\s*[:.\\-]?\\s*)($NUMBER)", I)

    /** Everything the walk below sees: a word (a/c and friends whole), a
     *  number with its separators — so "1,23,456.78" is one "#" and not a
     *  run of groups that gives away its size — a placeholder, a space, or
     *  any other single character. */
    private val TOKEN = Regex(
        "(?<![\\p{L}\\p{N}])(?:a/c|m/s|w/d|p2[pma])(?![\\p{L}\\p{N}])|" +
        "[\\p{L}\\p{M}]+(?:['’][\\p{L}\\p{M}]+)*|" +
        "\\p{N}+(?:[.,]\\p{N}+)*|[\\uE000-\\uE0FF]| +|.",
        setOf(I, RegexOption.DOT_MATCHES_ALL))

    private val WORD_RUN = Regex("$WORD(?: ?$WORD)+")
    private val SPACES = Regex(" {2,}")

    /**
     * The shape of a message: its wording, with nothing in it that belongs
     * to anyone.
     *
     * Values a pattern can recognise become a typed placeholder — `<amount>`,
     * `<account>`, `<upi>`, `<email>`, `<link>`, `<ref>` — and every other
     * number becomes `#`. Words after "to", "from", "Dear" and the like
     * become `<name>`. Every other word survives only if it is on
     * [VOCABULARY]; anything else is `<word>`. No digit, in any script, can
     * survive any path through this function.
     */
    fun redact(body: String?): String {
        var s = sanitize(body)
        if (s.isEmpty()) return ""
        s = EMAIL.replace(s, S_EMAIL.toString())
        s = VPA.replace(s, S_UPI.toString())
        s = LINK.replace(s, S_LINK.toString())
        s = CARD_GROUPS.replace(s, S_ACCOUNT.toString())
        s = MASKED_NUMBER.replace(s, S_ACCOUNT.toString())
        s = KEYWORD_ACCOUNT.replace(s) { it.groupValues[1] + it.groupValues[2] + S_ACCOUNT }
        s = REFERENCE.replace(s) { it.groupValues[1] + it.groupValues[2] + S_REF }
        s = AMOUNT_BEFORE.replace(s) { it.groupValues[1] + it.groupValues[2] + S_AMOUNT }
        s = AMOUNT_AFTER.replace(s) { S_AMOUNT + it.groupValues[2] + it.groupValues[3] }
        s = VERB_AMOUNT.replace(s) { it.groupValues[1] + it.groupValues[2] + S_AMOUNT }
        s = LABELLED_AMOUNT.replace(s) { it.groupValues[1] + it.groupValues[2] + S_AMOUNT }

        val tokens = TOKEN.findAll(s).map { Tok(kindOf(it.value), it.value) }.toMutableList()
        maskNames(tokens)
        return finish(render(tokens))
    }

    /**
     * Canonical text with nothing in it that could pass for one of the
     * placeholders.
     *
     * `<`, `>` and `#` go, so every one in a shape is ours. Control, format,
     * private-use and unpaired-surrogate characters go, so an invisible mark
     * cannot split a number into two halves that each look harmless, and
     * every astral-plane character goes with them — no bank alert needs
     * one, and an alphabet of symbols is a way to spell a name past the
     * vocabulary.
     */
    private fun sanitize(body: String?): String {
        val text = Parsing.normalizeText(body).take(MAX_INPUT)
        val out = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch == '<' || ch == '>' || ch == '#' -> out.append(' ')
                Character.isWhitespace(ch) -> out.append(' ')
                else -> when (Character.getType(ch).toByte()) {
                    Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE,
                    Character.SURROGATE, Character.UNASSIGNED -> Unit
                    else -> out.append(ch)
                }
            }
        }
        return SPACES.replace(out.toString(), " ").trim()
    }

    private enum class Kind { WORD, NUMBER, PLACEHOLDER, SPACE, OTHER, NAME }

    private class Tok(val kind: Kind, val text: String)

    private fun kindOf(t: String): Kind {
        val c = t[0]
        return when {
            c in '\uE000'..'\uE0FF' -> Kind.PLACEHOLDER
            Character.isLetter(c) || Character.getType(c).toByte().let {
                it == Character.NON_SPACING_MARK || it == Character.COMBINING_SPACING_MARK ||
                it == Character.ENCLOSING_MARK
            } -> Kind.WORD
            Character.isDigit(c) || Character.getType(c).toByte().let {
                it == Character.LETTER_NUMBER || it == Character.OTHER_NUMBER
            } -> Kind.NUMBER
            c == ' ' -> Kind.SPACE
            else -> Kind.OTHER
        }
    }

    /** Characters that hold one name together: "R. K. Sharma", "Ramesh-Kumar",
     *  "A & B Traders". */
    private val JOINERS = setOf(".", "-", "&", "'", "’")

    /**
     * Whatever follows a payee or greeting marker, up to the next structural
     * word, is a name — even when it happens to be a word the vocabulary
     * would otherwise keep. "Paid to APRIL MAY" must not come out as a pair
     * of months.
     *
     * A single letter is always part of the name: it is an initial, and "A"
     * in "Mr. A. K. Sharma" is otherwise the article "a" and would survive.
     * A full stop joins only after a title or an initial; after a whole word
     * it ends the sentence, and the next sentence is not the payee.
     */
    private fun maskNames(tokens: MutableList<Tok>) {
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.kind == Kind.WORD && t.text.lowercase() in NAME_MARKERS) {
                var first = -1
                var last = -1
                var lastLength = 0
                var j = i + 1
                scan@ while (j < tokens.size) {
                    val u = tokens[j]
                    when {
                        u.kind == Kind.SPACE -> Unit
                        u.kind == Kind.OTHER && u.text == "." ->
                            if (first >= 0 && lastLength > 1) break@scan
                        u.kind == Kind.OTHER && u.text in JOINERS -> Unit
                        u.kind == Kind.WORD &&
                            (u.text.length == 1 || u.text.lowercase() !in BOUNDARY) -> {
                            if (first < 0) first = j
                            last = j
                            lastLength = u.text.length
                        }
                        else -> break@scan
                    }
                    j++
                }
                if (first >= 0) {
                    for (k in last downTo first + 1) tokens.removeAt(k)
                    tokens[first] = Tok(Kind.NAME, NAME)
                }
            }
            i++
        }
    }

    private fun render(tokens: List<Tok>): String {
        val out = StringBuilder()
        for ((i, t) in tokens.withIndex()) {
            when (t.kind) {
                Kind.WORD -> out.append(if (keeps(tokens, i)) t.text else WORD)
                Kind.NUMBER -> out.append(DIGITS)
                Kind.PLACEHOLDER -> out.append(PLACEHOLDERS[t.text[0]] ?: "")
                Kind.NAME -> out.append(NAME)
                Kind.SPACE -> out.append(' ')
                Kind.OTHER -> if (allowed(t.text[0])) out.append(t.text)
            }
        }
        return out.toString()
    }

    /** A month is a date only beside a number. On its own it is as likely to
     *  be someone called April. */
    private fun keeps(tokens: List<Tok>, i: Int): Boolean {
        val w = tokens[i].text.lowercase()
        if (w !in VOCABULARY) return false
        if (w !in MONTHS) return true
        return besideNumber(tokens, i, -1) || besideNumber(tokens, i, 1)
    }

    private fun besideNumber(tokens: List<Tok>, i: Int, step: Int): Boolean {
        var j = i + step
        while (j in tokens.indices) {
            val t = tokens[j]
            when {
                t.kind == Kind.NUMBER -> return true
                t.kind == Kind.SPACE -> Unit
                t.kind == Kind.OTHER && t.text in DATE_JOINERS -> Unit
                else -> return false
            }
            j += step
        }
        return false
    }

    private val DATE_JOINERS = setOf("-", "/", ".", ",", "'", "’")

    /** Punctuation and currency signs carry the format and nothing else.
     *  Letters and digits never reach here — they are tokens of their own. */
    private fun allowed(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
        Character.START_PUNCTUATION, Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION, Character.CURRENCY_SYMBOL,
        Character.MATH_SYMBOL -> true
        else -> false
    }

    private fun finish(raw: String): String {
        val s = SPACES.replace(WORD_RUN.replace(raw, WORD), " ").trim()
        if (s.length <= MAX_SHAPE) return s
        // At a space, so a placeholder is never cut in half.
        val cut = s.lastIndexOf(' ', MAX_SHAPE - 1).takeIf { it > 0 } ?: (MAX_SHAPE - 1)
        return s.substring(0, cut).trimEnd() + "…"
    }

    /** For de-duplication. Over the canonical text, so the receiver's copy and
     *  the inbox's copy of one message — which can differ in invisible marks
     *  and spacing — are one row, not two. */
    fun bodyHash(body: String?): String =
        MessageDigest.getInstance("SHA-256")
            .digest(Parsing.normalizeText(body).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // ── Storage policy ───────────────────────────────────────────────────

    /**
     * Whether a new row should be written into a table holding [stored] rows
     * whose oldest was seen at [oldestAt] (epoch millis, as is [candidateAt]).
     *
     * A full table refuses a message older than everything in it, because it
     * would be evicted by the very write that added it — and the launch scan
     * would then add it again on every launch, a write and a delete per
     * message, forever.
     */
    fun admits(stored: Int, oldestAt: Long?, candidateAt: Long, keep: Int = MAX_ROWS): Boolean =
        stored < keep || oldestAt == null || candidateAt > oldestAt

    /** Rows last seen before this are dropped. */
    fun expiresBefore(now: LocalDateTime): LocalDateTime = now.minusDays(MAX_AGE_DAYS)

    // ── What the user sees, and what sharing sends ───────────────────────

    /** One stored row, as the screens read it. */
    data class Row(
        val sender: String?,
        val shape: String,
        val reason: String,
        val seenCount: Int,
        val lastSeenAt: LocalDateTime,
    )

    /** One format from one sender: what a person can actually act on. */
    data class Group(
        val sender: String?,
        val title: String,
        val shape: String,
        val reason: String,
        val messages: Int,
        val lastSeen: LocalDateTime,
    )

    fun title(sender: String?): String = when {
        sender == null -> "Pasted by you"
        else -> Senders.KNOWN_ENTITIES[sender]?.let { "$it ($sender)" } ?: sender
    }

    fun reasonLabel(reason: String): String = when (reason) {
        REASON_NO_AMOUNT -> "no amount it recognises"
        REASON_NO_VERB -> "no word it knows for money moving"
        REASON_NO_ACCOUNT -> "no account, card or reference"
        else -> "not read"
    }

    /**
     * Rows folded into formats. Distinct messages in one format share a
     * shape — that is what the shape is for — so twelve debits in a format
     * the parser does not know are one line saying twelve, not twelve lines.
     *
     * Most messages first, because the format behind most of the missing
     * transactions is the one worth reporting; then the most recent; then
     * by name, so two equal groups cannot swap places between redraws.
     */
    fun summarize(rows: List<Row>): List<Group> =
        rows.groupBy { it.sender to it.shape }
            .map { (key, members) ->
                val latest = members.maxByOrNull { it.lastSeenAt }!!
                Group(key.first, title(key.first), key.second, latest.reason,
                    members.sumOf { maxOf(1, it.seenCount) }, latest.lastSeenAt)
            }
            .sortedWith(compareByDescending<Group> { it.messages }
                .thenByDescending { it.lastSeen }
                .thenBy { it.sender ?: "" }
                .thenBy { it.shape })

    /**
     * The text a share sends. Built from groups, which hold shapes and
     * nothing else: no hash, no time, and no path back to the message.
     */
    fun export(groups: List<Group>, parserVersion: String = Parsing.PARSER_VERSION): String =
        buildString {
            val total = groups.sumOf { it.messages }
            append("Bank messages SpendWise could not read\n\n")
            append("Each line below is the shape of a message, made on the phone: every ")
            append("number, amount, account or card number, UPI id, email, link, reference ")
            append("and name was replaced before this text existed. The messages themselves ")
            append("are not in it. It does say which bank sent them.\n\n")
            append("Parser ").append(parserVersion).append(". ")
            append(groups.size).append(if (groups.size == 1) " format, " else " formats, ")
            append(total).append(if (total == 1) " message.\n" else " messages.\n")
            for ((n, g) in groups.take(MAX_EXPORTED).withIndex()) {
                append('\n').append(n + 1).append(". ").append(g.title).append(" · ")
                append(g.messages).append(if (g.messages == 1) " message" else " messages")
                append(" · ").append(reasonLabel(g.reason)).append('\n')
                append(g.shape).append('\n')
            }
            if (groups.size > MAX_EXPORTED) {
                append("\n…and ").append(groups.size - MAX_EXPORTED)
                append(" less frequent formats.\n")
            }
        }

    // ── The allowlist ────────────────────────────────────────────────────

    val MONTHS: Set<String> = setOf(
        "january", "february", "march", "april", "may", "june", "july", "august",
        "september", "october", "november", "december",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec")

    /** Payee, payer and greeting markers. What follows one is a name. */
    val NAME_MARKERS: Set<String> = setOf(
        "to", "from", "by", "at", "towards", "toward", "favouring", "favoring",
        "beneficiary", "benef", "payee", "payer", "remitter", "sender", "recipient",
        "dear", "hi", "hello", "mr", "mrs", "ms", "miss", "shri", "smt", "sri", "m/s")

    /** Words that end a name rather than being part of one. */
    private val BOUNDARY: Set<String> = NAME_MARKERS + setOf(
        "your", "you", "the", "a", "an", "is", "was", "has", "have", "been", "be", "not",
        "on", "via", "using", "for", "of", "with", "in", "and", "or", "if", "it", "this",
        "that", "self", "own", "my", "our", "his", "her", "their", "me", "us",
        "upi", "imps", "neft", "rtgs", "nach", "ecs", "ach", "txn", "transaction",
        "ref", "reference", "refno", "utr", "rrn", "a/c", "ac", "acct", "acc", "account",
        "card", "cards", "avl", "bal", "balance", "info", "rs", "inr", "amt", "amount",
        "date", "dated", "atm", "pos", "customer", "user", "sir", "madam", "member",
        "cardholder", "valued", "wallet", "mobile", "no", "number", "call", "sms",
        "charge", "charges", "fee", "fees", "emi", "loan")

    /**
     * Every word a shape may contain. Function words, the vocabulary of
     * money moving, and dates — what a parser has to be taught, and nothing
     * that names a person, a shop or a bank. Bank names are left out on
     * purpose: the sender already says which bank, and several are also
     * surnames.
     */
    val VOCABULARY: Set<String> = MONTHS + NAME_MARKERS + setOf(
        // structure
        "a", "an", "the", "is", "are", "was", "were", "be", "been", "being", "has", "have",
        "had", "will", "shall", "can", "could", "would", "should", "must", "do", "does",
        "did", "done", "not", "no", "yes", "of", "for", "on", "with", "without", "via",
        "using", "use", "used", "in", "into", "as", "and", "or", "if", "then", "than", "so",
        "this", "that", "these", "those", "it", "its", "your", "you", "yours", "our", "we",
        "us", "my", "me", "their", "they", "them", "there", "here", "when", "what", "which",
        "who", "how", "please", "kindly", "pls", "plz", "thank", "thanks", "regards", "team",
        "sir", "madam", "mam", "customer", "customers", "user", "member", "cardholder",
        "holder", "valued", "now", "today", "yesterday", "date", "dated", "time", "day",
        "days", "hrs", "hr", "hour", "hours", "min", "mins", "minute", "minutes", "am", "pm",
        "ist", "upto", "up", "till", "until", "before", "after", "above", "below", "more",
        "less", "total", "new", "other", "only", "also", "any", "all", "each", "every", "per",
        "same", "through", "within", "during", "since", "get", "got", "check", "details",
        "info", "information", "alert", "message", "msg", "sms", "email", "call", "contact",
        "visit", "report", "reply", "never", "anyone", "successful", "successfully",
        "success", "completed", "complete", "processed", "initiated", "pending", "status",
        "made", "effected", "once", "again", "already", "just", "self", "own", "dont",
        "don't", "didn't", "isn't", "it's", "you're", "you've", "we're",
        // money moving
        "account", "accounts", "acct", "acc", "ac", "a/c", "card", "cards", "debit",
        "debited", "credit", "credited", "deducted", "deduction", "charged", "charge",
        "charges", "fee", "fees", "gst", "tax", "tds", "interest", "withdrawn", "withdrawal",
        "withdraw", "deposit", "deposited", "transfer", "transferred", "trf", "tfr", "txn",
        "txns", "transaction", "transactions", "payment", "payments", "paid", "pay",
        "received", "receive", "sent", "send", "spent", "spend", "purchase", "purchased",
        "refund", "refunded", "reversal", "reversed", "ref", "reference", "refno", "num",
        "number", "id", "utr", "rrn", "imps", "neft", "rtgs", "upi", "vpa", "nach", "ecs",
        "ach", "si", "mandate", "autopay", "auto", "emi", "loan", "atm", "pos", "ecom",
        "online", "merchant", "branch", "ifsc", "bank", "banking", "netbanking", "net",
        "mobile", "app", "wallet", "balance", "bal", "avl", "avail", "available", "ledger",
        "clr", "clear", "cleared", "limit", "lmt", "amount", "amt", "rs", "re", "inr",
        "rupees", "rupee", "usd", "eur", "gbp", "aed", "sgd", "dr", "cr", "remarks", "rem",
        "narration", "savings", "sb", "current", "fd", "rd", "cheque", "chq", "presented",
        "returned", "bounced", "salary", "p2p", "p2m", "p2a", "w/d", "collect", "request",
        "block", "blocked", "unblock", "care", "helpline", "toll", "free", "fraud",
        "dispute", "unauthorised", "unauthorized", "reverse", "cash", "value", "visa",
        "mastercard", "rupay", "contactless", "international", "domestic", "foreign",
        "ending", "end", "linked", "statement", "due", "minimum", "outstanding", "order",
        "approval", "auth", "code", "xx",
        // days
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "mon", "tue", "tues", "wed", "thu", "thur", "thurs", "fri", "sat", "sun")
}
