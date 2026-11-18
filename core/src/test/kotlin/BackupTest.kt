import com.jeevavibeapp.spendwise.core.Backup
import java.time.LocalDate
import java.time.LocalDateTime

private var passed = 0
private var failed = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else { failed++; println("  FAIL  $name${if (detail.isNotEmpty()) "  [$detail]" else ""}") }
}

/** Pinned clock. Restoring is one of the few paths that falls back to "now"
 *  for a missing timestamp, and a test that reads the wall clock proves
 *  nothing about what it produced. */
private val NOW = LocalDateTime.of(2026, 8, 25, 12, 0)

/** A ledger, as far as a restore can tell: the keys already taken and the
 *  rows a plan added. Standing in for Room here is the point — the plan is
 *  supposed to be checkable without a database. */
private class Ledger {
    val categories = mutableListOf<Backup.Category>()
    val merchants = mutableListOf<Backup.Merchant>()
    val learning = mutableListOf<Backup.Learned>()
    val transactions = mutableListOf<Backup.Txn>()
    val senders = mutableListOf<Backup.Sender>()

    fun apply(plan: Backup.Plan) {
        if (plan.clearFirst) {
            categories.clear(); merchants.clear(); learning.clear()
            transactions.clear(); senders.clear()
        }
        categories += plan.categories
        merchants += plan.merchants
        learning += plan.learning
        transactions += plan.transactions
        senders += plan.senders
    }

    fun existing() = Backup.Existing(
        transactionIds = transactions.map { it.id }.toSet(),
        dedupKeys = transactions.mapNotNull { it.dedupKey }.toSet(),
        categoryIds = categories.map { it.id }.toSet(),
        categoryNames = categories.map { it.name }.toSet(),
        merchantIds = merchants.map { it.id }.toSet(),
        merchantNames = merchants.map { it.canonicalName }.toSet(),
        learningIds = learning.map { it.id }.toSet(),
        learningRawNames = learning.map { it.rawName }.toSet(),
        senderIds = senders.map { it.id }.toSet(),
        senderNames = senders.map { it.sender }.toSet(),
    )
}

private fun sampleDoc() = Backup.Document(
    createdAt = Backup.stamp(NOW),
    categories = listOf(
        Backup.Category("c1", "Food", icon = "utensils", color = "#ff6b81",
            budgetAmount = 8000.0),
        Backup.Category("c2", "Travel", budgetAmount = null, isArchived = true),
    ),
    merchants = listOf(
        Backup.Merchant("m1", "Swiggy", "c1"),
        Backup.Merchant("m2", "कैफ़े लोटा", null),
    ),
    learning = listOf(
        Backup.Learned("l1", "swiggy*order", "Swiggy", "c1",
            confirmationCount = 4, sampleCount = 9, avgAmount = 412.5,
            amountMin = 120.0, amountMax = 980.0,
            hourHistogram = "0,0,1,0,0,0,0,0,0,0,2,3,0,0,0,0,0,0,4,1,0,0,0,0",
            lastSeenAt = LocalDateTime.of(2026, 8, 20, 9, 30)),
    ),
    transactions = listOf(
        Backup.Txn("t1", 1234.5, "expense",
            LocalDateTime.of(2026, 7, 4, 19, 5), LocalDateTime.of(2026, 7, 4, 19, 6),
            categoryId = "c1", rawMerchant = "SWIGGY*ORDER", merchantName = "Swiggy",
            notes = "dinner \"with\" R\nsplit later", referenceNumber = "553201998877",
            source = "sms", confidence = 84, status = "confirmed",
            dedupKey = "abc123"),
        Backup.Txn("t2", 60000.0, "income",
            LocalDateTime.of(2026, 8, 1, 10, 0), LocalDateTime.of(2026, 8, 1, 10, 0),
            merchantName = "Payroll"),
    ),
    senders = listOf(
        Backup.Sender("s1", "HDFCBK",
            LocalDateTime.of(2026, 1, 2, 8, 0), LocalDateTime.of(2026, 8, 24, 21, 15),
            display = "HDFC Bank", kind = "bank", bank = "HDFC", trust = "trusted",
            messageCount = 190, capturedCount = 140, confirmedCount = 138),
    ),
    prefs = Backup.Prefs("INR", "dark", 80, 50, 5000.0),
)

/** A file as a user could hand-edit it, with only the fields it needs. */
private fun file(tables: String, settings: String = "{}", format: String = "1",
                 app: String = "SpendWise") =
    """{"format":$format,"app":"$app","created_at":"2026-08-25T12:00:00",""" +
        """"tables":$tables,"settings":$settings}"""

private fun readOk(raw: String): Backup.Read.Ok {
    val result = Backup.read(raw, NOW)
    if (result is Backup.Read.Ok) return result
    error("expected a readable backup, got ${(result as Backup.Read.Rejected).reason}")
}

private fun reasonFor(raw: String): String {
    val result = Backup.read(raw, NOW)
    return (result as? Backup.Read.Rejected)?.reason ?: ""
}

fun main() {
    println("=== a backup carries the ledger, never the inbox ===")
    val text = Backup.write(sampleDoc())
    check("transactions are in the file", text.contains("\"transactions\""))
    check("categories are in the file", text.contains("\"categories\""))
    check("merchants are in the file", text.contains("\"merchants\""))
    check("learning is in the file", text.contains("\"learning\""))
    check("senders are in the file", text.contains("\"sms_senders\""))
    check("settings are in the file", text.contains("\"settings\""))
    for (forbidden in listOf("\"body\"", "\"sms_body\"", "\"quarantine\"",
            "\"sms_sender\"", "\"parse_misses\"", "\"indicators\"")) {
        check("no $forbidden field is written", !text.contains(forbidden))
    }

    println("=== a backup round-trips ===")
    val back = readOk(text).doc
    check("every transaction comes back", back.transactions == sampleDoc().transactions,
        back.transactions.toString())
    check("every category comes back", back.categories == sampleDoc().categories)
    check("every merchant comes back", back.merchants == sampleDoc().merchants)
    check("learning comes back whole", back.learning == sampleDoc().learning)
    check("senders come back whole", back.senders == sampleDoc().senders)
    check("preferences come back", back.prefs == sampleDoc().prefs, back.prefs.toString())
    check("a merchant name outside ASCII survives",
        back.merchants.any { it.canonicalName == "कैफ़े लोटा" })
    check("quotes and newlines in a note survive",
        back.transactions[0].notes == "dinner \"with\" R\nsplit later",
        back.transactions[0].notes ?: "")
    check("re-writing what was read gives the same file", Backup.write(back) == text)

    println("=== a damaged file is refused, and no plan is produced ===")
    val refusals = listOf(
        "empty" to Backup.read("   ", NOW),
        "not json" to Backup.read("id,amount\n1,20", NOW),
        "json but not an object" to Backup.read("[1,2,3]", NOW),
        "another app" to Backup.read(file("{}", app = "LedgerPro"), NOW),
        "no format" to Backup.read("""{"app":"SpendWise","tables":{}}""", NOW),
        "newer format" to Backup.read(file("{}", format = "9"), NOW),
        "no tables" to Backup.read("""{"format":1,"app":"SpendWise"}""", NOW),
        "damaged section" to Backup.read(file("""{"transactions":5}"""), NOW),
        "not text at all" to Backup.read(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x41), NOW),
    )
    for ((name, result) in refusals) {
        check("$name is refused", result is Backup.Read.Rejected)
    }
    for ((name, result) in refusals) {
        val reason = (result as? Backup.Read.Rejected)?.reason ?: ""
        check("$name is refused in a sentence, not a type name",
            reason.endsWith(".") && reason.contains(" ") &&
                !reason.contains("Exception") && !reason.contains("kotlin."), reason)
    }
    check("an empty file says so", reasonFor("") == "That file is empty.")
    check("a CSV is pointed at Import", reasonFor("id,amount\n1,20").contains("Import"))
    check("a newer backup names the format it is",
        reasonFor(file("{}", format = "9")).contains("format 9"))
    check("a damaged section is named",
        reasonFor(file("""{"transactions":5}""")) ==
            "The transactions section of that backup is damaged.")
    check("the sms_senders section is named the way the app names it",
        reasonFor(file("""{"sms_senders":"nope"}""")) ==
            "The senders section of that backup is damaged.")
    check("a missing section is simply empty, not damage",
        readOk(file("""{"categories":[]}""")).doc.transactions.isEmpty())

    // Nesting is bounded so that a file built to recurse cannot take the
    // process down with the stack rather than a refusal.
    val deep = "[".repeat(4000) + "]".repeat(4000)
    check("a file nested beyond all reason is refused, not fatal",
        Backup.read(deep, NOW) is Backup.Read.Rejected)

    val damaged = Backup.planFrom(file("""{"transactions":5}"""), Backup.Existing(), false, NOW)
    check("a damaged file yields no plan at all", damaged is Backup.Restore.Rejected)
    val healthy = Backup.planFrom(text, Backup.Existing(), false, NOW)
    check("a good file yields a plan", healthy is Backup.Restore.Ready)

    println("=== nothing in the file is trusted ===")
    val hostile = readOk(file("""{"transactions":[
        {"id":"good","amount":"1,299.50","type":"expense","occurred_at":"2026-07-01T10:00"},
        {"id":"","amount":10,"type":"expense","occurred_at":"2026-07-01T10:00"},
        {"id":"${"x".repeat(80)}","amount":10,"type":"expense","occurred_at":"2026-07-01T10:00"},
        {"amount":10,"type":"expense","occurred_at":"2026-07-01T10:00"},
        {"id":"zero","amount":0,"type":"expense","occurred_at":"2026-07-01T10:00"},
        {"id":"neg","amount":-500,"type":"expense","occurred_at":"2026-07-01T10:00"},
        {"id":"huge","amount":1e13,"type":"expense","occurred_at":"2026-07-01T10:00"},
        {"id":"words","amount":"abc","type":"expense","occurred_at":"2026-07-01T10:00"},
        {"id":"transfer","amount":10,"type":"transfer","occurred_at":"2026-07-01T10:00"},
        {"id":"undated","amount":10,"type":"expense"},
        {"id":"nonsense-date","amount":10,"type":"expense","occurred_at":"whenever"},
        "not even a row"
    ]}""".replace("\n", ""))).doc
    check("a hand-typed amount with a comma is still an amount",
        hostile.transactions.size == 1 && hostile.transactions[0].amount == 1299.5,
        hostile.transactions.toString())
    check("eleven unusable rows are dropped, not fatal",
        hostile.dropped["transactions"] == 11, hostile.dropped.toString())
    check("dropped rows are counted for the user", Backup.summarise(hostile).dropped == 11)

    val edited = readOk(file("""{"transactions":[
        {"id":"t1","amount":10,"type":"expense","occurred_at":"2026-07-01T10:00",
         "notes":"${"n".repeat(9000)}","source":null,"status":null,
         "confidence":9000,"is_deleted":"yes","category_prompted":1}
    ]}""".replace("\n", ""))).doc.transactions[0]
    check("an enormous note is cut to what the app will store",
        edited.notes?.length == 2000, edited.notes?.length.toString())
    check("an emptied source falls back rather than storing null",
        edited.source == "restore" && edited.status == "confirmed")
    check("a confidence of 9000 is clamped", edited.confidence == 100)
    check("1 means true", edited.categoryPrompted)
    check("a word that is not true is false", !edited.isDeleted)

    val bad = readOk(file("""{"categories":[{"id":"c1"},{"id":"c2","name":"Food"}],
        "merchants":[{"id":"m1"},{"id":"m2","canonical_name":"Swiggy"}],
        "learning":[{"id":"l1","merchant_name":"Swiggy"},
                    {"id":"l2","raw_name":"swiggy*x","merchant_name":"Swiggy"}],
        "sms_senders":[{"id":"s1"},{"id":"s2","sender":"HDFCBK"}]}""".replace("\n", ""))).doc
    check("a category with no name is dropped", bad.categories.size == 1)
    check("a merchant with no name is dropped", bad.merchants.size == 1)
    check("a learned mapping with no raw name is dropped", bad.learning.size == 1)
    check("a sender with no sender is dropped", bad.senders.size == 1)
    check("a sender with no dates still restores, dated now",
        bad.senders[0].firstSeenAt == NOW && bad.senders[0].lastSeenAt == NOW)

    println("=== preferences are clamped, not obeyed ===")
    val prefs = readOk(file("{}", """{"currency":"INR","theme":"neon",
        "auto_save_threshold":900,"confirm_threshold":-20,
        "high_value_amount":"12,000"}""".replace("\n", ""))).doc.prefs
    check("a 900% auto-save threshold is clamped to 100", prefs?.autoSaveThreshold == 100)
    check("a negative threshold is clamped to 0", prefs?.confirmThreshold == 0)
    check("a theme the app does not have is left alone", prefs?.theme == null)
    check("a high-value limit goes through the amount gate",
        prefs?.highValueAmount == 12000.0, prefs?.highValueAmount.toString())
    val junkPrefs = readOk(file("{}", """{"auto_save_threshold":"soon",
        "high_value_amount":"lots"}""".replace("\n", ""))).doc.prefs
    check("an unreadable threshold leaves the device setting alone",
        junkPrefs?.autoSaveThreshold == null && junkPrefs?.highValueAmount == null)

    println("=== merging is idempotent ===")
    val doc = readOk(text).doc
    val ledger = Ledger()
    val first = Backup.plan(doc, ledger.existing())
    check("the first restore adds everything the file carries",
        first.totalAdded == 2 + 2 + 1 + 2 + 1, first.added.toString())
    check("nothing was skipped on a fresh ledger", first.totalSkipped == 0)
    check("a merge does not clear the ledger", !first.clearFirst)
    ledger.apply(first)

    val second = Backup.plan(doc, ledger.existing())
    check("restoring the same file again adds nothing",
        second.totalAdded == 0, second.added.toString())
    check("and says so, row by row", second.totalSkipped == first.totalAdded,
        second.skipped.toString())
    ledger.apply(second)
    check("the ledger is unchanged by the second restore",
        ledger.transactions.size == 2 && ledger.categories.size == 2 &&
            ledger.merchants.size == 2 && ledger.learning.size == 1 &&
            ledger.senders.size == 1)

    println("=== what counts as already here ===")
    val sameNames = Backup.plan(doc, Backup.Existing(
        categoryNames = setOf("Food"), merchantNames = setOf("Swiggy"),
        learningRawNames = setOf("swiggy*order"), senderNames = setOf("HDFCBK"),
        dedupKeys = setOf("abc123")))
    check("a category the ledger already has under another id is skipped",
        sameNames.categories.none { it.name == "Food" })
    check("a merchant already known by name is skipped",
        sameNames.merchants.none { it.canonicalName == "Swiggy" })
    check("a learned mapping already known by raw name is skipped",
        sameNames.learning.isEmpty())
    check("a sender already known is skipped", sameNames.senders.isEmpty())
    check("a transaction whose dedup key is already banked is skipped",
        sameNames.transactions.none { it.dedupKey == "abc123" })
    check("the rest of the file still restores",
        sameNames.transactions.size == 1 && sameNames.categories.size == 1)

    val twinned = readOk(file("""{"categories":[
        {"id":"c1","name":"Food"},{"id":"c1","name":"Fuel"},{"id":"c9","name":"Food"}]}"""
        .replace("\n", ""))).doc
    check("a file that repeats an id inside itself only adds it once",
        Backup.plan(twinned).categories.size == 1)

    println("=== replacing is the new-phone path ===")
    val replaced = Backup.plan(doc, ledger.existing(), replace = true)
    check("replacing clears first", replaced.clearFirst)
    check("and then restores the whole file over an occupied ledger",
        replaced.totalAdded == first.totalAdded, replaced.added.toString())
    ledger.apply(replaced)
    check("the ledger holds one copy, not two", ledger.transactions.size == 2)

    println("=== a row never points at a category that is not there ===")
    val dangling = readOk(file("""{"categories":[{"id":"c1","name":"Food"}],
        "merchants":[{"id":"m1","canonical_name":"Swiggy","category_id":"c1"},
                     {"id":"m2","canonical_name":"Blinkit","category_id":"gone"}],
        "transactions":[
          {"id":"t1","amount":10,"type":"expense","occurred_at":"2026-07-01T10:00","category_id":"c1"},
          {"id":"t2","amount":10,"type":"expense","occurred_at":"2026-07-01T10:00","category_id":"gone"},
          {"id":"t3","amount":10,"type":"expense","occurred_at":"2026-07-01T10:00","category_id":"ledger"}]}"""
        .replace("\n", ""))).doc
    val resolved = Backup.plan(dangling, Backup.Existing(categoryIds = setOf("ledger")))
    check("a category the same file carries is kept",
        resolved.transactions[0].categoryId == "c1")
    check("a category nothing carries is dropped to none, and the row still restores",
        resolved.transactions[1].categoryId == null && resolved.transactions.size == 3)
    check("a category already in the ledger is kept",
        resolved.transactions[2].categoryId == "ledger")
    check("the same rule applies to merchants",
        resolved.merchants[0].categoryId == "c1" && resolved.merchants[1].categoryId == null)

    println("=== the confirmation screen has something to show ===")
    val summary = readOk(text).summary
    check("it says when the backup was taken", summary.createdAt == Backup.stamp(NOW))
    check("it counts each section",
        summary.transactions == 2 && summary.categories == 2 && summary.merchants == 2 &&
            summary.learning == 1 && summary.senders == 1)
    check("it says how far back the ledger goes",
        summary.first == LocalDate.of(2026, 7, 4) && summary.last == LocalDate.of(2026, 8, 1),
        "${summary.first}..${summary.last}")
    check("an empty backup has no date range",
        Backup.summarise(readOk(file("{}")).doc).first == null)

    println("=== a file written by the Python build still restores ===")
    // Older backups are ISO text with snake_case keys and columns this build
    // no longer has. Extra fields are ignored rather than refused: a restore
    // that fails because the file is too old helps nobody.
    val legacy = readOk("""{"format":1,"app":"SpendWise","created_at":"2026-03-01T08:00:00",
        "tables":{"categories":[{"id":"c1","name":"Food","type":"expense","icon":"Tag",
            "color":"#6366f1","budget_amount":null,"is_archived":0}],
        "learning":[{"id":"l1","raw_name":"swiggy","merchant_id":"m1","merchant_name":"Swiggy",
            "confidence":72,"hour_histogram":"[]"}],
        "transactions":[{"id":"t1","amount":250.0,"type":"expense","occurred_at":"2026-02-28T19:04:00",
            "created_at":"2026-02-28T19:04:00","merchant_id":"m1","source":"sms","is_deleted":0}]},
        "settings":{"currency":"INR","theme":"system","auto_save_threshold":80}}"""
        .replace("\n", "")).doc
    check("its transactions restore", legacy.transactions.size == 1)
    check("a 0/1 flag is read as false", !legacy.transactions[0].isDeleted)
    check("columns this build dropped are ignored, not fatal",
        legacy.learning.size == 1 && legacy.learning[0].rawName == "swiggy")
    check("its settings restore", legacy.prefs?.currency == "INR")

    println()
    println("=".repeat(60))
    println("$passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
