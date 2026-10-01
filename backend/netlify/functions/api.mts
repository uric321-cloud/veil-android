import type { Config, Context } from "@netlify/functions";
import { aggregateEvents, aiConfigured } from "../lib/ai.ts";
import { HttpError, bearer, cookie, json, readJson, str } from "../lib/http.ts";
import {
  adminCount, appendChat, authDevice, clearAlerts, clearChat, createAdmin, createJob, createPairingCode, decideRequest,
  getAiBlocklist, getChat, getJob, getSummary, internalSecret, listDevices, listEvents, listRequests, login, logout,
  ownedDevice, pairDevice, publicAdmin, publicDevice, queueCommand, renameDevice, sessionAdmin, syncDevice,
  takeRecoveryCode, updateConfig, type Admin, type Job,
} from "../lib/model.ts";

const SESSION_COOKIE = "veil_session";

function env(name: string): string | undefined {
  return Netlify.env.get(name) || undefined;
}

/** Starts an AI job on the background worker. Skipped quietly when no API key is configured. */
async function kick(req: Request, job: Job): Promise<void> {
  if (!aiConfigured()) return;
  try {
    await fetch(new URL("/.netlify/functions/ai-background", req.url), {
      method: "POST",
      headers: { "content-type": "application/json", "x-veil-internal": await internalSecret() },
      body: JSON.stringify({ jobId: job.id }),
    });
  } catch (err) {
    console.error(`could not start job ${job.id}: ${err}`);
  }
}

function sessionCookie(value: string, maxAge: number): string {
  return `${SESSION_COOKIE}=${encodeURIComponent(value)}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=${maxAge}`;
}

async function requireAdmin(req: Request): Promise<Admin> {
  // State-changing admin calls must carry this header: a cross-site form can't
  // add it, and a cross-site fetch with it would need CORS, which we never grant.
  if (req.method !== "GET" && req.headers.get("x-veil") !== "1") throw new HttpError(403, "Missing request header");
  const admin = await sessionAdmin(cookie(req, SESSION_COOKIE));
  if (!admin) throw new HttpError(401, "Please sign in");
  return admin;
}

function provisioning() {
  return {
    apkUrl: env("VEIL_APK_URL") ?? "https://github.com/uric321-cloud/veil-android/releases/latest/download/VEIL.apk",
    // SHA-256 of the signing certificate in keystore/veil-test.jks (base64url).
    signatureChecksum: env("VEIL_SIGNATURE_CHECKSUM") ?? "ziTwy4yi4d7sU3b4W9g1rEwFV4_hA9qPXjs_O2AGnew",
  };
}

type Handler = (req: Request, params: string[], ctx: Context) => Promise<Response>;

const routes: [string, RegExp, Handler][] = [
  // ---------------------------------------------------------------- phone
  ["POST", /^\/api\/device\/pair$/, async (req) => {
    const body = await readJson<{ code: string; deviceName?: string; appVersion?: string; status?: object }>(req);
    const { device, token, adminName } = await pairDevice(body);
    return json({
      deviceId: device.id, token, adminName, recoveryHash: device.recoveryHash,
      configVersion: device.configVersion, config: device.config, pollSeconds: 60,
    });
  }],
  ["POST", /^\/api\/device\/sync$/, async (req) => {
    const device = await authDevice(bearer(req));
    const result = await syncDevice(device, await readJson(req));
    for (const rid of result.reviewRequestIds) await kick(req, await createJob("review", { deviceId: device.id, requestId: rid }));
    if (result.classifyDomains.length) await kick(req, await createJob("classify", { domains: result.classifyDomains }));
    return json(result.response);
  }],
  ["GET", /^\/api\/device\/ai-blocklist$/, async (req) => {
    await authDevice(bearer(req));
    return json(await getAiBlocklist());
  }],

  // ---------------------------------------------------------------- admin session
  ["GET", /^\/api\/session$/, async (req) => {
    const admin = await sessionAdmin(cookie(req, SESSION_COOKIE));
    return json({
      admin: admin ? publicAdmin(admin) : null,
      firstRun: (await adminCount()) === 0,
      signupOpen: !!env("ADMIN_SIGNUP_CODE"),
      aiConfigured: aiConfigured(),
    });
  }],
  ["POST", /^\/api\/signup$/, async (req) => {
    if (req.headers.get("x-veil") !== "1") throw new HttpError(403, "Missing request header");
    const body = await readJson<{ email: string; password: string; name: string; signupCode?: string }>(req);
    await createAdmin(body, env("ADMIN_SIGNUP_CODE"));
    const { admin, token } = await login(body.email, body.password);
    return json({ admin: publicAdmin(admin) }, 200, { "set-cookie": sessionCookie(token, 30 * 86400) });
  }],
  ["POST", /^\/api\/login$/, async (req) => {
    if (req.headers.get("x-veil") !== "1") throw new HttpError(403, "Missing request header");
    const body = await readJson<{ email: string; password: string }>(req);
    const { admin, token } = await login(body.email, body.password);
    return json({ admin: publicAdmin(admin) }, 200, { "set-cookie": sessionCookie(token, 30 * 86400) });
  }],
  ["POST", /^\/api\/logout$/, async (req) => {
    await logout(cookie(req, SESSION_COOKIE));
    return json({ ok: true }, 200, { "set-cookie": sessionCookie("", 0) });
  }],

  // ---------------------------------------------------------------- admin: devices
  ["GET", /^\/api\/devices$/, async (req) => {
    const admin = await requireAdmin(req);
    const devices = await listDevices(admin.id);
    const out = [];
    for (const d of devices) {
      const pending = (await listRequests(d.id, 30)).filter((r) => r.status === "pending").length;
      out.push({ ...publicDevice(d), pendingRequests: pending });
    }
    return json({ devices: out, aiConfigured: aiConfigured() });
  }],
  ["POST", /^\/api\/pairing-codes$/, async (req) => {
    const admin = await requireAdmin(req);
    const body = await readJson<{ deviceName?: string }>(req);
    const code = await createPairingCode(admin.id, str(body.deviceName, 60));
    return json({ ...code, server: new URL(req.url).origin, provisioning: provisioning() });
  }],
  ["GET", /^\/api\/devices\/([\w-]+)$/, async (req, [id]) => {
    const admin = await requireAdmin(req);
    const d = await ownedDevice(admin.id, id);
    return json({
      device: publicDevice(d),
      requests: await listRequests(d.id, 30),
      summary: await getSummary(d.id),
      recoveryCode: await takeRecoveryCode(d.id),
      aiConfigured: aiConfigured(),
    });
  }],
  ["PATCH", /^\/api\/devices\/([\w-]+)$/, async (req, [id]) => {
    const admin = await requireAdmin(req);
    const body = await readJson<{ name?: string }>(req);
    return json({ device: publicDevice(await renameDevice(await ownedDevice(admin.id, id), str(body.name, 60))) });
  }],
  ["PATCH", /^\/api\/devices\/([\w-]+)\/config$/, async (req, [id]) => {
    const admin = await requireAdmin(req);
    const d = await updateConfig(await ownedDevice(admin.id, id), await readJson(req));
    return json({ device: publicDevice(d) });
  }],
  ["POST", /^\/api\/devices\/([\w-]+)\/commands$/, async (req, [id]) => {
    const admin = await requireAdmin(req);
    const body = await readJson<{ type: "refresh_lists" | "release" | "sync_now" }>(req);
    return json({ command: await queueCommand(await ownedDevice(admin.id, id), body.type) });
  }],
  ["POST", /^\/api\/devices\/([\w-]+)\/alerts\/clear$/, async (req, [id]) => {
    const admin = await requireAdmin(req);
    await clearAlerts(await ownedDevice(admin.id, id));
    return json({ ok: true });
  }],
  ["GET", /^\/api\/devices\/([\w-]+)\/activity$/, async (req, [id]) => {
    const admin = await requireAdmin(req);
    const d = await ownedDevice(admin.id, id);
    const days = Math.max(1, Math.min(30, Number(new URL(req.url).searchParams.get("days")) || 7));
    const events = await listEvents(d.id, days);
    return json({ events: events.slice(0, 1000), stats: aggregateEvents(events), days });
  }],
  ["POST", /^\/api\/devices\/([\w-]+)\/requests\/([\w-]+)$/, async (req, [id, rid]) => {
    const admin = await requireAdmin(req);
    const body = await readJson<{ approve: boolean; minutes?: number; note?: string }>(req);
    const r = await decideRequest(await ownedDevice(admin.id, id), rid, body.approve === true, Number(body.minutes ?? 0), str(body.note, 300));
    return json({ request: r });
  }],
  ["POST", /^\/api\/devices\/([\w-]+)\/requests\/([\w-]+)\/review$/, async (req, [id, rid]) => {
    const admin = await requireAdmin(req);
    const d = await ownedDevice(admin.id, id);
    if (!aiConfigured()) throw new HttpError(503, "AI is not configured: set ANTHROPIC_API_KEY on the Netlify site");
    const job = await createJob("review", { deviceId: d.id, requestId: rid });
    await kick(req, job);
    return json({ job: { id: job.id, status: job.status } });
  }],
  ["POST", /^\/api\/devices\/([\w-]+)\/summary$/, async (req, [id]) => {
    const admin = await requireAdmin(req);
    const d = await ownedDevice(admin.id, id);
    if (!aiConfigured()) throw new HttpError(503, "AI is not configured: set ANTHROPIC_API_KEY on the Netlify site");
    const body = await readJson<{ periodDays?: number }>(req);
    const periodDays = [1, 7, 30].includes(Number(body.periodDays)) ? Number(body.periodDays) : 7;
    const job = await createJob("summary", { deviceId: d.id, periodDays });
    await kick(req, job);
    return json({ job: { id: job.id, status: job.status } });
  }],
  ["GET", /^\/api\/devices\/([\w-]+)\/chat$/, async (req, [id]) => {
    const admin = await requireAdmin(req);
    const d = await ownedDevice(admin.id, id);
    return json({ messages: await getChat(d.id) });
  }],
  ["POST", /^\/api\/devices\/([\w-]+)\/chat$/, async (req, [id]) => {
    const admin = await requireAdmin(req);
    const d = await ownedDevice(admin.id, id);
    if (!aiConfigured()) throw new HttpError(503, "AI is not configured: set ANTHROPIC_API_KEY on the Netlify site");
    const message = str((await readJson<{ message: string }>(req)).message, 2000);
    if (!message) throw new HttpError(400, "Type a question first");
    await appendChat(d.id, { role: "admin", text: message, at: Date.now() });
    const job = await createJob("chat", { adminId: admin.id, deviceId: d.id, message });
    await kick(req, job);
    return json({ job: { id: job.id, status: job.status } });
  }],
  ["DELETE", /^\/api\/devices\/([\w-]+)\/chat$/, async (req, [id]) => {
    const admin = await requireAdmin(req);
    await clearChat((await ownedDevice(admin.id, id)).id);
    return json({ ok: true });
  }],
  ["GET", /^\/api\/jobs\/([\w-]+)$/, async (req, [id]) => {
    await requireAdmin(req);
    const job = await getJob(id);
    if (!job) throw new HttpError(404, "Job not found");
    return json({ job: { id: job.id, kind: job.kind, status: job.status, result: job.result, error: job.error } });
  }],
];

export default async (req: Request, ctx: Context): Promise<Response> => {
  const path = new URL(req.url).pathname.replace(/\/+$/, "");
  try {
    for (const [method, pattern, handler] of routes) {
      const m = pattern.exec(path);
      if (m && method === req.method) return await handler(req, m.slice(1), ctx);
    }
    throw new HttpError(404, "Not found");
  } catch (err) {
    if (err instanceof HttpError) return json({ error: err.message }, err.status);
    console.error(err);
    return json({ error: "Server error" }, 500);
  }
};

export const config: Config = {
  path: "/api/*",
};
