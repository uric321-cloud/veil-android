import app.veil.android.screen.Redaction
import app.veil.android.screen.Severity
import app.veil.android.screen.TextAction
import app.veil.android.screen.TextRuleEngine
import app.veil.android.screen.Tiers

var failures = 0
fun check(name: String, cond: Boolean) {
    if (cond) println("  ok   $name") else { failures++; println("  FAIL $name") }
}

fun idx(vararg entries: Triple<String, String, Severity>): Map<String, Pair<String, Severity>> {
    val m = HashMap<String, Pair<String, Severity>>()
    for ((term, cat, sev) in entries) {
        val n = TextRuleEngine.normalize(term)
        m[n] = cat to sev
        val a = TextRuleEngine.deob(n)
        if (a.isNotEmpty() && a !in m) m[a] = cat to sev
    }
    return m
}

val TERMS = idx(
    Triple("damn", "profanity", Severity.MILD),
    Triple("fuck", "profanity", Severity.STRONG),
    Triple("shit", "profanity", Severity.STRONG),
    Triple("porn", "sexual", Severity.EXPLICIT),
    Triple("ass", "sexual", Severity.STRONG)
)
val PHRASES = listOf(Triple("send nudes", "sexual", Severity.STRONG))

fun engine(tier: String, logOnly: Boolean = false, deob: Boolean = false, allow: Set<String> = emptySet()): TextRuleEngine =
    TextRuleEngine(
        termIndex = TERMS,
        phrases = PHRASES,
        tierActions = Tiers.actions(tier),
        enabledCategories = Tiers.categories(tier) + "custom",
        deobfuscate = deob,
        logOnly = logOnly,
        allow = allow
    )

fun actions(rs: List<Redaction>) = rs.map { it.action }.toSet()

fun main() {
    println("normalize / deob")
    check("normalize strips punctuation", TextRuleEngine.normalize("FUCK!!") == "fuck")
    check("normalize trims apostrophes", TextRuleEngine.normalize("'ass'") == "ass")
    check("deob collapses repeats", TextRuleEngine.deob("shiiit") == "shit")
    check("deob maps leet", TextRuleEngine.deob("p0rn") == "porn")

    println("CHILD tier")
    val child = engine(Tiers.CHILD)
    check("mild -> strike", child.scan("oh damn it").let { it.size == 1 && it[0].action == TextAction.STRIKE })
    check("strong -> bar", child.scan("what the fuck").let { it.size == 1 && it[0].action == TextAction.BAR })
    check("explicit -> bar", child.scan("free porn here").let { it.size == 1 && it[0].action == TextAction.BAR })
    check("word boundary: 'assume' is not 'ass'", child.scan("I assume so").isEmpty())
    val assHit = child.scan("nice ass")
    check("'ass' matched with correct range", assHit.size == 1 && assHit[0].start == 5 && assHit[0].end == 8)
    val phrase = child.scan("please send nudes now")
    check("phrase matched", phrase.size == 1 && phrase[0].start == 7 && phrase[0].end == 17 && phrase[0].action == TextAction.BAR)
    check("clean text -> nothing", child.scan("the quick brown fox").isEmpty())

    println("TEEN tier")
    val teen = engine(Tiers.TEEN)
    check("teen ignores mild", teen.scan("oh damn").isEmpty())
    check("teen strikes strong", teen.scan("this shit").let { it.size == 1 && it[0].action == TextAction.STRIKE })
    check("teen bars explicit", teen.scan("porn").let { it.size == 1 && it[0].action == TextAction.BAR })

    println("ADULT tier")
    val adult = engine(Tiers.ADULT)
    check("adult: profanity category off", adult.scan("this shit").isEmpty())
    check("adult: strong sexual ignored", adult.scan("nice ass").isEmpty())
    check("adult: explicit sexual -> strike", adult.scan("porn").let { it.size == 1 && it[0].action == TextAction.STRIKE })

    println("warn-and-log")
    val log = engine(Tiers.CHILD, logOnly = true)
    check("logOnly turns cover into LOG", log.scan("fuck").let { it.size == 1 && it[0].action == TextAction.LOG })

    println("de-obfuscation toggle")
    check("off: stretched word not caught", engine(Tiers.CHILD, deob = false).scan("fuuuck").isEmpty())
    check("on: stretched word caught", engine(Tiers.CHILD, deob = true).scan("fuuuck").let { it.size == 1 && it[0].action == TextAction.BAR })
    check("on: leet word caught", engine(Tiers.CHILD, deob = true).scan("p0rn").isNotEmpty())

    println("allow list")
    check("allowed word is not flagged", engine(Tiers.CHILD, allow = setOf("ass")).scan("nice ass").isEmpty())

    println("multiple hits in one string")
    val multi = child.scan("damn that porn")
    check("two hits found", multi.size == 2)

    println(if (failures == 0) "ALL PASSED" else "$failures FAILURES")
    if (failures > 0) System.exit(1)
}
