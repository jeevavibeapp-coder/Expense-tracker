import com.jeevavibeapp.spendwise.core.*
import kotlin.random.Random
private fun String.digits(base:Char)=map {if(it in '0'..'9')base+(it-'0') else it}.joinToString("")
fun main(){
 val rnd=Random(20261118)
 var pass=0;var fail=0;var crashes=0;var negative=0;var invented=0
 repeat(10000){i->
  val bad=i%5==0
  var text=if(bad) "INR 100 paid to KFC on 18-Nov-2026 "+(24+rnd.nextInt(6))+":"+(60+rnd.nextInt(6))+" Ref 123456789012"
    else "INR 123.45 debited from account XX1234 to ZOMATO on 18-Nov-2026 at 12:30:45. UTR 123456789012."
  text=when(rnd.nextInt(7)){0->text.uppercase();1->text.replace(" ","  ");2->text.replace(" ","\u200b ");3->text.digits('\u0966');4->text.digits('\u0be6');5->text.digits('\u0c66');else->text}
  try{
   val p=Parsing.parse(text)
   val neg=p.amount!=null && (p.amount!!<0 || !p.amount!!.isFinite())
   val made=bad && p.occurredAt!=null && (p.occurredAt!!.hour!=0 || p.occurredAt!!.minute!=0 || p.occurredAt!!.second!=0)
   if(neg)negative++;if(made)invented++
   if(neg||made){fail++;if(fail<=5)println("FAIL fuzz["+i+"] input="+text+" result="+p)}else pass++
  }catch(t:Throwable){crashes++;fail++;if(crashes<=5)println("FAIL crash["+i+"] "+t)}
 }
 println("seed=20261118 inputs=10000 crashes="+crashes+" negative_or_nonfinite="+negative+" invented_times="+invented)
 println("SolFuzzTest: "+pass+" passed, "+fail+" failed")
 if(fail>0)kotlin.system.exitProcess(1)
}
