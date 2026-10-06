import { createHash } from "node:crypto";
/** An Android package name. Lives here (not config.ts) so config.ts can import this module without a cycle. */
export const PACKAGE_RE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/;

/**
 * In-app blocking: switch off parts of an app while the rest keeps working
 * (WhatsApp's Updates tab, YouTube Shorts...). Each feature is a few rules the
 * phone's accessibility service checks against the screen of that app:
 *
 *   match  - a screen element: its view id contains `viewId`, its text equals
 *            `text`, its description contains `desc` (any given field must
 *            match; `selected` also requires the element to be selected)
 *   action - "cover": draw a solid bar over that element
 *            "leave": go back off this screen (for whole screens like a feed)
 *
 * Apps change their layouts, so the catalog lives here on the server: fixing a
 * rule reaches every phone on its next check-in, without an app update.
 */

export interface InAppMatch {
  viewId?: string;
  text?: string;
  desc?: string;
  selected?: boolean;
}

export interface InAppRule {
  app: string;
  match: InAppMatch;
  action: "cover" | "leave";
}

export interface InAppFeature {
  id: string;
  app: string;
  appName: string;
  name: string;
  description: string;
  rules: InAppRule[];
}

const WA = ["com.whatsapp", "com.whatsapp.w4b"];

function forApps(apps: string[], rules: Omit<InAppRule, "app">[]): InAppRule[] {
  return apps.flatMap((app) => rules.map((r) => ({ ...r, app })));
}

export const CATALOG: InAppFeature[] = [
  {
    id: "whatsapp_updates", app: "com.whatsapp", appName: "WhatsApp",
    name: "Status and Channels",
    description: "Hides the Updates tab (Status and Channels). Chats and calls keep working.",
    rules: forApps(WA, [
      { match: { text: "Updates" }, action: "cover" },
      { match: { text: "Status" }, action: "cover" },
      { match: { text: "Updates", selected: true }, action: "leave" },
      { match: { viewId: "status_playback" }, action: "leave" },
      { match: { viewId: "newsletter" }, action: "leave" },
    ]),
  },
  {
    id: "whatsapp_photos", app: "com.whatsapp", appName: "WhatsApp",
    name: "Profile photos",
    description: "Covers contacts' and groups' profile pictures.",
    rules: forApps(WA, [
      { match: { viewId: "contact_photo" }, action: "cover" },
      { match: { viewId: "conversation_contact_photo" }, action: "cover" },
      { match: { viewId: "profile_picture" }, action: "cover" },
      { match: { viewId: "photo_btn" }, action: "cover" },
    ]),
  },
  {
    id: "youtube_shorts", app: "com.google.android.youtube", appName: "YouTube",
    name: "Shorts",
    description: "Hides the Shorts tab and leaves the Shorts player.",
    rules: [
      { app: "com.google.android.youtube", match: { text: "Shorts" }, action: "cover" },
      { app: "com.google.android.youtube", match: { desc: "Shorts" }, action: "cover" },
      { app: "com.google.android.youtube", match: { viewId: "reel_" }, action: "leave" },
    ],
  },
  {
    id: "youtube_search", app: "com.google.android.youtube", appName: "YouTube",
    name: "Search",
    description: "Hides search, so only subscriptions and shared links can be watched.",
    rules: [
      { app: "com.google.android.youtube", match: { desc: "Search" }, action: "cover" },
      { app: "com.google.android.youtube", match: { viewId: "search_edit_text" }, action: "leave" },
    ],
  },
  {
    id: "youtube_comments", app: "com.google.android.youtube", appName: "YouTube",
    name: "Comments",
    description: "Covers the comments section under videos.",
    rules: [
      { app: "com.google.android.youtube", match: { viewId: "comment" }, action: "cover" },
    ],
  },
  {
    id: "instagram_reels", app: "com.instagram.android", appName: "Instagram",
    name: "Reels",
    description: "Hides the Reels tab and leaves the Reels player.",
    rules: [
      { app: "com.instagram.android", match: { desc: "Reels" }, action: "cover" },
      { app: "com.instagram.android", match: { viewId: "clips_" }, action: "leave" },
    ],
  },
  {
    id: "instagram_explore", app: "com.instagram.android", appName: "Instagram",
    name: "Explore",
    description: "Hides Search and Explore; the home feed of people followed stays.",
    rules: [
      { app: "com.instagram.android", match: { desc: "Search and explore" }, action: "cover" },
      { app: "com.instagram.android", match: { viewId: "explore" }, action: "leave" },
    ],
  },
  {
    id: "maps_photos", app: "com.google.android.apps.maps", appName: "Google Maps",
    name: "Place photos and reviews",
    description: "Covers photos and reviews on place pages. Navigation keeps working.",
    rules: [
      { app: "com.google.android.apps.maps", match: { desc: "Photo" }, action: "cover" },
      { app: "com.google.android.apps.maps", match: { viewId: "photo" }, action: "cover" },
      { app: "com.google.android.apps.maps", match: { text: "Reviews" }, action: "cover" },
    ],
  },
  {
    id: "google_discover", app: "com.google.android.googlequicksearchbox", appName: "Google app",
    name: "Discover feed",
    description: "Leaves the news and article feed in the Google app. Search keeps working.",
    rules: [
      { app: "com.google.android.googlequicksearchbox", match: { viewId: "discover" }, action: "leave" },
      { app: "com.google.android.googlequicksearchbox", match: { text: "Discover" }, action: "cover" },
    ],
  },
  {
    id: "spotify_video", app: "com.spotify.music", appName: "Spotify",
    name: "Video podcasts and canvases",
    description: "Covers video clips and video podcasts; audio keeps playing.",
    rules: [
      { app: "com.spotify.music", match: { viewId: "video" }, action: "cover" },
      { app: "com.spotify.music", match: { viewId: "canvas" }, action: "cover" },
    ],
  },
];

const MAX_CUSTOM = 50;

function short(v: unknown, max = 100): string | undefined {
  return typeof v === "string" && v.trim() ? v.trim().slice(0, max) : undefined;
}

/** Validates admin-made rules: a known package, at least one non-trivial matcher, a known action. */
export function sanitizeRules(v: unknown): InAppRule[] {
  if (!Array.isArray(v)) return [];
  const out: InAppRule[] = [];
  for (const x of v) {
    const o = (x && typeof x === "object" ? x : {}) as Record<string, any>;
    const m = (o.match && typeof o.match === "object" ? o.match : {}) as Record<string, any>;
    const app = short(o.app, 200);
    if (!app || !PACKAGE_RE.test(app)) continue;
    const match: InAppMatch = { viewId: short(m.viewId), text: short(m.text), desc: short(m.desc) };
    if (m.selected === true) match.selected = true;
    // Too-short matchers would hit half the screen.
    if (![match.viewId, match.text, match.desc].some((s) => s && s.length >= 3)) continue;
    for (const k of ["viewId", "text", "desc"] as const) if (!match[k]) delete match[k];
    out.push({ app, match, action: o.action === "leave" ? "leave" : "cover" });
    if (out.length >= MAX_CUSTOM) break;
  }
  return out;
}

export function sanitizeEnabled(v: unknown, fallback: string[]): string[] {
  if (!Array.isArray(v)) return fallback;
  const ids = new Set(CATALOG.map((f) => f.id));
  return [...new Set(v.map(String).filter((id) => ids.has(id)))].sort();
}

/** The rules a phone should run: the enabled catalog features plus the admin's own. */
export function effectiveRules(inApp: { enabled: string[]; custom: InAppRule[] } | undefined): { rules: InAppRule[]; hash: string } {
  const enabled = new Set(inApp?.enabled ?? []);
  const rules = [...CATALOG.filter((f) => enabled.has(f.id)).flatMap((f) => f.rules), ...(inApp?.custom ?? [])];
  const hash = createHash("sha256").update(JSON.stringify(rules)).digest("hex").slice(0, 16);
  return { rules, hash };
}

/** What the dashboard shows: features without the rule internals. */
export function publicCatalog() {
  return CATALOG.map(({ id, app, appName, name, description }) => ({ id, app, appName, name, description }));
}
