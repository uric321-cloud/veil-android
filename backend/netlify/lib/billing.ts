import { createHmac, timingSafeEqual } from "node:crypto";

/**
 * Subscription billing via Stripe, scaffolded and fully optional: with no
 * STRIPE_SECRET_KEY set, every function here no-ops and the whole product keeps
 * working. Uses the Stripe REST API over fetch (no SDK dependency). Price IDs
 * come from env so no real IDs are baked in.
 *
 * To turn it on, set on the Netlify site:
 *   STRIPE_SECRET_KEY        - your Stripe secret key (sk_live_... / sk_test_...)
 *   STRIPE_PRICE_MONTHLY     - the price id for the monthly family plan
 *   STRIPE_PRICE_YEARLY      - the price id for the yearly family plan
 *   STRIPE_WEBHOOK_SECRET    - the signing secret of the webhook you point at
 *                              /api/billing/webhook
 */

function env(name: string): string | undefined {
  const e = (globalThis as { Netlify?: { env: { get(k: string): string | undefined } } }).Netlify?.env;
  return (e?.get(name) ?? process.env[name]) || undefined;
}

export type SubStatus = "none" | "active" | "trialing" | "past_due" | "canceled";

export interface Subscription {
  status: SubStatus;
  plan?: "monthly" | "yearly";
  customerId?: string;
  currentPeriodEnd?: number;
}

export function billingConfigured(): boolean {
  return !!env("STRIPE_SECRET_KEY");
}

/** The plans the dashboard can offer (only those whose price id is configured). */
export function availablePlans(): { plan: "monthly" | "yearly"; price: string }[] {
  const out: { plan: "monthly" | "yearly"; price: string }[] = [];
  const m = env("STRIPE_PRICE_MONTHLY");
  const y = env("STRIPE_PRICE_YEARLY");
  if (m) out.push({ plan: "monthly", price: m });
  if (y) out.push({ plan: "yearly", price: y });
  return out;
}

async function stripe(path: string, params: Record<string, string>): Promise<any> {
  const key = env("STRIPE_SECRET_KEY");
  if (!key) throw new Error("Billing is not configured");
  const res = await fetch(`https://api.stripe.com/v1/${path}`, {
    method: "POST",
    headers: { authorization: `Bearer ${key}`, "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(params).toString(),
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(json?.error?.message ?? `Stripe error ${res.status}`);
  return json;
}

/**
 * Creates a Stripe Checkout session for a plan and returns its URL. The admin id
 * rides along as client_reference_id so the webhook can match the subscription
 * back to the account.
 */
export async function createCheckoutSession(adminId: string, plan: "monthly" | "yearly", origin: string): Promise<string> {
  const price = plan === "yearly" ? env("STRIPE_PRICE_YEARLY") : env("STRIPE_PRICE_MONTHLY");
  if (!price) throw new Error(`No price configured for the ${plan} plan`);
  const session = await stripe("checkout/sessions", {
    mode: "subscription",
    "line_items[0][price]": price,
    "line_items[0][quantity]": "1",
    client_reference_id: adminId,
    "metadata[adminId]": adminId,
    "metadata[plan]": plan,
    // Stamp the subscription too, so later subscription.* events carry the admin id.
    "subscription_data[metadata][adminId]": adminId,
    "subscription_data[metadata][plan]": plan,
    success_url: `${origin}/#/billing?ok=1`,
    cancel_url: `${origin}/#/billing?canceled=1`,
  });
  return session.url as string;
}

/**
 * Turns a parsed Stripe event into the admin id + subscription to apply, or null
 * to ignore. Pure (no storage), so it is unit-testable.
 */
export function subscriptionFromEvent(event: { type?: string; data?: { object?: any } }): { adminId: string; sub: Subscription } | null {
  const obj = event?.data?.object;
  if (!obj) return null;
  const type = event.type ?? "";
  if (type === "checkout.session.completed") {
    const adminId = obj.client_reference_id || obj.metadata?.adminId;
    if (!adminId) return null;
    return { adminId, sub: { status: "active", plan: obj.metadata?.plan, customerId: obj.customer } };
  }
  if (type === "customer.subscription.updated" || type === "customer.subscription.created") {
    const adminId = obj.metadata?.adminId;
    if (!adminId) return null;
    return { adminId, sub: { status: mapStatus(obj.status), plan: obj.metadata?.plan, customerId: obj.customer, currentPeriodEnd: obj.current_period_end ? obj.current_period_end * 1000 : undefined } };
  }
  if (type === "customer.subscription.deleted") {
    const adminId = obj.metadata?.adminId;
    if (!adminId) return null;
    return { adminId, sub: { status: "canceled", plan: obj.metadata?.plan, customerId: obj.customer } };
  }
  return null;
}

/** Verifies a Stripe webhook signature (t=…,v1=…) against STRIPE_WEBHOOK_SECRET. */
export function verifyWebhook(rawBody: string, sigHeader: string | null): boolean {
  const secret = env("STRIPE_WEBHOOK_SECRET");
  if (!secret || !sigHeader) return false;
  const parts = Object.fromEntries(sigHeader.split(",").map((p) => p.split("=") as [string, string]));
  const t = parts["t"];
  const v1 = parts["v1"];
  if (!t || !v1) return false;
  const expected = createHmac("sha256", secret).update(`${t}.${rawBody}`).digest("hex");
  try {
    return timingSafeEqual(Buffer.from(expected), Buffer.from(v1));
  } catch {
    return false;
  }
}

/** Maps a Stripe subscription status to ours. */
export function mapStatus(s: string): SubStatus {
  if (s === "active") return "active";
  if (s === "trialing") return "trialing";
  if (s === "past_due" || s === "unpaid") return "past_due";
  if (s === "canceled" || s === "incomplete_expired") return "canceled";
  return "none";
}
