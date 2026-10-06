import { PACKAGE_RE, sanitizeEnabled, sanitizeRules, type InAppRule } from "./inapp.ts";

export { PACKAGE_RE };

/**
 * The settings an admin controls on a paired phone. Mirrors the app's RuleStore
 * one-to-one (see app/src/main/java/app/veil/android/remote/RemoteConfig.kt),
 * plus the Device Owner lockdown policies. Everything that reaches storage goes
 * through sanitizeConfig, whether it came from the dashboard or the AI assistant.
 */

export const TIERS = ["young_child", "child", "teen", "adult", "custom"] as const;
export const TEXT_ACTIONS = ["ignore", "strike", "bar", "frost"] as const;

export interface TempAllow {
  host: string;
  until: number; // epoch ms
}

export interface LockdownPolicy {
  blockUninstall: boolean;      // always applied while paired; kept for display
  alwaysOnVpn: boolean;
  vpnLockdown: boolean;         // block all traffic while the VPN is down
  disallowVpnConfig: boolean;
  disallowPrivateDnsConfig: boolean;
  disallowSafeBoot: boolean;
  disallowFactoryReset: boolean;
  disallowAddUser: boolean;
  disallowAppsControl: boolean; // no force-stop / clear-data from Settings
  disallowUnknownSources: boolean;
  disallowDebugging: boolean;
}

export interface DeviceConfig {
  adultList: boolean;
  keywordsEnabled: boolean;
  keywords: string[];
  safeSearch: boolean;
  youtubeStrict: boolean;
  bypassProtection: boolean;
  upstreamFamily: boolean;
  notifyOnBlock: boolean;
  customBlock: string[];
  customAllow: string[];
  tempAllow: TempAllow[];
  aiClassification: boolean;
  screen: {
    enabled: boolean;
    tier: (typeof TIERS)[number];
    logOnly: boolean;
    deobfuscate: boolean;
    customMild: (typeof TEXT_ACTIONS)[number];
    customStrong: (typeof TEXT_ACTIONS)[number];
    customExplicit: (typeof TEXT_ACTIONS)[number];
    blockWords: string[];
    allowWords: string[];
    safeListApps: string[];
  };
  lockdown: LockdownPolicy;
  apps: AppPolicy;
  /** In-app blocking: enabled catalog features (lib/inapp.ts) and the admin's own rules. */
  inApp: { enabled: string[]; custom: InAppRule[] };
}

export const APP_MODES = ["off", "blocklist", "allowlist"] as const;

/**
 * Which apps may open. "blocklist": everything except `blocked`; "allowlist":
 * only `allowed` (plus the phone's essentials, which the phone never blocks).
 * approveNewApps: apps installed after pairing stay blocked until allowed.
 */
export interface AppPolicy {
  mode: (typeof APP_MODES)[number];
  allowed: string[];
  blocked: string[];
  approveNewApps: boolean;
}

export const DEFAULT_KEYWORDS = [
  "porn", "xxx", "hentai", "xvideos", "xnxx", "xhamster", "redtube", "youporn",
  "pornhub", "onlyfans", "nsfw", "chaturbate", "stripchat", "livejasmin",
  "brazzers", "rule34", "nhentai", "e-hentai", "fapello", "camgirl", "erome",
];

export const DEFAULT_SAFELIST = [
  "app.veil.android", "com.android.systemui", "com.android.settings", "com.android.dialer",
  "com.google.android.dialer", "com.android.deskclock", "com.google.android.deskclock",
];

export function defaultConfig(): DeviceConfig {
  return {
    adultList: true,
    keywordsEnabled: true,
    keywords: [...DEFAULT_KEYWORDS],
    safeSearch: true,
    youtubeStrict: false,
    bypassProtection: true,
    upstreamFamily: true,
    notifyOnBlock: true,
    customBlock: [],
    customAllow: [],
    tempAllow: [],
    aiClassification: true,
    screen: {
      enabled: true,
      tier: "child",
      logOnly: false,
      deobfuscate: false,
      customMild: "ignore",
      customStrong: "strike",
      customExplicit: "bar",
      blockWords: [],
      allowWords: [],
      safeListApps: [...DEFAULT_SAFELIST],
    },
    lockdown: {
      blockUninstall: true,
      alwaysOnVpn: true,
      vpnLockdown: false,
      disallowVpnConfig: true,
      disallowPrivateDnsConfig: true,
      disallowSafeBoot: true,
      disallowFactoryReset: true,
      disallowAddUser: true,
      disallowAppsControl: false,
      disallowUnknownSources: false,
      disallowDebugging: false,
    },
    apps: { mode: "off", allowed: [], blocked: [], approveNewApps: false },
    inApp: { enabled: [], custom: [] },
  };
}

/** Same rules as RuleStore.normalizeHost on the phone. */
export function normalizeHost(input: string): string | null {
  let s = String(input ?? "").trim().toLowerCase();
  if (!s) return null;
  s = s.replace(/^https?:\/\//, "");
  s = s.split("/")[0].split("?")[0].split("#")[0].split(":")[0];
  s = s.replace(/^\*\./, "").replace(/^www\./, "").replace(/^\.+|\.+$/g, "");
  if (!s || !s.includes(".")) return null;
  if (!/^[a-z0-9.-]+$/.test(s)) return null;
  if (s.length > 253) return null;
  return s;
}

const MAX_LIST = 2000;

function bool(v: unknown, fallback: boolean): boolean {
  return typeof v === "boolean" ? v : fallback;
}

function oneOf<T extends string>(v: unknown, allowed: readonly T[], fallback: T): T {
  return typeof v === "string" && (allowed as readonly string[]).includes(v) ? (v as T) : fallback;
}

function hosts(v: unknown, fallback: string[]): string[] {
  if (!Array.isArray(v)) return fallback;
  const out = new Set<string>();
  for (const x of v) {
    const h = normalizeHost(String(x));
    if (h) out.add(h);
    if (out.size >= MAX_LIST) break;
  }
  return [...out].sort();
}

function words(v: unknown, fallback: string[], minLen: number): string[] {
  if (!Array.isArray(v)) return fallback;
  const out = new Set<string>();
  for (const x of v) {
    const w = String(x).trim().toLowerCase();
    if (w.length >= minLen && w.length <= 64 && !/\s/.test(w)) out.add(w);
    if (out.size >= MAX_LIST) break;
  }
  return [...out].sort();
}


function packages(v: unknown, fallback: string[], always: string[] = ["app.veil.android"], never: string[] = []): string[] {
  if (!Array.isArray(v)) return fallback;
  const out = new Set<string>(always);
  for (const x of v) {
    const p = String(x).trim();
    if (PACKAGE_RE.test(p) && !never.includes(p)) out.add(p);
    if (out.size >= MAX_LIST) break;
  }
  return [...out].sort();
}

function tempAllows(v: unknown, fallback: TempAllow[], now: number): TempAllow[] {
  if (!Array.isArray(v)) return fallback.filter((t) => t.until > now);
  const byHost = new Map<string, number>();
  for (const x of v) {
    const o = x as Partial<TempAllow>;
    const h = normalizeHost(String(o?.host ?? ""));
    const until = Number(o?.until);
    if (!h || !Number.isFinite(until) || until <= now) continue;
    byHost.set(h, Math.max(byHost.get(h) ?? 0, until));
  }
  return [...byHost].map(([host, until]) => ({ host, until })).sort((a, b) => a.host.localeCompare(b.host));
}

/**
 * Applies a partial update on top of `base`, dropping anything malformed.
 * Unknown keys are ignored, so a stale dashboard or a confused model can't
 * inject settings the phone doesn't understand.
 */
export function sanitizeConfig(patch: unknown, base: DeviceConfig = defaultConfig(), now = Date.now()): DeviceConfig {
  const p = (patch && typeof patch === "object" ? patch : {}) as Record<string, any>;
  const s = (p.screen && typeof p.screen === "object" ? p.screen : {}) as Record<string, any>;
  const l = (p.lockdown && typeof p.lockdown === "object" ? p.lockdown : {}) as Record<string, any>;
  const a = (p.apps && typeof p.apps === "object" ? p.apps : {}) as Record<string, any>;
  const b = base;
  // Devices paired before app control existed have no apps policy stored yet.
  const ba = b.apps ?? defaultConfig().apps;
  return {
    adultList: bool(p.adultList, b.adultList),
    keywordsEnabled: bool(p.keywordsEnabled, b.keywordsEnabled),
    keywords: words(p.keywords, b.keywords, 3),
    safeSearch: bool(p.safeSearch, b.safeSearch),
    youtubeStrict: bool(p.youtubeStrict, b.youtubeStrict),
    bypassProtection: bool(p.bypassProtection, b.bypassProtection),
    upstreamFamily: bool(p.upstreamFamily, b.upstreamFamily),
    notifyOnBlock: bool(p.notifyOnBlock, b.notifyOnBlock),
    customBlock: hosts(p.customBlock, b.customBlock),
    customAllow: hosts(p.customAllow, b.customAllow),
    tempAllow: tempAllows(p.tempAllow, b.tempAllow, now),
    aiClassification: bool(p.aiClassification, b.aiClassification),
    screen: {
      enabled: bool(s.enabled, b.screen.enabled),
      tier: oneOf(s.tier, TIERS, b.screen.tier),
      logOnly: bool(s.logOnly, b.screen.logOnly),
      deobfuscate: bool(s.deobfuscate, b.screen.deobfuscate),
      customMild: oneOf(s.customMild, TEXT_ACTIONS, b.screen.customMild),
      customStrong: oneOf(s.customStrong, TEXT_ACTIONS, b.screen.customStrong),
      customExplicit: oneOf(s.customExplicit, TEXT_ACTIONS, b.screen.customExplicit),
      blockWords: words(s.blockWords, b.screen.blockWords, 2),
      allowWords: words(s.allowWords, b.screen.allowWords, 2),
      safeListApps: packages(s.safeListApps, b.screen.safeListApps),
    },
    lockdown: {
      blockUninstall: true,
      alwaysOnVpn: bool(l.alwaysOnVpn, b.lockdown.alwaysOnVpn),
      vpnLockdown: bool(l.vpnLockdown, b.lockdown.vpnLockdown),
      disallowVpnConfig: bool(l.disallowVpnConfig, b.lockdown.disallowVpnConfig),
      disallowPrivateDnsConfig: bool(l.disallowPrivateDnsConfig, b.lockdown.disallowPrivateDnsConfig),
      disallowSafeBoot: bool(l.disallowSafeBoot, b.lockdown.disallowSafeBoot),
      disallowFactoryReset: bool(l.disallowFactoryReset, b.lockdown.disallowFactoryReset),
      disallowAddUser: bool(l.disallowAddUser, b.lockdown.disallowAddUser),
      disallowAppsControl: bool(l.disallowAppsControl, b.lockdown.disallowAppsControl),
      disallowUnknownSources: bool(l.disallowUnknownSources, b.lockdown.disallowUnknownSources),
      disallowDebugging: bool(l.disallowDebugging, b.lockdown.disallowDebugging),
    },
    inApp: {
      enabled: sanitizeEnabled(p.inApp?.enabled, b.inApp?.enabled ?? []),
      custom: Array.isArray(p.inApp?.custom) ? sanitizeRules(p.inApp.custom) : (b.inApp?.custom ?? []),
    },
    apps: (() => {
      const allowed = packages(a.allowed, ba.allowed, []);
      // VEIL itself can never be blocked; an app can't be on both lists (allowed wins).
      const blocked = packages(a.blocked, ba.blocked, [], ["app.veil.android"]).filter((x) => !allowed.includes(x));
      return { mode: oneOf(a.mode, APP_MODES, ba.mode), allowed, blocked, approveNewApps: bool(a.approveNewApps, ba.approveNewApps) };
    })(),
  };
}
