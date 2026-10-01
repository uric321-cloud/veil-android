# VEIL for Android — build one

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
around the filter. Everything runs on the phone; the app has no server.

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
Build three: request-to-approve flow with a key-holder app and web dashboard.
Build four: on-device image filtering (Android 17 ContentSafetyManager, LiteRT fallback).
