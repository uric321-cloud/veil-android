import Anthropic from "@anthropic-ai/sdk";
import { sanitizeConfig } from "./config.ts";
import {
  appendChat, finishJob, getChat, getClassification, getRequest, getSummary, listEvents, listRequests,
  ownedDevice, saveClassifications, saveRequest, saveSummary, type AiReview, type Classification,
  type Device, type DeviceEvent, type Job, type Summary,
} from "./model.ts";
import { kv } from "./store.ts";

const MODEL = "claude-opus-5-5";

let client: Anthropic | null = null;

function anthropic(): Anthropic {
  if (client) return client;
  const env = (globalThis as { Netlify?: { env: { get(k: string): string | undefined } } }).Netlify?.env;
  const apiKey = env?.get("ANTHROPIC_API_KEY") ?? process.env.ANTHROPIC_API_KEY;
  if (!apiKey) throw new Error("ANTHROPIC_API_KEY is not set on the Netlify site");
  client = new Anthropic({ apiKey });
  return client;
}

/** Tests swap in a fake client so no real API calls are made. */
export function useAnthropicForTests(fake: unknown): void {
  client = fake as Anthropic;
}

export function aiConfigured(): boolean {
  if (client) return true;
  const env = (globalThis as { Netlify?: { env: { get(k: string): string | undefined } } }).Netlify?.env;
  return !!(env?.get("ANTHROPIC_API_KEY") ?? process.env.ANTHROPIC_API_KEY);
}

type Effort = "low" | "medium" | "high";

/** One structured-output call. Fallbacks route a safety decline to another model instead of failing. */
async function ask<T>(system: string, user: string, schema: Record<string, unknown>, effort: Effort, maxTokens = 8000): Promise<T> {
  const response = await anthropic().beta.messages.create({
    model: MODEL,
    max_tokens: maxTokens,
    betas: ["server-side-fallback-2026-07-01"],
    fallbacks: "default",
    output_config: { effort, format: { type: "json_schema", schema } },
    system,
    messages: [{ role: "user", content: user }],
  });
  if (response.stop_reason === "refusal") throw new Error("The model declined this request");
  if (response.stop_reason === "max_tokens") throw new Error("The answer was cut off; try again");
  const text = response.content.map((b) => (b.type === "text" ? b.text : "")).join("");
  return JSON.parse(text) as T;
}

const PRODUCT = `VEIL is an Android content filter for accountability and family safety. A filtering VPN blocks adult sites and \
encrypted-DNS bypasses and enforces SafeSearch; a screen filter covers explicit words on screen. The phone is paired with an \
admin (a parent, partner or accountability helper) who controls VEIL's settings remotely. The admin sees only VEIL's own data: \
what VEIL blocked, tamper alerts and unblock requests. You help the admin make good decisions. Be direct, practical and fair to \
the phone's user: don't moralise, don't speculate about the person beyond the data, and say plainly when the data is too thin \
to conclude anything.`;

// ------------------------------------------------------------------ unblock request review

const REVIEW_SCHEMA = {
  type: "object",
  additionalProperties: false,
  required: ["recommendation", "suggestedMinutes", "category", "risk", "explanation"],
  properties: {
    recommendation: { type: "string", enum: ["approve", "approve_limited", "deny"] },
    suggestedMinutes: { type: "integer", description: "For approve_limited: minutes to allow. 0 for permanent approve or deny." },
    category: { type: "string", description: "What the site is, in a few words." },
    risk: { type: "string", enum: ["low", "medium", "high"] },
    explanation: { type: "string", description: "2-4 sentences for the admin." },
  },
};

export async function reviewAppRequest(device: Device, pkg: string, label: string, reason: string): Promise<AiReview> {
  const user = [
    `The phone's user asked the admin to allow the app "${label}" (Android package ${pkg}).`,
    `Their reason: ${reason || "(none given)"}`,
    `Screen-filter age tier on this phone: ${device.config.screen.tier}`,
    `Apps the admin already allows: ${(device.config.apps?.allowed ?? []).slice(0, 60).join(", ") || "(none listed)"}`,
    "",
    "Say what this app is and recommend approve or deny (approve_limited is not available for apps; use approve or deny). " +
      "Consider: does it contain or link to explicit content, open social feeds or short videos, unfiltered web browsing, " +
      "chat with strangers, or ways around the filter (VPNs, proxies, other browsers, alternative app stores)? " +
      "Utilities, banking, navigation, school and work apps are usually fine. If you don't recognise the package, say so " +
      "and lean to deny with low confidence in the explanation.",
  ].join("\n");
  const r = await ask<AiReview>(PRODUCT, user, REVIEW_SCHEMA, "medium");
  return { ...r, recommendation: r.recommendation === "approve_limited" ? "approve" : r.recommendation, suggestedMinutes: 0 };
}

export async function reviewRequest(device: Device, host: string, reason: string, history: DeviceEvent[]): Promise<AiReview> {
  const blocksForHost = history.filter((e) => e.type === "block" && e.host && (e.host === host || e.host.endsWith(`.${host}`)));
  const known = await getClassification(host);
  const user = [
    `The phone's user asked the admin to unblock: ${host}`,
    `Their reason: ${reason || "(none given)"}`,
    `Why VEIL blocked it: ${blocksForHost[0] ? `${blocksForHost[0].reason} (rule: ${blocksForHost[0].rule})` : "no recent block recorded for this host"}`,
    `Blocked attempts on this host in the last 7 days: ${blocksForHost.reduce((n, e) => n + (e.count ?? 1), 0)}`,
    known ? `Earlier classification: ${known.category} (${known.reason})` : "",
    `Screen-filter age tier on this phone: ${device.config.screen.tier}`,
    "",
    "Recommend approve (permanently allow), approve_limited (allow for a while, give minutes) or deny. Explain what the site is " +
      "and why it was probably blocked. Many blocks are false positives: CDNs, login or API hosts of ordinary services, or " +
      "keyword matches inside harmless names. Adult content, hookup services, proxies, VPNs and encrypted-DNS resolvers that " +
      "would bypass the filter should be denied.",
  ].filter(Boolean).join("\n");
  return ask<AiReview>(PRODUCT, user, REVIEW_SCHEMA, "medium");
}

// ------------------------------------------------------------------ site classification

const CATEGORIES = [
  "adult", "dating_hookup", "bypass_proxy_vpn_dns", "gambling", "violence_gore", "drugs", "social_media",
  "messaging", "video_streaming", "gaming", "news", "shopping", "education", "search", "productivity",
  "technology_infrastructure", "ads_tracking", "finance", "health", "government", "other", "unknown",
] as const;

const CLASSIFY_SCHEMA = {
  type: "object",
  additionalProperties: false,
  required: ["results"],
  properties: {
    results: {
      type: "array",
      items: {
        type: "object",
        additionalProperties: false,
        required: ["domain", "category", "confidence", "reason"],
        properties: {
          domain: { type: "string" },
          category: { type: "string", enum: [...CATEGORIES] },
          confidence: { type: "number", description: "0 to 1" },
          reason: { type: "string", description: "A few words." },
        },
      },
    },
  },
};

/** Only these get added to the shared AI blocklist, and only when the model is confident. */
const AUTO_BLOCK = new Set(["adult", "bypass_proxy_vpn_dns"]);
const AUTO_BLOCK_CONFIDENCE = 0.85;

export async function classifyDomains(domains: string[]): Promise<Classification[]> {
  const out: Classification[] = [];
  for (let i = 0; i < domains.length; i += 50) {
    const batch = domains.slice(i, i + 50);
    const res = await ask<{ results: { domain: string; category: string; confidence: number; reason: string }[] }>(
      "You classify internet domain names for a content filter. Use what you know about each site; when a name is an " +
        "infrastructure host (CDN, API, telemetry, update server) classify it as technology_infrastructure or ads_tracking. " +
        "Use 'unknown' with low confidence rather than guessing. Only use 'adult' for sites whose main purpose is sexual " +
        "content, and 'bypass_proxy_vpn_dns' for web proxies, VPN services and DNS-over-HTTPS/TLS resolvers.",
      `Classify each domain. Return one result per domain, same spelling:\n${batch.join("\n")}`,
      CLASSIFY_SCHEMA,
      "low",
    );
    const now = Date.now();
    for (const r of res.results) {
      if (!batch.includes(r.domain)) continue;
      const confidence = Math.max(0, Math.min(1, Number(r.confidence) || 0));
      out.push({
        domain: r.domain,
        category: r.category,
        confidence,
        reason: String(r.reason ?? "").slice(0, 200),
        block: AUTO_BLOCK.has(r.category) && confidence >= AUTO_BLOCK_CONFIDENCE,
        at: now,
      });
    }
  }
  return out;
}

// ------------------------------------------------------------------ summaries

const SUMMARY_SCHEMA = {
  type: "object",
  additionalProperties: false,
  required: ["text", "highlights", "concernLevel"],
  properties: {
    text: { type: "string", description: "A short plain-language report for the admin, 3-8 sentences." },
    highlights: { type: "array", items: { type: "string" }, description: "Up to 5 one-line bullet points." },
    concernLevel: { type: "string", enum: ["none", "low", "medium", "high"] },
  },
};

export function aggregateEvents(events: DeviceEvent[]) {
  const blocksByHost = new Map<string, { count: number; reason: string }>();
  const blocksByReason = new Map<string, number>();
  const byHour = new Array(24).fill(0) as number[];
  let textCovered = 0;
  const tamper: { at: string; rule: string; detail: string }[] = [];
  for (const e of events) {
    const n = e.count ?? 1;
    if (e.type === "block" && e.host) {
      const cur = blocksByHost.get(e.host) ?? { count: 0, reason: e.reason ?? "" };
      cur.count += n;
      blocksByHost.set(e.host, cur);
      blocksByReason.set(e.reason ?? "other", (blocksByReason.get(e.reason ?? "other") ?? 0) + n);
      byHour[new Date(e.at).getUTCHours()] += n;
    } else if (e.type === "text") {
      textCovered += n;
    } else if (e.type === "tamper") {
      tamper.push({ at: new Date(e.at).toISOString(), rule: e.rule ?? "", detail: e.detail ?? "" });
    }
  }
  return {
    totalBlocks: [...blocksByHost.values()].reduce((a, b) => a + b.count, 0),
    topBlockedHosts: [...blocksByHost].sort((a, b) => b[1].count - a[1].count).slice(0, 25).map(([host, v]) => ({ host, ...v })),
    blocksByReason: Object.fromEntries(blocksByReason),
    blocksByUtcHour: byHour,
    textCovered,
    tamper: tamper.slice(0, 50),
  };
}

export async function summarize(device: Device, periodDays: number): Promise<Summary> {
  const events = await listEvents(device.id, periodDays);
  const requests = await listRequests(device.id, periodDays);
  const data = {
    device: device.name,
    periodDays,
    status: device.status,
    lastSeen: new Date(device.lastSeen).toISOString(),
    activity: aggregateEvents(events),
    unblockRequests: requests.map((r) => ({ host: r.host, reason: r.reason, status: r.status })),
  };
  const s = await ask<Omit<Summary, "periodDays" | "at">>(
    PRODUCT,
    `Write the admin's ${periodDays === 1 ? "daily" : `${periodDays}-day`} report for this phone from VEIL's data. Lead with ` +
      `anything that needs the admin's attention (tamper alerts, protection off, repeated attempts at the same kind of site, ` +
      `late-night patterns). Ordinary background blocks (ads, CDNs, a stray keyword) are not a concern - say so briefly.\n\n` +
      JSON.stringify(data),
    SUMMARY_SCHEMA,
    "medium",
  );
  return { ...s, highlights: s.highlights.slice(0, 5), periodDays, at: Date.now() };
}

// ------------------------------------------------------------------ assistant chat

const CHAT_SCHEMA = {
  type: "object",
  additionalProperties: false,
  required: ["reply", "hasProposal", "proposalSummary", "proposalPatchJson"],
  properties: {
    reply: { type: "string", description: "Answer to the admin, plain text, concise." },
    hasProposal: { type: "boolean", description: "True only if the admin asked for a settings change or one is clearly warranted." },
    proposalSummary: { type: "string", description: "One sentence describing the proposed change, or empty." },
    proposalPatchJson: {
      type: "string",
      description: "A JSON object with only the settings keys to change, using the exact shape of the current config (full lists, not diffs). Empty string when hasProposal is false.",
    },
  },
};

export async function chat(device: Device, message: string): Promise<{ reply: string; proposal: { summary: string; patch: Record<string, unknown> } | null }> {
  const [history, events, requests, summary] = await Promise.all([
    getChat(device.id), listEvents(device.id, 7), listRequests(device.id, 14), getSummary(device.id),
  ]);
  const context = {
    device: { name: device.name, lastSeen: new Date(device.lastSeen).toISOString(), status: device.status, alerts: device.alerts.slice(0, 20) },
    currentConfig: device.config,
    last7Days: aggregateEvents(events),
    unblockRequests: requests.slice(0, 30).map((r) => ({ host: r.host, reason: r.reason, status: r.status, ai: r.ai?.recommendation })),
    latestReport: summary?.text ?? null,
  };
  const transcript = history.slice(-20).map((m) => `${m.role === "admin" ? "Admin" : "You"}: ${m.text}`).join("\n");
  const res = await ask<{ reply: string; hasProposal: boolean; proposalSummary: string; proposalPatchJson: string }>(
    `${PRODUCT}\n\nYou are the assistant on the admin's dashboard for one phone. You can explain what VEIL did and why, and you ` +
      `can propose settings changes, which the admin applies with a button - you never change anything yourself. Config notes: ` +
      `customBlock/customAllow are domain lists; tempAllow entries are {host, until epoch ms}; screen.tier is one of young_child, ` +
      `child, teen, adult, custom; lockdown holds Device Owner restrictions.`,
    `Phone data (JSON):\n${JSON.stringify(context)}\n\nConversation so far:\n${transcript || "(none)"}\n\nAdmin: ${message}`,
    CHAT_SCHEMA,
    "medium",
  );
  let proposal: { summary: string; patch: Record<string, unknown> } | null = null;
  if (res.hasProposal && res.proposalPatchJson.trim()) {
    try {
      const patch = JSON.parse(res.proposalPatchJson) as Record<string, unknown>;
      sanitizeConfig(patch, device.config); // must be a usable patch; the real apply re-sanitizes
      proposal = { summary: res.proposalSummary, patch };
    } catch {
      proposal = null;
    }
  }
  return { reply: res.reply, proposal };
}

// ------------------------------------------------------------------ job runner (background function)

export async function runJob(job: Job): Promise<void> {
  try {
    switch (job.kind) {
      case "review": {
        const { deviceId, requestId } = job.payload as { deviceId: string; requestId: string };
        const r = await getRequest(deviceId, requestId);
        const device = await kv().get<Device>(`devices/${deviceId}`);
        if (!r || !device) throw new Error("Request or device missing");
        r.ai = r.kind === "app"
          ? await reviewAppRequest(device, r.host, r.label ?? r.host, r.reason)
          : await reviewRequest(device, r.host, r.reason, await listEvents(deviceId, 7));
        await saveRequest(r);
        await finishJob(job, r.ai);
        break;
      }
      case "classify": {
        const domains = (job.payload.domains as string[]).slice(0, 200);
        const results = await classifyDomains(domains);
        await saveClassifications(results);
        await finishJob(job, { classified: results.length, blocked: results.filter((c) => c.block).map((c) => c.domain) });
        break;
      }
      case "summary": {
        const { deviceId, periodDays } = job.payload as { deviceId: string; periodDays: number };
        const device = await kv().get<Device>(`devices/${deviceId}`);
        if (!device) throw new Error("Device missing");
        const s = await summarize(device, periodDays || 1);
        await saveSummary(deviceId, s);
        await finishJob(job, s);
        break;
      }
      case "chat": {
        const { adminId, deviceId, message } = job.payload as { adminId: string; deviceId: string; message: string };
        const device = await ownedDevice(adminId, deviceId);
        const res = await chat(device, message);
        await appendChat(deviceId, { role: "assistant", text: res.reply, at: Date.now(), proposal: res.proposal });
        await finishJob(job, res);
        break;
      }
    }
  } catch (err) {
    const message = err instanceof Anthropic.APIError ? `Claude API error ${err.status}: ${err.message}` : String((err as Error)?.message ?? err);
    console.error(`job ${job.id} (${job.kind}) failed: ${message}`);
    if (job.kind === "chat") {
      const { deviceId } = job.payload as { deviceId: string };
      await appendChat(deviceId, { role: "assistant", text: `Sorry, I couldn't answer that (${message}).`, at: Date.now(), proposal: null });
    }
    await finishJob(job, null, message);
  }
}
