import java.io.File
import kotlin.math.pow
private fun luminance(rgb:Int):Double {
 val v=listOf((rgb shr 16) and 255,(rgb shr 8) and 255,rgb and 255).map { val c=it/255.0; if(c<=0.04045)c/12.92 else ((c+0.055)/1.055).pow(2.4) }
 return .2126*v[0]+.7152*v[1]+.0722*v[2]
}
fun main(args:Array<String>) {
 val rel="android/nativeapp/src/main/java/com/jeevavibeapp/spendwise/ui/Theme.kt"
 val file=args.firstOrNull()?.let { File(it) } ?: listOf(File(rel),File("../"+rel)).first { it.exists() }
 val text=file.readText()
 fun rgb(name:String):Int {
  val rx=Regex("""val\s+"""+name+"""\s*=\s*Color\(0xFF([0-9A-Fa-f]{6})\)""")
  return rx.find(text)?.groupValues?.get(1)?.toInt(16) ?: error("Could not read color "+name+" from "+file)
 }
 val bg=luminance(rgb("LightSurface")); var passed=0;var failed=0
 // SettingsAndOnboarding.ThresholdSlider uses these tints for bodyMedium label text.
 for(name in listOf("Income","Warn")){
  val fg=luminance(rgb(name));val ratio=(maxOf(bg,fg)+.05)/(minOf(bg,fg)+.05)
  if(ratio>=4.5)passed++ else {failed++;println("FAIL S-002 "+name+" normal-text contrast="+ratio+" required>=4.5")}
 }
 println("SolContrastTest: "+passed+" passed, "+failed+" failed")
 if(failed>0)kotlin.system.exitProcess(1)
}