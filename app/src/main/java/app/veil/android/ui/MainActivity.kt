package app.veil.android.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.veil.android.Diagnostics
import app.veil.android.VeilApp
import app.veil.android.VeilLog
import app.veil.android.admin.DeviceOwner
import app.veil.android.remote.RemoteStore
import app.veil.android.remote.RemoteSync
import app.veil.android.rules.BlockLog
import app.veil.android.rules.ListSource
import app.veil.android.rules.RuleStore
import app.veil.android.vpn.VeilVpnService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var store: RuleStore
    private lateinit var remote: RemoteStore
    private lateinit var lists: ListSource
    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var statusLine: TextView
    private val tabViews = HashMap<String, TextView>()
    private var currentTab = "home"
    private var unlockedUntil = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (remote.isPaired || currentTab == "home" || currentTab == "activity") render()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RuleStore.get(this)
        remote = RemoteStore.get(this)
        lists = ListSource(this)
        BlockLog.init(this)
        buildChrome()
        currentTab = intent?.getStringExtra(EXTRA_TAB) ?: "home"
        if (currentTab !in TABS) currentTab = "home"
        render()
        requestNotificationPermissionIfNeeded()
        offerCrashReportIfAny()
        handlePairLink(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (handlePairLink(intent)) return
        val tab = intent?.getStringExtra(EXTRA_TAB) ?: return
        if (tab in TABS) { currentTab = tab; render() }
    }

    override fun onResume() {
        super.onResume()
        VeilVpnService.stateListener = { render() }
        RemoteSync.listener = { render() }
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        VeilVpnService.stateListener = null
        RemoteSync.listener = null
        handler.removeCallbacks(ticker)
    }

    // ------------------------------------------------------------------ chrome

    private fun buildChrome() {
        val c = this
        root = Ui.vertical(c)
        root.setBackgroundColor(Ui.BG)
        root.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val header = Ui.vertical(c).apply {
            setPadding(Ui.dp(c, 20f), Ui.dp(c, 14f), Ui.dp(c, 20f), Ui.dp(c, 8f))
        }
        header.addView(Ui.text(c, "VEIL", 24f, Ui.PRIMARY, true))
        statusLine = Ui.caption(c, "")
        header.addView(statusLine)
        root.addView(header)

        content = FrameLayout(c).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(content)

        tabBar = Ui.horizontal(c).apply {
            setBackgroundColor(Color.WHITE)
            elevation = Ui.dp(c, 8f).toFloat()
        }
        for ((key, label) in listOf("home" to "Home", "rules" to "Rules", "activity" to "Activity", "settings" to "Settings")) {
            val t = Ui.text(c, label, 14f, Ui.MUTED, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(c, 14f), 0, Ui.dp(c, 14f))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { currentTab = key; render() }
            }
            tabViews[key] = t
            tabBar.addView(t)
        }
        root.addView(tabBar)

        root.setOnApplyWindowInsetsListener { v, insets ->
            val top: Int; val bottom: Int; val left: Int; val right: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val sb = insets.getInsets(WindowInsets.Type.systemBars())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                top = sb.top; left = sb.left; right = sb.right; bottom = maxOf(sb.bottom, ime.bottom)
            } else {
                @Suppress("DEPRECATION")
                run { top = insets.systemWindowInsetTop; left = insets.systemWindowInsetLeft; right = insets.systemWindowInsetRight; bottom = insets.systemWindowInsetBottom }
            }
            v.setPadding(left, top, right, bottom)
            insets
        }
        setContentView(root)
    }

    private fun render() {
        val managed = remote.isPaired
        val running = VeilVpnService.isRunning
        statusLine.text = when {
            managed -> "Managed by ${adminName()}"
            running -> "Protection is on · ${store.blockedToday} blocked today"
            store.protectionWanted -> "Protection is off · tap Turn on"
            else -> "Not protecting this phone yet"
        }
        statusLine.setTextColor(if (running) Ui.GOOD else Ui.MUTED)
        // A managed phone shows one simple screen: no tabs, no list of blocked sites.
        tabBar.visibility = if (managed) View.GONE else View.VISIBLE
        for ((k, t) in tabViews) t.setTextColor(if (k == currentTab) Ui.ACCENT else Ui.MUTED)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val column = Ui.vertical(this).apply { setPadding(0, Ui.dp(this@MainActivity, 4f), 0, Ui.dp(this@MainActivity, 24f)) }
        if (managed) buildManagedHome(column)
        else when (currentTab) {
            "rules" -> buildRules(column)
            "activity" -> buildActivity(column)
            "settings" -> buildSettings(column)
            else -> buildHome(column)
        }
        scroll.addView(column)
        // Keep scroll position when re-rendering the same tab.
        val old = content.getChildAt(0) as? ScrollView
        val y = old?.scrollY ?: 0
        content.removeAllViews()
        content.addView(scroll)
        if (y > 0) scroll.post { scroll.scrollTo(0, y) }
    }

    // ------------------------------------------------------------------ Home

    private fun buildHome(col: LinearLayout) {
        val c = this
        val running = VeilVpnService.isRunning
        val status = Ui.card(c)
        val row = Ui.horizontal(c)
        row.addView(Ui.text(c, if (running) "Protected" else "Not protected", 26f, if (running) Ui.GOOD else Ui.BAD, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Ui.pill(c, if (remote.isPaired) "Managed" else "Self mode", Ui.PRIMARY))
        status.addView(row)
        status.addView(Ui.caption(c, if (running)
            "Adult sites, your block list and encrypted-DNS bypasses are being filtered on every app on this phone."
        else
            "Turn protection on to filter adult sites and your block list across every app on this phone."))
        status.addView(Ui.space(c, 8f))
        if (running) {
            status.addView(Ui.wideButton(c, "Turn off protection", filled = false, color = Ui.BAD) { withPin { stopProtection() } })
        } else {
            status.addView(Ui.wideButton(c, "Turn on protection", filled = true, color = Ui.GOOD) { startProtection() })
        }
        col.addView(status)

        if (remote.isPaired) {
            val m = Ui.card(c)
            m.addView(Ui.heading(c, "Managed by ${adminName()}"))
            m.addView(Ui.caption(c, "${adminName()} sets VEIL's rules on this phone" +
                (if (DeviceOwner.isOwner(c)) " and VEIL is locked in place (it can't be uninstalled or switched off)." else ".") +
                " If a site you need is blocked, ask for it in Activity."))
            val pending = remote.requests().count { it.status == "pending" }
            if (pending > 0) m.addView(Ui.caption(c, "$pending request${if (pending > 1) "s" else ""} waiting for ${adminName()}."))
            col.addView(m)
        }

        val stats = Ui.card(c)
        stats.addView(Ui.heading(c, "Today"))
        val srow = Ui.horizontal(c)
        srow.addView(stat(c, "${store.blockedToday}", "blocked today"))
        srow.addView(stat(c, "${store.blockedTotal}", "blocked all time"))
        srow.addView(stat(c, formatCount(store.listDomainCount.takeIf { it > 0 } ?: 47663), "adult domains"))
        stats.addView(srow)
        col.addView(stats)

        val screen = Ui.card(c)
        val srow2 = Ui.horizontal(c)
        srow2.addView(Ui.text(c, "Screen filter", 16f, Ui.TEXT, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        srow2.addView(Ui.pill(c, if (store.screenProtectionWanted) "On" else "Off", if (store.screenProtectionWanted) Ui.GOOD else Ui.MUTED))
        screen.addView(srow2)
        screen.addView(Ui.caption(c, "Covers inappropriate text in any app, blurred or struck through in place. (Stage 1 — text; images come next.)"))
        screen.addView(Ui.wideButton(c, "Open screen filter", filled = !store.screenProtectionWanted) {
            startActivity(Intent(c, ScreenFilterActivity::class.java))
        })
        col.addView(screen)

        if (store.privateDnsHost.isNotEmpty()) {
            val warn = Ui.card(c)
            warn.addView(Ui.text(c, "Private DNS is turned on", 16f, Ui.WARN, true))
            warn.addView(Ui.caption(c, "Android's Private DNS is set to ${store.privateDnsHost}. While it is on, apps can resolve names around VEIL. Set it to Automatic or Off."))
            warn.addView(Ui.wideButton(c, "Open network settings", filled = false, color = Ui.WARN) { open(Intent(Settings.ACTION_WIRELESS_SETTINGS)) })
            col.addView(warn)
        }

        if (store.lastRevokeAt > 0 && !running && store.protectionWanted) {
            val warn = Ui.card(c)
            warn.addView(Ui.text(c, "Protection was turned off", 16f, Ui.BAD, true))
            warn.addView(Ui.caption(c, "Another VPN app took over, or the VPN was removed in Settings, ${DateUtils.getRelativeTimeSpanString(store.lastRevokeAt)}. Turn it back on above."))
            col.addView(warn)
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val bo = Ui.card(c)
            bo.addView(Ui.heading(c, "Keep it running"))
            bo.addView(Ui.caption(c, "Some phones (Samsung, Xiaomi, OnePlus) stop background apps to save battery. Turn battery optimization off for VEIL so protection is not killed overnight. VEIL only handles DNS and uses very little power."))
            bo.addView(Ui.wideButton(c, "Battery settings", filled = false) { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })
            col.addView(bo)
        }

        if (!remote.isPaired) {
        val ao = Ui.card(c)
        ao.addView(Ui.heading(c, "Make it harder to turn off"))
        ao.addView(Ui.caption(c, "1. Set a PIN in Settings so rules and the off switch need it.\n2. In Android's VPN settings, open VEIL and turn on Always-on VPN so it restarts by itself. Leave “Block connections without VPN” OFF: VEIL only carries DNS, and that option would cut all internet."))
        ao.addView(Ui.wideButton(c, "Open VPN settings", filled = false) { open(Intent(Settings.ACTION_VPN_SETTINGS)) })
        col.addView(ao)
        }

        val tr = Ui.card(c)
        tr.addView(Ui.heading(c, "What this build can and can't do"))
        tr.addView(Ui.caption(c,
            "Can: block adult sites from a 47,000-domain list plus keyword rules, in every app and browser, by refusing their DNS lookups. " +
            "Force SafeSearch on Google, Bing and DuckDuckGo and Restricted Mode on YouTube. Refuse encrypted-DNS tricks that route around it. " +
            "Tell you when Private DNS or another VPN gets in the way.\n\n" +
            "Can't yet: see inside pages (a blocked site's images on an allowed site), block by URL path, filter images with AI, or stop an uninstall. " +
            "A second VPN, a factory reset, or Android's Private DNS set to a hostname will bypass it, and you will be told when that happens. " +
            "Those are the next builds."))
        col.addView(tr)
    }

    private fun stat(c: Context, value: String, label: String): View {
        val v = Ui.vertical(c).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        v.addView(Ui.text(c, value, 22f, Ui.PRIMARY, true))
        v.addView(Ui.caption(c, label))
        return v
    }

    private fun formatCount(n: Int): String = String.format(Locale.US, "%,d", n)

    // ------------------------------------------------------------------ Rules

    private fun buildRules(col: LinearLayout) {
        val c = this
        val cats = Ui.card(c)
        cats.addView(Ui.heading(c, "Filters"))
        cats.addView(Ui.switchRow(c, "Adult content list", "${formatCount(store.listDomainCount.takeIf { it > 0 } ?: 47663)} known adult domains, matched with all their subdomains.", store.adultListEnabled) { v ->
            gated({ store.adultListEnabled = v }, { render() })
        })
        cats.addView(Ui.switchRow(c, "Keyword rules", "Block any site whose name contains one of your keywords (edit below).", store.keywordsEnabled) { v ->
            gated({ store.keywordsEnabled = v }, { render() })
        })
        cats.addView(Ui.switchRow(c, "Safe search", "Forces SafeSearch on Google, Bing and DuckDuckGo and Restricted Mode on YouTube, including the YouTube app.", store.safeSearchEnabled) { v ->
            gated({ store.safeSearchEnabled = v }, { render() })
        })
        if (store.safeSearchEnabled) {
            cats.addView(Ui.switchRow(c, "YouTube strict mode", "Strict hides far more videos than the default moderate setting.", store.youtubeStrict) { v ->
                gated({ store.youtubeStrict = v }, { render() })
            })
        }
        cats.addView(Ui.switchRow(c, "Bypass protection", "Refuses public encrypted-DNS resolvers (Google, Cloudflare, Quad9, NextDNS…) so browsers can't route lookups around VEIL. Takes effect the next time protection starts.", store.bypassProtectionEnabled) { v ->
            gated({ store.bypassProtectionEnabled = v }, { render() })
        })
        cats.addView(Ui.switchRow(c, "Extra upstream filtering", "Also resolves through Cloudflare for Families, which blocks adult sites on its side. Sites it blocks won't show in Activity. Your allow list always bypasses it.", store.upstreamFamilyFilter) { v ->
            gated({ store.upstreamFamilyFilter = v }, { render() })
        })
        col.addView(cats)

        col.addView(listCard("Block list", "Sites to block even if no filter catches them. Subdomains are included; “www.” is dropped automatically.", store.customBlock.sorted(),
            add = { store.addBlock(it) }, remove = { store.removeBlock(it) }, hint = "example.com"))
        col.addView(listCard("Allow list", "Sites that must always work, even if a filter would block them. Wins over everything else.", store.customAllow.sorted(),
            add = { store.addAllow(it) }, remove = { store.removeAllow(it) }, hint = "example.com"))
        col.addView(listCard("Keywords", "Any site name containing one of these is blocked. Keep them specific: “sex” would also block essex.gov.uk.", store.keywords.sorted(),
            add = { store.addKeyword(it) }, remove = { store.removeKeyword(it) }, hint = "keyword"))
    }

    private fun listCard(title: String, description: String, items: List<String>, add: (String) -> Boolean, remove: (String) -> Unit, hint: String): View {
        val c = this
        val card = Ui.card(c)
        card.addView(Ui.heading(c, "$title (${items.size})"))
        card.addView(Ui.caption(c, description))
        card.addView(Ui.space(c, 8f))
        val row = Ui.horizontal(c)
        val input = Ui.rowInput(c, hint)
        row.addView(input)
        row.addView(Ui.button(c, "Add") {
            val text = input.text.toString()
            withPin {
                if (add(text)) { input.setText(""); render(); toast("Added") } else toast("That doesn't look right. Enter something like example.com")
            }
        }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = Ui.dp(c, 8f) })
        card.addView(row)
        for (item in items) {
            card.addView(Ui.chipRow(c, item, null, "Remove", Ui.BAD) { withPin { remove(item); render() } })
        }
        return card
    }

    // ------------------------------------------------------------------ Activity

    private fun buildActivity(col: LinearLayout) {
        val c = this
        val entries = BlockLog.snapshot()
        val head = Ui.card(c)
        head.addView(Ui.heading(c, "Recent blocks"))
        head.addView(Ui.caption(c, when {
            entries.isEmpty() -> "Nothing blocked yet. Blocks show up here as they happen, newest first."
            remote.isPaired -> "Newest first. If a site you need is blocked, tap “Ask” to ask ${adminName()} to allow it."
            else -> "Newest first. Repeated blocks of the same site within a minute are grouped. “Allow” adds the site to your allow list."
        }))
        if (entries.isNotEmpty() && !remote.isPaired) head.addView(Ui.wideButton(c, "Clear list", filled = false, color = Ui.MUTED) { withPin { BlockLog.clear(); render() } })
        col.addView(head)
        if (remote.isPaired) buildRequests(col)
        buildBlockedApps(col)
        val fmt = SimpleDateFormat("EEE HH:mm", Locale.getDefault())
        val list = Ui.card(c)
        for (e in entries.take(150)) {
            val sub = "${e.reason} · ${if (e.count > 1) "${e.count}× · " else ""}${fmt.format(Date(e.lastAt))}"
            if (remote.isPaired) {
                list.addView(Ui.chipRow(c, e.host, sub, "Ask", Ui.ACCENT) { askToUnblock(e.host) })
            } else {
                list.addView(Ui.chipRow(c, e.host, sub, "Allow", Ui.ACCENT) {
                    withPin { if (store.addAllow(e.host)) { toast("${e.host} allowed"); render() } }
                })
            }
        }
        if (entries.isNotEmpty()) col.addView(list)
    }

    // ------------------------------------------------------------------ Settings

    private fun buildSettings(col: LinearLayout) {
        val c = this
        col.addView(adminCard())
        if (!remote.isPaired) {
        val pin = Ui.card(c)
        pin.addView(Ui.heading(c, if (store.hasPin) "PIN is set" else "No PIN"))
        pin.addView(Ui.caption(c, "With a PIN, turning protection off and changing any rule asks for it first. Give it to someone you trust if you want them to hold the key."))
        if (store.hasPin) {
            pin.addView(Ui.wideButton(c, "Change PIN", filled = false) { withPin { setPinDialog() } })
            pin.addView(Ui.wideButton(c, "Remove PIN", filled = false, color = Ui.BAD) { withPin { store.setPin(null); toast("PIN removed"); render() } })
        } else {
            pin.addView(Ui.wideButton(c, "Set a PIN") { setPinDialog() })
        }
        col.addView(pin)
        }

        val n = Ui.card(c)
        n.addView(Ui.heading(c, "Notifications"))
        n.addView(Ui.switchRow(c, "Notify on block", "A short notice when a site is blocked, at most once per site every 10 minutes.", store.notifyOnBlock) { v -> gated({ store.notifyOnBlock = v }, { render() }) })
        col.addView(n)

        val lists = Ui.card(c)
        lists.addView(Ui.heading(c, "Adult content list"))
        val when_ = if (store.listUpdatedAt > 0) "Updated ${DateUtils.getRelativeTimeSpanString(store.listUpdatedAt)}" else "Using the list bundled with this build"
        lists.addView(Ui.caption(c, "$when_ · ${formatCount(store.listDomainCount.takeIf { it > 0 } ?: 47663)} domains. Updates download the two public lists this build is made from and merge them."))
        if (!remote.isPaired) lists.addView(Ui.wideButton(c, "Update list now", filled = false) { updateLists() })
        col.addView(lists)

        val d = Ui.card(c)
        d.addView(Ui.heading(c, "Diagnostics"))
        d.addView(Ui.caption(c, "If something doesn't work, share this report. It contains device model, Android version, VEIL's settings, recent blocks and VEIL's own log. No browsing history."))
        val drow = Ui.horizontal(c)
        drow.addView(Ui.button(c, "Share report") { Diagnostics.share(c, Diagnostics.report(c), "VEIL diagnostics") })
        drow.addView(Ui.button(c, "Copy", filled = false) { Diagnostics.copy(c, Diagnostics.report(c)) }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = Ui.dp(c, 8f) })
        d.addView(Ui.space(c, 8f))
        d.addView(drow)
        col.addView(d)

        val about = Ui.card(c)
        about.addView(Ui.heading(c, "About"))
        about.addView(Ui.caption(c, "VEIL ${app.veil.android.BuildConfig.VERSION_NAME}. Filtering runs on this phone. Allowed lookups go to Cloudflare (1.1.1.1) or Cloudflare for Families (1.1.1.3), and to Quad9 as a fallback." +
            if (remote.isPaired) " While paired, VEIL also reports to your admin as described under “Managed by”." else " Unpaired, VEIL sends no browsing data anywhere."))
        col.addView(about)
    }

    // ------------------------------------------------------------------ actions

    private fun startProtection() {
        val prep = VpnService.prepare(this)
        if (prep != null) {
            try {
                startActivityForResult(prep, REQ_VPN)
            } catch (t: Throwable) {
                toast("Could not open the VPN permission dialog: ${t.message}")
            }
            return
        }
        store.protectionWanted = true
        store.lastRevokeAt = 0
        VeilVpnService.start(this)
        toast("Starting protection…")
        handler.postDelayed({ render() }, 1200)
    }

    private fun stopProtection() {
        store.protectionWanted = false
        VeilVpnService.stop(this)
        handler.postDelayed({ render() }, 800)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) startProtection()
            else toast("VEIL needs the VPN permission to filter. Nothing was changed.")
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(PERM_POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        try { requestPermissions(arrayOf(PERM_POST_NOTIFICATIONS), REQ_NOTIF) } catch (_: Throwable) {}
    }

    private fun updateLists() {
        toast("Updating list…")
        Thread {
            val result = try {
                val n = lists.updateFromInternet()
                store.listDomainCount = n
                store.listUpdatedAt = System.currentTimeMillis()
                "List updated: ${formatCount(n)} domains"
            } catch (t: Throwable) {
                VeilLog.e("List update failed", t)
                "Update failed: ${t.message}"
            }
            handler.post { toast(result); render() }
        }.start()
    }

    // ------------------------------------------------------------------ admin / managed mode

    private fun adminName(): String = remote.adminName.ifEmpty { "your admin" }

    /** The whole screen a managed phone's user sees: status, request, their requests. */
    private fun buildManagedHome(col: LinearLayout) {
        val c = this
        val running = VeilVpnService.isRunning

        val status = Ui.card(c)
        status.addView(Ui.text(c, if (running) "Protected" else "Protection is off", 24f, if (running) Ui.GOOD else Ui.BAD, true))
        status.addView(Ui.caption(c, "This phone is looked after by ${adminName()}. If a website or app you need is blocked, ask for it below — most requests are answered within a minute."))
        if (!running) status.addView(Ui.wideButton(c, "Turn protection on", filled = true, color = Ui.GOOD) { startProtection() })
        col.addView(status)

        buildSetupPrompts(col)

        val req = Ui.card(c)
        req.addView(Ui.heading(c, "Need something allowed?"))
        req.addView(Ui.wideButton(c, "Request a website") { requestSiteDialog() })
        req.addView(Ui.wideButton(c, "Request an app", filled = false) { requestAppDialog() })
        col.addView(req)

        buildRequests(col)
        col.addView(adminCard())
    }

    /** Only shown while a needed permission is still off, so a working phone stays uncluttered. */
    private fun buildSetupPrompts(col: LinearLayout) {
        val c = this
        if (!RemoteSync.accessibilityOn(this)) {
            val card = Ui.card(c)
            card.addView(Ui.text(c, "Finish setup", 16f, Ui.WARN, true))
            card.addView(Ui.caption(c, "Turn on VEIL's screen filter so it can cover inappropriate words and images and keep blocked apps closed."))
            card.addView(Ui.wideButton(c, "Open setup") { startActivity(Intent(c, ScreenFilterActivity::class.java)) })
            col.addView(card)
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val card = Ui.card(c)
            card.addView(Ui.caption(c, "To keep protection running, let VEIL run in the background (battery can otherwise stop it overnight)."))
            card.addView(Ui.wideButton(c, "Allow background running", filled = false) { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })
            col.addView(card)
        }
    }

    private fun requestSiteDialog() {
        val input = Ui.input(this, "example.com")
        val reason = Ui.input(this, "Why do you need it? (optional)", singleLine = false)
        val wrap = Ui.vertical(this, 20f).apply {
            addView(Ui.text(this@MainActivity, "Website address", 13f, Ui.MUTED)); addView(input)
            addView(Ui.space(this@MainActivity, 10f)); addView(reason)
        }
        AlertDialog.Builder(this)
            .setTitle("Request a website")
            .setMessage("Ask ${adminName()} to allow a website.")
            .setView(wrap)
            .setPositiveButton("Send") { _, _ ->
                if (RemoteSync.requestUnblock(this, input.text.toString(), reason.text.toString())) { toast("Request sent to ${adminName()}"); render() }
                else toast("Enter a website like example.com")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestAppDialog() {
        val apps = app.veil.android.apps.AppControl.launchableApps(this).filter { it.pkg != packageName }
        if (apps.isEmpty()) { toast("No apps to request"); return }
        val labels = apps.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Request an app")
            .setItems(labels) { _, i ->
                val a = apps[i]
                if (RemoteSync.requestApp(this, a.pkg, a.label, "")) toast("Request sent for ${a.label}")
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun managedNotice() {
        AlertDialog.Builder(this)
            .setTitle("Managed by ${adminName()}")
            .setMessage("VEIL's settings on this phone are set by ${adminName()}. To get a blocked site allowed, open Activity and tap “Ask”.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun adminCard(): View {
        val c = this
        val card = Ui.card(c)
        if (!remote.isPaired) {
            card.addView(Ui.heading(c, "Pair with an admin"))
            card.addView(Ui.caption(c, "Let a parent, partner or accountability helper manage VEIL from their dashboard. They'll set the rules, see what VEIL blocked and get alerts if protection is switched off. You'll need the server address and the pairing code from their dashboard."))
            card.addView(Ui.wideButton(c, "Pair with an admin") { pairDialog() })
            return card
        }
        card.addView(Ui.heading(c, "Managed by ${adminName()}"))
        val last = if (remote.lastSyncAt > 0) "Last check-in ${DateUtils.getRelativeTimeSpanString(remote.lastSyncAt)}" else "Not checked in yet"
        card.addView(Ui.caption(c, "$last · ${remote.server.removePrefix("https://")}" + if (DeviceOwner.isOwner(c)) " · Device Owner lockdown active" else ""))
        if (remote.lastSyncError.isNotEmpty()) card.addView(Ui.text(c, remote.lastSyncError, 13f, Ui.WARN))
        card.addView(Ui.space(c, 6f))
        card.addView(Ui.text(c, "What ${adminName()} can see", 14f, Ui.TEXT, true))
        card.addView(Ui.caption(c, "• VEIL's settings and whether protection and the screen filter are on\n" +
            "• Sites VEIL blocked, and how many words it covered on screen\n" +
            "• Alerts when protection, the screen filter or Private DNS is changed\n" +
            "• Your unblock requests and reasons\n" +
            "• The names of apps installed on this phone, so ${adminName()} can choose which may open\n" +
            (if (store.aiClassification) "• Names of sites no block list covers, for AI classification (not stored against this phone)\n" else "") +
            "\nNot your messages, photos, passwords or the content of pages, and nothing from other apps. ${adminName()} controls only VEIL."))
        card.addView(Ui.space(c, 6f))
        card.addView(Ui.wideButton(c, "Check in now", filled = false) { RemoteSync.syncNow(c); toast("Checking in…") })
        card.addView(Ui.wideButton(c, "Recovery code", filled = false, color = Ui.MUTED) { recoveryDialog() })
        return card
    }

    /**
     * Pairing: the server is filled in already (the built-in admin server, or
     * the one from a pairing link), so normally only the code is typed.
     */
    private fun pairDialog(serverPrefill: String = app.veil.android.BuildConfig.DEFAULT_SERVER, codePrefill: String = "") {
        val c = this
        val codeLabel = Ui.text(c, "Pairing code (from the admin website)", 13f, Ui.MUTED)
        val code = Ui.input(c, "e.g. ABCD-2345").apply {
            setText(codePrefill)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val serverLabel = Ui.text(c, "Admin server (change only if your admin uses a different one)", 13f, Ui.MUTED)
        val server = Ui.input(c, "veil-admin.netlify.app").apply {
            setText(serverPrefill.removePrefix("https://"))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val wrap = Ui.vertical(c, 20f).apply {
            addView(codeLabel); addView(code); addView(Ui.space(c, 12f)); addView(serverLabel); addView(server)
        }
        val dialog = AlertDialog.Builder(c)
            .setTitle("Pair with an admin")
            .setMessage("Once paired, only your admin can change VEIL's settings. You can always see what they can see under Settings.")
            .setView(wrap)
            .setPositiveButton("Pair") { _, _ -> startPairing(server.text.toString(), code.text.toString()) }
            .setNegativeButton("Cancel", null)
            .create()
        // Show the keyboard straight away for the code.
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        if (codePrefill.isEmpty()) code.requestFocus()
    }

    private fun startPairing(server: String, code: String) {
        if (code.isBlank()) { toast("Enter the pairing code from the admin website"); return }
        toast("Pairing…")
        RemoteSync.pair(this, server.ifBlank { app.veil.android.BuildConfig.DEFAULT_SERVER }, code) { err ->
            handler.post {
                if (err != null) {
                    AlertDialog.Builder(this).setTitle("Couldn't pair").setMessage(err).setPositiveButton("OK", null).show()
                } else {
                    toast("Paired with ${adminName()}")
                }
                render()
            }
        }
    }

    /** veil://pair?server=…&code=… from the dashboard's pairing link. Returns true if it was one. */
    private fun handlePairLink(i: Intent?): Boolean {
        val data = i?.data ?: return false
        if (data.scheme != "veil" || data.host != "pair") return false
        i.data = null // don't re-handle on rotation
        val server = data.getQueryParameter("server").orEmpty().ifBlank { app.veil.android.BuildConfig.DEFAULT_SERVER }
        val code = data.getQueryParameter("code").orEmpty()
        currentTab = "settings"
        render()
        if (remote.isPaired) {
            toast("This phone is already paired with ${adminName()}")
            return true
        }
        pairDialog(server, code)
        return true
    }

    private fun recoveryDialog() {
        val input = Ui.pinInput(this, "8-digit recovery code")
        val wrap = Ui.vertical(this, 20f).apply { addView(input) }
        AlertDialog.Builder(this)
            .setTitle("Recovery code")
            .setMessage("Only for emergencies, such as the admin's server being gone. The code removes all of VEIL's lockdown and unpairs this phone. ${adminName()} is told when it's used, and when a wrong code is tried.")
            .setView(wrap)
            .setPositiveButton("Release") { _, _ ->
                when (val r = RemoteSync.recover(this, input.text.toString())) {
                    is RemoteSync.RecoveryResult.Released -> toast("Releasing VEIL…")
                    is RemoteSync.RecoveryResult.Wrong -> toast("Wrong code. ${r.attemptsLeft} tries left.")
                    is RemoteSync.RecoveryResult.Locked -> toast("Too many wrong codes. Try again ${DateUtils.getRelativeTimeSpanString(r.until)}.")
                }
                handler.postDelayed({ render() }, 1500)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askToUnblock(host: String) {
        val reason = Ui.input(this, "Why do you need it? (optional)", singleLine = false)
        val wrap = Ui.vertical(this, 20f).apply { addView(reason) }
        AlertDialog.Builder(this)
            .setTitle("Ask to allow $host")
            .setMessage("${adminName()} gets your request and decides. You'll get a notification with the answer.")
            .setView(wrap)
            .setPositiveButton("Send") { _, _ ->
                if (RemoteSync.requestUnblock(this, host, reason.text.toString())) toast("Request sent to ${adminName()}") else toast("Couldn't create the request")
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun buildBlockedApps(col: LinearLayout) {
        val c = this
        val blocked = app.veil.android.apps.AppControl.blockedNow(c)
        if (blocked.isEmpty()) return
        val card = Ui.card(c)
        card.addView(Ui.heading(c, "Blocked apps (${blocked.size})"))
        card.addView(Ui.caption(c, if (remote.isPaired) "These apps can't be opened. Tap “Ask” to ask ${adminName()} for one." else "These apps can't be opened on this phone."))
        val pending = remote.requests().filter { it.kind == "app" && it.status == "pending" }.map { it.host }.toSet()
        for (pkg in blocked.sortedBy { app.veil.android.apps.AppControl.label(c, it).lowercase() }) {
            val label = app.veil.android.apps.AppControl.label(c, pkg)
            when {
                pkg in pending -> card.addView(Ui.chipRow(c, label, "Waiting for ${adminName()}", "", Ui.MUTED) {})
                remote.isPaired -> card.addView(Ui.chipRow(c, label, null, "Ask", Ui.ACCENT) {
                    app.veil.android.apps.BlockedAppActivity.show(c, pkg)
                })
                else -> card.addView(Ui.chipRow(c, label, null, "", Ui.MUTED) {})
            }
        }
        col.addView(card)
    }

    private fun buildRequests(col: LinearLayout) {
        val c = this
        val reqs = remote.requests().take(20)
        if (reqs.isEmpty()) return
        val card = Ui.card(c)
        card.addView(Ui.heading(c, "Your requests"))
        val fmt = SimpleDateFormat("EEE HH:mm", Locale.getDefault())
        val now = System.currentTimeMillis()
        for (r in reqs) {
            val state = when {
                r.status == "pending" -> if (r.sent) "Waiting for ${adminName()}" else "Sending…"
                r.status == "approved" && r.until == 0L -> "Always allowed"
                r.status == "approved" && r.until > now -> "Allowed until ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(r.until))}"
                r.status == "approved" -> "Allowance ended"
                else -> "Declined" + if (r.note.isNotEmpty()) ": ${r.note}" else ""
            }
            val title = if (r.kind == "app") "${r.label.ifEmpty { r.host }} (app)" else r.host
            card.addView(Ui.chipRow(c, title, "$state · ${fmt.format(Date(r.at))}", "", Ui.MUTED) {})
        }
        col.addView(card)
    }

    // ------------------------------------------------------------------ PIN

    /** Runs [action] after a PIN check when a PIN is set (cached for five minutes). */
    private fun withPin(action: () -> Unit) {
        if (remote.isPaired) { managedNotice(); render(); return }
        if (!store.hasPin || System.currentTimeMillis() < unlockedUntil) { action(); return }
        val input = Ui.pinInput(this, "PIN")
        val wrap = Ui.vertical(this, 20f).apply { addView(input) }
        AlertDialog.Builder(this)
            .setTitle("Enter PIN")
            .setView(wrap)
            .setPositiveButton("Unlock") { _, _ ->
                if (store.verifyPin(input.text.toString())) {
                    unlockedUntil = System.currentTimeMillis() + 5 * 60_000
                    action()
                } else {
                    toast("Wrong PIN")
                    render()
                }
            }
            .setNegativeButton("Cancel") { _, _ -> render() }
            .show()
    }

    /** Applies a rule change behind the PIN, restoring the switch if cancelled. */
    private fun gated(change: () -> Unit, after: () -> Unit) {
        withPin { change(); after() }
    }

    private fun setPinDialog() {
        val a = Ui.pinInput(this, "New PIN (4+ digits)")
        val b = Ui.pinInput(this, "Repeat PIN")
        val wrap = Ui.vertical(this, 20f).apply { addView(a); addView(b) }
        AlertDialog.Builder(this)
            .setTitle("Set a PIN")
            .setView(wrap)
            .setPositiveButton("Save") { _, _ ->
                val p1 = a.text.toString(); val p2 = b.text.toString()
                when {
                    p1.length < 4 -> toast("Use at least 4 digits")
                    p1 != p2 -> toast("The PINs don't match")
                    else -> { store.setPin(p1); unlockedUntil = 0; toast("PIN set"); }
                }
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ misc

    private fun offerCrashReportIfAny() {
        val f = VeilApp.crashFile(this)
        if (!f.exists()) return
        AlertDialog.Builder(this)
            .setTitle("VEIL crashed last time")
            .setMessage("A crash report was saved. Sharing it helps fix the problem; it contains no browsing history.")
            .setPositiveButton("Share") { _, _ -> Diagnostics.share(this, Diagnostics.report(this), "VEIL crash report"); f.delete() }
            .setNegativeButton("Dismiss") { _, _ -> f.delete() }
            .show()
    }

    private fun open(i: Intent) {
        try { startActivity(i) } catch (t: Throwable) { toast("That settings screen isn't available on this phone") }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_TAB = "tab"
        private val TABS = setOf("home", "rules", "activity", "settings")
        private const val REQ_VPN = 101
        private const val REQ_NOTIF = 102
        private const val PERM_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    }
}
