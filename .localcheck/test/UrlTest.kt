import app.veil.android.screen.UrlVerdict

var failures = 0
fun check(name: String, cond: Boolean) {
    if (cond) println("  ok   $name") else { failures++; println("  FAIL $name") }
}

fun main() {
    val parts = listOf("reddit.com/r/", "/explore")
    val keywords = listOf("porn", "sex", "xxx")
    val allow = listOf("essex.gov.uk", "sexeducation.org")

    println("host parsing")
    check("https with path", UrlVerdict.hostOf("https://www.reddit.com/r/all") == "reddit.com")
    check("no scheme", UrlVerdict.hostOf("example.com/x") == "example.com")
    check("strips port and query", UrlVerdict.hostOf("http://site.com:8080/a?b=c") == "site.com")

    println("path rules")
    check("blocks a blocked path", UrlVerdict.blocked("https://reddit.com/r/something", parts, keywords, allow) != null)
    check("allows the bare host", UrlVerdict.blocked("https://reddit.com/", parts, keywords, allow) == null)
    check("blocks /explore", UrlVerdict.blocked("https://instagram.com/explore/tags", parts, keywords, allow) != null)

    println("keywords in path/query only")
    check("blocks ?q=porn", UrlVerdict.blocked("https://g.com/search?q=porn", parts, keywords, allow) != null)
    check("blocks /sex/", UrlVerdict.blocked("https://site.com/sex/info", parts, keywords, allow) != null)
    check("does NOT block essex.gov.uk host", UrlVerdict.blocked("https://essex.gov.uk/news", parts, keywords, allow) == null)
    check("keyword not matched inside a word", UrlVerdict.blocked("https://site.com/essex", parts, keywords, allow) == null)

    println("allow list and non-urls")
    check("allowed host is never blocked", UrlVerdict.blocked("https://sexeducation.org/porn-facts", parts, keywords, allow) == null)
    check("plain search text ignored", UrlVerdict.blocked("how to bake bread", parts, keywords, allow) == null)
    check("empty ignored", UrlVerdict.blocked("", parts, keywords, allow) == null)

    println(if (failures == 0) "ALL PASSED" else "$failures FAILURES")
    if (failures > 0) System.exit(1)
}
