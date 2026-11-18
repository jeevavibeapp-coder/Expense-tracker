
import com.jeevavibeapp.spendwise.core.*
import java.time.LocalDateTime

fun main() {
    val now = LocalDateTime.of(2026,11,18,12,0)
    val samples = linkedMapOf(
        "old9_otp" to "Use OTP 123456 for your purchase of Rs.5,000.00 at AMAZON. Do not share it.",
        "old9_failed" to "Your UPI payment of Rs.500.00 to AMAZON has failed. No amount has been debited.",
        "old9_refunded" to "Rs.500.00 refunded to your A/c XX1234 on 18-Nov-2026.",
        "old9_mixed_refund" to "Rs.500.00 credited to your account as refund for the amount paid to AMAZON.",
        "old10_account" to "Rs.450.00 debited from a/c XX1234 on 05-Jan-2024 to ZOMATO Ref 883120114455 UPI",
        "old11_time" to "Rs.100 paid to KFC on 18-Nov-2026 23:30:45 Ref 123456789012",
        "old11_bad_time" to "Rs.100 paid to KFC on 18-Nov-2026 25:61 Ref 123456789012",
        "refund_with_evidence" to "INR 500.00 credited to account XX1234 as refund for the amount paid to AMAZON. UTR 123456789012.",
        "cashback_credit" to "Cashback of Rs.100.00 credited to your account XX1234. Ref 123456789012.",
        "completed_recharge" to "INR 399.00 debited from your account XX1234 for mobile recharge. UPI Ref 123456789012.",
        "completed_reversal" to "Rs.500.00 credited to account XX1234 for reversed UPI transaction. Ref 123456789012.",
        "false_seed" to "INR 450.00 debited from account XX1234 to AMAZON CAFE on 18-Nov-2026 Ref 123456789012.",
        "dedup_no_ref_noon" to "INR 500.00 debited from account XX1234 to AMAZON on 18-Nov-2026 at 12:00.",
        "dedup_no_ref_evening" to "INR 500.00 debited from account XX1234 to AMAZON on 18-Nov-2026 at 18:00.",
        "dedup_credit" to "INR 500.00 credited to account XX1234 from AMAZON on 18-Nov-2026 at 12:05.",
        "sbi" to "A/C X9218 debited by 199.0 on date 08Jul26 trf to SWIGGY Refno 553201998877",
        "icici" to "ICICI Bank Acct XX1234 debited for INR 499.00 on 18-Nov-2026; SWIGGY credited. UPI Ref 123456789012.",
        "axis" to "INR 500.00 debited from A/C XX1234 on 18-Nov-2026 UPI/P2M/519023481234/ZOMATO/UPI",
        "kotak" to "Sent Rs.20.00 from Kotak Bank AC X1234 to swiggy8@ybl on 18-Nov-2026"
    )
    for ((name,body) in samples) {
        val p=Parsing.parse(body)
        val sender=Senders.assess("VM-HDFCBK",body)
        val result=Pipeline.ingest("VM-HDFCBK",body,now,{null},{ raw,_,_ ->
            val normalized=Engine.normalizeMerchant(raw)
            val seed=Engine.seedLookup(normalized)
            Pipeline.Resolution(seed?.first ?: normalized,seed?.second,if(seed==null) 0 else 90)
        })
        val detail=when(result) {
            is Pipeline.Outcome.Capture -> "Capture;merchant="+result.merchantName+";status="+result.status+";confidence="+result.confidence+";key="+result.dedupKey
            is Pipeline.Outcome.Hold -> "Hold;risk="+result.verdict.risk
            else -> "NotFinancial"
        }
        println(listOf(name,p.amount,p.type,p.rawMerchant,p.referenceNumber,p.occurredAt,p.matched,p.looksTransactional,sender.action,sender.risk,detail).joinToString("\t"))
    }
    for (sender in listOf("VM-HDFCBK","AD-NEWBNK","+919812345678",null)) {
        val body=samples.getValue("old10_account")
        println("SENDER\t"+sender+"\t"+Senders.assess(sender,body))
    }
    val hist=List(24) { if(it==12) 4 else 0 }
    for(name in listOf("Kumar Cafe","Kumar Canteen")) {
        val row=LearningRow("KUMAR",name,"food",4,0,4,100.0,100.0,100.0,hist)
        println("OLD_TIE_SCORE\t"+name+"\t"+Engine.score(row,100.0,"food",now))
    }
    val credit=Tx("credit",500.0,"income",now,"RAHUL",null)
    val expense=Tx("purchase",500.0,"expense",now.plusMinutes(2),null,null)
    println("OLD_FRAUD_CREDIT\t"+Fraud.evaluate(expense,listOf(credit)))
    val otherExpense=Tx("groceries",500.0,"expense",now,"Grocery Shop",null)
    println("FRAUD_UNRESOLVED\t"+Fraud.evaluate(expense,listOf(otherExpense)))
    val prior=List(8){i->Tx("old"+i,50.0,"expense",now.minusHours(1).plusMinutes(i.toLong()),"KFC",null)}
    println("FRAUD_ZERO_VARIANCE\t"+Fraud.evaluate(Tx("large",50000.0,"expense",now,"KFC",null),prior))
    val backup="""{"app":"SpendWise","format":1,"tables":{"transactions":[{"id":"utc","amount":100,"type":"expense","occurred_at":"2026-11-18T00:30:00Z","created_at":"2026-11-18T00:30:00Z"},{"id":"positive","amount":100,"type":"expense","occurred_at":"2026-11-18T00:30:00+05:30","created_at":"2026-11-18T00:30:00+05:30"},{"id":"negative","amount":100,"type":"expense","occurred_at":"2026-11-18T00:30:00-05:00","created_at":"2026-11-18T00:30:00-05:00"}]}}"""
    val read=Backup.read(backup,now)
    if(read is Backup.Read.Ok) println("BACKUP_OFFSETS\t"+read.doc.transactions.map{it.id+":"+it.occurredAt}+"\tdropped="+read.doc.dropped)
    else println("BACKUP_OFFSETS\t"+read)
}
