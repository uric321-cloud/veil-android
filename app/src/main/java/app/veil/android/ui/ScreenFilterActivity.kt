package app.veil.android.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import app.veil.android.rules.RuleStore
import app.veil.android.screen.Tiers
import app.veil.android.screen.VeilAccessibilityService

/**
 * Onboarding and settings for the Stage 1 text filter: grant the two permissions,
 * turn it on, pick a tier, and manage word lists. Kept separate from MainActivity
 * so the screen engine is self-contained.
 */
class ScreenFilterActivity : Activity() {

    private lateinit var store: RuleStore
    private lateinit var root: ScrollView
    private var unlockedUntil = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RuleStore.get(this)
        root = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setOnApplyWindowInsetsListener { v, insets ->
                val top: Int; val bottom: Int; val left: Int; val right: Int
                if (Build.VERSION.SDK_INT >= 30) {
                    val sb = insets.getInsets(WindowInsets.Type.systemBars())
                    val ime = insets.getInsets(WindowInsets.Type.ime())
                    top = sb.top; left = sb.left; right = sb.right; bottom = maxOf(sb.bottom, ime.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    run { top = insets.systemWindowInsetTop; left = insets.systemWindowInsetLeft; right = insets.systemWindowInsetRight; bottom = insets.systemWindowInsetBottom }
                }
                v.setPadding(left, top, right, bottom); insets
            }
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val c = this
        val col = Ui.vertical(c).apply { setPadding(0, Ui.dp(c, 8f), 0, Ui.dp(c, 24f)) }

        val header = Ui.vertical(c).apply { setPadding(Ui.dp(c, 20f), Ui.dp(c, 12f), Ui.dp(c, 20f), Ui.dp(c, 4f)) }
        header.addView(Ui.text(c, "Screen filter", 24f, Ui.PRIMARY, true))
        header.addView(Ui.caption(c, "Covers inappropriate text in any app. Everything is checked on this phone; nothing is sent anywhere."))
        col.addView(header)

        val accOn = isAccessibilityOn()
        val overlayOn = Settings.canDrawOverlays(c)
        val active = store.screenProtectionWanted && store.textEnabled && accOn && overlayOn

        // ---- status ----
        val status = Ui.card(c)
        status.addView(Ui.text(c, if (active) "Active" else "Not active", 22f, if (active) Ui.GOOD else Ui.BAD, true))
        status.addView(Ui.caption(c, when {
            !accOn -> "Turn on accessibility access below to let VEIL read on-screen text."
            !overlayOn -> "Allow display over other apps below so VEIL can cover flagged text."
            !store.screenProtectionWanted -> "Permissions are set. Turn the filter on below."
            else -> "VEIL is covering flagged text across your apps."
        }))
        col.addView(status)

        // ---- permissions ----
        val perms = Ui.card(c)
        perms.addView(Ui.heading(c, "Permissions"))
        perms.addView(permRow(c, "Accessibility access", accOn,
            "Lets VEIL read on-screen text. On Android 13+ you may first need “Allow restricted settings” from this app's App info.") {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        perms.addView(permRow(c, "Display over other apps", overlayOn,
            "Lets VEIL draw the cover on top of other apps.") {
            val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            open(i)
        })
        perms.addView(Ui.wideButton(c, "Open this app's info", filled = false) {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        col.addView(perms)

        // ---- master toggle ----
        val master = Ui.card(c)
        master.addView(Ui.switchRow(c, "Text filtering", "Cover inappropriate words in every app.", store.screenProtectionWanted && store.textEnabled) { want ->
            withPin({
                store.textEnabled = true
                store.screenProtectionWanted = want
            }, {
                if (want && (!accOn || !overlayOn)) toast("Grant the two permissions above to activate.")
                render()
            })
        })
        col.addView(master)

        // ---- tier ----
        val tierCard = Ui.card(c)
        tierCard.addView(Ui.heading(c, "Strictness: ${Tiers.label(store.textTier)}"))
        tierCard.addView(Ui.caption(c, "How much to cover, from a young child's phone to an adult self-filter."))
        tierCard.addView(Ui.wideButton(c, "Change strictness", filled = false) { withPin({}, { pickTier() }) })
        if (store.textTier == Tiers.CUSTOM) {
            tierCard.addView(Ui.space(c, 6f))
            tierCard.addView(actionRow(c, "Mild words", store.customMild) { store.customMild = it; render() })
            tierCard.addView(actionRow(c, "Strong words", store.customStrong) { store.customStrong = it; render() })
            tierCard.addView(actionRow(c, "Explicit words", store.customExplicit) { store.customExplicit = it; render() })
        }
        col.addView(tierCard)

        val img = Ui.card(c)
        img.addView(Ui.heading(c, "Images"))
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            img.addView(Ui.caption(c, "An on-device model checks images on screen and covers explicit ones in any app. Images never leave this phone. ${store.imagesCoveredTotal} covered so far."))
            img.addView(Ui.switchRow(c, "Cover explicit images", null, store.imageFilter) { v ->
                withPin({ store.imageFilter = v }, { render() })
            })
            val labels = mapOf("low" to "Only clearly explicit", "medium" to "Explicit (recommended)", "high" to "Explicit and suggestive")
            img.addView(actionRowImages(c, labels[store.imageStrictness] ?: store.imageStrictness) {
                val keys = labels.keys.toList()
                AlertDialog.Builder(this).setTitle("What to cover")
                    .setItems(keys.map { labels[it] }.toTypedArray()) { _, i -> withPin({ store.imageStrictness = keys[i] }, { render() }) }
                    .show()
            })
        } else {
            img.addView(Ui.caption(c, "Covering images needs Android 11 or newer."))
        }
        col.addView(img)

        // ---- options ----
        val opts = Ui.card(c)
        opts.addView(Ui.heading(c, "Options"))
        opts.addView(Ui.switchRow(c, "Warn and log only", "Don't cover anything; just count hits in the log (for accountability).", store.textWarnLogOnly) {
            withPin({ store.textWarnLogOnly = it }, { render() })
        })
        opts.addView(Ui.switchRow(c, "Catch disguised spellings", "Also match leetspeak and stretched words (føøck). May raise false positives.", store.textDeobfuscate) {
            withPin({ store.textDeobfuscate = it }, { render() })
        })
        col.addView(opts)

        // ---- lists ----
        col.addView(wordListCard(c, "Always cover", "Your own words to cover on top of the built-in lists.",
            store.textBlockWords.sorted(), { store.addTextBlockWord(it) }, { store.removeTextBlockWord(it) }))
        col.addView(wordListCard(c, "Never cover", "Words VEIL should always leave alone.",
            store.textAllowWords.sorted(), { store.addTextAllowWord(it) }, { store.removeTextAllowWord(it) }))

        // ---- privacy ----
        val priv = Ui.card(c)
        priv.addView(Ui.heading(c, "Privacy"))
        priv.addView(Ui.caption(c, "VEIL reads the screen only to cover flagged words. Matching happens on this phone, nothing about what is on your screen leaves it, and password fields are never read or logged."))
        col.addView(priv)

        root.removeAllViews()
        root.addView(col)
    }

    private fun permRow(c: Activity, title: String, granted: Boolean, help: String, onFix: () -> Unit): LinearLayout {
        val card = Ui.vertical(c)
        val row = Ui.horizontal(c)
        row.addView(Ui.text(c, title, 15f, Ui.TEXT, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Ui.pill(c, if (granted) "Granted" else "Needed", if (granted) Ui.GOOD else Ui.BAD))
        card.addView(row)
        card.addView(Ui.caption(c, help))
        if (!granted) card.addView(Ui.wideButton(c, "Grant", filled = true) { onFix() })
        card.addView(Ui.space(c, 6f))
        return card
    }

    private fun actionRow(c: Activity, label: String, value: String, onPick: (String) -> Unit): LinearLayout {
        val row = Ui.horizontal(c)
        row.setPadding(0, Ui.dp(c, 4f), 0, Ui.dp(c, 4f))
        row.addView(Ui.text(c, label, 14f, Ui.TEXT, false).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Ui.button(c, actionLabel(value), filled = false) {
            val names = arrayOf("ignore", "strike", "bar", "frost")
            val labels = names.map { actionLabel(it) }.toTypedArray()
            AlertDialog.Builder(c).setTitle(label)
                .setItems(labels) { _, i -> withPin({ onPick(names[i]) }, {}) }
                .show()
        })
        return row
    }

    private fun actionLabel(a: String): String = when (a) {
        "ignore" -> "Leave"
        "strike" -> "Strike"
        "bar" -> "Bar"
        "frost" -> "Frost"
        else -> a
    }

    private fun wordListCard(c: Activity, title: String, help: String, items: List<String>, add: (String) -> Boolean, remove: (String) -> Unit): LinearLayout {
        val card = Ui.card(c)
        card.addView(Ui.heading(c, "$title (${items.size})"))
        card.addView(Ui.caption(c, help))
        card.addView(Ui.space(c, 8f))
        val row = Ui.horizontal(c)
        val input = Ui.rowInput(c, "word")
        row.addView(input)
        row.addView(Ui.button(c, "Add") {
            withPin({
                if (add(input.text.toString())) { input.setText(""); render() } else toast("Enter a single word")
            }, {})
        }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = Ui.dp(c, 8f) })
        card.addView(row)
        for (w in items) card.addView(Ui.chipRow(c, w, null, "Remove", Ui.BAD) { withPin({ remove(w) }, { render() }) })
        return card
    }

    private fun actionRowImages(c: Activity, value: String, onClick: () -> Unit): LinearLayout {
        val row = Ui.horizontal(c)
        row.addView(Ui.text(c, "What to cover: $value", 14f).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Ui.button(c, "Change", filled = false) { onClick() })
        return row
    }

    private fun pickTier() {
        val labels = Tiers.IDS.map { Tiers.label(it) }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Strictness")
            .setItems(labels) { _, i -> store.textTier = Tiers.IDS[i]; render() }
            .show()
    }

    // ---- PIN gate (mirrors MainActivity) ----
    private fun withPin(change: () -> Unit, after: () -> Unit) {
        val remote = app.veil.android.remote.RemoteStore.get(this)
        if (remote.isPaired) {
            AlertDialog.Builder(this).setTitle("Managed by ${remote.adminName.ifEmpty { "your admin" }}")
                .setMessage("The screen filter's settings on this phone are set by your admin.")
                .setPositiveButton("OK", null).show()
            render()
            return
        }
        if (!store.hasPin || System.currentTimeMillis() < unlockedUntil) { change(); after(); return }
        val input = Ui.pinInput(this, "PIN")
        val wrap = Ui.vertical(this, 20f).apply { addView(input) }
        AlertDialog.Builder(this).setTitle("Enter PIN").setView(wrap)
            .setPositiveButton("Unlock") { _, _ ->
                if (store.verifyPin(input.text.toString())) {
                    unlockedUntil = System.currentTimeMillis() + 5 * 60_000
                    change(); after()
                } else { toast("Wrong PIN"); render() }
            }
            .setNegativeButton("Cancel") { _, _ -> render() }
            .show()
    }

    private fun isAccessibilityOn(): Boolean {
        val expected = "$packageName/${VeilAccessibilityService::class.java.name}"
        val flat = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return flat.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun open(i: Intent) {
        try { startActivity(i) } catch (t: Throwable) { toast("That settings screen isn't available on this phone") }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
