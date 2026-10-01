# VEIL Stage 1 — Text Redaction Engine

> Build ticket. Mirrors the living spec; the Claude Doc version is the source of truth for discussion.
> Status: ready to start · Estimate: 3–4 weeks (one senior Android dev) · Depends on: build one (in repo)

## Goal and context

Stage 1 builds the part of VEIL that reads the text on screen in any app and covers
anything inappropriate in place — a strike-through, a bar, or a frosted box over the
offending words — the instant they appear. It is the first piece of the screen engine
and the quickest visible win: no ML model, light on battery, and it proves the
read → decide → cover loop that the image stage (Stage 2) reuses. It runs on a normal
phone install; the Device Owner lockdown that makes it un-disableable is Stage 5 and is
out of scope here. Build it as new modules in this project, not a separate app.

## Scope

**In scope**

- An accessibility service that reads on-screen text and its position in any foreground app.
- A rule engine that flags words and phrases by severity, from built-in lists, the user's
  own lists, and the active tier.
- An overlay that covers flagged text in place (strike-through, solid bar, or frosted box)
  and tracks it as the screen scrolls.
- A settings screen for tiers, custom words, and redaction style, plus onboarding for the
  two required permissions.
- A warn-and-log option (count a hit without covering) for accountability setups.

**Out of scope (later stages)**

- Image and video detection and blur (Stage 2).
- OCR for text drawn as pixels (Stage 2); Stage 1 handles only text exposed via accessibility.
- Device Owner lockdown, uninstall / Safe-Mode protection, forced VPN (Stage 5).
- Any key-holder/parent companion app or remote reporting (Stage 3); Stage 1 logging is local only.
- True content-aware blur of the pixels underneath (needs screen capture; Stage 2).

## User-visible behavior

- With protection on, a flagged word anywhere on screen is covered within a moment of
  appearing — Messages, WhatsApp, a browser, a social feed, email, anywhere text shows.
- The cover sits over the words only; the rest of the message stays readable.
- As the user scrolls, covers move with their text; new flagged text is covered as it scrolls in.
- Redaction style is the key-holder's choice: a line through the word, a solid bar, or a
  frosted box. Severity can escalate (strike a mild word, bar a slur).
- Changing the tier or editing the word lists takes effect immediately, no restart.
- Warn-and-log mode covers nothing but counts each hit in the local activity log.
- Nothing is covered on the lock screen, in VEIL itself, or in safe-listed apps.

## Technical design — accessibility reader

An `AccessibilityService` is the only API that reads another app's on-screen text with
positions. Declare it with `canRetrieveWindowContent="true"`, events
`typeWindowContentChanged | typeWindowStateChanged | typeViewScrolled`, flags
`flagRetrieveInteractiveWindows | flagReportViewIds`, feedback `feedbackGeneric`.
Do **not** set `isAccessibilityTool` (Play policy: this is a monitoring use, declared as such).

- **Reading the screen.** On a relevant event, take `rootInActiveWindow` and walk the
  `AccessibilityNodeInfo` tree depth-first. For each node read `getText()` (fallback
  `getContentDescription()`) and `getBoundsInScreen(Rect)`. Collect `(string, Rect)`.
  Recycle nodes. Skip off-screen / zero-size subtrees.
- **Word-level positions.** `getBoundsInScreen()` is per-node, not per-word. Estimate a
  word's sub-rect from font metrics, or use per-character bounds where exposed; a
  whole-line cover is the acceptable fallback.
- **Debounce.** Events fire in bursts. Coalesce with a ~150 ms settle timer; scan once.
  Diff against the previous scan so unchanged covers are left in place (no flicker).
  Never poll on a fixed timer — the service is purely event-driven, which is what keeps it
  off the battery.

## Technical design — rules and tiers

Extend build one's rule model; don't invent a new one. A text rule has a pattern, a
category, and a severity.

- **Matching.** Normalize each string: lowercase, trim surrounding punctuation, collapse
  repeats. Match whole words on word boundaries (so "sex" doesn't fire inside "Essex"),
  plus explicit phrases. Optional leet pass (`@→a`, `0→o`, `$→s`) behind a toggle, off by
  default (raises false positives).
- **Severity levels.** `mild`, `strong`, `explicit`. Each tier maps severity → outcome
  (e.g. Teen = strike `strong`, bar `explicit`, ignore `mild`). Outcomes are build one's
  four: allow, cover, blur (Stage 2), warn-and-log.
- **Lists.** Built-in lists ship as assets (`assets/text/*.json`) grouped by category
  (profanity, sexual, slurs, self-harm, violence) and severity. User add/remove lives in
  `RuleStore`. Effective = built-in ∪ user-added − user-removed, recomputed on change.
- **Data format.** `{ "category": "sexual", "severity": "explicit", "terms": [...], "phrases": [...] }`.
  Keep the matcher compiled in memory (a normalized set per severity) so a scan is hash
  lookups, not a list scan per word.

The tier is a stored setting (Young-child, Child, Teen, Adult, Custom) picking the
severity→outcome map and category toggles; Custom exposes each directly.

## Technical design — overlay redactor

A foreground service owns one overlay added via `WindowManager`, type
`TYPE_APPLICATION_OVERLAY`, flags `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_NO_LIMITS`.
Not-touchable matters: taps pass through to the app, so covering never breaks it. The
overlay view is one custom `View` sized to the screen that draws the cover list in
`onDraw`; update by handing it the new `(Rect, style)` list and calling `invalidate()`.

**Three redaction styles**

- **Strike-through** — a line through the word's rect. Lightest; word still legible → mild.
- **Solid bar** — opaque rounded rect; word unreadable.
- **Frosted box** — semi-opaque panel; "covered" without a hard black bar.

**Honest note on "blur."** A true blur of the pixels *underneath* an overlay is not
possible from an overlay alone — it can't see what's behind it. Blurring underlying content
needs capturing that region (Stage 2). In Stage 1, "blur" means the frosted box; the UI may
call it blur, but the developer should know it is a cover, not a gaussian blur.

**Scroll tracking.** On `typeViewScrolled`, re-scan (debounced) and update rects. Scans
return absolute screen bounds, so a redraw with new rects is enough; diff against the last
frame to avoid flicker. Clear covers when the foreground app is safe-listed or the screen
turns off.

**Lifecycle.** Overlay service runs while protection is on and the accessibility service is
connected; stops with either; survives the accessibility service rebinding.

## Performance, permissions, onboarding

**Battery rules this stage must honor**

- Event-driven only; no polling loop anywhere.
- Debounce bursts; scan once per settled change.
- Diff scans; leave unchanged covers; never redraw the whole overlay needlessly.
- Nothing runs with the screen off or the device idle.
- Skip safe-listed apps before any tree walk (check the foreground package first).
- Cap node-walk depth/count; walk and match off the main thread, apply overlay on main.

**Permissions (both one-time; auto-granted and locked in the Device Owner build later)**

1. Accessibility access (Settings → Accessibility). Android 13+ sideload also needs
   "Allow restricted settings" first.
2. Display-over-other-apps (`SYSTEM_ALERT_WINDOW`), checked via `Settings.canDrawOverlays()`.

**Onboarding.** A short flow with plain-language prominent disclosure (required by Play
policy for this use), deep-linking to each toggle and detecting when granted; handles the
restricted-settings step on Android 13+; confirms both before saying protection is active;
re-prompts if either is revoked.

## Modules and files

New Kotlin under `app/src/main/java/app/veil/android/`, plus two existing files extended.

| File | Responsibility |
| --- | --- |
| `screen/VeilAccessibilityService.kt` | The service: config, events, debounce, orchestration |
| `screen/ScreenTextScanner.kt` | Walks the node tree → `(text, Rect)`, off the main thread |
| `screen/TextRuleEngine.kt` | Normalizes and matches; returns flagged spans + severity + outcome |
| `screen/OverlayController.kt` | Foreground service; owns the WindowManager overlay (add/update/clear) |
| `screen/RedactionOverlayView.kt` | Custom view drawing strike / bar / frosted covers |
| `screen/ForegroundAppTracker.kt` | Current package + safe-list check (the first cheap gate) |
| `rules/TextRules.kt` | Loads/compiles built-in lists; merges user lists from RuleStore |
| `assets/text/*.json` | Built-in word/phrase lists by category and severity |
| `ui/onboarding/ScreenPermissionsFlow.kt` | Guides and detects the accessibility + overlay grants |
| `res/xml/accessibility_service_config.xml` | The service declaration |
| `rules/RuleStore.kt` (extend) | Tier, per-category + text-severity settings, redaction style, warn-and-log |
| `ui/MainActivity.kt` (extend) | Settings for tiers, word lists, style; wire onboarding |

Manifest: the accessibility service with `BIND_ACCESSIBILITY_SERVICE`, the overlay
foreground service, and `SYSTEM_ALERT_WINDOW`. Keep the engine self-contained in `screen/`
so it toggles independently of the network layer.

## Acceptance criteria

- [ ] Flagged word in Google Messages covered within ~300 ms of becoming visible.
- [ ] Same works in WhatsApp, Chrome, Gmail, and one social feed — no per-app code.
- [ ] Cover is over the flagged word(s) only (whole-line fallback only where a sub-rect can't be derived).
- [ ] Scrolling moves covers with text and covers newly visible flagged text; nothing stranded > one scan.
- [ ] All three styles (strike, bar, frosted) render and are switchable.
- [ ] Severity works: a tier that strikes mild but bars explicit does exactly that.
- [ ] Editing the word list or switching tier changes behavior immediately, no restart.
- [ ] Warn-and-log covers nothing and increments the local log per hit.
- [ ] Safe-listed apps, lock screen, and VEIL itself are never covered.
- [ ] Taps/typing/scroll pass through the overlay; no app is made unusable.
- [ ] Screen on but static 10 min → no measurable CPU.
- [ ] Password fields never covered and never logged.
- [ ] Revoking accessibility or overlay permission is detected and surfaced.

## Test plan

Two phones (a Pixel and a Samsung) on current Android, plus one Android 10–12 device for
the restricted-settings path.

| Area | Scenario | Pass condition |
| --- | --- | --- |
| Messaging | Flagged words in Messages, WhatsApp | Covered ~300 ms, incoming and typed |
| Browser | Flagged words on a web page in Chrome | Covered; survives scroll |
| Scroll | Fast-scroll a long flagged thread | Covers track; no lasting misplacement; no flicker storm |
| Rotation | Rotate while covers show | Reposition correctly after rotation |
| Split-screen | Two apps visible | Flagged text covered in whichever shows it |
| Tiers | Young-child → Teen → Adult | Coverage changes to match, immediately |
| Styles | strike / bar / frosted | Each renders correctly |
| Passthrough | Tap/type under a cover | App works; input not blocked |
| Safe-list | Open a safe-listed app | Nothing covered; engine idle (no CPU) |
| Permissions | Revoke accessibility, then overlay | "Not fully protecting" within seconds |
| Battery | Static 10 min; then 30 min mixed use | No idle drain; active drain within target |
| Privacy | Type into a password field | Never covered, never logged |

Attach a short screen-recording of the Messages and scroll cases with the build.

## Edge cases and limits

- **Passwords / sensitive fields (hard rule).** Never cover, set aside, or log the contents
  of a password (`isPassword`) or autofill-sensitive field. Matching may run to decide
  coverage, but raw text of these fields must never reach the log or any report.
- **Custom-drawn text.** Pixel-painted text exposes nothing to accessibility → Stage 2 OCR.
  Note it, don't solve it here.
- **Scroll flicker.** Diff-against-last-scan + debounce prevents it. Make it a test.
- **Word sub-rects.** Approximate from font metrics; prefer a whole-line cover to a misplaced box.
- **RTL / non-Latin.** Normalization and boundaries must not break Arabic/Hebrew/CJK; do no harm.
- **Notifications / heads-up.** Decide whether to cover the shade (nice to have) or defer; state it.
- **Multi-window / PiP.** Absolute bounds handle split-screen; verify PiP strands no cover.
- **Latency is inherent.** Sub-second gap before a cover lands; fast text can flash. Honest limit.
- **Safe Mode / disabling.** Expected on this normal-install stage; Stage 5 (lockdown) addresses it.

## Dependencies, effort, definition of done

**Dependencies.** Android SDK and the existing VEIL project only. No third-party libraries.
No ML model (Stage 2). ML Kit text recognition only when the OCR fallback is built (Stage 2).

**Effort.** ~3–4 weeks, one strong Android dev: ~1 wk reader + scanner, ~1 wk overlay +
styles + scroll tracking, a few days rule engine + tiers on build one's model, a few days
onboarding + settings. OEM quirks (Samsung overlays, background limits) are part of this.

**Definition of done**

- [ ] Every acceptance criterion passes on both the Pixel and the Samsung.
- [ ] The test-plan table is green, including the privacy and battery rows.
- [ ] A screen-recording of the Messages and scroll cases is attached.
- [ ] Code in the `screen/` package, builds in CI, behind the permission onboarding (off until granted).
- [ ] No raw screen text or password content is written to the log or leaves the device.
- [ ] Merged to `main`; the build publishes an APK the way build one does.

When done, Stage 2 (image/video blur + OCR fallback) reuses this read → decide → cover loop
with a model and screen capture added.
