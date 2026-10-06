import { defaultConfig, normalizeHost, PACKAGE_RE, sanitizeConfig, type DeviceConfig } from "./config.ts";
import { randomInt } from "node:crypto";
import { effectiveRules, type InAppRule } from "./inapp.ts";
import { hashPassword, id, normalizePairingCode, pairingCode, sha256, token, verifyPassword } from "./crypto.ts";
import { HttpError, str } from "./http.ts";
import { kv } from "./store.ts";

// ------------------------------------------------------------------ types

export interface Admin {
  id: string;
  email: string;
  name: string;
  passHash: string;
  createdAt: number;
}

export interface DeviceStatus {
  protection?: boolean;
  vpnRunning?: boolean;
  screenFilter?: boolean;
  accessibilityOn?: boolean;
  overlayAllowed?: boolean;
  deviceOwner?: boolean;
  privateDns?: string;
  blockedToday?: number;
  blockedTotal?: number;
  textCoveredTotal?: number;
  imagesCoveredTotal?: number;
  imageFilterSupported?: boolean;
  androidVersion?: string;
  model?: string;
}

export interface Command {
  id: string;
  type: "refresh_lists" | "release" | "sync_now";
  at: number;
}

export interface Alert {
  type: string;
  at: number;
  detail: string;
}

export interface Device {
  id: string;
  adminId: string;
  name: string;
  tokenHash: string;
  recoveryHash: string;
  pairedAt: number;
  lastSeen: number;
  appVersion: string;
  status: DeviceStatus;
  config: DeviceConfig;
  configVersion: number;
  appliedConfigVersion: number;
  commands: Command[];
  alerts: Alert[];
  state: "active" | "releasing" | "released";
  /** Set when we alerted the admin that this phone went silent; cleared on next check-in. */
  silentAlerted?: boolean;
  /** Launchable apps on the phone, for the admin's Apps tab. Sent only when it changes. */
  apps?: InstalledApp[];
}

export interface InstalledApp {
  package: string;
  label: string;
  system: boolean;
  blocked: boolean;
}

export interface DeviceEvent {
  type: "block" | "tamper" | "text" | "image" | "info";
  at: number;
  host?: string;
  reason?: string;
  rule?: string;
  count?: number;
  detail?: string;
}

export interface AiReview {
  recommendation: "approve" | "approve_limited" | "deny";
  suggestedMinutes: number;
  category: string;
  risk: "low" | "medium" | "high";
  explanation: string;
  /** The model is genuinely unsure and wants a person to decide. */
  needsHuman?: boolean;
}

export interface UnblockRequest {
  id: string;
  deviceId: string;
  localId: string;
  /** "site" (default; host is a domain) or "app" (host is the package name). */
  kind?: "site" | "app";
  label?: string;
  host: string;
  reason: string;
  createdAt: number;
  status: "pending" | "approved" | "denied";
  until?: number; // approved: epoch ms, 0 = permanently allowed
  decidedAt?: number;
  note?: string;
  ai?: AiReview;
  autoApproved?: boolean;
  autoDenied?: boolean;
  /** AI handled it but sent it to a human because it was unsure. */
  escalated?: boolean;
}

export interface Classification {
  domain: string;
  category: string;
  block: boolean;
  /** Confidently safe: allowed for phones in allowed-sites-only mode that opted in. */
  allow?: boolean;
  confidence: number;
  reason: string;
  at: number;
}

export interface Job {
  id: string;
  kind: "review" | "classify" | "summary" | "chat";
  status: "queued" | "done" | "error";
  payload: Record<string, unknown>;
  result?: unknown;
  error?: string;
  createdAt: number;
}

export interface ChatMessage {
  role: "admin" | "assistant";
  text: string;
  at: number;
  proposal?: { summary: string; patch: Record<string, unknown> } | null;
}

export interface Summary {
  text: string;
  highlights: string[];
  concernLevel: "none" | "low" | "medium" | "high";
  periodDays: number;
  at: number;
}

// ------------------------------------------------------------------ keys

const K = {
  admin: (id: string) => `admins/${id}`,
  adminEmail: (email: string) => `admin-email/${sha256(email)}`,
  adminDevices: (id: string) => `admin-devices/${id}`,
  session: (tokenHash: string) => `sessions/${tokenHash}`,
  loginFail: (email: string) => `login-fail/${sha256(email)}`,
  pair: (code: string) => `pair/${code}`,
  device: (id: string) => `devices/${id}`,
  deviceToken: (hash: string) => `device-token/${hash}`,
  events: (deviceId: string, day: string) => `events/${deviceId}/${day}`,
  request: (deviceId: string, id: string) => `requests/${deviceId}/${id}`,
  classify: (domain: string) => `classify/${domain}`,
  aiBlocklist: () => `ai-blocklist`,
  job: (id: string) => `jobs/${id}`,
  chat: (deviceId: string) => `chat/${deviceId}`,
  summary: (deviceId: string) => `summary/${deviceId}`,
  secret: () => `internal-secret`,
};

const DAY = 86_400_000;
const SESSION_TTL = 30 * DAY;
const PAIR_TTL = 30 * 60_000;
const MAX_EVENTS_PER_DAY = 3000;
const MAX_ALERTS = 100;

export function dayKey(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10);
}

// ------------------------------------------------------------------ admins

export async function adminCount(): Promise<number> {
  return (await kv().list("admins/")).length;
}

/**
 * Sign-up is closed by default: the very first admin can register freely
 * (first-run setup), later ones need ADMIN_SIGNUP_CODE when it is set.
 */
export async function createAdmin(input: { email: string; password: string; name: string; signupCode?: string }, requiredCode: string | undefined): Promise<Admin> {
  const email = str(input.email, 200).toLowerCase();
  const name = str(input.name, 80) || email.split("@")[0];
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) throw new HttpError(400, "Enter a valid email address");
  if (typeof input.password !== "string" || input.password.length < 10) throw new HttpError(400, "Password must be at least 10 characters");
  const existing = await adminCount();
  if (existing > 0) {
    if (!requiredCode) throw new HttpError(403, "Sign-up is closed. Ask the owner to set ADMIN_SIGNUP_CODE.");
    if (str(input.signupCode, 200) !== requiredCode) throw new HttpError(403, "Wrong sign-up code");
  }
  if (await kv().get(K.adminEmail(email))) throw new HttpError(409, "An admin with that email already exists");
  const admin: Admin = { id: id("adm"), email, name, passHash: await hashPassword(input.password), createdAt: Date.now() };
  await kv().set(K.admin(admin.id), admin);
  await kv().set(K.adminEmail(email), admin.id);
  await kv().set(K.adminDevices(admin.id), []);
  return admin;
}

export async function login(emailIn: string, password: string): Promise<{ admin: Admin; token: string }> {
  const email = str(emailIn, 200).toLowerCase();
  const fails = (await kv().get<{ count: number; until: number }>(K.loginFail(email))) ?? { count: 0, until: 0 };
  if (fails.until > Date.now()) throw new HttpError(429, "Too many attempts. Try again in 15 minutes.");
  const adminId = await kv().get<string>(K.adminEmail(email));
  const admin = adminId ? await kv().get<Admin>(K.admin(adminId)) : null;
  if (!admin || typeof password !== "string" || !(await verifyPassword(password, admin.passHash))) {
    const count = fails.count + 1;
    await kv().set(K.loginFail(email), { count: count >= 10 ? 0 : count, until: count >= 10 ? Date.now() + 15 * 60_000 : 0 });
    throw new HttpError(401, "Wrong email or password");
  }
  await kv().del(K.loginFail(email));
  const t = token();
  await kv().set(K.session(sha256(t)), { adminId: admin.id, expires: Date.now() + SESSION_TTL });
  return { admin, token: t };
}

export async function sessionAdmin(t: string | null): Promise<Admin | null> {
  if (!t) return null;
  const s = await kv().get<{ adminId: string; expires: number }>(K.session(sha256(t)));
  if (!s || s.expires < Date.now()) return null;
  return kv().get<Admin>(K.admin(s.adminId));
}

export async function logout(t: string | null): Promise<void> {
  if (t) await kv().del(K.session(sha256(t)));
}

export function publicAdmin(a: Admin) {
  return { id: a.id, email: a.email, name: a.name };
}

// ------------------------------------------------------------------ pairing

export async function createPairingCode(adminId: string, deviceName: string): Promise<{ code: string; expires: number }> {
  const code = pairingCode();
  const expires = Date.now() + PAIR_TTL;
  await kv().set(K.pair(code), { adminId, deviceName: str(deviceName, 60), expires });
  return { code, expires };
}

export async function pairDevice(input: { code: string; deviceName?: string; appVersion?: string; status?: DeviceStatus }): Promise<{ device: Device; token: string; recoveryCode: string; adminName: string }> {
  const code = normalizePairingCode(str(input.code, 20));
  const p = await kv().get<{ adminId: string; deviceName: string; expires: number }>(K.pair(code));
  if (!p || p.expires < Date.now()) throw new HttpError(404, "That pairing code is wrong or has expired. Ask your admin for a new one.");
  await kv().del(K.pair(code)); // single use
  const admin = await kv().get<Admin>(K.admin(p.adminId));
  if (!admin) throw new HttpError(404, "Admin account no longer exists");

  const t = token();
  // Offline escape hatch: typed into the phone, it releases the lockdown even
  // if this backend is gone. Shown once to the admin, stored only as a hash.
  const recoveryCode = String(randomInt(0, 100_000_000)).padStart(8, "0");
  const now = Date.now();
  const device: Device = {
    id: id("dev"),
    adminId: admin.id,
    name: p.deviceName || str(input.deviceName, 60) || "Phone",
    tokenHash: sha256(t),
    recoveryHash: sha256(`veil-recovery:${recoveryCode}`),
    pairedAt: now,
    lastSeen: now,
    appVersion: str(input.appVersion, 40),
    status: sanitizeStatus(input.status),
    config: defaultConfig(),
    configVersion: 1,
    appliedConfigVersion: 0,
    commands: [],
    alerts: [],
    state: "active",
  };
  await kv().set(K.device(device.id), device);
  await kv().set(K.deviceToken(device.tokenHash), device.id);
  const list = (await kv().get<string[]>(K.adminDevices(admin.id))) ?? [];
  await kv().set(K.adminDevices(admin.id), [...list, device.id]);
  await setLastRecoveryCode(device.id, recoveryCode);
  return { device, token: t, recoveryCode, adminName: admin.name };
}

/** Kept until the admin has seen it once on the dashboard. */
async function setLastRecoveryCode(deviceId: string, code: string): Promise<void> {
  await kv().set(`recovery-once/${deviceId}`, code);
}

export async function takeRecoveryCode(deviceId: string): Promise<string | null> {
  const code = await kv().get<string>(`recovery-once/${deviceId}`);
  if (code) await kv().del(`recovery-once/${deviceId}`);
  return code;
}

export async function authDevice(t: string | null): Promise<Device> {
  if (!t) throw new HttpError(401, "Missing device token");
  const deviceId = await kv().get<string>(K.deviceToken(sha256(t)));
  const device = deviceId ? await kv().get<Device>(K.device(deviceId)) : null;
  if (!device || device.state === "released") throw new HttpError(401, "This phone is not paired");
  return device;
}

function sanitizeStatus(s: unknown): DeviceStatus {
  const o = (s && typeof s === "object" ? s : {}) as Record<string, unknown>;
  const b = (k: string) => (typeof o[k] === "boolean" ? (o[k] as boolean) : undefined);
  const n = (k: string) => (typeof o[k] === "number" && Number.isFinite(o[k]) ? (o[k] as number) : undefined);
  return {
    protection: b("protection"),
    vpnRunning: b("vpnRunning"),
    screenFilter: b("screenFilter"),
    accessibilityOn: b("accessibilityOn"),
    overlayAllowed: b("overlayAllowed"),
    deviceOwner: b("deviceOwner"),
    privateDns: str(o.privateDns, 200),
    blockedToday: n("blockedToday"),
    blockedTotal: n("blockedTotal"),
    textCoveredTotal: n("textCoveredTotal"),
    imagesCoveredTotal: n("imagesCoveredTotal"),
    imageFilterSupported: b("imageFilterSupported"),
    androidVersion: str(o.androidVersion, 20),
    model: str(o.model, 80),
  };
}

// ------------------------------------------------------------------ sync

export interface SyncInput {
  appVersion?: string;
  appliedConfigVersion?: number;
  status?: DeviceStatus;
  events?: DeviceEvent[];
  requests?: { localId: string; kind?: string; host: string; label?: string; reason?: string; at?: number }[];
  unknownDomains?: string[];
  apps?: { package: string; label?: string; system?: boolean; blocked?: boolean }[];
  /** Hash of the in-app rules the phone runs; the server sends new rules when it differs. */
  inAppHash?: string;
  commandAcks?: string[];
}

export interface SyncResult {
  response: {
    serverTime: number;
    configVersion: number;
    config?: DeviceConfig;
    commands: Command[];
    requests: { localId: string; status: UnblockRequest["status"]; until?: number; note?: string }[];
    aiBlocklistVersion: number;
    inAppHash: string;
    inAppRules?: InAppRule[];
    adminName: string;
    pollSeconds: number;
  };
  reviewRequestIds: string[];
  classifyDomains: string[];
}

const TAMPER_LABELS: Record<string, string> = {
  vpn_revoked: "Protection VPN was turned off or replaced",
  vpn_stopped: "Protection was stopped",
  accessibility_off: "Screen filter (accessibility) was turned off",
  overlay_off: "Screen filter overlay permission was removed",
  private_dns: "Private DNS was set to bypass filtering",
  recovery_failed: "Wrong recovery code entered on the phone",
  recovery_used: "Recovery code used on the phone: lockdown released",
  not_device_owner: "VEIL is not Device Owner: lockdown is not active",
};

export async function syncDevice(device: Device, input: SyncInput): Promise<SyncResult> {
  const now = Date.now();
  device.lastSeen = now;
  device.silentAlerted = false;
  device.appVersion = str(input.appVersion, 40) || device.appVersion;
  if (input.status) device.status = sanitizeStatus(input.status);
  if (typeof input.appliedConfigVersion === "number") device.appliedConfigVersion = input.appliedConfigVersion;

  // Commands the phone has carried out.
  const acks = new Set((input.commandAcks ?? []).map(String));
  const released = device.commands.some((c) => c.type === "release" && acks.has(c.id));
  device.commands = device.commands.filter((c) => !acks.has(c.id));

  // Activity
  const events = (Array.isArray(input.events) ? input.events : []).slice(0, 500).map(cleanEvent).filter((e): e is DeviceEvent => !!e);
  for (const e of events) {
    if (e.type === "tamper") {
      device.alerts.unshift({ type: e.rule ?? "tamper", at: e.at, detail: TAMPER_LABELS[e.rule ?? ""] ?? e.detail ?? "Tamper alert" });
    }
  }
  device.alerts = device.alerts.slice(0, MAX_ALERTS);
  await appendEvents(device.id, events);

  if (Array.isArray(input.apps)) {
    device.apps = input.apps.slice(0, 1000)
      .filter((x) => x && PACKAGE_RE.test(str(x.package, 200)))
      .map((x) => ({ package: str(x.package, 200), label: str(x.label, 80) || str(x.package, 200), system: x.system === true, blocked: x.blocked === true }))
      .sort((a, b) => a.label.localeCompare(b.label));
  }

  // Unblock requests raised on the phone
  const reviewRequestIds: string[] = [];
  for (const r of (Array.isArray(input.requests) ? input.requests : []).slice(0, 20)) {
    const kind = r?.kind === "app" ? "app" : "site";
    const host = kind === "app" ? (PACKAGE_RE.test(str(r?.host, 200)) ? str(r?.host, 200) : null) : normalizeHost(r?.host ?? "");
    const localId = str(r?.localId, 60);
    if (!host || !localId) continue;
    const rid = `req_${sha256(`${device.id}:${localId}`).slice(0, 16)}`;
    if (await kv().get(K.request(device.id, rid))) continue; // retry of one we already have
    const req: UnblockRequest = {
      id: rid, deviceId: device.id, localId, host, kind, label: kind === "app" ? str(r.label, 80) || host : undefined,
      reason: str(r.reason, 500), createdAt: Number(r.at) || now, status: "pending",
    };
    await kv().set(K.request(device.id, rid), req);
    reviewRequestIds.push(rid);
  }

  // Sites the phone saw that no list covers, for AI classification
  const classifyDomains: string[] = [];
  if (device.config.aiClassification && Array.isArray(input.unknownDomains)) {
    for (const d of input.unknownDomains.slice(0, 100)) {
      const h = normalizeHost(String(d));
      if (!h || classifyDomains.includes(h)) continue;
      if (!(await kv().get(K.classify(h)))) classifyDomains.push(h);
    }
  }

  // Drop temporary allows that ran out, so the phone gets a clean list.
  const live = device.config.tempAllow.filter((t) => t.until > now);
  if (live.length !== device.config.tempAllow.length) {
    device.config.tempAllow = live;
    device.configVersion += 1;
  }

  if (released) device.state = "released";
  await kv().set(K.device(device.id), device);
  if (released) await kv().del(K.deviceToken(device.tokenHash));

  const recent = await listRequests(device.id, 7);
  const admin = await kv().get<Admin>(K.admin(device.adminId));
  const blocklist = await getAiBlocklist();
  const inApp = effectiveRules(device.config.inApp);
  return {
    response: {
      serverTime: now,
      configVersion: device.configVersion,
      config: device.appliedConfigVersion !== device.configVersion ? device.config : undefined,
      commands: device.commands,
      requests: recent.filter((r) => r.status !== "pending").map((r) => ({ localId: r.localId, status: r.status, until: r.until, note: r.note })),
      aiBlocklistVersion: blocklist.version,
      inAppHash: inApp.hash,
      inAppRules: input.inAppHash === inApp.hash ? undefined : inApp.rules,
      adminName: admin?.name ?? "your admin",
      pollSeconds: 60,
    },
    reviewRequestIds,
    classifyDomains,
  };
}

function cleanEvent(e: unknown): DeviceEvent | null {
  const o = (e && typeof e === "object" ? e : null) as Record<string, unknown> | null;
  if (!o) return null;
  const type = o.type;
  if (type !== "block" && type !== "tamper" && type !== "text" && type !== "image" && type !== "info") return null;
  const at = Number(o.at);
  return {
    type,
    at: Number.isFinite(at) && at > 0 ? at : Date.now(),
    host: str(o.host, 253) || undefined,
    reason: str(o.reason, 120) || undefined,
    rule: str(o.rule, 120) || undefined,
    count: Number.isFinite(Number(o.count)) ? Math.max(1, Math.min(100_000, Number(o.count))) : undefined,
    detail: str(o.detail, 300) || undefined,
  };
}

async function appendEvents(deviceId: string, events: DeviceEvent[]): Promise<void> {
  const byDay = new Map<string, DeviceEvent[]>();
  for (const e of events) {
    const k = dayKey(e.at);
    byDay.set(k, [...(byDay.get(k) ?? []), e]);
  }
  for (const [day, list] of byDay) {
    const existing = (await kv().get<DeviceEvent[]>(K.events(deviceId, day))) ?? [];
    await kv().set(K.events(deviceId, day), [...existing, ...list].slice(-MAX_EVENTS_PER_DAY));
  }
}

export async function listEvents(deviceId: string, days: number): Promise<DeviceEvent[]> {
  const out: DeviceEvent[] = [];
  const now = Date.now();
  for (let i = 0; i < days; i++) {
    out.push(...((await kv().get<DeviceEvent[]>(K.events(deviceId, dayKey(now - i * DAY)))) ?? []));
  }
  return out.sort((a, b) => b.at - a.at);
}

// ------------------------------------------------------------------ admin views

export async function listDevices(adminId: string): Promise<Device[]> {
  const ids = (await kv().get<string[]>(K.adminDevices(adminId))) ?? [];
  const out: Device[] = [];
  for (const i of ids) {
    const d = await kv().get<Device>(K.device(i));
    if (d && d.state !== "released") out.push(d);
  }
  return out;
}

export async function ownedDevice(adminId: string, deviceId: string): Promise<Device> {
  const d = await kv().get<Device>(K.device(deviceId));
  if (!d || d.adminId !== adminId || d.state === "released") throw new HttpError(404, "Device not found");
  return d;
}

/** Fetch a device by id with no owner check. For tests and internal jobs only. */
export async function getDeviceForTest(deviceId: string): Promise<Device | null> {
  return kv().get<Device>(K.device(deviceId));
}

export async function saveDevice(d: Device): Promise<void> {
  await kv().set(K.device(d.id), d);
}

export function publicDevice(d: Device) {
  const { tokenHash: _t, recoveryHash: _r, ...rest } = d;
  return { ...rest, online: Date.now() - d.lastSeen < 5 * 60_000, pendingConfig: d.appliedConfigVersion !== d.configVersion };
}

export async function updateConfig(device: Device, patch: unknown): Promise<Device> {
  device.config = sanitizeConfig(patch, device.config);
  device.configVersion += 1;
  await saveDevice(device);
  return device;
}

export async function renameDevice(device: Device, name: string): Promise<Device> {
  const n = str(name, 60);
  if (!n) throw new HttpError(400, "Name can't be empty");
  device.name = n;
  await saveDevice(device);
  return device;
}

export async function queueCommand(device: Device, type: Command["type"]): Promise<Command> {
  if (!["refresh_lists", "release", "sync_now"].includes(type)) throw new HttpError(400, "Unknown command");
  if (device.commands.some((c) => c.type === type)) return device.commands.find((c) => c.type === type)!;
  const c: Command = { id: id("cmd"), type, at: Date.now() };
  device.commands.push(c);
  if (type === "release") device.state = "releasing";
  await saveDevice(device);
  return c;
}

export async function clearAlerts(device: Device): Promise<void> {
  device.alerts = [];
  await saveDevice(device);
}

// ------------------------------------------------------------------ requests

export async function listRequests(deviceId: string, days = 30): Promise<UnblockRequest[]> {
  const keys = await kv().list(`requests/${deviceId}/`);
  const since = Date.now() - days * DAY;
  const out: UnblockRequest[] = [];
  for (const k of keys) {
    const r = await kv().get<UnblockRequest>(k);
    if (r && (r.createdAt >= since || r.status === "pending")) out.push(r);
  }
  return out.sort((a, b) => b.createdAt - a.createdAt);
}

export async function getRequest(deviceId: string, requestId: string): Promise<UnblockRequest | null> {
  return kv().get<UnblockRequest>(K.request(deviceId, requestId));
}

export async function saveRequest(r: UnblockRequest): Promise<void> {
  await kv().set(K.request(r.deviceId, r.id), r);
}

/**
 * Approving with minutes > 0 adds a temporary allow that the phone drops on
 * its own at expiry; minutes = 0 adds the site to the permanent allow list.
 */
export async function decideRequest(device: Device, requestId: string, approve: boolean, minutes: number, note: string): Promise<UnblockRequest> {
  const r = await getRequest(device.id, requestId);
  if (!r) throw new HttpError(404, "Request not found");
  const now = Date.now();
  r.status = approve ? "approved" : "denied";
  r.decidedAt = now;
  r.note = str(note, 300) || undefined;
  if (approve && r.kind === "app") {
    // Apps are allowed permanently; the admin can block them again from the Apps tab.
    r.until = 0;
    const apps = device.config.apps ?? defaultConfig().apps;
    await updateConfig(device, { apps: { allowed: [...apps.allowed, r.host], blocked: apps.blocked.filter((p) => p !== r.host) } });
  } else if (approve) {
    const m = Math.max(0, Math.min(60 * 24 * 30, Math.floor(Number(minutes) || 0)));
    if (m === 0) {
      r.until = 0;
      await updateConfig(device, { customAllow: [...device.config.customAllow, r.host] });
    } else {
      r.until = now + m * 60_000;
      await updateConfig(device, { tempAllow: [...device.config.tempAllow, { host: r.host, until: r.until }] });
    }
  }
  await saveRequest(r);
  return r;
}

// ------------------------------------------------------------------ AI data

export async function getClassification(domain: string): Promise<Classification | null> {
  return kv().get<Classification>(K.classify(domain));
}

export async function saveClassifications(list: Classification[]): Promise<void> {
  for (const c of list) await kv().set(K.classify(c.domain), c);
  const toBlock = list.filter((c) => c.block).map((c) => c.domain);
  const toAllow = list.filter((c) => c.allow && !c.block).map((c) => c.domain);
  if (toBlock.length === 0 && toAllow.length === 0) return;
  const bl = await getAiBlocklist();
  const blocked = new Set([...bl.domains, ...toBlock]);
  const allowed = new Set([...bl.allow, ...toAllow].filter((d) => !blocked.has(d)));
  if (blocked.size !== bl.domains.length || allowed.size !== bl.allow.length) {
    await kv().set(K.aiBlocklist(), { version: bl.version + 1, domains: [...blocked].sort(), allow: [...allowed].sort() });
  }
}

/** The shared AI lists: `domains` blocked for everyone, `allow` used only by allowed-sites-only phones. */
export async function getAiBlocklist(): Promise<{ version: number; domains: string[]; allow: string[] }> {
  const v = await kv().get<{ version: number; domains: string[]; allow?: string[] }>(K.aiBlocklist());
  return { version: v?.version ?? 0, domains: v?.domains ?? [], allow: v?.allow ?? [] };
}

export async function createJob(kind: Job["kind"], payload: Record<string, unknown>): Promise<Job> {
  const job: Job = { id: id("job"), kind, status: "queued", payload, createdAt: Date.now() };
  await kv().set(K.job(job.id), job);
  return job;
}

export async function getJob(jobId: string): Promise<Job | null> {
  return kv().get<Job>(K.job(jobId));
}

export async function finishJob(job: Job, result: unknown, error?: string): Promise<void> {
  job.status = error ? "error" : "done";
  job.result = result;
  job.error = error;
  await kv().set(K.job(job.id), job);
}

export async function getChat(deviceId: string): Promise<ChatMessage[]> {
  return (await kv().get<ChatMessage[]>(K.chat(deviceId))) ?? [];
}

export async function appendChat(deviceId: string, ...msgs: ChatMessage[]): Promise<ChatMessage[]> {
  const list = [...(await getChat(deviceId)), ...msgs].slice(-60);
  await kv().set(K.chat(deviceId), list);
  return list;
}

export async function clearChat(deviceId: string): Promise<void> {
  await kv().del(K.chat(deviceId));
}

export async function getSummary(deviceId: string): Promise<Summary | null> {
  return kv().get<Summary>(K.summary(deviceId));
}

export async function saveSummary(deviceId: string, s: Summary): Promise<void> {
  await kv().set(K.summary(deviceId), s);
}

export async function allActiveDevices(): Promise<Device[]> {
  const keys = await kv().list("devices/");
  const out: Device[] = [];
  for (const k of keys) {
    const d = await kv().get<Device>(k);
    if (d && d.state === "active") out.push(d);
  }
  return out;
}

/**
 * Raises one "phone went silent" alert per device that was checking in and then
 * stopped — the signal that VEIL was uninstalled, turned off, or the phone was
 * powered down. A phone checks in about once a minute, so a gap well past that
 * means it is no longer reporting. The alert is raised once (silentAlerted) and
 * cleared on the next check-in (syncDevice). Returns the number newly alerted.
 */
export async function checkSilentDevices(now = Date.now()): Promise<number> {
  const SILENT_AFTER = 15 * 60_000;   // not heard from in 15 min
  const GIVE_UP_AFTER = 7 * DAY;      // stop alerting about long-gone phones
  let raised = 0;
  for (const d of await allActiveDevices()) {
    const gap = now - d.lastSeen;
    if (d.silentAlerted || gap < SILENT_AFTER || gap > GIVE_UP_AFTER) continue;
    d.alerts.unshift({
      type: "device_silent",
      at: now,
      detail: "This phone stopped checking in. VEIL may have been uninstalled or turned off, or the phone is off or out of coverage.",
    });
    d.alerts = d.alerts.slice(0, MAX_ALERTS);
    d.silentAlerted = true;
    await kv().set(K.device(d.id), d);
    raised++;
  }
  return raised;
}

/** Shared secret between the API and the background worker, created on first use. */
export async function internalSecret(): Promise<string> {
  const existing = await kv().get<string>(K.secret());
  if (existing) return existing;
  const s = token();
  await kv().set(K.secret(), s);
  return s;
}
