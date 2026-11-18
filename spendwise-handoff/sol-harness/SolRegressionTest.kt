import com.jeevavibeapp.spendwise.core.*
import java.time.LocalDateTime
private var pass=0
private var fail=0
private val now=LocalDateTime.of(2026,11,18,12,0)
private fun ck(id:String,ok:Boolean,actual:Any?) { if(ok) pass++ else {fail++;println("FAIL "+id+" actual="+actual)} }
private fun capture(body:String)=Pipeline.ingest("VM-HDFCBK",body,now,{null},{raw,_,_->
    val seed=Engine.seedLookup(Engine.normalizeMerchant(raw))
    Pipeline.Resolution(seed?.first,seed?.second,if(seed==null)0 else 90)
})
fun main(){
 val completed=listOf(
 "Cashback of Rs.100.00 credited to your account XX1234. Ref 123456789012." to "income",
 "INR 399.00 debited from your account XX1234 for mobile recharge. UPI Ref 123456789012." to "expense",
 "Rs.500.00 credited to account XX1234 for reversed UPI transaction. Ref 123456789012." to "income",
 "Rs.500.00 refunded to your A/c XX1234 on 18-Nov-2026." to "income")
 completed.forEachIndexed {i,pair->val r=capture(pair.first);ck("K1-"+i,r is Pipeline.Outcome.Capture && r.parsed.type==pair.second,r)}
 val refund="INR 500.00 credited to account XX1234 as refund for the amount paid to AMAZON. UTR 123456789012."
 val p=Parsing.parse(refund);ck("K2-income",p.type=="income",p);ck("K2-merchant",p.rawMerchant=="AMAZON",p)
 val earlier=Parsing.parse("INR 500.00 credited to account XX1234 as refund of the amount debited earlier. UTR 123456789012.")
 ck("K2-historical-debit",earlier.type=="income",earlier)
 val debit="INR 500.00 debited from account XX1234 to AMAZON on 18-Nov-2026 at 12:00."
 val evening=debit.replace("12:00","18:00")
 val credit="INR 500.00 credited to account XX1234 from AMAZON on 18-Nov-2026 at 12:05."
 fun key(s:String)=(capture(s) as Pipeline.Outcome.Capture).dedupKey
 ck("K3-replay",key(debit)==key(debit),key(debit))
 ck("K3-two-purchases",key(debit)!=key(evening),listOf(key(debit),key(evening)))
 ck("K3-debit-credit",key(debit)!=key(credit),listOf(key(debit),key(credit)))
 val axis=Parsing.parse("INR 500.00 debited from A/C XX1234 on 18-Nov-2026 UPI/P2M/519023481234/ZOMATO/UPI")
 ck("K3-axis-reference",axis.referenceNumber=="519023481234",axis)
 ck("K4-prefix",Engine.seedLookup("AMAZON CAFE")==null,Engine.seedLookup("AMAZON CAFE"))
 ck("K4-instamart",Engine.seedLookup("SWIGGY INSTAMART")?.second=="Groceries",Engine.seedLookup("SWIGGY INSTAMART"))
 val expense=Tx("purchase",500.0,"expense",now.plusMinutes(2),null,null)
 ck("K5-credit-control",Fraud.evaluate(expense,listOf(Tx("credit",500.0,"income",now,"RAHUL",null))).isEmpty(),"credit then expense")
 val alerts=Fraud.evaluate(expense,listOf(Tx("other",500.0,"expense",now,"Grocery Shop",null)))
 ck("K5-unresolved",alerts.isEmpty(),alerts)
 for((id,stamp,want) in listOf(
 Triple("utc","2026-11-18T00:30:00Z",LocalDateTime.of(2026,11,18,6,0)),
 Triple("positive","2026-11-18T00:30:00+05:30",LocalDateTime.of(2026,11,18,0,30)),
 Triple("negative","2026-11-18T00:30:00-05:00",LocalDateTime.of(2026,11,18,11,0)))){
 val text="""{"app":"SpendWise","format":1,"tables":{"transactions":[{"id":""" + "\""+id+"\"" + ""","amount":100,"type":"expense","occurred_at":""" + "\""+stamp+"\"" + ""","created_at":"""+"\""+stamp+"\""+"""}]}}"""
 val result=Backup.read(text,now)
 val actual=(result as? Backup.Read.Ok)?.doc?.transactions?.singleOrNull()?.occurredAt
 ck("K7-"+id,actual==want,result)
 }
 val clock=Parsing.parse("Rs.100 paid to KFC on 18-Nov-2026 23:30:45 Ref 123456789012")
 ck("T1-seconds",clock.occurredAt==LocalDateTime.of(2026,11,18,23,30,45),clock)
 val invalid=Parsing.parse("Rs.100 paid to KFC on 18-Nov-2026 25:61 Ref 123456789012")
 ck("T1-invalid",invalid.occurredAt==null || invalid.occurredAt==LocalDateTime.of(2026,11,18,0,0),invalid)
 val salon=Parsing.parse("INR 600.00 paid to YOUR SALON on 18-Nov-2026 from account XX1234. UTR 123456789012.")
 ck("S-001-your-merchant",salon.rawMerchant=="YOUR SALON",salon)
 println("SolRegressionTest: "+pass+" passed, "+fail+" failed")
 if(fail>0) kotlin.system.exitProcess(1)
}
