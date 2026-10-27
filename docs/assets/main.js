/* Luxe Park — shared behaviour: header, menu, reveal, lightbox, video, hero video, forms. */
(function () {
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));

  /* Header shadow on scroll */
  const header = $(".site-header");
  const onScroll = () => header && header.classList.toggle("is-scrolled", window.scrollY > 8);
  onScroll(); addEventListener("scroll", onScroll, { passive: true });

  /* Mobile menu */
  const menu = $("[data-mobile-menu]"), menuBtn = $("[data-menu]");
  const setMenu = (open) => { if (!menu) return; menu.classList.toggle("is-open", open); menuBtn?.setAttribute("aria-expanded", String(open)); document.body.style.overflow = open ? "hidden" : ""; };
  menuBtn?.addEventListener("click", () => setMenu(true));
  $("[data-menu-close]")?.addEventListener("click", () => setMenu(false));
  menu?.addEventListener("click", (e) => { if (e.target.closest("a")) setMenu(false); });

  /* Reveal on scroll */
  const io = "IntersectionObserver" in window ? new IntersectionObserver((entries) => {
    entries.forEach((en) => { if (en.isIntersecting) { en.target.classList.add("is-in"); io.unobserve(en.target); } });
  }, { rootMargin: "0px 0px -8% 0px", threshold: 0.05 }) : null;
  window.lpReveal = (root = document) => $$(".reveal:not(.is-in)", root).forEach((el) => io ? io.observe(el) : el.classList.add("is-in"));
  window.lpReveal();

  /* Lightbox (works for any [data-lb-src], grouped by nearest [data-gallery] or the whole page) */
  const lb = $("[data-lightbox]");
  if (lb) {
    const img = $("img", lb), cap = $("figcaption", lb);
    let items = [], idx = 0;
    const show = (i) => { idx = (i + items.length) % items.length; const it = items[idx]; img.src = it.src; img.alt = it.cap; cap.textContent = it.cap + (items.length > 1 ? `  ·  ${idx + 1} / ${items.length}` : ""); };
    const open = (btn) => {
      const group = btn.closest("[data-gallery]") || btn.closest(".g-grid") || btn.closest(".plans") || document;
      const all = $$("[data-lb-src]", group).filter((b) => !b.closest(".is-hidden"));
      items = (all.length ? all : [btn]).map((b) => ({ src: b.dataset.lbSrc, cap: b.dataset.lbCap || "" }));
      show(Math.max(0, items.findIndex((it) => it.src === btn.dataset.lbSrc)));
      lb.hidden = false; document.body.style.overflow = "hidden";
      $("[data-lb-close]", lb).focus();
    };
    const close = () => { lb.hidden = true; document.body.style.overflow = ""; };
    document.addEventListener("click", (e) => { const b = e.target.closest("[data-lb-src]"); if (b) { e.preventDefault(); open(b); } });
    $("[data-lb-close]", lb).addEventListener("click", close);
    $("[data-lb-prev]", lb).addEventListener("click", () => show(idx - 1));
    $("[data-lb-next]", lb).addEventListener("click", () => show(idx + 1));
    lb.addEventListener("click", (e) => { if (e.target === lb) close(); });
    document.addEventListener("keydown", (e) => { if (lb.hidden) return; if (e.key === "Escape") close(); if (e.key === "ArrowRight") show(idx + 1); if (e.key === "ArrowLeft") show(idx - 1); });
    let tx = 0; lb.addEventListener("touchstart", (e) => { tx = e.touches[0].clientX; }, { passive: true });
    lb.addEventListener("touchend", (e) => { const dx = e.changedTouches[0].clientX - tx; if (Math.abs(dx) > 50) show(idx + (dx < 0 ? 1 : -1)); });
  }

  /* Gallery filter chips */
  $$("[data-gallery-filter]").forEach((group) => {
    group.addEventListener("click", (e) => {
      const chip = e.target.closest(".chip"); if (!chip) return;
      $$(".chip", group).forEach((c) => c.classList.toggle("is-active", c === chip));
      const cat = chip.dataset.cat;
      $$("[data-gallery] .g-item").forEach((it) => it.classList.toggle("is-hidden", cat !== "all" && it.dataset.cat !== cat));
    });
  });

  /* Video modal (plays with sound after a tap) */
  const vm = $("[data-video-modal]");
  if (vm) {
    const frame = $("iframe", vm);
    const open = (id) => { frame.src = `https://www.youtube.com/embed/${id}?autoplay=1&mute=0&rel=0&modestbranding=1&playsinline=1`; vm.hidden = false; document.body.style.overflow = "hidden"; };
    const close = () => { frame.src = ""; vm.hidden = true; document.body.style.overflow = ""; };
    document.addEventListener("click", (e) => { const b = e.target.closest("[data-video-open]"); if (b) open(b.dataset.videoOpen); });
    $("[data-video-close]", vm).addEventListener("click", close);
    vm.addEventListener("click", (e) => { if (e.target === vm) close(); });
    document.addEventListener("keydown", (e) => { if (!vm.hidden && e.key === "Escape") close(); });
  }

  /* Hero background video: starts instantly, muted, behind the headline */
  const hero = $("[data-hero-video]");
  if (hero && !matchMedia("(prefers-reduced-motion: reduce)").matches && innerWidth > 700) {
    const id = hero.dataset.heroVideo;
    const f = document.createElement("iframe");
    f.title = "Luxe Park video tour"; f.allow = "autoplay; fullscreen"; f.tabIndex = -1; f.setAttribute("aria-hidden", "true");
    f.src = `https://www.youtube.com/embed/${id}?autoplay=1&mute=1&loop=1&playlist=${id}&controls=0&rel=0&modestbranding=1&playsinline=1&disablekb=1&iv_load_policy=3`;
    f.addEventListener("load", () => setTimeout(() => hero.classList.add("is-playing"), 900));
    hero.appendChild(f);
  }

  /* Contact form: prefill from URL, validate, then POST to data-endpoint (set one on the form to connect a mailbox) */
  const form = $("[data-contact-form]");
  if (form) {
    const q = new URLSearchParams(location.search);
    const planSel = form.querySelector("[name=plan]");
    if (q.get("plan") && planSel) planSel.value = q.get("plan");
    if (q.get("topic") === "furniture" && planSel) planSel.value = "furniture";
    if (q.get("unit")) form.querySelector("[name=message]").value = `I'm interested in apartment #${q.get("unit")}.`;
    const status = $("[data-form-status]", form);
    form.addEventListener("submit", (e) => {
      e.preventDefault();
      const d = Object.fromEntries(new FormData(form).entries());
      const bad = ["first", "last", "email"].filter((k) => !String(d[k] || "").trim());
      if (bad.length || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(d.email)) { status.textContent = "Please add your name and a valid email."; status.classList.add("err"); return; }
      status.classList.remove("err");
      const body = [`Name: ${d.first} ${d.last}`, `Email: ${d.email}`, `Phone: ${d.phone || "-"}`, `Interested in: ${planSel?.selectedOptions[0]?.text || "Any"}`, `Move-in: ${d.movein || "-"}`, `Contact me by: ${d.contact}`, `Texts OK: ${d.sms ? "yes" : "no"}`, "", d.message || ""].join("\n");
      const endpoint = form.dataset.endpoint;
      if (endpoint) {
        fetch(endpoint, { method: "POST", headers: { "Content-Type": "application/json", Accept: "application/json" }, body: JSON.stringify(d) })
          .then((r) => { if (!r.ok) throw 0; status.textContent = "Thanks! We'll be in touch shortly."; form.reset(); })
          .catch(() => { status.textContent = "Something went wrong. Please call 888-546-7290."; status.classList.add("err"); });
      } else {
        status.innerHTML = "This form isn't connected to a mailbox yet. Call or text <a href=\"tel:+18885467290\">888-546-7290</a> and we'll help right away.";
        console.info("Contact form payload (connect a form endpoint via data-endpoint on the form):\n" + body);
      }
    });
  }

  /* Tour booking: pick a type, a day, a time, then your details. Posts to data-endpoint; otherwise hands
     the exact slot to the RentCafe scheduler so the office still gets it. */
  const bk = $("[data-booking]");
  if (bk) {
    const PLANS = window.LP ? window.LP.PLANS : {};
    const HOURS = { 0: null, 1: [9.5, 17.5], 2: [9.5, 17.5], 3: [9.5, 17.5], 4: [9.5, 17.5], 5: [9.5, 17.5], 6: [10, 16.5] };
    const st = { step: 0, type: "guided", day: null, time: null, plan: "", unit: "" };
    const fmtT = (h) => { const hh = Math.floor(h), mm = h % 1 ? "30" : "00"; return `${((hh + 11) % 12) + 1}:${mm} ${hh >= 12 ? "PM" : "AM"}`; };
    const days = () => { const out = []; const d = new Date(); d.setHours(0, 0, 0, 0); for (let i = 0; i < 14; i++) { const x = new Date(d); x.setDate(d.getDate() + i); out.push(x); } return out; };
    const openFor = (x) => st.type === "self" ? [8, 20] : HOURS[x.getDay()];
    const slots = (x) => { const o = openFor(x); if (!o) return []; const out = []; const now = new Date(); for (let h = o[0]; h + 0.5 <= o[1] + 0.001; h += 0.5) { const t = new Date(x); t.setHours(Math.floor(h), h % 1 ? 30 : 0, 0, 0); if (t > now) out.push(h); } return out; };
    const card = $(".modal-card", bk);
    function render() {
      const closeBtn = `<button class="modal-close" data-book-close aria-label="Close"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M6 6l12 12M18 6 6 18"/></svg></button>`;
      const prog = `<div class="quiz-progress">${[0, 1, 2].map((i) => `<i class="${i <= st.step ? "on" : ""}"></i>`).join("")}</div>`;
      const unitLine = st.unit ? `Apartment #${st.unit}` : st.plan && PLANS[st.plan] ? PLANS[st.plan].name : "Any floor plan";
      if (st.step === 0) {
        card.innerHTML = `${closeBtn}<p class="eyebrow">Book a tour · ${unitLine}</p><h2>How would you like to see it?</h2>${prog}
          <div class="quiz-opts">
            <button class="quiz-opt ${st.type === "guided" ? "is-on" : ""}" data-book-type="guided"><strong>Guided with our team</strong><span>Mon – Fri 9:30 – 5:30, Sat 10 – 4:30. We meet you at the office.</span></button>
            <button class="quiz-opt ${st.type === "self" ? "is-on" : ""}" data-book-type="self"><strong>Self-guided</strong><span>8 AM – 8 PM every day. Register once, get a code, let yourself in.</span></button>
          </div><div class="quiz-foot"><span class="muted">About two minutes.</span><button class="btn btn-dark" data-book-next>Pick a day</button></div>`;
      } else if (st.step === 1) {
        const ds = days(); if (st.day == null) st.day = ds.findIndex((x) => slots(x).length);
        const dayBtns = ds.map((x, i) => { const n = slots(x).length; return `<button class="day ${i === st.day ? "is-on" : ""}" data-book-day="${i}" ${n ? "" : "disabled"}><span>${x.toLocaleDateString("en-US", { weekday: "short" })}</span><strong>${x.getDate()}</strong><em>${i === 0 ? "Today" : x.toLocaleDateString("en-US", { month: "short" })}</em></button>`; }).join("");
        const ts = slots(ds[st.day] || ds[0]);
        card.innerHTML = `${closeBtn}<p class="eyebrow">Book a tour · ${st.type === "self" ? "Self-guided" : "Guided"}</p><h2>When works for you?</h2>${prog}
          <div class="days">${dayBtns}</div>
          <div class="times">${ts.length ? ts.map((h) => `<button class="time ${st.time === h ? "is-on" : ""}" data-book-time="${h}">${fmtT(h)}</button>`).join("") : `<p class="muted">Closed that day. Pick another.</p>`}</div>
          <div class="quiz-foot"><button class="btn btn-ghost" data-book-back>Back</button><button class="btn btn-dark" data-book-next ${st.time == null ? "disabled" : ""}>Your details</button></div>`;
      } else if (st.step === 2) {
        const x = days()[st.day];
        card.innerHTML = `${closeBtn}<p class="eyebrow">Book a tour · ${x.toLocaleDateString("en-US", { weekday: "long", month: "long", day: "numeric" })} at ${fmtT(st.time)}</p><h2>Almost there.</h2>${prog}
          <form class="contact-form book-form" data-book-form novalidate>
            <div class="field-row"><label>First name<input name="first" required autocomplete="given-name"></label><label>Last name<input name="last" required autocomplete="family-name"></label></div>
            <div class="field-row"><label>Phone<input name="phone" type="tel" required autocomplete="tel"></label><label>Email<input name="email" type="email" required autocomplete="email"></label></div>
            <label>Interested in<select name="plan">${st.unit ? `<option value="${st.unit}">Apartment #${st.unit}</option>` : ""}<option value="">Any floor plan</option>${Object.values(PLANS).map((p) => `<option value="${p.slug}" ${!st.unit && st.plan === p.slug ? "selected" : ""}>${p.name} · ${p.beds} bed</option>`).join("")}</select></label>
            <label class="check"><input type="checkbox" name="sms" checked> Text me a confirmation and a reminder.</label>
            <p class="form-status" data-book-status aria-live="polite"></p>
            <div class="quiz-foot"><button type="button" class="btn btn-ghost" data-book-back>Back</button><button class="btn btn-gold" type="submit">Confirm tour</button></div>
          </form>`;
      } else {
        const x = days()[st.day];
        card.innerHTML = `${closeBtn}<p class="eyebrow">You're booked</p><h2>${x.toLocaleDateString("en-US", { weekday: "long", month: "long", day: "numeric" })}, ${fmtT(st.time)}</h2>
          <p>${st.type === "self" ? "Finish your one-time Tour24 registration below and your access code will be ready for that time." : "We'll confirm by text. Meet us at the leasing office, 4358 Riverside Drive, five minutes early."}</p>
          <div class="btn-row">${st.type === "self" ? `<a class="btn btn-gold" href="https://app.tour24now.com/prospect/tour/luxe-park/register/" target="_blank" rel="noopener">Register with Tour24</a>` : ""}<button class="btn btn-ghost" data-book-close>Done</button></div>`;
      }
    }
    const open = (btn) => { st.step = 0; st.time = null; st.day = null; st.type = btn.dataset.book === "self" ? "self" : "guided"; st.plan = btn.dataset.bookPlan || ""; st.unit = btn.dataset.bookUnit || ""; render(); bk.hidden = false; document.body.style.overflow = "hidden"; };
    const close = () => { bk.hidden = true; if (!$("[data-drawer].is-open")) document.body.style.overflow = ""; };
    document.addEventListener("click", (e) => {
      const o = e.target.closest("[data-book]"); if (o) { e.preventDefault(); open(o); return; }
      if (bk.hidden) return;
      if (e.target === bk || e.target.closest("[data-book-close]")) { close(); return; }
      const t = e.target.closest("[data-book-type]"); if (t) { st.type = t.dataset.bookType; st.day = null; st.time = null; render(); return; }
      const d = e.target.closest("[data-book-day]"); if (d) { st.day = +d.dataset.bookDay; st.time = null; render(); return; }
      const tm = e.target.closest("[data-book-time]"); if (tm) { st.time = +tm.dataset.bookTime; render(); return; }
      if (e.target.closest("[data-book-next]")) { st.step++; render(); return; }
      if (e.target.closest("[data-book-back]")) { st.step--; render(); }
    });
    bk.addEventListener("submit", (e) => {
      e.preventDefault(); const f = e.target; const d = Object.fromEntries(new FormData(f).entries()); const status = $("[data-book-status]", f);
      if (!d.first || !d.last || !d.phone || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(d.email)) { status.textContent = "Please fill in your name, phone and a valid email."; status.classList.add("err"); return; }
      const x = days()[st.day]; const when = new Date(x); when.setHours(Math.floor(st.time), st.time % 1 ? 30 : 0, 0, 0);
      const payload = { ...d, tourType: st.type, when: when.toISOString(), whenLabel: `${x.toDateString()} ${fmtT(st.time)}`, unit: st.unit, source: "luxeparkga.com" };
      const endpoint = bk.dataset.endpoint;
      const done = () => { st.step = 3; render(); };
      if (endpoint) {
        fetch(endpoint, { method: "POST", headers: { "Content-Type": "application/json", Accept: "application/json" }, body: JSON.stringify(payload) }).then((r) => { if (!r.ok) throw 0; done(); }).catch(() => { status.textContent = "Couldn't send that. Call 888-546-7290 and we'll book it by phone."; status.classList.add("err"); });
      } else if (st.type === "guided") {
        const planName = PLANS[d.plan] ? PLANS[d.plan].name : (st.unit ? PLANS[(window.LP.UNITS.find((u) => u.num === st.unit) || {}).plan]?.name : "") || "";
        const url = `https://www.luxeparkga.com/scheduletour?tourType=GT${planName ? "&floorplan=" + encodeURIComponent(planName) : ""}`;
        status.classList.remove("err"); status.innerHTML = `Opening the office calendar in a new tab. Choose <strong>${x.toLocaleDateString("en-US", { weekday: "short", month: "short", day: "numeric" })} at ${fmtT(st.time)}</strong> there to lock it in.`;
        window.open(url, "_blank", "noopener"); setTimeout(done, 1500);
      } else done();
    });
    document.addEventListener("keydown", (e) => { if (!bk.hidden && e.key === "Escape") close(); });
  }

  /* Live counts anywhere on the site (home page finder) */
  if (window.LP) {
    const U = window.LP.UNITS, P = window.LP.PLANS;
    $$("[data-count-beds]").forEach((el) => { const n = +el.dataset.countBeds; const us = U.filter((u) => P[u.plan].beds === n); el.textContent = us.length ? `${us.length} available` : "waitlist"; });
    $$("[data-from-beds]").forEach((el) => { const n = +el.dataset.fromBeds; const us = U.filter((u) => P[u.plan].beds === n); const m = us.length ? Math.min(...us.map((u) => u.rent)) : Math.min(...Object.values(P).filter((p) => p.beds === n).map((p) => p.from)); el.textContent = `from $${m.toLocaleString()}`; });
    $$("[data-count-all]").forEach((el) => { el.textContent = U.length; });
    $$("[data-count-now]").forEach((el) => { el.textContent = U.filter((u) => !u.avail).length; });
  }
})();
