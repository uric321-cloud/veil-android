import app.veil.android.screen.InAppMatch

var failures = 0
fun check(name: String, cond: Boolean) {
    if (cond) println("  ok   $name") else { failures++; println("  FAIL $name") }
}

fun m(viewId: String? = null, text: String? = null, desc: String? = null, selected: Boolean = false,
      nViewId: String? = null, nText: String? = null, nDesc: String? = null, nSelected: Boolean = false) =
    InAppMatch.matches(viewId, text, desc, selected, nViewId, nText, nDesc, nSelected)

fun main() {
    println("view id")
    check("contains after :id/", m(viewId = "reel_", nViewId = "com.google.android.youtube:id/reel_player_page_container"))
    check("package part is ignored", !m(viewId = "youtube", nViewId = "com.google.android.youtube:id/player"))
    check("case-insensitive", m(viewId = "contact_photo", nViewId = "com.whatsapp:id/Contact_Photo"))
    check("missing id never matches", !m(viewId = "reel_", nViewId = null))
    println("text")
    check("exact, trimmed, any case", m(text = "updates", nText = "  Updates "))
    check("not a substring", !m(text = "updates", nText = "Updates and calls"))
    println("description")
    check("contains", m(desc = "shorts", nDesc = "Shorts, tab 2 of 5"))
    println("selected")
    check("selected required", !m(text = "updates", selected = true, nText = "Updates", nSelected = false))
    check("selected present", m(text = "updates", selected = true, nText = "Updates", nSelected = true))
    println("combined")
    check("all given fields must match", !m(viewId = "tab", text = "updates", nViewId = "x:id/tab_title", nText = "Chats"))
    check("both match", m(viewId = "tab", text = "updates", nViewId = "x:id/tab_title", nText = "Updates"))

    println(if (failures == 0) "ALL PASSED" else "$failures FAILURES")
    if (failures > 0) System.exit(1)
}
