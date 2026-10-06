# VEIL for Android

A content filter for Android that blocks adult sites, your own block list and
encrypted-DNS bypass tricks in every app on the phone, and forces SafeSearch on
Google, Bing, DuckDuckGo and YouTube. Build one is the DNS-filtering engine with
custom allow/block lists, keyword rules, a PIN, block notifications and an
activity log. No web traffic passes through the app; only DNS lookups do.

## How it works (one paragraph)

The app runs a local Android VPN that captures nothing but DNS. Every lookup is
checked against the rules: blocked names get "does not exist" (NXDOMAIN),
SafeSearch names get the safe endpoint's addresses, everything else is forwarded
to Cloudflare or Quad9 through a socket that bypasses the tunnel. Traffic to
well-known public resolvers (Google DNS, Cloudflare, Quad9, NextDNS, ...) is
pulled into the tunnel and refused, so browsers' "secure DNS" cannot route
around the filter. Filtering runs on the phone. Unpaired ("self mode") the app
talks to no server; paired with an admin it checks in with the admin backend
described below.

## Admin backend and managed phones

`backend/` is the admin side: a Netlify site (Functions + Blobs) that serves the
dashboard and the API phones check in with. One admin account manages any
number of phones.

- **Install and pair (no erase).** VEIL installs like any app and is paired
  afterwards — no factory reset. The admin clicks *Add a phone* for a single-use
  code (30 minutes) and sends the pairing link (`/p/CODE`) to the phone; opening
  it launches VEIL with the server and code filled in. This is the normal way to
  set up a client's existing phone. Device Owner (below) is an optional extra for
  an un-removable install.
- **Alert on removal.** On a normal (non-Device-Owner) install the user can still
  uninstall VEIL. A scheduled check (`watch-devices`, every 15 min) raises a
  *phone went silent* alert when a phone that was checking in stops, so the admin
  knows if VEIL was removed or the phone was turned off.
- **Tamper alerts, immediate.** When a guard is turned off on the phone - the
  filtering VPN, the screen filter (accessibility), the screen-cover overlay, or
  a bypassing Private DNS is set - the phone reports it on its next check-in and
  the admin is alerted at once (push + email), with or without AI. On the phone a
  persistent "protection needs attention" notification stays up, and a
  **Protection status** health check on the home screen lists every guard with a
  one-tap fix, so setup problems (above all the accessibility permission) are
  obvious.
- **Check-ins.** About once a minute the phone sends its status, what VEIL
  blocked, tamper alerts and unblock requests, and receives settings changes,
  request decisions, commands and the shared AI blocklist. A paired phone's
  settings are read-only; *Activity → Ask* sends an unblock request instead.
- **What the admin can see** (also listed on the phone under *Settings*): VEIL's
  settings and status, sites VEIL blocked, words covered on screen (counts),
  tamper alerts, unblock requests, and - if *AI site classification* is on -
  the names of sites no list covers, which are classified but not stored
  against the phone. Never messages, photos, page content or other apps' data.
- **Device Owner lockdown.** Set up from the QR code on the first Welcome screen
  of a factory-reset phone (tap six times), or with
  `adb shell dpm set-device-owner app.veil.android/.admin.VeilDeviceAdmin`.
  VEIL then can't be uninstalled, runs as always-on VPN and applies the admin's
  chosen restrictions (VPN and Private DNS settings, Safe Mode, factory reset,
  extra users, and optionally app control, unknown sources and debugging).
  The QR code downloads `releases/latest/download/VEIL.apk`, which CI publishes
  from `main` only.
- **Getting out.** The admin's *Release phone* removes every restriction and
  Device Owner. The 8-digit recovery code shown once at pairing does the same
  on the phone with no server needed (5 tries, then a 30-minute lock; the admin
  is alerted).
- **AI (Claude).** With `ANTHROPIC_API_KEY` set on the Netlify site: a review
  and recommendation on every unblock request, classification of sites no list
  covers (confident adult/bypass results are added to a shared blocklist), daily
  reports at 06:00 UTC plus on-demand 7/30-day reports, and a per-phone
  assistant that can propose settings changes the admin applies with a button.
  Every change, from the dashboard or the assistant, goes through the same
  validation (`backend/netlify/lib/config.ts`).

### Beyond site filtering (0.3.0)

- **App control** (Apps tab): off, block chosen apps, or only allowed apps, plus
  "new apps need approval". Device Owner phones suspend blocked apps; others close
  them through the accessibility service. Essentials (launcher, phone, SMS,
  keyboard, Settings, VEIL) are never blocked. Users can ask for an app.
  - **Managed phones default to allow-list** (only approved apps run) with high
    image strictness. On the first check-in the phone sends its app list and the
    AI classifies every app: safe ones are approved automatically, unsafe ones
    stay blocked, and unsure ones become pending "approve this app?" requests for
    the admin. New apps installed later are scanned the same way. Phones paired
    before app control existed stay "off" until the admin turns it on.
  - Each phone has a stable **Client ID** (`dev_…`), shown on the dashboard and
    searchable, so a client can be looked up by ID.
- **In-app blocking**: switch off WhatsApp Status/Channels and profile photos,
  YouTube Shorts/search/comments, Instagram Reels/Explore, Maps photos, the Google
  Discover feed and more, or add custom rules. Rules live in
  `backend/netlify/lib/inapp.ts` and reach phones on check-in, so they can be
  fixed when an app changes without shipping a new APK.
- **Notification filtering**: a notification-listener service cancels an incoming
  notification whose text contains a blocked word (same text engine and rules as
  the screen filter), so a flagged message never shows in the shade or as a
  banner - the one place on-screen covering can't catch in time. On by default for
  managed phones; the user grants notification access once (separate from
  accessibility). Toggle: *Screen filter → Block notifications with bad words*.
- **Filter levels**: Open, Standard, Strict, Allowed sites only. In allowed-sites-
  only mode, sites the AI is confident are education, government, banking,
  health or app infrastructure open on their own.
- **AI request handling** (Web filter tab → *How requests are handled*). Default
  *AI decides, ask me only when unsure*: the AI allows clearly-safe requests and
  blocks clearly-unsafe ones on its own (within ~20s), and escalates to the admin
  only when it sets `needsHuman`. Other modes: *AI allows clearly-safe only*, or
  *I decide everything*. Every AI decision is shown and can be overridden.
- **Image filtering** (Android 11+): an on-device MobileNetV2 model (from nsfwjs,
  MIT, see `app/src/main/assets/models/NOTICE.txt`) checks image areas in
  accessibility screenshots and covers explicit ones. Nothing leaves the phone.
  Strictness runs low / medium / high / **max**. Managed phones default to *max*,
  which is fail-closed: every image area is covered the moment it appears and
  revealed only once the model has cleared it, and anything the model is not
  clearly confident is safe stays covered. Max trades more false covers for the
  strongest guarantee that explicit images are not shown; no on-device filter can
  promise a literal zero miss rate.
  Rebuild notes: the model was rebuilt in Keras from the nsfwjs weights and
  exported to TFLite; outputs match the original within 0.007.

### Running the backend

| Setting (Netlify environment variables) | Purpose |
|---|---|
| `ANTHROPIC_API_KEY` | Turns on the AI features. Without it everything else works. |
| `ADMIN_SIGNUP_CODE` | Lets more admins sign up. Without it only the first account can be created. |
| `VEIL_APK_URL`, `VEIL_SIGNATURE_CHECKSUM` | Override the APK and signing-certificate checksum in the QR code (defaults: latest release, test key). |

Deploys: CI deploys `backend/` from `main` once the repository has a
`NETLIFY_AUTH_TOKEN` secret (site id defaults to the `veil-admin` site; override
with a `NETLIFY_SITE_ID` repository variable). Locally:

```
cd backend && npm ci
npm test                                                  # API + AI tests (fake model)
VEIL_FAKE_AI=1 node --experimental-strip-types test/dev-server.ts   # dashboard on :8888
```

## Getting a build

Builds are made by GitHub Actions on every push to `main` and published under
**Releases** as an `.apk`. On the phone: open the Releases page, download the
`.apk`, open it, allow installs from the browser when asked, install.

Every build is signed with the test key in `keystore/` so a new build installs
over the previous one without uninstalling. Replace that key with a private
upload key before the Play Store submission.

## Testing checklist for a new build

1. Install, open VEIL, tap **Turn on protection**, accept the VPN prompt.
2. Open Chrome and visit a known adult site: it must fail to load, and it must
   appear under **Activity** with a notification.
3. Visit wikipedia.org and a news site: they must load normally.
4. Search something on google.com: the results page shows SafeSearch is on.
5. **Rules → Block list**: add `reddit.com`, confirm reddit stops loading; remove it.
6. **Rules → Allow list**: add a blocked site, confirm it loads again; remove it.
7. Chrome → Settings → Privacy → Use secure DNS → choose Cloudflare: adult sites
   must still be blocked (Chrome falls back because the resolver is refused).
8. Android Settings → Network → Private DNS → set a hostname (`dns.google`):
   VEIL must show a warning within a minute. Set it back to Automatic.
9. Settings → Set a PIN. Turning protection off must now ask for it.
10. Reboot the phone: protection must come back on by itself.
11. Leave it on overnight: check battery use for VEIL in Android's battery screen.
12. If anything fails: **Settings → Diagnostics → Share report** and send it.

## Project layout

```
app/src/main/java/app/veil/android/
  VeilApp.kt            Application: crash capture, notification channels
  VeilLog.kt            in-memory log exported by Diagnostics
  Diagnostics.kt        the shareable report
  BootReceiver.kt       restarts protection after reboot / update
  dns/DnsMessage.kt     DNS wire format: parse questions, build answers
  dns/Upstream.kt       forwards allowed queries to public resolvers
  vpn/Packets.kt        IPv4/IPv6 + UDP/TCP parse and build (checksums, RST)
  vpn/VeilVpnService.kt the VPN: tunnel loop, decisions, notifications, bypass alerts
  rules/RuleStore.kt    settings, lists, PIN, counters (SharedPreferences)
  rules/Matcher.kt      the decision for one DNS name; SafeSearch mapping
  rules/ListSource.kt   bundled/updated adult list, list updater
  rules/BlockLog.kt     recent blocks for the Activity tab
  ui/MainActivity.kt    Home / Rules / Activity / Settings
  ui/Ui.kt              view helpers (no AndroidX, no XML layouts)
app/src/main/assets/lists/adult.txt    47,663 adult domains (StevenBlack + Sinfonietta)
app/src/main/assets/lists/bypass.txt   public DoH/DoT resolver hostnames
.github/workflows/build.yml            CI: builds and publishes the APK
.localcheck/                           offline type-check + engine tests (see below)
```

## Local type-check and engine tests (no Android SDK needed)

`.localcheck/check.sh` compiles every source against a plain `android.jar` with
`kotlinc`, and `.localcheck/test/` holds engine tests (DNS codec, packet
checksums, rule matching, live resolver forwarding). They were used to verify
build one before the first CI run. A normal Android Studio setup does not need
them.

To run the engine tests with only a JDK (17+), `curl` and `unzip`:

```
.localcheck/run-tests.sh          # EngineTest + TextEngineTest, exits 1 on any failure
.localcheck/run-tests.sh --live   # also UpstreamTest: real DNS lookups, read the output
```

The first run downloads the Kotlin compiler and a compile-time `android.jar`
into `~/.cache/veil-localcheck` (override with `VEIL_TOOLS`).

## Roadmap

Build two: browser address-bar reading (Accessibility) for URL-level rules and
an in-page block screen, unsupported-browser blocking, tamper guarding.
Build three (done in 0.2.0): admin dashboard, request-to-approve, remote settings,
tamper alerts, Device Owner lockdown, AI review and reports.
Build four: on-device image filtering (Android 17 ContentSafetyManager, LiteRT fallback).
