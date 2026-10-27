/* Luxe Park — home explorer: layouts, property map (community → building → floor → apartment),
   list view, apartment drawer with cost estimate, compare tray, and the "help me choose" quiz. */
(function () {
  const D = window.LP; const root = document.querySelector("[data-explorer]"); if (!D || !root) return;
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
  const money = (n) => "$" + Math.round(n).toLocaleString();
  const I = {
    arrow: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14M13 6l6 6-6 6"/></svg>',
    close: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M6 6l12 12M18 6 6 18"/></svg>',
    up: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m6 15 6-6 6 6"/></svg>',
    down: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m6 9 6 6 6-6"/></svg>',
  };
  const ORD = { 1: "1st floor · ground level", 2: "2nd floor", 3: "3rd floor", 4: "4th floor · top floor" };
  const fmtDate = (iso) => new Date(iso + "T12:00:00").toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" });
  const availShort = (u) => (u.avail ? fmtDate(u.avail).replace(/, \d{4}$/, "") : "Now");
  const availLabel = (u) => (u.avail ? "Available " + fmtDate(u.avail) : "Available now");
  const todayIso = () => new Date().toISOString().slice(0, 10);
  const planOf = (u) => D.PLANS[u.plan];
  const byNum = (n) => D.UNITS.find((u) => u.num === String(n));
  const unitsIn = (b, f) => D.UNITS.filter((u) => u.bldg === b && (f == null || u.floor === f));

  const state = { view: "layouts", beds: "all", movein: "any", maxRent: 1500, sort: "rent", sortDir: 1, bldg: null, floor: null, unit: null, compare: [], plan: "slash", mode: "plan", hs: null, photo: 0 };

  /* ---------- filters ---------- */
  const moveOk = (u) => state.movein === "any" || (state.movein === "now" ? !u.avail : (!u.avail || u.avail <= "2026-12-31"));
  const passes = (u) => (state.beds === "all" || planOf(u).beds === +state.beds) && u.rent <= state.maxRent && moveOk(u);
  const filtered = () => D.UNITS.filter(passes);
  const sortUnits = (arr) => arr.slice().sort((a, b) => {
    const k = state.sort; let r = 0;
    if (k === "rent") r = a.rent - b.rent; else if (k === "sqft") r = planOf(a).sqft - planOf(b).sqft;
    else if (k === "plan") r = planOf(a).name.localeCompare(planOf(b).name); else if (k === "bldg") r = a.bldg - b.bldg || a.floor - b.floor;
    else if (k === "floor") r = a.floor - b.floor || a.bldg - b.bldg; else if (k === "avail") r = (a.avail || "0").localeCompare(b.avail || "0");
    return r * state.sortDir || a.rent - b.rent;
  });

  /* ---------- toolbar ---------- */
  const countEl = $("[data-count]", root);
  function renderCount() {
    const n = filtered().length;
    const filtersOn = state.beds !== "all" || state.movein !== "any" || state.maxRent < 1500;
    countEl.innerHTML = `<strong>${n}</strong> of ${D.UNITS.length} available homes${filtersOn ? ` match &nbsp;<button class="x-reset" data-reset>Reset filters</button>` : ""}<span class="stamp">Prices updated ${new Date(D.UPDATED.replace(" ", "T")).toLocaleDateString("en-US", { month: "short", day: "numeric" })}</span>`;
  }
  root.addEventListener("click", (e) => {
    const v = e.target.closest("[data-view]"); if (v) { setView(v.dataset.view); return; }
    const c = e.target.closest("[data-beds-filter] .chip"); if (c) { state.beds = c.dataset.beds; renderAll(); return; }
    if (e.target.closest("[data-reset]")) { state.beds = "all"; state.movein = "any"; state.maxRent = 1500; syncControls(); renderAll(); }
  });
  $("[data-movein]", root).addEventListener("change", (e) => { state.movein = e.target.value; renderAll(); });
  const rangeIn = $("[data-maxrent]", root), rangeOut = $("[data-maxrent-out]", root);
  rangeIn.addEventListener("input", (e) => { state.maxRent = +e.target.value; rangeOut.textContent = state.maxRent >= 1500 ? "Any" : money(state.maxRent); renderAll(); });
  $("[data-sort]", root)?.addEventListener("change", (e) => { state.sort = e.target.value; state.sortDir = 1; renderAll(); });
  function syncControls() {
    $$("[data-beds-filter] .chip", root).forEach((c) => c.classList.toggle("is-active", c.dataset.beds === state.beds));
    $("[data-movein]", root).value = state.movein; rangeIn.value = state.maxRent; rangeOut.textContent = state.maxRent >= 1500 ? "Any" : money(state.maxRent);
    $$("[data-view]", root).forEach((b) => b.classList.toggle("is-active", b.dataset.view === state.view));
    $$("[data-panel]", root).forEach((p) => { p.hidden = p.dataset.panel !== state.view; });
  }
  function setView(v) { state.view = v; syncControls(); renderAll(); pushHash(); const bar = $(".x-toolbar", root).getBoundingClientRect(); if (bar.top > 0) return; const first = $("[data-panel]:not([hidden])", root).getBoundingClientRect().top; window.scrollBy({ top: first - bar.bottom - 12, behavior: "smooth" }); }

  /* ---------- layouts view ---------- */
  const unitChip = (u, extra = "") => `<button class="unit-chip ${state.unit === u.num ? "is-selected" : ""} ${extra}" data-open-unit="${u.num}"><strong>#${u.num}</strong><span>Building ${u.bldg} · Floor ${u.floor}</span><em>${money(u.rent)}/mo</em><i class="${u.avail ? "soon" : ""}">${u.avail ? "From " + availShort(u) : "Available now"}</i></button>`;
  const I2 = { left: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m15 6-6 6 6 6"/></svg>', zoom: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5M11 8v6M8 11h6"/></svg>' };
  function renderLayouts() {
    const units = filtered(); const plans = Object.values(D.PLANS); const maxSq = Math.max(...plans.map((p) => p.sqft));
    const visible = plans.filter((p) => state.beds === "all" || p.beds === +state.beds);
    if (!visible.find((p) => p.slug === state.plan)) state.plan = (visible[0] || plans[0]).slug;
    const p = D.PLANS[state.plan]; const mine = sortUnits(units.filter((u) => u.plan === p.slug)); const all = D.UNITS.filter((u) => u.plan === p.slug);
    const minRent = mine.length ? Math.min(...mine.map((u) => u.rent)) : p.from;
    const list = visible.map((q) => { const n = units.filter((u) => u.plan === q.slug).length; const tot = D.UNITS.filter((u) => u.plan === q.slug).length;
      return `<button class="plan-row ${q.slug === state.plan ? "is-active" : ""}" data-plan="${q.slug}" style="--tone:${q.tone};--w:${Math.round((q.sqft / maxSq) * 100)}%">
        <span class="plan-row-top"><strong>${q.name}</strong><em>from ${money(n ? Math.min(...units.filter((u) => u.plan === q.slug).map((u) => u.rent)) : q.from)}</em></span>
        <span class="plan-row-sub">${q.beds} bed · ${q.baths} bath · ${q.sqft.toLocaleString()} sq ft</span>
        <span class="plan-row-bar"><i></i></span>
        <span class="plan-row-avail ${tot ? "" : "wait"}">${tot ? `${n} of ${tot} available` : "Waitlist"}</span></button>`; }).join("");
    let media = "";
    if (state.mode === "photos") {
      const ph = D.PHOTOS[p.slug]; const i = ((state.photo % ph.length) + ph.length) % ph.length;
      media = `<div class="stage-photos"><img src="${D.IMG(ph[i], 1400)}" alt="${p.name} photo ${i + 1}" data-lb-src="${D.IMG(ph[i], 1800)}" data-lb-cap="${p.name}">
        <button class="ph-nav prev" data-photo="-1" aria-label="Previous photo">${I2.left}</button><button class="ph-nav next" data-photo="1" aria-label="Next photo">${I.arrow}</button>
        <div class="ph-dots">${ph.map((_, k) => `<button class="${k === i ? "on" : ""}" data-photo-go="${k}" aria-label="Photo ${k + 1}"></button>`).join("")}</div>
        <p class="ph-note">${D.PHOTO_NOTE[p.slug]}</p></div>`;
    } else if (state.mode === "3d") {
      media = p.matterport ? `<div class="stage-3d"><iframe src="https://my.matterport.com/show/?m=${p.matterport}&play=1&qs=1&brand=0" title="${p.name} 3D walkthrough" allow="xr-spatial-tracking; fullscreen; vr" allowfullscreen loading="lazy"></iframe></div>`
        : `<div class="stage-3d placeholder"><img src="${D.IMG(p.img, 1200)}" alt=""><div class="stage-3d-msg"><p class="eyebrow">3D walkthrough</p><h3>Scan coming soon for ${p.name}</h3><p>Once this layout is scanned, you'll walk through it room by room right here. Until then, the video tour and the room photos are the next best thing.</p><div class="btn-row"><button class="btn btn-gold" data-video-open="g8NwPw3CMiE">Play the video tour</button><button class="btn btn-outline" data-mode="photos">See room photos</button></div></div></div>`;
    } else if (state.mode === "size") {
      media = `<div class="footprints">${plans.map((q) => { const w = Math.sqrt(q.sqft / maxSq) * 100; return `<button class="fp ${q.slug === state.plan ? "is-active" : ""}" data-plan="${q.slug}" style="--w:${w}%;--tone:${q.tone}"><span class="fp-box"><b>${q.sqft.toLocaleString()}</b><small>sq ft</small></span><span class="fp-label">${q.name}<em>${q.beds} bd · ${q.baths} ba</em></span></button>`; }).join("")}
        <p class="ph-note">Boxes are drawn to scale by floor area. ${p.name} is ${Math.round((p.sqft / D.PLANS.shortleaf.sqft) * 100 - 100) || "0"}% larger than our smallest plan.</p></div>`;
    } else {
      const hs = D.HOTSPOTS[p.slug] || [];
      media = `<div class="stage-plan" data-tilt><img src="${D.IMG(p.img, 1400)}" alt="${p.name} floor plan">
        ${hs.map((h, k) => `<button class="hs ${state.hs === k ? "is-on" : ""}" style="left:${h.x}%;top:${h.y}%" data-hs="${k}" aria-label="${h.label}"><i></i></button>`).join("")}
        ${state.hs != null && hs[state.hs] ? (() => { const h = hs[state.hs]; const flip = h.x > 55; return `<div class="hs-pop ${flip ? "flip" : ""}" style="left:${h.x}%;top:${h.y}%"><img src="${D.IMG(h.photo, 600)}" alt=""><strong>${h.label}</strong><span>${h.note}</span></div>`; })() : ""}
        <button class="stage-zoom" data-lb-src="${D.IMG(p.img, 1600)}" data-lb-cap="${p.name} · ${p.beds} bed / ${p.baths} bath · ${p.sqft.toLocaleString()} sq ft" aria-label="Enlarge plan">${I2.zoom}</button>
        <p class="hs-hint">${state.hs == null ? "Tap a gold dot to peek inside each room" : ""}</p></div>`;
    }
    const chips = mine.length ? `<div class="unit-chips">${mine.map((u) => unitChip(u)).join("")}</div>`
      : all.length ? `<p class="muted">No ${p.name} homes match your filters. <button class="x-reset" data-reset>Reset filters</button></p>`
      : `<p class="muted">Fully leased right now. Join the waitlist and we'll tell you the moment one opens.</p>`;
    const primary = all.length ? `<button class="btn btn-dark" data-open-unit="${mine[0]?.num || all[0].num}">View a home</button>`
      : `<a class="btn btn-dark" href="${D.waitlistUrl(p.slug)}" target="_blank" rel="noopener">Join the waitlist</a>`;
    $("[data-plans]", root).innerHTML = `<div class="showcase" id="${p.slug}" style="--tone:${p.tone}">
      <aside class="plan-list" aria-label="Floor plans">${list}</aside>
      <div class="stage">
        <div class="stage-head">
          <div class="stage-title"><p class="eyebrow">${p.beds} bed · ${p.baths} bath · <span data-countup="${p.sqft}">${p.sqft.toLocaleString()}</span> sq ft</p><h2>${p.name}</h2></div>
          <div class="stage-tabs" role="tablist"><button class="${state.mode === "plan" ? "is-active" : ""}" data-mode="plan">Plan</button><button class="${state.mode === "photos" ? "is-active" : ""}" data-mode="photos">Photos</button><button class="${state.mode === "3d" ? "is-active" : ""}" data-mode="3d">3D tour${p.matterport ? "" : ' <i class="soon-dot" title="coming soon"></i>'}</button><button class="${state.mode === "size" ? "is-active" : ""}" data-mode="size">Compare size</button></div>
        </div>
        <div class="stage-media" key="${p.slug}-${state.mode}">${media}</div>
        <div class="stage-body">
          <div class="stage-info">
            <p class="plan-rent"><span>from</span> ${money(minRent)}<small>/mo</small></p>
            <p class="plan-blurb">${p.blurb}</p>
            <ul class="plan-rooms">${p.rooms.map((r) => `<li>${r}</li>`).join("")}</ul>
          </div>
          <div class="stage-homes">
            <p class="unit-chips-title">${all.length ? `${all.length} ${p.name} home${all.length > 1 ? "s" : ""} available · pick one` : "Availability"}</p>
            ${chips}
            <div class="plan-actions">${primary}<button class="btn btn-outline" data-book="guided" data-book-plan="${p.slug}">Book a tour</button><a class="btn btn-ghost" href="${D.LINKS.selfTour}" target="_blank" rel="noopener">Self-guided now</a></div>
          </div>
        </div>
      </div></div>`;
    const sm = $(".stage-media", root); requestAnimationFrame(() => sm && sm.classList.add("is-in"));
  }
  root.addEventListener("click", (e) => {
    const pr = e.target.closest("[data-plan]"); if (pr) { if (state.plan !== pr.dataset.plan) { state.plan = pr.dataset.plan; state.hs = null; state.photo = 0; } renderLayouts(); return; }
    const md = e.target.closest("[data-mode]"); if (md) { state.mode = md.dataset.mode; state.hs = null; renderLayouts(); return; }
    const h = e.target.closest("[data-hs]"); if (h) { state.hs = state.hs === +h.dataset.hs ? null : +h.dataset.hs; renderLayouts(); return; }
    if (e.target.closest(".hs-pop")) { const hs = D.HOTSPOTS[state.plan][state.hs]; const lbBtn = document.createElement("button"); lbBtn.dataset.lbSrc = D.IMG(hs.photo, 1800); lbBtn.dataset.lbCap = hs.label; lbBtn.hidden = true; document.body.appendChild(lbBtn); lbBtn.click(); lbBtn.remove(); return; }
    const pn = e.target.closest("[data-photo]"); if (pn) { state.photo += +pn.dataset.photo; renderLayouts(); return; }
    const pg = e.target.closest("[data-photo-go]"); if (pg) { state.photo = +pg.dataset.photoGo; renderLayouts(); return; }
    if (state.hs != null && !e.target.closest(".stage-plan")) { state.hs = null; renderLayouts(); }
  });
  /* gentle 3D tilt on the plan render */
  root.addEventListener("mousemove", (e) => { const t = e.target.closest("[data-tilt]"); if (!t || matchMedia("(prefers-reduced-motion: reduce)").matches) return; const r = t.getBoundingClientRect(); const x = (e.clientX - r.left) / r.width - 0.5, y = (e.clientY - r.top) / r.height - 0.5; t.style.setProperty("--rx", (-y * 6).toFixed(2) + "deg"); t.style.setProperty("--ry", (x * 8).toFixed(2) + "deg"); });
  root.addEventListener("mouseleave", (e) => { $$("[data-tilt]", root).forEach((t) => { t.style.setProperty("--rx", "0deg"); t.style.setProperty("--ry", "0deg"); }); }, true);
  document.addEventListener("keydown", (e) => { if (state.view !== "layouts" || !drawer.hidden || e.target.matches("input,select,textarea")) return; const order = Object.keys(D.PLANS).filter((k) => state.beds === "all" || D.PLANS[k].beds === +state.beds); const i = order.indexOf(state.plan); if (e.key === "ArrowDown" || e.key === "ArrowRight") { if (state.mode === "photos" && e.key === "ArrowRight") { state.photo++; } else state.plan = order[(i + 1) % order.length]; state.hs = null; renderLayouts(); } if (e.key === "ArrowUp" || e.key === "ArrowLeft") { if (state.mode === "photos" && e.key === "ArrowLeft") { state.photo--; } else state.plan = order[(i - 1 + order.length) % order.length]; state.hs = null; renderLayouts(); } });

  /* ---------- map view ---------- */
  const siteMap = $("[data-sitemap]", root), inner = $(".site-map-inner", root), svg = $("[data-sitemap-svg]", root), pins = $("[data-pins]", root), panel = $("[data-map-panel]", root), crumbs = $("[data-crumbs]", root), plateStage = $("[data-plate-stage]", root);
  function scrollStage() {
    const bar = $(".x-toolbar", root).getBoundingClientRect().bottom; const el = $(".map-explorer", root); if (!el || el.offsetParent === null) return;
    const top = el.getBoundingClientRect().top; if (top < bar - 4 || top > innerHeight * 0.55) window.scrollBy({ top: top - bar - 12, behavior: "smooth" });
  }
  function renderMap() {
    const units = filtered();
    svg.innerHTML = D.BUILDINGS.map((b) => { const n = units.filter((u) => u.bldg === b.n).length; return `<polygon points="${b.pts}" class="bpoly ${n ? "" : "none"} ${state.bldg === b.n ? "is-active" : ""}" data-bldg="${b.n}" tabindex="0" role="button" aria-label="Building ${b.n}, ${n} available"></polygon>`; }).join("");
    pins.innerHTML = D.BUILDINGS.map((b) => { const us = units.filter((u) => u.bldg === b.n); const min = us.length ? Math.min(...us.map((u) => u.rent)) : 0;
      return `<button class="pin ${us.length ? "" : "none"} ${state.bldg === b.n ? "is-active" : ""}" style="left:${b.cx / 20}%;top:${b.cy / 15}%" data-bldg="${b.n}"><strong>Building ${b.n}</strong><span>${us.length ? `${us.length} available · from ${money(min)}` : "Fully leased"}</span></button>`; }).join("");
    if (state.bldg) { const b = D.BUILDINGS.find((x) => x.n === state.bldg); inner.style.transformOrigin = `${b.cx / 20}% ${b.cy / 15}%`; inner.style.transform = "scale(1.7)"; siteMap.classList.add("is-zoomed"); }
    else { inner.style.transform = ""; siteMap.classList.remove("is-zoomed"); }
    siteMap.hidden = !!state.floor; plateStage.hidden = !state.floor;
    if (state.floor) plateStage.innerHTML = plateHtml(state.bldg, state.floor);
    renderCrumbs(); renderPanel();
  }
  function renderCrumbs() {
    const parts = [`<button data-go="community">Community</button>`];
    if (state.bldg) parts.push(`<i>›</i>${state.floor ? `<button data-go="bldg">Building ${state.bldg}</button>` : `<span>Building ${state.bldg}</span>`}`);
    if (state.floor) parts.push(`<i>›</i><span>Floor ${state.floor}</span>`);
    crumbs.innerHTML = parts.join("");
  }
  function renderPanel() {
    const units = filtered();
    if (!state.bldg) {
      const rows = D.BUILDINGS.slice().sort((a, b) => a.n - b.n).map((b) => { const us = units.filter((u) => u.bldg === b.n); const min = us.length ? Math.min(...us.map((u) => u.rent)) : 0;
        return `<button class="bldg-row ${us.length ? "" : "none"}" data-bldg="${b.n}"><span class="bldg-num">${b.n}</span><span><strong>Building ${b.n}</strong><span>${us.length ? us.length + " home" + (us.length > 1 ? "s" : "") + " available · 4 floors" : "Fully leased · 4 floors"}</span></span><em>${us.length ? "from " + money(min) : ""}</em></button>`; }).join("");
      panel.innerHTML = `<h3>Choose a building</h3><p class="panel-sub">Tap a building on the map or in the list. Seven buildings surround the pool and the dog park.</p><div class="bldg-list">${rows}</div>`;
      return;
    }
    const b = state.bldg;
    if (!state.floor) {
      const all = unitsIn(b);
      const floors = D.FLOORS.slice().reverse().map((f) => { const us = units.filter((u) => u.bldg === b && u.floor === f); const min = us.length ? Math.min(...us.map((u) => u.rent)) : 0; const pct = Math.round((us.length / 10) * 100);
        return `<button class="floor-bar ${us.length ? "" : "none"}" style="--fill:${pct}%" data-floor="${f}"><b>Floor ${f}</b><span><strong>${us.length ? us.length + " available" : "Fully leased"}</strong><span>${f === 4 ? "Top floor, vaulted feel" : f === 1 ? "Ground level, walk right in" : "10 homes on this floor"}</span></span><em>${us.length ? "from " + money(min) : ""}</em></button>`; }).join("");
      panel.innerHTML = `<button class="back-link" data-go="community">${I.arrow} All buildings</button><div class="panel-head"><div><h3>Building ${b}</h3><p class="panel-sub">${all.length ? all.length + " homes available across 4 floors. Pick a floor to see exactly where each one sits." : "Fully leased right now."}</p></div></div><div class="floors">${floors}</div><p class="muted">Floors are listed top to bottom, the way you'd see the building from the courtyard.</p>`;
      return;
    }
    const f = state.floor; const us = unitsIn(b, f); const bd = D.BUILDINGS.find((x) => x.n === b);
    const list = sortUnits(us).map((u) => unitChip(u, passes(u) ? "" : "is-dim")).join("");
    panel.innerHTML = `<button class="back-link" data-go="bldg">${I.arrow} Building ${b}</button>
      <div class="panel-head"><div><h3>Building ${b} · Floor ${f}</h3><p class="panel-sub">${us.length ? `${us.length} available. Tap a highlighted home on the floor plate.` : "Nothing open on this floor right now."}</p></div>
      <div class="floor-nav"><button data-floor="${f + 1}" ${f >= 4 ? "disabled" : ""} aria-label="Floor up">${I.up}</button><button data-floor="${f - 1}" ${f <= 1 ? "disabled" : ""} aria-label="Floor down">${I.down}</button></div></div>
      <div class="legend"><span><i class="l-avail"></i>Available</span><span><i class="l-dim"></i>Outside your filters</span><span><i class="l-leased"></i>Leased</span></div>
      ${us.length ? `<div class="floor-units">${list}</div>` : `<button class="btn btn-outline btn-block" data-go="bldg">See other floors</button>`}
      <button class="mini-map" data-go="bldg" aria-label="Back to Building ${b} on the site map"><div class="mini-map-inner"><img src="${D.LINKS.siteMap}" alt=""><svg viewBox="0 0 2000 1500"><polygon points="${bd.pts}"></polygon></svg></div><span>Building ${b} on the property</span></button>`;
  }
  function plateHtml(b, f) {
    const us = unitsIn(b, f);
    const slots = Object.entries(D.SLOTS).map(([slot, s]) => {
      const u = us.find((x) => x.slot === slot); const p = D.PLANS[s.plan]; const cx = s.x + s.w / 2, cy = s.y + s.h / 2;
      const cls = u ? `avail ${passes(u) ? "" : "dim"} ${state.unit === u.num ? "is-selected" : ""}` : "leased";
      const label = u ? `#${u.num}` : `${p.beds} bed`; const sub = u ? money(u.rent) + "/mo" : "leased";
      return `<g class="slot ${cls}" ${u ? `data-open-unit="${u.num}" tabindex="0" role="button" aria-label="Apartment ${u.num}, ${p.name}, ${money(u.rent)}"` : ""}><rect x="${s.x}" y="${s.y}" width="${s.w}" height="${s.h}" rx="10"></rect>${u && !state.unit ? `<circle class="pulse" cx="${cx}" cy="${cy - 44}" r="9"></circle>` : ""}<text class="slot-num" x="${cx}" y="${cy + 2}">${label}</text><text class="slot-sub" x="${cx}" y="${cy + 30}">${sub}</text></g>`;
    }).join("");
    return `<div class="plate-head"><div><strong>Building ${b} · Floor ${f}</strong><span>${f === 4 ? "Top floor" : f === 1 ? "Ground level" : ORD[f]} · 10 homes · ${us.length} available</span></div><div class="plate-key"><span><i class="l-avail"></i>Tap to open</span></div></div>
      <div class="plate big"><img src="${D.LINKS.plate}" alt="Floor plate for Building ${b}, Floor ${f}"><svg viewBox="0 0 1200 900" aria-hidden="false">${slots}</svg></div>`;
  }
  root.addEventListener("click", (e) => {
    const go = e.target.closest("[data-go]"); if (go) { if (go.dataset.go === "community") { state.bldg = null; state.floor = null; } else { state.floor = null; } renderMap(); pushHash(); scrollStage(); return; }
    const bb = e.target.closest("[data-bldg]"); if (bb && state.view === "map") { state.bldg = +bb.dataset.bldg; state.floor = null; renderMap(); pushHash(); scrollStage(); return; }
    const fb = e.target.closest("[data-floor]"); if (fb && !fb.disabled) { state.floor = +fb.dataset.floor; renderMap(); pushHash(); scrollStage(); return; }
  });
  root.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { const t = e.target.closest("[data-bldg],[data-open-unit]"); if (t && t.tagName !== "BUTTON") { e.preventDefault(); t.click(); } } });

  /* ---------- list view ---------- */
  function renderList() {
    const us = sortUnits(filtered());
    const cols = [["num", "Apartment"], ["plan", "Floor plan"], ["sqft", "Size"], ["bldg", "Building"], ["floor", "Floor"], ["rent", "Rent"], ["avail", "Available"]];
    const th = cols.map(([k, l]) => `<th data-sortkey="${k}" class="${state.sort === k ? "is-sorted" : ""} ${state.sortDir < 0 ? "desc" : ""}">${l}</th>`).join("");
    const rows = us.map((u) => { const p = planOf(u); return `<tr data-open-unit="${u.num}" tabindex="0"><td class="num">#${u.num}</td><td><span class="tone" style="background:${p.tone}"></span>${p.name} · ${p.beds} bd / ${p.baths} ba</td><td>${p.sqft.toLocaleString()} sq ft</td><td>Building ${u.bldg}</td><td>Floor ${u.floor}</td><td class="rent">${money(u.rent)}/mo</td><td><span class="pill ${u.avail ? "soon" : ""}">${u.avail ? availShort(u) : "Now"}</span></td></tr>`; }).join("");
    $("[data-list]", root).innerHTML = us.length ? `<table class="units"><thead><tr>${th}</tr></thead><tbody>${rows}</tbody></table>` : `<p class="empty">No homes match. <button class="x-reset" data-reset>Reset filters</button></p>`;
  }
  root.addEventListener("click", (e) => { const th = e.target.closest("[data-sortkey]"); if (!th) return; const k = th.dataset.sortkey; state.sortDir = state.sort === k ? -state.sortDir : 1; state.sort = k; renderList(); });

  /* ---------- drawer ---------- */
  const drawer = $("[data-drawer]"), backdrop = $("[data-drawer-backdrop]");
  function dealFor(iso) { return D.DEALS.find((d) => d.month === iso.slice(0, 7)); }
  function costHtml(u, iso) {
    const d = dealFor(iso); const start = new Date(iso + "T12:00:00");
    const months = [0, 1, 2].map((i) => new Date(start.getFullYear(), start.getMonth() + i, 1).toLocaleDateString("en-US", { month: "short" }));
    const amounts = d ? d.calc(u.rent) : [u.rent, u.rent, u.rent];
    const saved = u.rent * 3 - amounts.reduce((a, b) => a + b, 0);
    const dealBox = d ? `<div class="deal"><strong>${d.title} special applied</strong><ul>${d.lines.map((l) => `<li>${l}</li>`).join("")}</ul></div>` : `<div class="deal none"><strong>No published special for that month yet</strong><ul><li>Ask the office about current offers and the preferred employer program.</li></ul></div>`;
    return `${dealBox}<div class="months">${months.map((m, i) => `<div><span>${m}</span><strong class="${amounts[i] === 0 ? "free" : ""}">${amounts[i] === 0 ? "Free" : money(amounts[i])}</strong></div>`).join("")}</div>
      <div class="savings"><span>First 3 months</span><span>${money(amounts.reduce((a, b) => a + b, 0))}${saved > 0 ? ` &nbsp;<b>save ${money(saved)}</b>` : ""}</span></div>
      <p class="fine" style="margin:10px 0 0">Estimate based on today's published rent and specials. The office prorates your first month and confirms final numbers. Gift cards not included in totals.</p>`;
  }
  function openUnit(num) {
    const u = byNum(num); if (!u) return; const p = planOf(u); state.unit = u.num;
    const minIso = u.avail && u.avail > todayIso() ? u.avail : todayIso();
    const similar = D.UNITS.filter((x) => x.plan === u.plan && x.num !== u.num).sort((a, b) => a.rent - b.rent).slice(0, 4);
    const inCompare = state.compare.includes(u.num);
    drawer.innerHTML = `<div class="drawer-head"><button class="drawer-close" data-drawer-close aria-label="Close">${I.close}</button>
        <p class="eyebrow">${p.name} · ${p.beds} bed / ${p.baths} bath</p><h2>Apartment #${u.num}</h2><p class="where">Building ${u.bldg} · ${ORD[u.floor]}</p></div>
      <div class="drawer-body">
        <button class="drawer-plan" data-lb-src="${D.IMG(p.img, 1400)}" data-lb-cap="Apartment #${u.num} · ${p.name}"><img src="${D.IMG(p.img, 900)}" alt="${p.name} floor plan"></button>
        <dl class="specs"><div><dt>Monthly rent</dt><dd class="big">${money(u.rent)}</dd></div><div><dt>Available</dt><dd>${availLabel(u)}</dd></div><div><dt>Size</dt><dd>${p.sqft.toLocaleString()} sq ft · $${(u.rent / p.sqft).toFixed(2)} / sq ft</dd></div><div><dt>Deposit</dt><dd>No-deposit option via Obligo</dd></div></dl>
        <section class="cost"><h3>What you'd pay</h3><p class="muted" style="margin:0">Pick a move-in date. Specials apply automatically.</p>
          <label>Move-in date <input type="date" data-movein-date min="${minIso}" value="${minIso}"></label><div data-cost>${costHtml(u, minIso)}</div></section>
        ${p.matterport ? `<section><h3>Walk through it</h3><div class="stage-3d"><iframe src="https://my.matterport.com/show/?m=${p.matterport}&play=1&qs=1&brand=0" title="${p.name} 3D walkthrough" allow="xr-spatial-tracking; fullscreen; vr" allowfullscreen loading="lazy"></iframe></div></section>` : ""}
        <section><h3>Inside this home</h3><ul class="plan-rooms">${p.rooms.map((r) => `<li>${r}</li>`).join("")}<li>Stainless appliances</li><li>Granite counters</li><li>Vinyl wood floors</li><li>Smart thermostat</li><li>Smart lock</li><li>500 Mbps internet</li></ul></section>
        <div class="drawer-actions">
          <a class="btn btn-gold btn-lg btn-block" href="${D.applyUrl(u)}" target="_blank" rel="noopener">Apply for #${u.num}</a>
          <div class="btn-row"><button class="btn btn-outline" data-book="guided" data-book-unit="${u.num}" data-book-plan="${p.slug}">Book a tour of #${u.num}</button><a class="btn btn-outline" href="${D.LINKS.selfTour}" target="_blank" rel="noopener">Self-guided now</a></div>
          <div class="btn-row"><button class="btn btn-ghost" data-compare-toggle="${u.num}">${inCompare ? "Remove from compare" : "Add to compare"}</button><button class="btn btn-ghost" data-share="${u.num}">Copy link</button><a class="btn btn-ghost" href="contact.html?unit=${u.num}">Ask a question</a></div>
        </div>
        ${similar.length ? `<section><h3>Other ${p.name} homes</h3><div class="similar">${similar.map((s) => unitChip(s)).join("")}</div></section>` : ""}
      </div>`;
    drawer.hidden = false; backdrop.hidden = false; requestAnimationFrame(() => { drawer.classList.add("is-open"); backdrop.classList.add("is-open"); });
    document.body.style.overflow = "hidden"; $(".drawer-close", drawer).focus();
    $("[data-movein-date]", drawer).addEventListener("change", (e) => { const v = e.target.value || minIso; $("[data-cost]", drawer).innerHTML = costHtml(u, v); });
    renderAll(false); pushHash();
  }
  function closeDrawer() { if (drawer.hidden) return; drawer.classList.remove("is-open"); backdrop.classList.remove("is-open"); document.body.style.overflow = ""; state.unit = null; setTimeout(() => { drawer.hidden = true; backdrop.hidden = true; }, 400); renderAll(false); pushHash(); }
  document.addEventListener("click", (e) => {
    const o = e.target.closest("[data-open-unit]"); if (o && o.dataset.openUnit) { e.preventDefault(); openUnit(o.dataset.openUnit); return; }
    if (e.target.closest("[data-drawer-close]") || e.target === backdrop) closeDrawer();
    const ct = e.target.closest("[data-compare-toggle]"); if (ct) { toggleCompare(ct.dataset.compareToggle); openUnit(ct.dataset.compareToggle); }
    const sh = e.target.closest("[data-share]"); if (sh) { const url = location.origin + location.pathname + "#unit/" + sh.dataset.share; (navigator.clipboard?.writeText(url) || Promise.reject()).then(() => { sh.textContent = "Link copied"; }, () => { prompt("Copy this link", url); }); }
  });
  document.addEventListener("keydown", (e) => { if (e.key === "Escape") { closeDrawer(); $("[data-compare-modal]").hidden = true; $("[data-quiz]").hidden = true; } });

  /* ---------- compare ---------- */
  const tray = $("[data-compare]"), cmpModal = $("[data-compare-modal]");
  function toggleCompare(num) { const i = state.compare.indexOf(num); if (i >= 0) state.compare.splice(i, 1); else { if (state.compare.length >= 3) state.compare.shift(); state.compare.push(num); } renderTray(); }
  function renderTray() {
    tray.hidden = state.compare.length === 0;
    tray.innerHTML = `<strong>Compare</strong><div class="ct-units">${state.compare.map((n) => `<span class="ct-unit">#${n}<button data-compare-remove="${n}" aria-label="Remove">${I.close}</button></span>`).join("")}</div><button class="btn btn-gold" data-compare-open ${state.compare.length < 2 ? "disabled" : ""}>Compare ${state.compare.length}</button>`;
  }
  tray.addEventListener("click", (e) => { const r = e.target.closest("[data-compare-remove]"); if (r) { toggleCompare(r.dataset.compareRemove); return; } if (e.target.closest("[data-compare-open]")) openCompare(); });
  function openCompare() {
    const us = state.compare.map(byNum).filter(Boolean); const best = (vals, lowIsBest = true) => { const v = lowIsBest ? Math.min(...vals) : Math.max(...vals); return vals.map((x) => x === v); };
    const rows = [
      ["Floor plan", us.map((u) => `<img src="${D.IMG(planOf(u).img, 400)}" alt=""><br>${planOf(u).name}`)],
      ["Beds / baths", us.map((u) => `${planOf(u).beds} / ${planOf(u).baths}`)],
      ["Size", us.map((u) => planOf(u).sqft.toLocaleString() + " sq ft"), best(us.map((u) => planOf(u).sqft), false)],
      ["Rent", us.map((u) => money(u.rent) + "/mo"), best(us.map((u) => u.rent))],
      ["Rent per sq ft", us.map((u) => "$" + (u.rent / planOf(u).sqft).toFixed(2)), best(us.map((u) => u.rent / planOf(u).sqft))],
      ["Building · floor", us.map((u) => `Building ${u.bldg} · Floor ${u.floor}`)],
      ["Available", us.map((u) => (u.avail ? fmtDate(u.avail) : "Now")), best(us.map((u) => (u.avail ? +new Date(u.avail) : 0)))],
      ["", us.map((u) => `<a class="btn btn-dark btn-sm" href="${D.applyUrl(u)}" target="_blank" rel="noopener">Apply</a> <button class="btn btn-ghost btn-sm" data-open-unit="${u.num}">Details</button>`)],
    ];
    $(".modal-card", cmpModal).innerHTML = `<button class="modal-close" data-modal-close aria-label="Close">${I.close}</button><p class="eyebrow">Side by side</p><h2>Compare homes</h2>
      <table class="compare"><thead><tr><th></th>${us.map((u) => `<th>#${u.num}</th>`).join("")}</tr></thead><tbody>${rows.map(([l, cells, b]) => `<tr><th>${l}</th>${cells.map((c, i) => `<td class="${b && b[i] ? "best" : ""}">${c}</td>`).join("")}</tr>`).join("")}</tbody></table>`;
    cmpModal.hidden = false;
  }
  cmpModal.addEventListener("click", (e) => { if (e.target === cmpModal || e.target.closest("[data-modal-close]")) cmpModal.hidden = true; if (e.target.closest("[data-open-unit]")) cmpModal.hidden = true; });

  /* ---------- quiz ---------- */
  const quiz = $("[data-quiz]"); const qs = { step: 0, beds: null, budget: null, movein: null };
  const STEPS = [
    { key: "beds", q: "How many bedrooms?", opts: [["1", "1 bedroom", "715 – 842 sq ft"], ["2", "2 bedrooms", "1,109 sq ft, two full baths"], ["3", "3 bedrooms", "1,386 sq ft, waitlist"], ["all", "Show me everything", "I'm flexible"]] },
    { key: "budget", q: "What monthly budget feels right?", opts: [["1300", "Under $1,300", "Best value one bedrooms"], ["1400", "Under $1,400", "Most one bedrooms and a few two bedrooms"], ["1460", "Under $1,460", "Every available two bedroom"], ["1500", "No limit", "Show every home"]] },
    { key: "movein", q: "When do you want to move?", opts: [["now", "As soon as possible", "Homes ready today"], ["dec", "By the end of December", "Lock in a fall special"], ["any", "Early 2027 or later", "Everything, including upcoming"]] },
  ];
  function renderQuiz() {
    const s = STEPS[qs.step]; const card = $(".modal-card", quiz);
    if (!s) {
      state.beds = qs.beds; state.maxRent = +qs.budget; state.movein = qs.movein; state.view = "layouts"; syncControls(); renderAll();
      const us = sortUnits(filtered());
      card.innerHTML = `<button class="modal-close" data-modal-close aria-label="Close">${I.close}</button><p class="eyebrow">Your matches</p><h2>${us.length ? `${us.length} home${us.length > 1 ? "s" : ""} fit` : "Nothing fits exactly, yet"}</h2>
        <p class="muted">${us.length ? "Filters are set on the page behind this. Tap any home to see it." : "Join the waitlist or loosen a filter and we'll show you what's close."}</p>
        <div class="quiz-result">${us.slice(0, 4).map((u) => unitChip(u)).join("")}</div>
        <div class="quiz-foot"><button class="btn btn-ghost" data-quiz-restart>Start over</button><button class="btn btn-dark" data-modal-close>See all matches</button></div>`;
      return;
    }
    card.innerHTML = `<button class="modal-close" data-modal-close aria-label="Close">${I.close}</button><p class="eyebrow">Help me choose · ${qs.step + 1} of ${STEPS.length}</p><h2>${s.q}</h2>
      <div class="quiz-progress">${STEPS.map((_, i) => `<i class="${i <= qs.step ? "on" : ""}"></i>`).join("")}</div>
      <div class="quiz-opts">${s.opts.map(([v, l, d]) => `<button class="quiz-opt" data-quiz-pick="${v}"><strong>${l}</strong><span>${d}</span></button>`).join("")}</div>
      <div class="quiz-foot">${qs.step ? `<button class="btn btn-ghost" data-quiz-back>Back</button>` : "<span></span>"}<span class="muted">No forms, no phone number.</span></div>`;
  }
  document.addEventListener("click", (e) => {
    if (e.target.closest("[data-quiz-open]")) { qs.step = 0; renderQuiz(); quiz.hidden = false; return; }
    if (quiz.hidden) return;
    const pick = e.target.closest("[data-quiz-pick]"); if (pick) { qs[STEPS[qs.step].key] = pick.dataset.quizPick; qs.step++; renderQuiz(); return; }
    if (e.target.closest("[data-quiz-back]")) { qs.step--; renderQuiz(); return; }
    if (e.target.closest("[data-quiz-restart]")) { qs.step = 0; renderQuiz(); return; }
    if (e.target === quiz || e.target.closest("[data-modal-close]")) { quiz.hidden = true; }
    if (e.target.closest("[data-open-unit]")) quiz.hidden = true;
  });

  /* ---------- url state ---------- */
  function pushHash() {
    let h = "";
    if (state.unit) h = `unit/${state.unit}`; else if (state.view === "map") h = "map" + (state.bldg ? `/b${state.bldg}` : "") + (state.floor ? `/f${state.floor}` : ""); else if (state.view === "list") h = "list";
    history.replaceState(null, "", location.pathname + location.search + (h ? "#" + h : ""));
  }
  function readUrl() {
    const q = new URLSearchParams(location.search); if (["1", "2", "3"].includes(q.get("beds"))) state.beds = q.get("beds");
    const h = location.hash.slice(1); const m = h.match(/^map(?:\/b(\d))?(?:\/f(\d))?/);
    if (m) { state.view = "map"; if (m[1]) state.bldg = +m[1]; if (m[2]) state.floor = +m[2]; } else if (h === "list") state.view = "list";
    const u = h.match(/^unit\/(\d{4})/); if (u && byNum(u[1])) setTimeout(() => openUnit(u[1]), 300);
    const anchor = ["shortleaf", "longleaf", "slash", "loblolly"].includes(h) ? h : null; if (anchor) { state.plan = anchor; setTimeout(() => $("[data-plans]", root)?.scrollIntoView({ behavior: "smooth", block: "start" }), 200); }
    if (!anchor && state.beds !== "all") state.plan = Object.values(D.PLANS).find((p) => p.beds === +state.beds)?.slug || state.plan;
  }

  function renderAll(withCount = true) { if (withCount) renderCount(); renderLayouts(); renderMap(); renderList(); }
  readUrl(); syncControls(); renderAll(); renderTray();
  addEventListener("hashchange", () => { const h = location.hash.slice(1); if (!h.startsWith("unit") && !drawer.hidden) closeDrawer(); if (h.startsWith("map")) { const m = h.match(/^map(?:\/b(\d))?(?:\/f(\d))?/); state.view = "map"; state.bldg = m[1] ? +m[1] : null; state.floor = m[2] ? +m[2] : null; syncControls(); renderAll(); } });
})();
