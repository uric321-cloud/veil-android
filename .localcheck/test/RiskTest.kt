import app.veil.android.screen.RiskClassifier

var failures = 0
fun check(name: String, cond: Boolean) {
    if (cond) println("  ok   $name") else { failures++; println("  FAIL $name") }
}

fun main() {
    println("on-device risk detection (category only)")

    check("grooming: secret", RiskClassifier.categories("hey keep this our little secret ok?").contains(RiskClassifier.GROOMING))
    check("grooming: don't tell parents", RiskClassifier.categories("don't tell your parents we talk").contains(RiskClassifier.GROOMING))
    check("sextortion: leak threat", RiskClassifier.categories("send money or i'll post your photos").contains(RiskClassifier.SEXTORTION))
    check("self-harm", RiskClassifier.categories("i just want to die lately").contains(RiskClassifier.SELF_HARM))
    check("bullying: phrase", RiskClassifier.categories("nobody likes you, go die").contains(RiskClassifier.BULLYING))
    check("bullying: kys with boundary", RiskClassifier.categories("just kys honestly").contains(RiskClassifier.BULLYING))

    // Self vs. other is distinguished.
    check("kill myself is self-harm not bullying",
        RiskClassifier.categories("i might kill myself").let { it.contains(RiskClassifier.SELF_HARM) && !it.contains(RiskClassifier.BULLYING) })
    check("kill yourself is bullying not self-harm",
        RiskClassifier.categories("you should kill yourself").let { it.contains(RiskClassifier.BULLYING) && !it.contains(RiskClassifier.SELF_HARM) })

    // Low false positives on ordinary text, and never returns the text itself.
    check("ordinary text: nothing", RiskClassifier.categories("what time is dinner? i'll be home late").isEmpty())
    check("empty: nothing", RiskClassifier.categories("").isEmpty())
    check("null: nothing", RiskClassifier.categories(null).isEmpty())
    check("kys substring 'skys' does NOT match", RiskClassifier.categories("the skys are blue today").isEmpty())

    println(if (failures == 0) "ALL PASSED" else "$failures FAILURES")
    if (failures > 0) System.exit(1)
}
