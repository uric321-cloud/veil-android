import app.veil.android.screen.CatalogRule
import app.veil.android.screen.CatalogVerdict

var failures = 0
fun check(name: String, cond: Boolean) {
    if (cond) println("  ok   $name") else { failures++; println("  FAIL $name") }
}

fun main() {
    val rules = listOf(
        CatalogRule("victoriassecret.com", "/lounge", false, "loungewear"),
        CatalogRule("victoriassecret.com", "/lingerie", true, "lingerie"),
        CatalogRule("victoriassecret.com", "/swim", true, "swimwear"),
        CatalogRule("reddit.com", "/r/nsfw", true, "adult"),
    )

    println("mixed-site sections")
    check("loungewear is explicitly allowed", CatalogVerdict.verdict("https://www.victoriassecret.com/lounge/robes", rules) == (false to "loungewear"))
    check("lingerie section is blocked", CatalogVerdict.verdict("https://victoriassecret.com/lingerie/bras", rules) == (true to "lingerie"))
    check("swim section is blocked", CatalogVerdict.verdict("https://victoriassecret.com/swim", rules) == (true to "swimwear"))
    check("bare domain has no opinion", CatalogVerdict.verdict("https://victoriassecret.com/", rules) == null)
    check("subdomain still matches", CatalogVerdict.verdict("https://m.victoriassecret.com/lingerie", rules) == (true to "lingerie"))

    println("other sites / safety")
    check("reddit nsfw blocked", CatalogVerdict.verdict("https://reddit.com/r/nsfw/top", rules) == (true to "adult"))
    check("unrelated site: no opinion", CatalogVerdict.verdict("https://wikipedia.org/wiki/Cat", rules) == null)
    check("empty url: no opinion", CatalogVerdict.verdict("", rules) == null)
    check("no rules: no opinion", CatalogVerdict.verdict("https://victoriassecret.com/lingerie", emptyList()) == null)

    println(if (failures == 0) "ALL PASSED" else "$failures FAILURES")
    if (failures > 0) System.exit(1)
}
