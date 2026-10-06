package app.veil.android.apps

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import app.veil.android.remote.RemoteStore
import app.veil.android.remote.RemoteSync
import app.veil.android.ui.Ui

/** "This app is blocked" with a way to ask the admin for it. */
class BlockedAppActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: run { finish(); return }
        val label = AppControl.label(this, pkg)
        val remote = RemoteStore.get(this)
        val admin = remote.adminName.ifEmpty { "your admin" }
        val pending = remote.requests().any { it.kind == "app" && it.host == pkg && it.status == "pending" }

        val col = Ui.vertical(this, 24f).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.BG)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val card = Ui.card(this)
        card.addView(Ui.text(this, "$label is blocked", 22f, Ui.TEXT, true))
        card.addView(Ui.caption(this, if (remote.isPaired) "$admin hasn't allowed this app on this phone." else "This app is blocked on this phone."))
        card.addView(Ui.space(this, 12f))
        if (remote.isPaired && !pending) {
            val reason = Ui.input(this, "Why do you need it? (optional)", singleLine = false)
            card.addView(reason)
            card.addView(Ui.space(this, 8f))
            card.addView(Ui.wideButton(this, "Ask $admin to allow it") {
                RemoteSync.requestApp(this, pkg, label, reason.text.toString())
                Toast.makeText(this, "Request sent to $admin", Toast.LENGTH_SHORT).show()
                finish()
            })
        } else if (pending) {
            card.addView(Ui.caption(this, "You've already asked. You'll get a notification when $admin decides."))
        }
        card.addView(Ui.wideButton(this, "OK", filled = false) { finish() })
        col.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(col)
    }

    companion object {
        const val EXTRA_PACKAGE = "package"

        fun show(c: Context, pkg: String) {
            c.startActivity(Intent(c, BlockedAppActivity::class.java).putExtra(EXTRA_PACKAGE, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
        }
    }
}
