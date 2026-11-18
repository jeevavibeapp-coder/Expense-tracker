import com.jeevavibeapp.spendwise.core.*
import java.time.LocalDateTime
fun main(){
 val now=LocalDateTime.of(2026,7,8,12,0)
 val samples=listOf(
 "Rs.100.00 debited from A/c XX1234 on 08-07-26. UPI Ref 512345678901",
 "Rs.5,000.00 credited to your A/c XX9012 on 08-07-26. UPI Ref 512345678901",
 "INR 62,500.00 credited to A/c XX1234 on 01-07-26 towards SALARY JUL26. Ref 998877665544",
 "Refund of Rs.500.00 for your order paid via UPI has been credited to your A/c XX1234 on 08-07-26. Ref 512345678901",
 "Rs.250.00 credited to A/c XX1234 on 08-07-26 as refund of the amount paid to AMAZON. UPI Ref 512345678901",
 "You have received a refund of Rs.120 for the bill paid at DMART. Credited to A/c XX1234 UPI Ref 512345678901",
 "INR 500.00 credited to account XX1234 as refund for the amount paid to AMAZON. UTR 123456789012.",
 "INR 500.00 credited to account XX1234 on 18-Nov-2026 as refund for an item purchased from AMAZON. UTR 123456789012.",
 "INR 600.00 paid to YOUR SALON on 18-Nov-2026 from account XX1234. UTR 123456789012.",
 "INR 500.00 debited from A/C XX1234 on 18-Nov-2026 UPI/P2M/519023481234/ZOMATO/UPI")
 samples.forEachIndexed{i,b->
 val p=Parsing.parse(b)
 println(listOf(i,b,Pipeline.dedupKey(p,p.occurredAt?:now),p.type,p.rawMerchant).joinToString("\t"))
 }
}
