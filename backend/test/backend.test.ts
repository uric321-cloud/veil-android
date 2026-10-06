import assert from "node:assert/strict";
import { beforeEach, describe, it } from "node:test";
import { defaultConfig, normalizeHost, sanitizeConfig } from "../netlify/lib/config.ts";
import { CATALOG, effectiveRules } from "../netlify/lib/inapp.ts";
import { memoryKV, useKV } from "../netlify/lib/store.ts";

const envVars: Record<string, string> = {};
(globalThis as any).Netlify = { env: { get: (k: string) => envVars[k] }, context: { deploy: { context: "dev" } } };

const { default: api } = await import("../netlify/functions/api.mts");
const model = await import("../netlify/lib/model.ts");

const BASE = "https://veil.test";

function call(method: string, path: string, opts: { body?: unknown; cookie?: string; token?: string; csrf?: boolean } = {}) {
  const headers: Record<string, string> = { "content-type": "application/json" };
  if (opts.csrf !== false) headers["x-veil"] = "1";
  if (opts.cookie) headers.cookie = opts.cookie;
  if (opts.token) headers.authorization = `Bearer ${opts.token}`;
  return api(new Request(BASE + path, { method, headers, body: opts.body === undefined ? undefined : JSON.stringify(opts.body) }), {} as any);
}

async function body(res: Response) {
  return (await res.json()) as any;
}

function sessionFrom(res: Response): string {
  const c = res.headers.get("set-cookie") ?? "";
  return c.split(";")[0];
}

async function signupAndPair() {
  const s = await call("POST", "/api/signup", { body: { email: "a@example.com", password: "correct horse battery", name: "Ana" } });
  assert.equal(s.status, 200);
  const cookie = sessionFrom(s);
  const pc = await body(await call("POST", "/api/pairing-codes", { cookie, body: { deviceName: "Sam's phone" } }));
  const pr = await call("POST", "/api/device/pair", { body: { code: pc.code.slice(0, 4) + "-" + pc.code.slice(4).toLowerCase(), appVersion: "0.2.0" }, csrf: false });
  assert.equal(pr.status, 200);
  const pair = await body(pr);
  return { cookie, pair, code: pc.code };
}

beforeEach(() => {
  useKV(memoryKV());
  for (const k of Object.keys(envVars)) delete envVars[k];
});

describe("config", () => {
  it("normalizes hosts like the phone does", () => {
    assert.equal(normalizeHost("https://www.Example.com/path?q=1"), "example.com");
    assert.equal(normalizeHost("*.sub.example.co.uk:443"), "sub.example.co.uk");
    assert.equal(normalizeHost("localhost"), null);
    assert.equal(normalizeHost("bad host.com"), null);
  });

  it("new (managed) devices default to allow-list apps and high image strictness", () => {
    const c = defaultConfig();
    assert.equal(c.apps.mode, "allowlist");
    assert.equal(c.apps.approveNewApps, true);
    assert.equal(c.screen.imageStrictness, "high");
    // but an existing phone with no apps policy stays "off", not suddenly locked down
    const legacy = defaultConfig() as any;
    delete legacy.apps;
    assert.equal(sanitizeConfig({ youtubeStrict: true }, legacy).apps.mode, "off");
  });

  it("drops malformed values and unknown keys, and never lets uninstall blocking be turned off", () => {
    const c = sanitizeConfig({
      customBlock: ["Example.com", "not a host", "https://bad.example/x"],
      screen: { tier: "nonsense", blockWords: ["ok", "two words", "x"] },
      lockdown: { blockUninstall: false, vpnLockdown: true },
      evil: true,
    }, defaultConfig());
    assert.deepEqual(c.customBlock, ["bad.example", "example.com"]);
    assert.equal(c.screen.tier, "child");
    assert.deepEqual(c.screen.blockWords, ["ok"]);
    assert.equal(c.lockdown.blockUninstall, true);
    assert.equal(c.lockdown.vpnLockdown, true);
    assert.equal((c as any).evil, undefined);
    assert.ok(c.screen.safeListApps.includes("app.veil.android"));
  });

  it("keeps an app off both lists, never blocks VEIL, and defaults old configs", () => {
    const c = sanitizeConfig({ apps: { mode: "allowlist", allowed: ["com.waze", "bad pkg"], blocked: ["com.waze", "app.veil.android", "com.tiktok"] } });
    assert.equal(c.apps.mode, "allowlist");
    assert.deepEqual(c.apps.allowed, ["com.waze"]);
    assert.deepEqual(c.apps.blocked, ["com.tiktok"]);
    const legacy = defaultConfig() as any;
    delete legacy.apps;
    assert.equal(sanitizeConfig({ youtubeStrict: true }, legacy).apps.mode, "off");
  });

  it("accepts only known in-app features and well-formed custom rules", () => {
    const c = sanitizeConfig({ inApp: {
      enabled: ["whatsapp_updates", "made_up", "whatsapp_updates"],
      custom: [
        { app: "com.example.app", match: { viewId: "feed_list" }, action: "leave" },
        { app: "com.example.app", match: { text: "ab" }, action: "cover" },      // matcher too short
        { app: "not a package", match: { text: "Explore" } },
        { app: "com.example.app", match: { desc: "Stories", junk: 1 }, action: "explode" },
      ],
    } });
    assert.deepEqual(c.inApp.enabled, ["whatsapp_updates"]);
    assert.deepEqual(c.inApp.custom, [
      { app: "com.example.app", match: { viewId: "feed_list" }, action: "leave" },
      { app: "com.example.app", match: { desc: "Stories" }, action: "cover" },
    ]);
  });

  it("every catalog feature has well-formed rules", () => {
    for (const f of CATALOG) {
      assert.ok(f.rules.length > 0, f.id);
      for (const r of f.rules) assert.ok(r.match.viewId || r.match.text || r.match.desc, f.id);
    }
  });

  it("defaults the web mode and auto-approval safely, including for old configs", () => {
    const legacy = defaultConfig() as any;
    for (const k of ["webMode", "aiAutoApprove", "aiAutoAllowSafe", "level"]) delete legacy[k];
    const c = sanitizeConfig({ webMode: "everything", aiAutoApprove: "always" }, legacy);
    assert.equal(c.webMode, "filter");
    assert.equal(c.aiAutoApprove, "off");
    assert.equal(c.aiAutoAllowSafe, true);
    assert.equal(sanitizeConfig({ webMode: "allowlist", level: "allowlist" }).webMode, "allowlist");
  });

  it("drops expired temporary allows", () => {
    const now = Date.now();
    const c = sanitizeConfig({ tempAllow: [{ host: "a.com", until: now - 1 }, { host: "b.com", until: now + 60_000 }] });
    assert.deepEqual(c.tempAllow.map((t) => t.host), ["b.com"]);
  });
});

describe("admin accounts", () => {
  it("lets the first admin sign up, then requires the sign-up code", async () => {
    assert.equal((await call("POST", "/api/signup", { body: { email: "a@example.com", password: "correct horse battery" } })).status, 200);
    const closed = await call("POST", "/api/signup", { body: { email: "b@example.com", password: "correct horse battery" } });
    assert.equal(closed.status, 403);
    envVars.ADMIN_SIGNUP_CODE = "letmein";
    assert.equal((await call("POST", "/api/signup", { body: { email: "b@example.com", password: "correct horse battery", signupCode: "nope" } })).status, 403);
    assert.equal((await call("POST", "/api/signup", { body: { email: "b@example.com", password: "correct horse battery", signupCode: "letmein" } })).status, 200);
  });

  it("rejects short passwords and wrong logins, and locks out after repeated failures", async () => {
    assert.equal((await call("POST", "/api/signup", { body: { email: "a@example.com", password: "short" } })).status, 400);
    await call("POST", "/api/signup", { body: { email: "a@example.com", password: "correct horse battery" } });
    for (let i = 0; i < 10; i++) assert.equal((await call("POST", "/api/login", { body: { email: "a@example.com", password: "wrong password!" } })).status, 401);
    assert.equal((await call("POST", "/api/login", { body: { email: "a@example.com", password: "correct horse battery" } })).status, 429);
  });

  it("requires the CSRF header and a session on admin calls", async () => {
    const s = await call("POST", "/api/signup", { body: { email: "a@example.com", password: "correct horse battery" } });
    const cookie = sessionFrom(s);
    assert.equal((await call("POST", "/api/pairing-codes", { cookie, body: {}, csrf: false })).status, 403);
    assert.equal((await call("GET", "/api/devices")).status, 401);
    assert.equal((await call("GET", "/api/devices", { cookie })).status, 200);
    await call("POST", "/api/logout", { cookie });
    assert.equal((await call("GET", "/api/devices", { cookie })).status, 401);
  });
});

describe("pairing and sync", () => {
  it("pairs with a single-use code and returns the default config", async () => {
    const { pair, code } = await signupAndPair();
    assert.match(pair.token, /^[\w-]{40,}$/);
    assert.equal(pair.adminName, "Ana");
    assert.equal(pair.config.adultList, true);
    assert.equal((await call("POST", "/api/device/pair", { body: { code }, csrf: false })).status, 404);
  });

  it("shows the recovery code to the admin exactly once", async () => {
    const { cookie, pair } = await signupAndPair();
    const first = await body(await call("GET", `/api/devices/${pair.deviceId}`, { cookie }));
    assert.match(first.recoveryCode, /^\d{8}$/);
    const second = await body(await call("GET", `/api/devices/${pair.deviceId}`, { cookie }));
    assert.equal(second.recoveryCode, null);
    assert.equal(first.device.tokenHash, undefined);
    assert.equal(first.device.recoveryHash, undefined);
  });

  it("records activity, raises tamper alerts and creates unblock requests", async () => {
    const { cookie, pair } = await signupAndPair();
    const now = Date.now();
    const res = await body(await call("POST", "/api/device/sync", {
      token: pair.token, csrf: false,
      body: {
        appliedConfigVersion: 1,
        status: { protection: true, vpnRunning: true, deviceOwner: true, blockedToday: 3 },
        events: [
          { type: "block", at: now - 1000, host: "bad.example", reason: "Adult content list", rule: "bad.example", count: 3 },
          { type: "tamper", at: now, rule: "accessibility_off" },
        ],
        requests: [{ localId: "r1", host: "school-portal.example", reason: "homework" }],
        unknownDomains: ["news.example", "NEWS.example", "not a domain"],
      },
    }));
    assert.equal(res.config, undefined); // already on version 1
    assert.equal(res.adminName, "Ana");

    const detail = await body(await call("GET", `/api/devices/${pair.deviceId}`, { cookie }));
    assert.equal(detail.device.status.deviceOwner, true);
    assert.equal(detail.device.alerts[0].type, "accessibility_off");
    assert.equal(detail.requests.length, 1);
    assert.equal(detail.requests[0].status, "pending");

    const activity = await body(await call("GET", `/api/devices/${pair.deviceId}/activity?days=1`, { cookie }));
    assert.equal(activity.stats.totalBlocks, 3);
    assert.equal(activity.stats.topBlockedHosts[0].host, "bad.example");

    // A retried sync with the same request id does not duplicate it.
    await call("POST", "/api/device/sync", { token: pair.token, csrf: false, body: { requests: [{ localId: "r1", host: "school-portal.example" }] } });
    assert.equal((await model.listRequests(pair.deviceId)).length, 1);
  });

  it("delivers config changes and approved requests on the next sync", async () => {
    const { cookie, pair } = await signupAndPair();
    await call("POST", "/api/device/sync", { token: pair.token, csrf: false, body: { appliedConfigVersion: 1, requests: [{ localId: "r1", host: "school-portal.example" }] } });
    const [req] = await model.listRequests(pair.deviceId);

    const patched = await body(await call("PATCH", `/api/devices/${pair.deviceId}/config`, { cookie, body: { youtubeStrict: true } }));
    assert.equal(patched.device.configVersion, 2);
    const decided = await body(await call("POST", `/api/devices/${pair.deviceId}/requests/${req.id}`, { cookie, body: { approve: true, minutes: 30 } }));
    assert.equal(decided.request.status, "approved");

    const res = await body(await call("POST", "/api/device/sync", { token: pair.token, csrf: false, body: { appliedConfigVersion: 1 } }));
    assert.equal(res.configVersion, 3);
    assert.equal(res.config.youtubeStrict, true);
    assert.deepEqual(res.config.tempAllow.map((t: any) => t.host), ["school-portal.example"]);
    assert.equal(res.requests[0].localId, "r1");
    assert.equal(res.requests[0].status, "approved");

    const again = await body(await call("POST", "/api/device/sync", { token: pair.token, csrf: false, body: { appliedConfigVersion: 3 } }));
    assert.equal(again.config, undefined);
  });

  it("stores the app inventory and turns an approved app request into an allowed app", async () => {
    const { cookie, pair } = await signupAndPair();
    await call("PATCH", `/api/devices/${pair.deviceId}/config`, { cookie, body: { apps: { mode: "blocklist", blocked: ["com.zhiliaoapp.musically"] } } });
    await call("POST", "/api/device/sync", { token: pair.token, csrf: false, body: {
      apps: [{ package: "com.zhiliaoapp.musically", label: "TikTok", blocked: true }, { package: "com.waze", label: "Waze" }, { package: "not valid" }],
      requests: [{ localId: "a1", kind: "app", host: "com.zhiliaoapp.musically", label: "TikTok", reason: "school project" }],
    } });
    const d = await body(await call("GET", `/api/devices/${pair.deviceId}`, { cookie }));
    assert.deepEqual(d.device.apps.map((a: any) => a.label), ["TikTok", "Waze"]);
    const req = d.requests[0];
    assert.equal(req.kind, "app");
    assert.equal(req.label, "TikTok");
    await call("POST", `/api/devices/${pair.deviceId}/requests/${req.id}`, { cookie, body: { approve: true, minutes: 30 } });
    const after = await body(await call("GET", `/api/devices/${pair.deviceId}`, { cookie }));
    assert.deepEqual(after.device.config.apps.allowed, ["com.zhiliaoapp.musically"]);
    assert.deepEqual(after.device.config.apps.blocked, []);
    assert.equal(after.requests[0].until, 0);
  });

  it("sends in-app rules only when the phone's copy is out of date", async () => {
    const { cookie, pair } = await signupAndPair();
    await call("PATCH", `/api/devices/${pair.deviceId}/config`, { cookie, body: { inApp: { enabled: ["youtube_shorts"] } } });
    const first = await body(await call("POST", "/api/device/sync", { token: pair.token, csrf: false, body: { inAppHash: "" } }));
    assert.ok(first.inAppRules.every((r: any) => r.app === "com.google.android.youtube"));
    assert.equal(first.inAppHash, effectiveRules({ enabled: ["youtube_shorts"], custom: [] }).hash);
    const second = await body(await call("POST", "/api/device/sync", { token: pair.token, csrf: false, body: { inAppHash: first.inAppHash } }));
    assert.equal(second.inAppRules, undefined);
    const d = await body(await call("GET", `/api/devices/${pair.deviceId}`, { cookie }));
    assert.ok(d.inAppCatalog.some((f: any) => f.id === "youtube_shorts" && f.appName === "YouTube"));
    assert.equal(d.inAppCatalog[0].rules, undefined);
  });

  it("approving permanently adds the site to the allow list", async () => {
    const { cookie, pair } = await signupAndPair();
    await call("POST", "/api/device/sync", { token: pair.token, csrf: false, body: { requests: [{ localId: "r2", host: "wiki.example" }] } });
    const [req] = await model.listRequests(pair.deviceId);
    await call("POST", `/api/devices/${pair.deviceId}/requests/${req.id}`, { cookie, body: { approve: true, minutes: 0 } });
    const d = await body(await call("GET", `/api/devices/${pair.deviceId}`, { cookie }));
    assert.deepEqual(d.device.config.customAllow, ["wiki.example"]);
  });

  it("keeps each admin's phones private", async () => {
    const { pair } = await signupAndPair();
    envVars.ADMIN_SIGNUP_CODE = "code";
    const other = sessionFrom(await call("POST", "/api/signup", { body: { email: "b@example.com", password: "correct horse battery", signupCode: "code" } }));
    assert.equal((await call("GET", `/api/devices/${pair.deviceId}`, { cookie: other })).status, 404);
    assert.equal((await call("PATCH", `/api/devices/${pair.deviceId}/config`, { cookie: other, body: { adultList: false } })).status, 404);
    assert.deepEqual((await body(await call("GET", "/api/devices", { cookie: other }))).devices, []);
  });

  it("releases a phone: command delivered, then the token stops working after the ack", async () => {
    const { cookie, pair } = await signupAndPair();
    await call("POST", `/api/devices/${pair.deviceId}/commands`, { cookie, body: { type: "release" } });
    const res = await body(await call("POST", "/api/device/sync", { token: pair.token, csrf: false, body: {} }));
    assert.equal(res.commands[0].type, "release");
    await call("POST", "/api/device/sync", { token: pair.token, csrf: false, body: { commandAcks: [res.commands[0].id] } });
    assert.equal((await call("POST", "/api/device/sync", { token: pair.token, csrf: false, body: {} })).status, 401);
    assert.deepEqual((await body(await call("GET", "/api/devices", { cookie }))).devices, []);
  });

  it("rejects syncs without a valid token", async () => {
    assert.equal((await call("POST", "/api/device/sync", { csrf: false, body: {} })).status, 401);
    assert.equal((await call("POST", "/api/device/sync", { token: "made-up", csrf: false, body: {} })).status, 401);
  });
});

describe("silent devices", () => {
  it("alerts once when a paired phone stops checking in, and clears on the next check-in", async () => {
    const { pair } = await signupAndPair();
    // Just paired: lastSeen is now, so no alert.
    assert.equal((await model.checkSilentDevices()).length, 0);
    // 20 minutes later it has gone quiet.
    const future = Date.now() + 20 * 60_000;
    assert.equal((await model.checkSilentDevices(future)).length, 1);
    assert.equal((await model.checkSilentDevices(future + 60_000)).length, 0); // not repeated
    const d = await model.getDeviceForTest(pair.deviceId);
    assert.equal(d!.alerts[0].type, "device_silent");
    // A check-in clears the flag, so a later silence alerts again.
    await call("POST", "/api/device/sync", { token: pair.token, csrf: false, body: {} });
    assert.equal((await model.getDeviceForTest(pair.deviceId))!.silentAlerted, false);
    assert.equal((await model.checkSilentDevices(Date.now() + 20 * 60_000)).length, 1);
  });
});

describe("AI blocklist", () => {
  it("only adds confident adult or bypass classifications, and bumps the version", async () => {
    const { pair } = await signupAndPair();
    await model.saveClassifications([
      { domain: "adult.example", category: "adult", block: true, confidence: 0.95, reason: "", at: 0 },
      { domain: "news.example", category: "news", block: false, confidence: 0.9, reason: "", at: 0 },
    ]);
    const bl = await body(await call("GET", "/api/device/ai-blocklist", { token: pair.token, csrf: false }));
    assert.deepEqual(bl, { version: 1, domains: ["adult.example"], allow: [] });
    await model.saveClassifications([{ domain: "adult.example", category: "adult", block: true, confidence: 0.95, reason: "", at: 0 }]);
    assert.equal((await model.getAiBlocklist()).version, 1); // no change, no new version
  });
});
