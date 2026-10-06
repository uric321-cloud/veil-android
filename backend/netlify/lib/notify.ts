import webpush from "web-push";
import { getAdmin } from "./model.ts";
import { kv } from "./store.ts";

/**
 * Admin alerts when something needs attention (an app or site to approve, a
 * phone gone silent). Two channels, both best-effort:
 *  - Web Push: the admin turns it on once in the dashboard; works with the
 *    dashboard closed. VAPID keys are generated once and kept in storage.
 *  - Email: sent too when RESEND_API_KEY is set (resend.com REST API).
 */

interface Sub { endpoint: string; keys: { p256dh: string; auth: string }; }

function env(name: string): string | undefined {
  const e = (globalThis as { Netlify?: { env: { get(k: string): string | undefined } } }).Netlify?.env;
  return (e?.get(name) ?? process.env[name]) || undefined;
}

const VAPID_KEY = "push/vapid";
const subsKey = (adminId: string) => `push/subs/${adminId}`;

/** The VAPID key pair, generated once and stored so it survives redeploys. */
export async function vapid(): Promise<{ publicKey: string; privateKey: string }> {
  const existing = await kv().get<{ publicKey: string; privateKey: string }>(VAPID_KEY);
  if (existing) return existing;
  const keys = webpush.generateVAPIDKeys();
  await kv().set(VAPID_KEY, keys);
  return keys;
}

export async function vapidPublicKey(): Promise<string> {
  return (await vapid()).publicKey;
}

export async function addSubscription(adminId: string, sub: Sub): Promise<void> {
  if (!sub?.endpoint || !sub?.keys?.p256dh || !sub?.keys?.auth) return;
  const list = (await kv().get<Sub[]>(subsKey(adminId))) ?? [];
  if (list.some((s) => s.endpoint === sub.endpoint)) return;
  list.push({ endpoint: sub.endpoint, keys: { p256dh: sub.keys.p256dh, auth: sub.keys.auth } });
  await kv().set(subsKey(adminId), list.slice(-20));
}

export async function removeSubscription(adminId: string, endpoint: string): Promise<void> {
  const list = (await kv().get<Sub[]>(subsKey(adminId))) ?? [];
  await kv().set(subsKey(adminId), list.filter((s) => s.endpoint !== endpoint));
}

async function sendWebPush(adminId: string, payload: { title: string; body: string; url: string; tag?: string }): Promise<void> {
  const list = (await kv().get<Sub[]>(subsKey(adminId))) ?? [];
  if (list.length === 0) return;
  const keys = await vapid();
  webpush.setVapidDetails(env("VAPID_SUBJECT") ?? "mailto:admin@veil.app", keys.publicKey, keys.privateKey);
  const data = JSON.stringify(payload);
  const alive: Sub[] = [];
  for (const s of list) {
    try {
      await webpush.sendNotification(s as webpush.PushSubscription, data);
      alive.push(s);
    } catch (err) {
      const status = (err as { statusCode?: number }).statusCode;
      if (status === 404 || status === 410) continue; // subscription gone; drop it
      alive.push(s); // transient error: keep it
      console.error(`web push failed (${status ?? "?"})`);
    }
  }
  if (alive.length !== list.length) await kv().set(subsKey(adminId), alive);
}

async function sendEmail(to: string, subject: string, text: string, url: string): Promise<void> {
  const key = env("RESEND_API_KEY");
  if (!key || !to) return;
  const from = env("RESEND_FROM") ?? "VEIL <onboarding@resend.dev>";
  try {
    const res = await fetch("https://api.resend.com/emails", {
      method: "POST",
      headers: { authorization: `Bearer ${key}`, "content-type": "application/json" },
      body: JSON.stringify({ from, to, subject, text: `${text}\n\n${url}` }),
    });
    if (!res.ok) console.error(`email send failed: ${res.status} ${await res.text().catch(() => "")}`);
  } catch (err) {
    console.error(`email send error: ${err}`);
  }
}

/** Fire both channels for one admin. Never throws. */
export async function notifyAdmin(adminId: string, n: { title: string; body: string; path?: string; tag?: string }): Promise<void> {
  try {
    const base = env("URL") ?? "https://veil-admin.netlify.app";
    const url = base.replace(/\/$/, "") + (n.path ?? "/");
    await sendWebPush(adminId, { title: n.title, body: n.body, url, tag: n.tag });
    const admin = await getAdmin(adminId);
    if (admin?.email) await sendEmail(admin.email, n.title, n.body, url);
  } catch (err) {
    console.error(`notifyAdmin failed: ${err}`);
  }
}

export function notificationsConfigured(): { email: boolean } {
  return { email: !!env("RESEND_API_KEY") };
}
