"use strict";

// ------------------------------------------------------------------ helpers

/** Builds DOM nodes. Strings become text nodes, so phone- or AI-supplied text is never parsed as HTML. */
function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
    else if (k === "class") el.className = v;
    else if (v === true) el.setAttribute(k, "");
    else el.setAttribute(k, String(v));
  }
  for (const c of children.flat(Infinity)) {
    if (c === null || c === undefined || c === false) continue;
    add(el, c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return el;
}

const $app = () => document.getElementById("app");

/** append/replaceChildren that skip null, undefined and false (DOM would print them as text). */
function clean(nodes) {
  return nodes.flat(Infinity).filter((n) => n !== null && n !== undefined && n !== false);
}
function add(el, ...nodes) {
  el.append(...clean(nodes));
}
function fill(el, ...nodes) {
  el.replaceChildren(...clean(nodes));
}

function toast(msg) {
  const t = document.getElementById("toast");
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => (t.hidden = true), 3500);
}

async function api(method, path, body) {
  const res = await fetch(path, {
    method,
    headers: { "content-type": "application/json", "x-veil": "1" },
    body: body === undefined ? undefined : JSON.stringify(body),
    credentials: "same-origin",
  });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401 && path !== "/api/login") {
    state.session = null;
    route();
  }
  if (!res.ok) throw new Error(data.error || `Request failed (${res.status})`);
  return data;
}

async function waitForJob(jobId, timeoutMs = 180000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    await new Promise((r) => setTimeout(r, 2500));
    const { job } = await api("GET", `/api/jobs/${jobId}`);
    if (job.status === "done") return job.result;
    if (job.status === "error") throw new Error(job.error || "The AI job failed");
  }
  throw new Error("Still working - check back in a minute");
}

function ago(ms) {
  if (!ms) return "never";
  const s = Math.round((Date.now() - ms) / 1000);
  if (s < 60) return "just now";
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  if (s < 86400) return `${Math.round(s / 3600)} h ago`;
  return `${Math.round(s / 86400)} d ago`;
}

function when(ms) {
  return new Date(ms).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

function pill(kind, text) {
  return h("span", { class: `pill ${kind}` }, h("span", { class: "dot" }), text);
}

function toggle(checked, onChange, labelText) {
  const input = h("input", { type: "checkbox", role: "switch", "aria-label": labelText, checked });
  input.addEventListener("change", async () => {
    input.disabled = true;
    try {
      await onChange(input.checked);
    } catch (e) {
      input.checked = !input.checked;
      toast(e.message);
    } finally {
      input.disabled = false;
    }
  });
  return h("label", { class: "toggle" }, input, h("span"));
}

function switchRow(title, desc, checked, onChange) {
  return h("div", { class: "switch-row" },
    h("div", { class: "text" }, h("strong", {}, title), desc ? h("p", {}, desc) : null),
    toggle(checked, onChange, title));
}

function busy(button, fn) {
  return async (...args) => {
    const label = button.textContent;
    button.disabled = true;
    button.textContent = "Working…";
    try {
      await fn(...args);
    } catch (e) {
      toast(e.message);
    } finally {
      button.disabled = false;
      button.textContent = label;
    }
  };
}

function button(label, onClick, cls = "") {
  const b = h("button", { type: "button", class: cls }, label);
  b.addEventListener("click", busy(b, onClick));
  return b;
}

// ------------------------------------------------------------------ state + routing

const state = { session: null, firstRun: false, signupOpen: false, aiConfigured: false, device: null, timer: null };

async function boot() {
  const s = await fetch("/api/session").then((r) => r.json()).catch(() => ({}));
  state.session = s.admin || null;
  state.firstRun = !!s.firstRun;
  state.signupOpen = !!s.signupOpen;
  state.aiConfigured = !!s.aiConfigured;
  window.addEventListener("hashchange", route);
  route();
}

function route() {
  clearInterval(state.timer);
  renderWho();
  if (!state.session) return renderAuth();
  const parts = location.hash.replace(/^#\/?/, "").split("/");
  if (parts[0] === "device" && parts[1]) return renderDevice(parts[1], parts[2] || "overview");
  return renderDevices();
}

function renderWho() {
  const who = document.getElementById("who");
  fill(who, );
  if (!state.session) return;
  add(who, h("span", {}, state.session.name), button("Sign out", async () => {
    await api("POST", "/api/logout");
    state.session = null;
    location.hash = "#/";
    route();
  }, "ghost small"));
}

// ------------------------------------------------------------------ sign in / sign up

function renderAuth(mode) {
  mode = mode || (state.firstRun ? "signup" : "login");
  const signup = mode === "signup";
  const err = h("p", { class: "error", role: "alert" });
  const email = h("input", { type: "email", id: "email", autocomplete: "email", required: true });
  const password = h("input", { type: "password", id: "password", autocomplete: signup ? "new-password" : "current-password", required: true, minlength: signup ? 10 : undefined });
  const name = h("input", { type: "text", id: "name", autocomplete: "name" });
  const code = h("input", { type: "text", id: "code", autocomplete: "off" });
  const submit = h("button", { type: "submit" }, signup ? "Create admin account" : "Sign in");
  const form = h("form", { class: "card stack", novalidate: true },
    h("h1", {}, signup ? (state.firstRun ? "Set up VEIL Admin" : "Create an admin account") : "Sign in"),
    h("p", { class: "muted" }, signup
      ? (state.firstRun ? "You're the first admin on this server. Your account controls VEIL on the phones you pair." : "You need the sign-up code from whoever runs this server.")
      : "Manage VEIL on the phones you look after."),
    signup ? h("div", {}, h("label", { for: "name" }, "Your name"), name, h("p", { class: "muted small" }, "Shown on the paired phone, e.g. \"Managed by Ana\".")) : null,
    h("div", {}, h("label", { for: "email" }, "Email"), email),
    h("div", {}, h("label", { for: "password" }, "Password"), password, signup ? h("p", { class: "muted small" }, "At least 10 characters.") : null),
    signup && !state.firstRun ? h("div", {}, h("label", { for: "code" }, "Sign-up code"), code) : null,
    err, submit,
    !state.firstRun ? h("p", { class: "small" }, signup
      ? h("a", { href: "#", onclick: (e) => { e.preventDefault(); renderAuth("login"); } }, "I already have an account")
      : (state.signupOpen ? h("a", { href: "#", onclick: (e) => { e.preventDefault(); renderAuth("signup"); } }, "Create an admin account") : null)) : null,
  );
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    err.textContent = "";
    submit.disabled = true;
    try {
      const res = signup
        ? await api("POST", "/api/signup", { email: email.value, password: password.value, name: name.value, signupCode: code.value })
        : await api("POST", "/api/login", { email: email.value, password: password.value });
      state.session = res.admin;
      state.firstRun = false;
      route();
    } catch (ex) {
      err.textContent = ex.message;
    } finally {
      submit.disabled = false;
    }
  });
  fill($app(), h("div", { class: "auth" }, form));
  email.focus();
}

// ------------------------------------------------------------------ device list

function deviceHealth(d) {
  if (d.state === "releasing") return pill("warn", "Releasing");
  if (!d.online) return pill("neutral", `Offline · ${ago(d.lastSeen)}`);
  if (d.status.protection === false || d.status.vpnRunning === false) return pill("bad", "Protection off");
  if (d.alerts.length) return pill("warn", `${d.alerts.length} alert${d.alerts.length > 1 ? "s" : ""}`);
  return pill("good", "Protected");
}

async function renderDevices() {
  fill($app(), h("p", { class: "muted" }, "Loading phones…"));
  let data;
  try {
    data = await api("GET", "/api/devices");
  } catch (e) {
    return fill($app(), h("p", { class: "error" }, e.message));
  }
  state.aiConfigured = data.aiConfigured;
  const head = h("div", { class: "spread" },
    h("div", {}, h("h1", {}, "Phones"), h("p", { class: "muted" }, "Phones running VEIL that you manage.")),
    button("Add a phone", async () => addPhoneDialog()));
  const banner = data.aiConfigured ? null : h("div", { class: "banner info" },
    h("strong", {}, "AI features are off. "),
    "Add an ANTHROPIC_API_KEY environment variable to this Netlify site to turn on request review, site classification, reports and the assistant.");
  const cards = data.devices.map((d) => h("article", {
    class: "card device-card", tabindex: 0, role: "link",
    onclick: () => (location.hash = `#/device/${d.id}`),
    onkeydown: (e) => { if (e.key === "Enter") location.hash = `#/device/${d.id}`; },
  },
    h("div", { class: "spread" }, h("h2", {}, d.name), deviceHealth(d)),
    h("dl", { class: "kv", style: "margin-top:12px" },
      h("dt", {}, "Last check-in"), h("dd", {}, ago(d.lastSeen)),
      h("dt", {}, "Blocked today"), h("dd", {}, d.status.blockedToday ?? "–"),
      h("dt", {}, "Lockdown"), h("dd", {}, d.status.deviceOwner ? "Device Owner" : "Not active"),
      h("dt", {}, "Unblock requests"), h("dd", {}, d.pendingRequests ? `${d.pendingRequests} waiting` : "None")),
  ));
  fill($app(), head, banner, cards.length ? h("div", { class: "grid" }, cards)
    : h("div", { class: "card empty" }, h("h2", {}, "No phones yet"), h("p", { class: "muted" }, "Add a phone to pair it with your account."), button("Add a phone", async () => addPhoneDialog())));
}

// ------------------------------------------------------------------ pairing

async function addPhoneDialog() {
  const nameInput = h("input", { type: "text", id: "devname", placeholder: "e.g. Sam's phone" });
  const body = h("div", { class: "stack" });
  const dlg = h("dialog", { "aria-labelledby": "pair-title" },
    h("div", { class: "spread" }, h("h2", { id: "pair-title" }, "Add a phone"), h("button", { type: "button", class: "ghost small", onclick: () => dlg.close() }, "Close")),
    body);
  dlg.addEventListener("close", () => { dlg.remove(); route(); });
  add(body, 
    h("div", {}, h("label", { for: "devname" }, "Name for this phone"), nameInput),
    button("Create pairing code", async () => {
      const res = await api("POST", "/api/pairing-codes", { deviceName: nameInput.value });
      showPairing(body, res);
    }));
  document.body.append(dlg);
  dlg.showModal();
  nameInput.focus();
}

function showPairing(body, res) {
  const pretty = `${res.code.slice(0, 4)}-${res.code.slice(4)}`;
  const payload = {
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "app.veil.android/.admin.VeilDeviceAdmin",
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": res.provisioning.apkUrl,
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": res.provisioning.signatureChecksum,
    "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": true,
    "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": false,
    "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": { veil_server: res.server, veil_pair_code: res.code },
  };
  const qrBox = h("div", { class: "qr", role: "img", "aria-label": "Setup QR code" });
  fill(body, 
    h("div", { class: "banner info" },
      h("div", { class: "muted small" }, `Pairing code · expires ${new Date(res.expires).toLocaleTimeString([], { timeStyle: "short" })}`),
      h("div", { class: "code" }, pretty)),
    h("h3", {}, "Option A: full lockdown (Device Owner) - recommended"),
    h("p", { class: "muted small" },
      "Needs a phone that has just been factory reset (no Google account yet). On the first Welcome screen, tap the screen six times in the same spot to open the QR scanner, connect to Wi-Fi, then scan this code. ",
      "Android downloads VEIL, makes it the device manager and pairs it with your account automatically. VEIL can then not be uninstalled or switched off on the phone."),
    qrBox,
    h("details", {}, h("summary", { class: "small" }, "Set up with a computer instead (adb)"),
      h("p", { class: "muted small" }, "Factory reset the phone, skip adding accounts, turn on USB debugging, install the VEIL APK, then run:"),
      h("pre", { class: "small", style: "white-space:pre-wrap;overflow-wrap:anywhere" }, "adb shell dpm set-device-owner app.veil.android/.admin.VeilDeviceAdmin"),
      h("p", { class: "muted small" }, `Then open VEIL on the phone and enter the server ${res.server} and code ${pretty}.`)),
    h("h3", {}, "Option B: pair without lockdown"),
    h("p", { class: "muted small" },
      `On a phone that already has VEIL: open VEIL → Settings → Pair with an admin, enter the server ${res.server} and the code above. `,
      "You'll control VEIL's settings, but the phone's user could still uninstall it (you'd get an alert when it stops checking in)."),
  );
  // Low error correction keeps the dense provisioning payload scannable.
  const qr = window.qrcode(0, "L");
  qr.addData(JSON.stringify(payload), "Byte");
  qr.make();
  const img = h("img", { src: qr.createDataURL(4, 4), alt: "" });
  add(qrBox, img);
}

// ------------------------------------------------------------------ device page

const TABS = [
  ["overview", "Overview"], ["requests", "Requests"], ["activity", "Activity"], ["filtering", "Web filter"],
  ["screen", "Screen filter"], ["apps", "Apps"], ["lockdown", "Lockdown"], ["assistant", "Assistant"],
];

async function loadDevice(id) {
  const data = await api("GET", `/api/devices/${id}`);
  state.device = data;
  state.aiConfigured = data.aiConfigured;
  if (data.recoveryCode) state.recoveryCode = { id, code: data.recoveryCode };
  return data;
}

async function renderDevice(id, tab) {
  if (!state.device || state.device.device.id !== id) fill($app(), h("p", { class: "muted" }, "Loading…"));
  let data;
  try {
    data = await loadDevice(id);
  } catch (e) {
    return fill($app(), h("p", { class: "error" }, e.message), h("a", { href: "#/" }, "Back to phones"));
  }
  const d = data.device;
  const pending = data.requests.filter((r) => r.status === "pending").length;
  const tabs = h("div", { class: "tabs", role: "tablist" }, TABS.map(([key, label]) => h("button", {
    type: "button", role: "tab", "aria-selected": key === tab ? "true" : "false",
    onclick: () => (location.hash = `#/device/${id}/${key}`),
  }, label, key === "requests" && pending ? h("span", { class: "badge" }, pending) : null,
     key === "overview" && d.alerts.length ? h("span", { class: "badge" }, d.alerts.length) : null)));

  const head = h("div", { class: "spread" },
    h("div", {}, h("a", { href: "#/", class: "small" }, "← Phones"), h("h1", {}, d.name),
      h("div", { class: "row" }, deviceHealth(d), h("span", { class: "muted small" }, `Checked in ${ago(d.lastSeen)} · VEIL ${d.appVersion || "?"}`),
        d.pendingConfig ? pill("warn", "Changes waiting for the phone") : null)),
    button("Rename", async () => {
      const name = prompt("New name for this phone", d.name);
      if (name) { await api("PATCH", `/api/devices/${id}`, { name }); route(); }
    }, "ghost small"));

  const content = h("div");
  const recovery = state.recoveryCode && state.recoveryCode.id === id ? h("div", { class: "banner warn" },
    h("strong", {}, "Write down this recovery code now. It is shown only once."),
    h("div", { class: "code", style: "margin:6px 0" }, state.recoveryCode.code),
    h("p", { class: "small", style: "margin:0" }, "Typed into VEIL on the phone (Settings → Recovery code), it removes the lockdown even if this server is unreachable. Keep it somewhere the phone's user can't see."),
    button("I've saved it", async () => { state.recoveryCode = null; route(); }, "small")) : null;

  fill($app(), head, recovery, tabs, content);
  const views = { overview: viewOverview, requests: viewRequests, activity: viewActivity, filtering: viewFiltering, screen: viewScreen, apps: viewApps, lockdown: viewLockdown, assistant: viewAssistant };
  await (views[tab] || viewOverview)(content, data);
  if (tab === "overview" || tab === "requests") {
    state.timer = setInterval(() => { if (!document.querySelector("dialog[open]")) renderDevice(id, tab); }, 30000);
  }
}

async function patchConfig(patch) {
  const id = state.device.device.id;
  const res = await api("PATCH", `/api/devices/${id}/config`, patch);
  state.device.device = res.device;
  toast("Saved. The phone picks it up within a minute.");
}

// ---------------- overview

function statusRow(label, ok, okText, badText, unknownText = "Unknown") {
  const v = ok === undefined || ok === null ? pill("neutral", unknownText) : ok ? pill("good", okText) : pill("bad", badText);
  return [h("dt", {}, label), h("dd", {}, v)];
}

function viewOverview(root, data) {
  const d = data.device;
  const s = d.status;
  const alerts = h("section", { class: "card" },
    h("div", { class: "spread" }, h("h2", {}, "Alerts"), d.alerts.length ? button("Clear", async () => { await api("POST", `/api/devices/${d.id}/alerts/clear`); route(); }, "ghost small") : null),
    d.alerts.length ? h("ul", { class: "list" }, d.alerts.slice(0, 20).map((a) => h("li", {}, h("strong", {}, a.detail), h("div", { class: "muted small" }, when(a.at)))))
      : h("p", { class: "muted" }, "No tamper alerts."));

  const status = h("section", { class: "card" }, h("h2", {}, "Status"),
    h("dl", { class: "kv" },
      statusRow("Web filter (VPN)", s.vpnRunning, "Running", "Off"),
      statusRow("Screen filter", s.accessibilityOn, "On", "Off"),
      statusRow("Device Owner lockdown", s.deviceOwner, "Active", "Not active"),
      h("dt", {}, "Private DNS"), h("dd", {}, s.privateDns ? pill("bad", s.privateDns) : "Not set"),
      h("dt", {}, "Blocked today / total"), h("dd", {}, `${s.blockedToday ?? "–"} / ${s.blockedTotal ?? "–"}`),
      h("dt", {}, "Words covered on screen"), h("dd", {}, s.textCoveredTotal ?? "–"),
      h("dt", {}, "Phone"), h("dd", {}, [s.model, s.androidVersion && `Android ${s.androidVersion}`].filter(Boolean).join(" · ") || "–")));

  const summary = data.summary;
  const reportBody = h("div", { class: "stack" });
  const renderReport = (sm) => {
    fill(reportBody, );
    if (!sm) return add(reportBody, h("p", { class: "muted" }, state.aiConfigured ? "No report yet. Reports are written every morning, or generate one now." : "AI reports need an ANTHROPIC_API_KEY on the server."));
    const level = { none: ["good", "Nothing of concern"], low: ["good", "Low concern"], medium: ["warn", "Worth a look"], high: ["bad", "Needs attention"] }[sm.concernLevel] || ["neutral", sm.concernLevel];
    add(reportBody, 
      h("div", { class: "row" }, pill(level[0], level[1]), h("span", { class: "muted small" }, `${sm.periodDays === 1 ? "Daily" : `${sm.periodDays}-day`} report · ${when(sm.at)}`)),
      h("p", { style: "white-space:pre-wrap" }, sm.text),
      sm.highlights.length ? h("ul", {}, sm.highlights.map((x) => h("li", {}, x))) : null);
  };
  renderReport(summary);
  const genButtons = state.aiConfigured ? h("div", { class: "row" }, [1, 7, 30].map((days) => button(days === 1 ? "Today" : `${days} days`, async () => {
    const { job } = await api("POST", `/api/devices/${d.id}/summary`, { periodDays: days });
    fill(reportBody, h("p", { class: "muted" }, "Writing the report…"));
    renderReport(await waitForJob(job.id));
  }, "secondary small"))) : null;
  const report = h("section", { class: "card" }, h("div", { class: "spread" }, h("h2", {}, "AI report"), genButtons), reportBody);

  const actions = h("section", { class: "card stack" }, h("h2", {}, "Actions"),
    h("div", { class: "row" },
      button("Check in now", async () => { await api("POST", `/api/devices/${d.id}/commands`, { type: "sync_now" }); toast("Sent."); }, "ghost"),
      button("Update block lists", async () => { await api("POST", `/api/devices/${d.id}/commands`, { type: "refresh_lists" }); toast("The phone will download fresh lists on its next check-in."); }, "ghost")),
    h("p", { class: "muted small" }, "Commands reach the phone on its next check-in (about once a minute while it's online)."));

  add(root, h("div", { class: "grid" }, h("div", {}, status, actions), h("div", {}, alerts, report)));
}

// ---------------- requests

function viewRequests(root, data) {
  const d = data.device;
  const pending = data.requests.filter((r) => r.status === "pending");
  const done = data.requests.filter((r) => r.status !== "pending");
  const aiBox = (r) => {
    if (!r.ai) {
      return state.aiConfigured ? h("div", { class: "ai" }, h("span", { class: "muted" }, "AI review pending… "),
        button("Run review", async () => {
          const { job } = await api("POST", `/api/devices/${d.id}/requests/${r.id}/review`);
          await waitForJob(job.id);
          route();
        }, "ghost small")) : null;
    }
    const rec = { approve: ["good", "Recommends allowing"], approve_limited: ["warn", `Recommends ${r.ai.suggestedMinutes} min`], deny: ["bad", "Recommends denying"] }[r.ai.recommendation];
    return h("div", { class: "ai" },
      h("div", { class: "row" }, h("strong", {}, "AI review"), pill(rec[0], rec[1]), h("span", { class: "muted small" }, `${r.ai.category} · ${r.ai.risk} risk`)),
      h("p", { style: "margin:6px 0 0" }, r.ai.explanation));
  };
  const decide = (r, approve, minutes) => async () => {
    await api("POST", `/api/devices/${d.id}/requests/${r.id}`, { approve, minutes });
    toast(approve ? "Allowed. The phone gets it within a minute." : "Denied.");
    route();
  };
  const pendingList = pending.length ? h("ul", { class: "list" }, pending.map((r) => h("li", { class: "stack" },
    h("div", { class: "spread" }, requestTitle(r), h("span", { class: "muted small" }, when(r.createdAt))),
    h("div", {}, h("span", { class: "muted" }, "Reason: "), r.reason || h("em", { class: "muted" }, "none given")),
    aiBox(r),
    r.kind === "app"
      ? h("div", { class: "row" },
        button("Allow app", decide(r, true, 0), "secondary small"),
        button("Deny", decide(r, false, 0), "danger small"))
      : h("div", { class: "row" },
        button("Allow 30 min", decide(r, true, 30), "secondary small"),
        button("Allow 1 day", decide(r, true, 1440), "secondary small"),
        button("Always allow", decide(r, true, 0), "secondary small"),
        button("Deny", decide(r, false, 0), "danger small"))))) : h("p", { class: "muted" }, "Nothing waiting. When VEIL blocks something, the phone's user can ask you to allow it.");
  add(root, 
    h("section", { class: "card" }, h("h2", {}, "Waiting for you"), pendingList),
    h("section", { class: "card" }, h("h2", {}, "Earlier"), done.length ? h("ul", { class: "list" }, done.map((r) => h("li", { class: "spread" },
      h("div", {}, requestTitle(r), h("div", { class: "muted small" }, r.reason || "")),
      h("div", { class: "row" }, r.autoApproved ? pill("neutral", "Auto-approved by AI") : null, r.status === "approved" ? pill("good", r.until === 0 ? "Always allowed" : r.until > Date.now() ? `Allowed until ${new Date(r.until).toLocaleString([], { dateStyle: "short", timeStyle: "short" })}` : "Allowance ended") : pill("neutral", "Denied"))))) : h("p", { class: "muted" }, "No earlier requests.")));
}

function requestTitle(r) {
  return r.kind === "app"
    ? h("span", {}, h("strong", {}, r.label || r.host), " ", h("span", { class: "muted small" }, `app · ${r.host}`))
    : h("span", { class: "host" }, r.host);
}

// ---------------- apps

function viewApps(root, data) {
  const d = data.device;
  const policy = d.config.apps || { mode: "off", allowed: [], blocked: [], approveNewApps: false };
  const apps = d.apps || [];
  const modeSel = h("select", { id: "appmode" }, [["off", "Off: every app can open"], ["blocklist", "Block chosen apps"], ["allowlist", "Only allowed apps"]]
    .map(([v, l]) => h("option", { value: v, selected: v === policy.mode }, l)));
  modeSel.addEventListener("change", async () => {
    try { await patchConfig({ apps: { mode: modeSel.value } }); route(); } catch (e) { toast(e.message); }
  });
  const isAllowed = (pkg) => policy.mode === "allowlist" ? policy.allowed.includes(pkg) : !policy.blocked.includes(pkg);
  const setAllowed = async (pkg, allowed) => {
    const allowedList = policy.allowed.filter((p) => p !== pkg);
    const blockedList = policy.blocked.filter((p) => p !== pkg);
    if (allowed) allowedList.push(pkg); else blockedList.push(pkg);
    await patchConfig({ apps: { allowed: allowedList, blocked: blockedList } });
    policy.allowed = allowedList;
    policy.blocked = blockedList;
  };
  const filter = h("input", { type: "text", placeholder: "Search apps", "aria-label": "Search apps" });
  const list = h("div");
  const draw = () => {
    const q = filter.value.trim().toLowerCase();
    const shown = apps.filter((a) => !q || a.label.toLowerCase().includes(q) || a.package.toLowerCase().includes(q));
    fill(list, shown.length ? shown.map((a) => switchRow(a.label, `${a.package}${a.system ? " · system app" : ""}${a.blocked ? " · blocked on the phone now" : ""}`, isAllowed(a.package), (v) => setAllowed(a.package, v)))
      : h("p", { class: "muted" }, apps.length ? "No apps match." : "The phone hasn't sent its app list yet. It does on its next check-in."));
  };
  filter.addEventListener("input", draw);
  draw();
  add(root,
    d.status.deviceOwner ? null : h("div", { class: "banner warn" }, h("strong", {}, "Without Device Owner, "),
      "blocked apps are closed by VEIL's screen filter as soon as they open. That needs the accessibility service on, and it's easier to get around than Device Owner, which pauses blocked apps completely."),
    h("section", { class: "card stack" }, h("h2", {}, "App control"),
      h("div", {}, h("label", { for: "appmode" }, "Mode"), modeSel),
      h("p", { class: "muted small" }, "The phone, messages, contacts, keyboard, home screen, Settings and VEIL itself are never blocked, so the phone always stays usable."),
      switchRow("New apps need approval", "Apps installed after this is turned on stay blocked until you allow them. The phone's user can ask for them.", policy.approveNewApps, (v) => patchConfig({ apps: { approveNewApps: v } }))),
    h("section", { class: "card" }, h("div", { class: "spread" }, h("h2", {}, `Apps on the phone (${apps.length})`), h("div", { style: "min-width:200px;flex:1;max-width:320px" }, filter)),
      policy.mode === "off" ? h("p", { class: "muted small" }, "App control is off. Choose a mode above; these switches then say which apps may open.") : null,
      list),
    inAppSection(data));
}

/** Catalog switches grouped by app, plus the admin's own rules. */
function inAppSection(data) {
  const d = data.device;
  const inApp = d.config.inApp || { enabled: [], custom: [] };
  const catalog = data.inAppCatalog || [];
  const groups = new Map();
  for (const f of catalog) groups.set(f.appName, [...(groups.get(f.appName) || []), f]);
  const setEnabled = async (id, on) => {
    const next = on ? [...new Set([...inApp.enabled, id])] : inApp.enabled.filter((x) => x !== id);
    await patchConfig({ inApp: { enabled: next } });
    inApp.enabled = next;
  };
  const features = h("section", { class: "card" }, h("h2", {}, "Block parts of apps"),
    h("p", { class: "muted small" }, "Keep an app working but switch off its risky parts. Needs VEIL's screen filter (accessibility) on the phone. Rules are kept up to date from this server, so they keep working when apps change."),
    [...groups].map(([appName, list]) => h("div", { style: "margin-top:12px" }, h("h3", {}, appName),
      list.map((f) => switchRow(f.name, f.description, inApp.enabled.includes(f.id), (v) => setEnabled(f.id, v))))));

  const custom = [...inApp.custom];
  const listBox = h("ul", { class: "list" });
  const describe = (r) => {
    const m = r.match;
    const what = m.viewId ? `view id contains “${m.viewId}”` : m.text ? `text is “${m.text}”` : `description contains “${m.desc}”`;
    return `${r.app}: ${r.action === "leave" ? "leave screens where" : "cover elements where"} ${what}${m.selected ? " (when selected)" : ""}`;
  };
  // Show what the server accepted (it drops malformed rules), not what was typed.
  const save = async (next) => {
    await patchConfig({ inApp: { custom: next } });
    custom.splice(0, custom.length, ...(state.device.device.config.inApp?.custom || []));
    drawCustom();
  };
  const drawCustom = () => fill(listBox, custom.length ? custom.map((r, i) => h("li", { class: "spread" }, h("span", { class: "small", style: "overflow-wrap:anywhere" }, describe(r)),
    button("Remove", async () => save(custom.filter((_, j) => j !== i)), "ghost small"))) : h("li", { class: "muted small" }, "No custom rules."));
  drawCustom();
  const appIn = h("input", { type: "text", placeholder: "com.example.app", "aria-label": "App package" });
  const kind = h("select", { "aria-label": "Match" }, [["viewId", "View id contains"], ["text", "Text is"], ["desc", "Description contains"]].map(([v, l]) => h("option", { value: v }, l)));
  const value = h("input", { type: "text", placeholder: "e.g. reel_player", "aria-label": "Match value" });
  const action = h("select", { "aria-label": "Action" }, [["cover", "Cover it"], ["leave", "Leave the screen"]].map(([v, l]) => h("option", { value: v }, l)));
  const customCard = h("section", { class: "card stack" }, h("h2", {}, "Custom rules"),
    h("p", { class: "muted small" }, "For apps not in the list above. The phone matches these against what's on screen in that app. Ask the assistant if you're not sure what to enter."),
    listBox,
    h("div", { class: "grid", style: "grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:8px" }, appIn, kind, value, action),
    button("Add rule", async () => {
      const r = { app: appIn.value.trim(), match: { [kind.value]: value.value.trim() }, action: action.value };
      const before = custom.length;
      await save([...custom, r]);
      if (custom.length === before) toast("That rule wasn't accepted: check the package name and use at least 3 characters to match.");
      else { value.value = ""; }
    }, "secondary"));
  return [features, customCard];
}

// ---------------- activity

async function viewActivity(root, data) {
  const d = data.device;
  const body = h("div", {}, h("p", { class: "muted" }, "Loading activity…"));
  let days = 7;
  const picker = h("select", { "aria-label": "Period" }, [1, 7, 30].map((n) => h("option", { value: n, selected: n === days }, n === 1 ? "Today" : `Last ${n} days`)));
  picker.addEventListener("change", () => { days = Number(picker.value); load(); });
  add(root, h("div", { class: "row", style: "margin-bottom:12px" }, picker), body);
  async function load() {
    const res = await api("GET", `/api/devices/${d.id}/activity?days=${days}`);
    const st = res.stats;
    const max = Math.max(1, ...st.blocksByUtcHour);
    // Shift UTC hours to the admin's local time for display.
    const offset = -new Date().getTimezoneOffset() / 60;
    const local = st.blocksByUtcHour.map((_, i) => st.blocksByUtcHour[((i - offset) % 24 + 24) % 24]);
    fill(body, 
      h("div", { class: "grid" },
        h("section", { class: "card" }, h("h2", {}, "Blocks"),
          h("p", { class: "code" }, st.totalBlocks),
          h("ul", { class: "list" }, Object.entries(st.blocksByReason).sort((a, b) => b[1] - a[1]).map(([reason, n]) => h("li", { class: "spread" }, h("span", {}, reason), h("strong", {}, n))))),
        h("section", { class: "card" }, h("h2", {}, "By hour of day"),
          h("div", { class: "bars", role: "img", "aria-label": "Blocked lookups by hour of day" }, local.map((n) => h("div", { style: `height:${Math.round((n / max) * 100)}%`, title: String(n) }))),
          h("div", { class: "bars-axis" }, h("span", {}, "0:00"), h("span", {}, "6:00"), h("span", {}, "12:00"), h("span", {}, "18:00"), h("span", {}, "23:00")),
          h("p", { class: "muted small" }, `Words covered on screen: ${st.textCovered}`))),
      h("section", { class: "card" }, h("h2", {}, "Most blocked sites"),
        st.topBlockedHosts.length ? h("ul", { class: "list" }, st.topBlockedHosts.map((x) => h("li", { class: "spread" },
          h("div", {}, h("span", { class: "host" }, x.host), h("div", { class: "muted small" }, x.reason)),
          h("div", { class: "row" }, h("strong", {}, x.count),
            button("Allow", async () => { await patchConfig({ customAllow: [...state.device.device.config.customAllow, x.host] }); }, "ghost small"))))) : h("p", { class: "muted" }, "Nothing blocked in this period.")),
      st.tamper.length ? h("section", { class: "card" }, h("h2", {}, "Tamper events"), h("ul", { class: "list" }, st.tamper.map((t) => h("li", {}, h("strong", {}, t.rule), " ", h("span", { class: "muted small" }, when(Date.parse(t.at))))))) : null);
  }
  await load();
}

// ---------------- list editor

function listEditor(title, help, items, placeholder, save) {
  const input = h("input", { type: "text", placeholder, "aria-label": `Add to ${title}` });
  const chips = h("div", { class: "chips" });
  let current = [...items];
  const draw = () => fill(chips, ...(current.length ? current.map((x) => h("span", { class: "chip" }, h("span", {}, x),
    h("button", { type: "button", "aria-label": `Remove ${x}`, onclick: async () => { const next = current.filter((y) => y !== x); try { await save(next); current = next; draw(); } catch (e) { toast(e.message); } } }, "×"))) : [h("span", { class: "muted small" }, "Empty")]));
  draw();
  const add = async () => {
    const vals = input.value.split(/[\s,]+/).map((v) => v.trim()).filter(Boolean);
    if (!vals.length) return;
    const next = [...new Set([...current, ...vals])];
    try { await save(next); current = next; input.value = ""; draw(); } catch (e) { toast(e.message); }
  };
  input.addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); add(); } });
  return h("section", { class: "card" }, h("h2", {}, title), h("p", { class: "muted small" }, help), chips,
    h("div", { class: "row" }, h("div", { style: "flex:1;min-width:180px" }, input), button("Add", add, "secondary")));
}

// ---------------- web filter

const STRICT_INAPP = ["whatsapp_updates", "youtube_shorts", "instagram_reels", "instagram_explore", "google_discover"];

/** One-tap presets. Each sets a coherent group of settings; everything stays adjustable afterwards. */
function presets(c) {
  const base = { adultList: true, keywordsEnabled: true, safeSearch: true, bypassProtection: true, upstreamFamily: true };
  const strictInApp = { enabled: [...new Set([...(c.inApp?.enabled || []), ...STRICT_INAPP])] };
  return [
    ["open", "Open", "Adult sites and bypasses blocked, SafeSearch on. Words covered only at the adult level.",
      { ...base, level: "open", webMode: "filter", youtubeStrict: false, screen: { tier: "adult", deobfuscate: false } }],
    ["standard", "Standard", "Adds teen-level word covering. A good default for most adults and teens.",
      { ...base, level: "standard", webMode: "filter", youtubeStrict: false, screen: { tier: "teen", deobfuscate: false } }],
    ["strict", "Strict", "Strict YouTube, child-level words including disguised spellings, and no Shorts, Reels, Explore, Status or Discover feeds.",
      { ...base, level: "strict", webMode: "filter", youtubeStrict: true, screen: { tier: "child", deobfuscate: true }, inApp: strictInApp }],
    ["allowlist", "Allowed sites only", "Everything in Strict, and only sites you allow (or the AI rates clearly safe) load. Like a whitelisted browser, but for every app.",
      { ...base, level: "allowlist", webMode: "allowlist", aiAutoAllowSafe: true, youtubeStrict: true, screen: { tier: "child", deobfuscate: true }, inApp: strictInApp }],
  ];
}

function viewFiltering(root, data) {
  const c = data.device.config;
  const set = (key) => (v) => patchConfig({ [key]: v, level: "custom" });
  const temp = c.tempAllow.filter((t) => t.until > Date.now());
  const levelCard = h("section", { class: "card stack" }, h("h2", {}, "Filter level"),
    h("p", { class: "muted small" }, c.level && c.level !== "custom" ? `Current level: ${presets(c).find((p) => p[0] === c.level)?.[1] ?? c.level}.` : "Current level: custom settings."),
    h("div", { class: "grid", style: "grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:10px" },
      presets(c).map(([id, name, desc, patch]) => h("div", { class: "card", style: `margin:0;${c.level === id ? "border-color:var(--accent);border-width:2px" : ""}` },
        h("h3", {}, name), h("p", { class: "muted small" }, desc),
        c.level === id ? pill("good", "Active") : button(`Use ${name}`, async () => {
          if (id === "allowlist" && !confirm("Only allowed sites will load on this phone. Apps may need a minute while VEIL learns which of their servers are safe. Continue?")) return;
          await patchConfig(patch);
          route();
        }, "secondary small")))),
    switchRow("Allowed sites only", "Only sites on the Always allow list, temporary allows and the phone's essential services load.", c.webMode === "allowlist", (v) => patchConfig({ webMode: v ? "allowlist" : "filter", level: "custom" })),
    switchRow("Let AI allow clearly safe sites", "In allowed-sites-only mode, sites the AI is confident are education, government, banking, health or app infrastructure open without asking. Never social media, video, news or shopping.", c.aiAutoAllowSafe !== false, set("aiAutoAllowSafe")),
    switchRow("Approve low-risk requests automatically", "When the AI reviews an unblock request as low-risk, it's approved straight away (the phone gets it within a minute). Anything else still waits for you.", c.aiAutoApprove === "low_risk", (v) => patchConfig({ aiAutoApprove: v ? "low_risk" : "off" })));
  add(root, levelCard, 
    h("section", { class: "card" }, h("h2", {}, "Web filter"),
      switchRow("Adult content list", "Blocks hundreds of thousands of known adult sites.", c.adultList, set("adultList")),
      switchRow("Keyword blocking", "Blocks any site whose name contains one of the keywords below.", c.keywordsEnabled, set("keywordsEnabled")),
      switchRow("SafeSearch", "Forces safe results on Google, Bing, DuckDuckGo and YouTube.", c.safeSearch, set("safeSearch")),
      switchRow("Strict YouTube", "Uses YouTube's strict restricted mode instead of moderate.", c.youtubeStrict, set("youtubeStrict")),
      switchRow("Bypass protection", "Blocks encrypted-DNS servers and other ways around the filter.", c.bypassProtection, set("bypassProtection")),
      switchRow("Family resolver", "Sends allowed lookups to Cloudflare for Families as a second filter.", c.upstreamFamily, set("upstreamFamily")),
      switchRow("AI site classification", "Sends names of sites no list covers to the server, where AI classifies them; confident adult and bypass sites get blocked for everyone. Disclosed on the phone.", c.aiClassification, set("aiClassification")),
      switchRow("Notify on the phone when something is blocked", null, c.notifyOnBlock, set("notifyOnBlock"))),
    temp.length ? h("section", { class: "card" }, h("h2", {}, "Temporarily allowed"),
      h("ul", { class: "list" }, temp.map((t) => h("li", { class: "spread" }, h("span", { class: "host" }, t.host),
        h("div", { class: "row" }, h("span", { class: "muted small" }, `until ${when(t.until)}`),
          button("End now", async () => { await patchConfig({ tempAllow: c.tempAllow.filter((x) => x.host !== t.host) }); route(); }, "ghost small")))))) : null,
    listEditor("Always block", "Sites blocked on top of the lists, including all their subdomains.", c.customBlock, "example.com", (v) => patchConfig({ customBlock: v })),
    listEditor("Always allow", "Sites never blocked by any list or keyword.", c.customAllow, "example.com", (v) => patchConfig({ customAllow: v })),
    listEditor("Keywords", "A site is blocked if its name contains any of these (at least 3 letters).", c.keywords, "word", (v) => patchConfig({ keywords: v })));
}

// ---------------- screen filter

function viewScreen(root, data) {
  const c = data.device.config.screen;
  const set = (key) => (v) => patchConfig({ screen: { [key]: v } });
  const tierSel = h("select", { id: "tier" }, [["young_child", "Young child"], ["child", "Child"], ["teen", "Teen"], ["adult", "Adult"], ["custom", "Custom"]].map(([v, l]) => h("option", { value: v, selected: v === c.tier }, l)));
  tierSel.addEventListener("change", async () => { try { await patchConfig({ screen: { tier: tierSel.value } }); route(); } catch (e) { toast(e.message); } });
  const actionSel = (key, label) => {
    const s = h("select", { "aria-label": label }, [["ignore", "Leave"], ["strike", "Line through"], ["bar", "Solid bar"], ["frost", "Frosted"]].map(([v, l]) => h("option", { value: v, selected: v === c[key] }, l)));
    s.addEventListener("change", () => patchConfig({ screen: { [key]: s.value } }).catch((e) => toast(e.message)));
    return h("div", { class: "spread", style: "padding:8px 0" }, h("span", {}, label), s);
  };
  add(root, 
    h("section", { class: "card" }, h("h2", {}, "Screen filter"),
      h("p", { class: "muted small" }, "Covers explicit words wherever text appears on the phone. The phone's user must turn on VEIL's accessibility service once; you get an alert if it's switched off."),
      switchRow("Cover words on screen", null, c.enabled, set("enabled")),
      h("div", { style: "padding:12px 0" }, h("label", { for: "tier" }, "Age tier"), tierSel),
      c.tier === "custom" ? h("div", {}, actionSel("customMild", "Mild words"), actionSel("customStrong", "Strong words"), actionSel("customExplicit", "Explicit words")) : null,
      switchRow("Warn and log only", "Log what would be covered without covering it.", c.logOnly, set("logOnly")),
      switchRow("Catch disguised words", "Also catches s.p.a.c.e.d, l33t and stretched spellings. More false positives.", c.deobfuscate, set("deobfuscate"))),
    listEditor("Extra words to cover", "Single words, at least 2 letters.", c.blockWords, "word", (v) => patchConfig({ screen: { blockWords: v } })),
    listEditor("Never cover", "Words the filter should leave alone.", c.allowWords, "word", (v) => patchConfig({ screen: { allowWords: v } })),
    listEditor("Apps skipped", "Android package names the screen filter never reads (VEIL itself is always skipped).", c.safeListApps, "com.example.app", (v) => patchConfig({ screen: { safeListApps: v } })));
}

// ---------------- lockdown

function viewLockdown(root, data) {
  const d = data.device;
  const l = d.config.lockdown;
  const set = (key) => (v) => patchConfig({ lockdown: { [key]: v } });
  add(root, 
    d.status.deviceOwner ? null : h("div", { class: "banner warn" }, h("strong", {}, "Lockdown isn't active on this phone. "),
      "VEIL is not the phone's Device Owner, so these settings have no effect and the user can uninstall VEIL. To turn on lockdown, factory reset the phone and set it up with the QR code from \"Add a phone\"."),
    h("section", { class: "card" }, h("h2", {}, "Device Owner lockdown"),
      h("p", { class: "muted small" }, "These Android policies apply only while VEIL is the Device Owner. They limit what can be changed on the phone; they don't give you any access to the phone's content. VEIL can never be uninstalled while paired."),
      switchRow("Always-on VPN", "Android restarts VEIL's filter automatically and the user can't switch it off.", l.alwaysOnVpn, set("alwaysOnVpn")),
      switchRow("Block internet when the filter is down", "Strictest setting. If the filter ever stops, nothing loads until it's back.", l.vpnLockdown, set("vpnLockdown")),
      switchRow("No VPN changes", "Stops other VPN apps from being set up.", l.disallowVpnConfig, set("disallowVpnConfig")),
      switchRow("No Private DNS changes", "Stops the Private DNS bypass.", l.disallowPrivateDnsConfig, set("disallowPrivateDnsConfig")),
      switchRow("No Safe Mode", "Safe Mode would start the phone without VEIL.", l.disallowSafeBoot, set("disallowSafeBoot")),
      switchRow("No factory reset from Settings", "Prevents wiping the phone to remove VEIL.", l.disallowFactoryReset, set("disallowFactoryReset")),
      switchRow("No extra users or guests", "Other user profiles would not be filtered.", l.disallowAddUser, set("disallowAddUser")),
      switchRow("No force-stopping or clearing apps", "Applies to all apps in Settings.", l.disallowAppsControl, set("disallowAppsControl")),
      switchRow("No installing apps from outside the Play Store", null, l.disallowUnknownSources, set("disallowUnknownSources")),
      switchRow("No developer options / USB debugging", null, l.disallowDebugging, set("disallowDebugging"))),
    h("section", { class: "card stack" }, h("h2", {}, "Release this phone"),
      h("p", { class: "muted small" }, "Removes every restriction, removes VEIL as Device Owner and unpairs the phone from your account. VEIL stays installed and the phone's user can then remove it. This can't be undone; pairing again needs a factory reset for full lockdown."),
      button("Release phone", async () => {
        if (!confirm(`Release ${d.name}? All lockdown is removed and the phone is unpaired.`)) return;
        await api("POST", `/api/devices/${d.id}/commands`, { type: "release" });
        toast("The phone will be released on its next check-in.");
        route();
      }, "danger")));
}

// ---------------- assistant

async function viewAssistant(root, data) {
  const d = data.device;
  if (!state.aiConfigured) {
    add(root, h("div", { class: "card" }, h("h2", {}, "Assistant"), h("p", { class: "muted" }, "The assistant needs an ANTHROPIC_API_KEY environment variable on the Netlify site.")));
    return;
  }
  const log = h("div", { class: "chat", "aria-live": "polite" });
  const input = h("textarea", { rows: 2, placeholder: "Ask about this phone, e.g. \"Why was discord.com blocked?\" or \"Tighten things up after 10pm\"", "aria-label": "Message" });
  const send = h("button", { type: "submit" }, "Send");
  const draw = (messages) => {
    fill(log, ...(messages.length ? messages.map((m) => h("div", { class: `msg ${m.role}` }, m.text,
      m.proposal ? h("div", { class: "proposal" }, h("strong", {}, "Proposed change: "), m.proposal.summary,
        h("div", { class: "row", style: "margin-top:6px" }, button("Apply", async () => { await patchConfig(m.proposal.patch); }, "small"))) : null))
      : [h("p", { class: "muted" }, "Ask anything about what VEIL did on this phone, or ask for a settings change. The assistant only sees VEIL's own data and never changes settings without you pressing Apply.")]));
    log.scrollTop = log.scrollHeight;
  };
  draw((await api("GET", `/api/devices/${d.id}/chat`)).messages);
  const form = h("form", { class: "row", style: "margin-top:12px;align-items:flex-end" }, h("div", { style: "flex:1;min-width:200px" }, input), send);
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    const message = input.value.trim();
    if (!message) return;
    send.disabled = true;
    input.value = "";
    add(log, h("div", { class: "msg admin" }, message), h("div", { class: "msg assistant muted" }, "Thinking…"));
    log.scrollTop = log.scrollHeight;
    try {
      const { job } = await api("POST", `/api/devices/${d.id}/chat`, { message });
      await waitForJob(job.id).catch(() => {});
      draw((await api("GET", `/api/devices/${d.id}/chat`)).messages);
    } catch (ex) {
      toast(ex.message);
    } finally {
      send.disabled = false;
      input.focus();
    }
  });
  input.addEventListener("keydown", (e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); form.requestSubmit(); } });
  add(root, h("section", { class: "card" },
    h("div", { class: "spread" }, h("h2", {}, "Assistant"), button("Clear", async () => { await api("DELETE", `/api/devices/${d.id}/chat`); draw([]); }, "ghost small")),
    log, form));
}

boot();
