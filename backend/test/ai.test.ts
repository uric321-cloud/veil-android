import assert from "node:assert/strict";
import { beforeEach, describe, it } from "node:test";
import { memoryKV, useKV } from "../netlify/lib/store.ts";

(globalThis as any).Netlify = { env: { get: () => undefined }, context: { deploy: { context: "dev" } } };

const ai = await import("../netlify/lib/ai.ts");
const model = await import("../netlify/lib/model.ts");

/** Fake Messages API: answers each call with the next canned JSON and records the request. */
function fakeClient(answers: unknown[], stop = "end_turn") {
  const calls: any[] = [];
  const client = {
    beta: {
      messages: {
        create: async (params: any) => {
          calls.push(params);
          const next = answers.shift();
          return { stop_reason: stop, content: [{ type: "text", text: JSON.stringify(next) }] };
        },
      },
    },
  };
  ai.useAnthropicForTests(client);
  return calls;
}

async function pairedDevice() {
  const admin = await model.createAdmin({ email: "a@example.com", password: "correct horse battery", name: "Ana" }, undefined);
  const { code } = await model.createPairingCode(admin.id, "Phone");
  const { device } = await model.pairDevice({ code });
  return { admin, device };
}

beforeEach(() => useKV(memoryKV()));

describe("AI jobs", () => {
  it("reviews an unblock request with structured output and server-side fallbacks", async () => {
    const { device } = await pairedDevice();
    await model.syncDevice(device, { requests: [{ localId: "r1", host: "cdn.example", reason: "my game won't load" }] });
    const [req] = await model.listRequests(device.id);
    const calls = fakeClient([{ recommendation: "approve", suggestedMinutes: 0, category: "game CDN", risk: "low", explanation: "Asset host for a game." }]);
    const job = await model.createJob("review", { deviceId: device.id, requestId: req.id });
    await ai.runJob(job);

    assert.equal(calls[0].model, "claude-opus-5-5");
    assert.equal(calls[0].fallbacks, "default");
    assert.deepEqual(calls[0].betas, ["server-side-fallback-2026-07-01"]);
    assert.equal(calls[0].output_config.format.type, "json_schema");
    assert.match(calls[0].messages[0].content, /cdn\.example/);
    assert.match(calls[0].messages[0].content, /my game won't load/);
    assert.equal((await model.getRequest(device.id, req.id))!.ai!.recommendation, "approve");
    assert.equal((await model.getJob(job.id))!.status, "done");
  });

  it("reviews an app request with the app prompt and never suggests a time limit", async () => {
    const { device } = await pairedDevice();
    await model.syncDevice(device, { requests: [{ localId: "a1", kind: "app", host: "com.android.chrome", label: "Chrome", reason: "need a browser" }] });
    const [req] = await model.listRequests(device.id);
    const calls = fakeClient([{ recommendation: "approve_limited", suggestedMinutes: 60, category: "Web browser", risk: "high", explanation: "Unfiltered browsing." }]);
    await ai.runJob(await model.createJob("review", { deviceId: device.id, requestId: req.id }));
    assert.match(calls[0].messages[0].content, /package com\.android\.chrome/);
    const r = await model.getRequest(device.id, req.id);
    assert.equal(r!.ai!.recommendation, "approve");
    assert.equal(r!.ai!.suggestedMinutes, 0);
  });

  it("auto-blocks only confident adult/bypass classifications and ignores domains it wasn't asked about", async () => {
    fakeClient([{ results: [
      { domain: "adult.example", category: "adult", confidence: 0.97, reason: "porn site" },
      { domain: "maybe.example", category: "adult", confidence: 0.6, reason: "unsure" },
      { domain: "proxy.example", category: "bypass_proxy_vpn_dns", confidence: 0.9, reason: "web proxy" },
      { domain: "news.example", category: "news", confidence: 0.99, reason: "news" },
      { domain: "injected.example", category: "adult", confidence: 1, reason: "not in the batch" },
    ] }]);
    const job = await model.createJob("classify", { domains: ["adult.example", "maybe.example", "proxy.example", "news.example"] });
    await ai.runJob(job);
    assert.deepEqual((await model.getAiBlocklist()).domains, ["adult.example", "proxy.example"]);
    assert.deepEqual((await model.getAiBlocklist()).allow, []); // news isn't on the safe list
    assert.equal((await model.getClassification("news.example"))!.block, false);
    assert.equal(await model.getClassification("injected.example"), null);
  });

  it("auto-approves only low-risk reviews, and only when the admin turned it on", async () => {
    const { device } = await pairedDevice();
    await model.updateConfig(device, { aiAutoApprove: "low_risk" });
    await model.syncDevice(device, { requests: [
      { localId: "s1", host: "school.example", reason: "homework" },
      { localId: "s2", host: "risky.example", reason: "pls" },
      { localId: "s3", host: "cdn.example", reason: "app broken" },
    ] });
    const reqs = await model.listRequests(device.id);
    const byHost = (h: string) => reqs.find((r) => r.host === h)!;
    fakeClient([
      { recommendation: "approve", suggestedMinutes: 0, category: "school", risk: "low", explanation: "" },
      { recommendation: "approve", suggestedMinutes: 0, category: "forum", risk: "medium", explanation: "" },
      { recommendation: "approve_limited", suggestedMinutes: 30, category: "cdn", risk: "low", explanation: "" },
    ]);
    for (const h of ["school.example", "risky.example", "cdn.example"]) {
      await ai.runJob(await model.createJob("review", { deviceId: device.id, requestId: byHost(h).id }));
    }
    const after = async (h: string) => (await model.getRequest(device.id, byHost(h).id))!;
    assert.equal((await after("school.example")).status, "approved");
    assert.equal((await after("school.example")).autoApproved, true);
    assert.equal((await after("risky.example")).status, "pending");
    const cdn = await after("cdn.example");
    assert.equal(cdn.status, "approved");
    assert.ok(cdn.until! > Date.now() + 25 * 60_000 && cdn.until! < Date.now() + 35 * 60_000);
    const d = (await model.ownedDevice(device.adminId, device.id));
    assert.deepEqual(d.config.customAllow, ["school.example"]);
    assert.deepEqual(d.config.tempAllow.map((t) => t.host), ["cdn.example"]);
  });

  it("leaves requests alone when auto-approval is off", async () => {
    const { device } = await pairedDevice();
    await model.updateConfig(device, { aiAutoApprove: "off" });
    await model.syncDevice(device, { requests: [{ localId: "s1", host: "school.example" }] });
    const [req] = await model.listRequests(device.id);
    fakeClient([{ recommendation: "approve", suggestedMinutes: 0, category: "school", risk: "low", explanation: "" }]);
    await ai.runJob(await model.createJob("review", { deviceId: device.id, requestId: req.id }));
    assert.equal((await model.getRequest(device.id, req.id))!.status, "pending");
  });

  it("ai_decides approves safe, blocks unsafe, and escalates only when unsure", async () => {
    const { device } = await pairedDevice(); // ai_decides is the default
    await model.syncDevice(device, { requests: [
      { localId: "a", host: "school.example", reason: "homework" },
      { localId: "b", host: "casino.example", reason: "fun" },
      { localId: "c", host: "forum.example", reason: "a community I use" },
    ] });
    const reqs = await model.listRequests(device.id);
    const byHost = (h: string) => reqs.find((r) => r.host === h)!;
    fakeClient([
      { recommendation: "approve", suggestedMinutes: 0, category: "school", risk: "low", explanation: "Learning site.", needsHuman: false },
      { recommendation: "deny", suggestedMinutes: 0, category: "gambling", risk: "high", explanation: "Online casino.", needsHuman: false },
      { recommendation: "approve", suggestedMinutes: 0, category: "forum", risk: "medium", explanation: "Mixed content.", needsHuman: true },
    ]);
    for (const h of ["school.example", "casino.example", "forum.example"]) {
      await ai.runJob(await model.createJob("review", { deviceId: device.id, requestId: byHost(h).id }));
    }
    const after = async (h: string) => (await model.getRequest(device.id, byHost(h).id))!;
    const school = await after("school.example");
    assert.equal(school.status, "approved"); assert.equal(school.autoApproved, true);
    const casino = await after("casino.example");
    assert.equal(casino.status, "denied"); assert.equal(casino.autoDenied, true);
    const forum = await after("forum.example");
    assert.equal(forum.status, "pending"); assert.equal(forum.escalated, true);
    assert.deepEqual((await model.ownedDevice(device.adminId, device.id)).config.customAllow, ["school.example"]);
  });

  it("adds confidently safe sites to the shared allow list, never a blocked one", async () => {
    fakeClient([{ results: [
      { domain: "school.example", category: "education", confidence: 0.95, reason: "" },
      { domain: "cdn.example", category: "technology_infrastructure", confidence: 0.9, reason: "" },
      { domain: "unsure.example", category: "education", confidence: 0.5, reason: "" },
      { domain: "video.example", category: "video_streaming", confidence: 0.99, reason: "" },
    ] }]);
    await ai.runJob(await model.createJob("classify", { domains: ["school.example", "cdn.example", "unsure.example", "video.example"] }));
    const lists = await model.getAiBlocklist();
    assert.deepEqual(lists.allow, ["cdn.example", "school.example"]);
    assert.deepEqual(lists.domains, []);
  });

  it("chat keeps a usable settings proposal and drops a malformed one", async () => {
    const { admin, device } = await pairedDevice();
    fakeClient([
      { reply: "Done - here's the change.", hasProposal: true, proposalSummary: "Turn on strict YouTube", proposalPatchJson: "{\"youtubeStrict\":true}" },
      { reply: "Hmm.", hasProposal: true, proposalSummary: "x", proposalPatchJson: "{not json" },
    ]);
    await ai.runJob(await model.createJob("chat", { adminId: admin.id, deviceId: device.id, message: "make youtube stricter" }));
    await ai.runJob(await model.createJob("chat", { adminId: admin.id, deviceId: device.id, message: "again" }));
    const msgs = await model.getChat(device.id);
    assert.deepEqual(msgs[0].proposal, { summary: "Turn on strict YouTube", patch: { youtubeStrict: true } });
    assert.equal(msgs[1].proposal, null);
  });

  it("classifies installed apps: approves safe, asks about unsure, blocks unsafe", async () => {
    const { device } = await pairedDevice(); // defaults to allow-list mode
    await model.syncDevice(device, { apps: [
      { package: "com.android.chrome", label: "Chrome" },
      { package: "com.waze", label: "Waze" },
      { package: "com.some.unknown", label: "Mystery" },
      { package: "com.adult.xxx", label: "XXX" },
    ] });
    const sel = (await model.syncDevice(device, { apps: [
      { package: "com.android.chrome", label: "Chrome" },
      { package: "com.waze", label: "Waze" },
      { package: "com.some.unknown", label: "Mystery" },
      { package: "com.adult.xxx", label: "XXX" },
    ] })).classifyApps.map((a) => a.package);
    assert.ok(sel.includes("com.waze") && sel.includes("com.adult.xxx"));
    fakeClient([{ results: [
      { package: "com.waze", decision: "allow", category: "navigation", reason: "maps" },
      { package: "com.android.chrome", decision: "block", category: "browser", reason: "unfiltered web" },
      { package: "com.some.unknown", decision: "ask", category: "unknown", reason: "not recognised" },
      { package: "com.adult.xxx", decision: "block", category: "adult", reason: "adult content" },
    ] }]);
    const counts = await ai.classifyApps([
      { package: "com.waze", label: "Waze" }, { package: "com.android.chrome", label: "Chrome" },
      { package: "com.some.unknown", label: "Mystery" }, { package: "com.adult.xxx", label: "XXX" },
    ]);
    const res = await model.recordAppClassification(device.id, counts);
    assert.deepEqual(res, { approved: 1, asked: 1, blocked: 2 });
    const d = await model.getDeviceForTest(device.id);
    assert.deepEqual(d!.config.apps.allowed, ["com.waze"]);
    const reqs = await model.listRequests(device.id);
    const ask = reqs.find((r) => r.kind === "app" && r.host === "com.some.unknown");
    assert.ok(ask && ask.status === "pending" && ask.escalated);
    // Already-classified apps are not offered again (use a freshly-loaded device, as the real sync does).
    const fresh = (await model.getDeviceForTest(device.id))!;
    const again = (await model.syncDevice(fresh, { apps: [{ package: "com.adult.xxx", label: "XXX" }] })).classifyApps;
    assert.equal(again.length, 0);
  });

  it("writes a summary from aggregated activity", async () => {
    const { device } = await pairedDevice();
    const now = Date.now();
    await model.syncDevice(device, { events: [
      { type: "block", at: now, category: "adult", count: 4 },
      { type: "block", at: now, category: "keyword", count: 1 },
      { type: "tamper", at: now, rule: "vpn_revoked" },
    ] });
    const calls = fakeClient([{ text: "Quiet day.", highlights: ["1", "2", "3", "4", "5", "6"], concernLevel: "low" }]);
    await ai.runJob(await model.createJob("summary", { deviceId: device.id, periodDays: 1 }));
    const sent = calls[0].messages[0].content as string;
    assert.match(sent, /"totalBlocks":5/);
    assert.match(sent, /vpn_revoked/);
    const s = await model.getSummary(device.id);
    assert.equal(s!.highlights.length, 5);
    assert.equal(s!.concernLevel, "low");
  });

  it("marks the job failed (and tells the admin in chat) when the model refuses", async () => {
    const { admin, device } = await pairedDevice();
    fakeClient([{}], "refusal");
    const job = await model.createJob("chat", { adminId: admin.id, deviceId: device.id, message: "hi" });
    await ai.runJob(job);
    assert.equal((await model.getJob(job.id))!.status, "error");
    assert.match((await model.getChat(device.id)).at(-1)!.text, /couldn't answer/);
  });
});
