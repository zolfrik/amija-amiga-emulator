/*
 * AmiJa website - the Workbench.
 *
 * One screen, a column of icons and at most two windows: the one opened from
 * an icon, and a viewer on top of it for a single screenshot or video.
 * Windows cannot be moved or resized; they open centred and can be closed.
 */
"use strict";

(function () {
  const WB = window.WB;
  const REPO = "https://github.com/zolfrik/amija-amiga-emulator";
  const RELEASES_API = "https://api.github.com/repos/zolfrik/amija-amiga-emulator/releases?per_page=10";
  const FLOPPY_SOUND = false; // drive noise when an icon opens: off, it tires in the long run
  const FLOPPY_SECONDS = 2.0;
  const FLOPPY_VOLUME = 0.5; // on top of samples already at a third: -16 dB, a third as loud

  const ICONS = [
    { id: "about", label: "AmiJa about", art: "amija", title: "AmiJa", cols: 78 },
    { id: "screenshots", label: "Screenshots", art: "screenshots", title: "Screenshots", cols: 84, hidden: true },
    { id: "download", label: "Download", art: "download", title: "Download", cols: 80 },
    { id: "video", label: "Video", art: "video", title: "Video", cols: 84 },
    { id: "github", label: "GitHub", art: "github", href: REPO },
  ];

  const body = document.body;
  const root = document.documentElement;
  const desktop = document.getElementById("desktop");
  const iconList = document.getElementById("icons");
  const memoryEl = document.getElementById("memory");

  let S = 1;
  let dpr = window.devicePixelRatio || 1;
  let narrow = false;
  let busy = false;
  let selected = null;
  let lastPointer = "mouse";
  let mainWin = null;
  let viewerWin = null;
  const artCanvases = []; // re-rendered when the scale changes

  /* ------------------------------------------------------------ scale */

  function computeLayout() {
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    const newS = vw >= 1500 && vh >= 820 ? 2 : 1;
    const newNarrow = vw < 720;
    const newDpr = window.devicePixelRatio || 1;
    const changed = newS !== S || newDpr !== dpr;
    S = newS;
    dpr = newDpr;
    narrow = newNarrow;
    root.style.setProperty("--S", String(S));
    body.classList.toggle("narrow", narrow);
    return changed;
  }

  const u = () => S; // one hires column, px
  const l = () => 2 * S; // one line, px

  /* Registers a canvas so it is redrawn at the right size after a resize.
     kind "hires"  = one character is one column by one line (icons, gadgets),
     kind "sprite" = two columns by one line (the mouse pointer). */
  function art(rows, kind, map, scale) {
    const c = document.createElement("canvas");
    const entry = { c, rows, kind, map, scale: scale || 1 };
    artCanvases.push(entry);
    paint(entry);
    return c;
  }

  function paint(e) {
    const pw = (e.kind === "sprite" ? 2 * u() : u()) * e.scale;
    const ph = l() * e.scale;
    WB.drawArt(e.c, e.rows, pw, ph, e.map);
  }

  function repaintAll() {
    for (let i = artCanvases.length - 1; i >= 0; i--) {
      if (!artCanvases[i].c.isConnected) artCanvases.splice(i, 1);
      else paint(artCanvases[i]);
    }
    makePointers();
    makeStripes();
  }

  /* A gadget/icon image pair: normal and complemented (pressed/selected). */
  function artPair(rows, kind, map) {
    const off = art(rows, kind, map);
    off.classList.add("off");
    const onMap = {};
    for (const k of Object.keys(WB.COMPLEMENT)) {
      const base = map && map[k] !== undefined ? map[k] : k;
      onMap[k] = WB.COMPLEMENT[base] !== undefined ? WB.COMPLEMENT[base] : base;
    }
    const on = art(rows, kind, onMap);
    on.classList.add("on");
    return [off, on];
  }

  /* --------------------------------------------------------- pointers */

  /* The title bar fill: solid bands on the active window, dotted ones on an
     inactive window. One tile is 4 columns by 10 lines. */
  function makeStripes() {
    const tile = (bandRows) =>
      WB.artCanvas(
        ["WWWW", "WWWW", bandRows[0], bandRows[1], "WWWW", "WWWW", bandRows[0], bandRows[1], "WWWW", "WWWW"],
        u(),
        l()
      ).toDataURL("image/png");
    root.style.setProperty("--stripes-active", `url(${tile(["BBBB", "BBBB"])})`);
    root.style.setProperty("--stripes-inactive", `url(${tile(["BBWB", "WBBB"])})`);
  }

  function makePointers() {
    const px = 2 * S;
    const url = (rows, k) => WB.artCanvas(rows, px, l(), null, k).toDataURL("image/png");
    const a1 = url(WB.ART.pointer, 1);
    const a2 = url(WB.ART.pointer, 2);
    const b1 = url(WB.ART.busy, 1);
    const b2 = url(WB.ART.busy, 2);
    const bx = 10 * px;
    const by = 6 * l();
    let style = document.getElementById("pointer-style");
    if (!style) {
      style = document.createElement("style");
      style.id = "pointer-style";
      document.head.appendChild(style);
    }
    const set = (u1, u2, x, y, fb) =>
      `cursor: url(${u1}) ${x} ${y}, ${fb};` +
      `cursor: -webkit-image-set(url(${u1}) 1x, url(${u2}) 2x) ${x} ${y}, ${fb};` +
      `cursor: image-set(url(${u1}) 1x, url(${u2}) 2x) ${x} ${y}, ${fb};`;
    style.textContent =
      `html, body, body * { ${set(a1, a2, 0, 0, "default")} }\n` +
      `body.busy, body.busy * { ${set(b1, b2, bx, by, "wait").replace(/;/g, " !important;")} }\n`;
  }

  /* ----------------------------------------------------- free memory */

  const fakeMem = { base: 1048576 - 157200 - 8 * Math.floor(Math.random() * 400), used: 0 };

  function freeMemory() {
    const pm = performance.memory;
    if (pm && pm.jsHeapSizeLimit) return Math.max(0, pm.jsHeapSizeLimit - pm.usedJSHeapSize);
    return fakeMem.base - fakeMem.used;
  }

  function showMemory() {
    const n = freeMemory();
    const short = n >= 10485760 ? `${Math.floor(n / 1048576)}M free` : `${Math.floor(n / 1024)}K free`;
    memoryEl.textContent = narrow ? short : `${n} free memory`;
  }

  function driftMemory() {
    const r = Math.random();
    if (r < 0.25) fakeMem.base -= 8 * (1 + Math.floor(Math.random() * 24));
    else if (r < 0.5) fakeMem.base += 8 * (1 + Math.floor(Math.random() * 24));
    fakeMem.base = Math.min(Math.max(fakeMem.base, 860000), 900000);
    showMemory();
  }

  /* --------------------------------------------------------- floppy */

  const Floppy = {
    ctx: null,
    buffers: null,
    loading: null,
    files: {
      startup: "sounds/drive_startup_A500.wav?v=5",
      spin: "sounds/drive_spin_A500.wav?v=5",
      click: "sounds/drive_click_A500.wav?v=5",
    },

    unlock() {
      if (!this.ctx) {
        const AC = window.AudioContext || window.webkitAudioContext;
        if (AC) {
          try {
            this.ctx = new AC();
          } catch (e) {
            this.ctx = null;
          }
        }
      }
      if (this.ctx && this.ctx.state === "suspended") this.ctx.resume().catch(() => {});
      if (!this.loading) this.loading = this.load();
    },

    async load() {
      if (!this.ctx || location.protocol === "file:") return;
      try {
        const out = {};
        for (const [k, f] of Object.entries(this.files)) {
          const r = await fetch(f);
          if (!r.ok) throw new Error(f);
          const data = await r.arrayBuffer();
          out[k] = await new Promise((ok, ko) => this.ctx.decodeAudioData(data, ok, ko));
        }
        this.buffers = out;
      } catch (e) {
        this.buffers = null;
      }
    },

    clickTimes(seconds) {
      const t = [];
      for (let i = 0; i < 7; i++) t.push(0.24 + i * 0.014); // seek to track 0
      for (let x = 0.62; x < seconds - 0.2; x += 0.17 + Math.random() * 0.05) t.push(x);
      return t;
    },

    async play(seconds) {
      this.unlock();
      await Promise.race([this.loading, new Promise((r) => setTimeout(r, 350))]);
      if (this.buffers && this.ctx && this.ctx.state === "running") this.playBuffers(seconds);
      else this.playElements(seconds);
    },

    playBuffers(seconds) {
      const ctx = this.ctx;
      const b = this.buffers;
      const t0 = ctx.currentTime + 0.02;
      const master = ctx.createGain();
      master.gain.value = 0.7 * FLOPPY_VOLUME;
      master.connect(ctx.destination);

      const one = (buf, at, gain) => {
        const s = ctx.createBufferSource();
        const g = ctx.createGain();
        g.gain.value = gain;
        s.buffer = buf;
        s.connect(g).connect(master);
        s.start(at);
        return s;
      };

      one(b.startup, t0, 0.9);
      const spin = ctx.createBufferSource();
      const sg = ctx.createGain();
      spin.buffer = b.spin;
      spin.loop = true;
      sg.gain.setValueAtTime(0, t0);
      sg.gain.linearRampToValueAtTime(0.8, t0 + 0.35);
      sg.gain.setValueAtTime(0.8, t0 + seconds - 0.3);
      sg.gain.linearRampToValueAtTime(0, t0 + seconds);
      spin.connect(sg).connect(master);
      spin.start(t0 + 0.05);
      spin.stop(t0 + seconds + 0.05);
      for (const t of this.clickTimes(seconds)) one(b.click, t0 + t, 0.75 + Math.random() * 0.25);
    },

    playElements(seconds) {
      const mk = (f, vol) => {
        const a = new Audio(f);
        a.volume = vol * FLOPPY_VOLUME;
        return a;
      };
      const ignore = () => {};
      mk(this.files.startup, 0.6).play().catch(ignore);
      const spin = mk(this.files.spin, 0.5);
      spin.loop = true;
      spin.play().catch(ignore);
      setTimeout(() => spin.pause(), seconds * 1000);
      for (const t of this.clickTimes(seconds)) {
        setTimeout(() => mk(this.files.click, 0.6).play().catch(ignore), t * 1000);
      }
    },
  };

  /* ---------------------------------------------------------- icons */

  function buildIcons() {
    for (const def of ICONS) {
      if (def.hidden) continue; // window kept, icon not shown for now
      const li = document.createElement("li");
      const btn = document.createElement("button");
      btn.className = "icon";
      btn.type = "button";
      btn.dataset.id = def.id;
      btn.setAttribute("aria-label", def.href ? `${def.label} (opens in a new tab)` : `Open ${def.label}`);
      const img = document.createElement("span");
      img.className = "image";
      img.append(...artPair(WB.ART[def.art], "hires"));
      const label = document.createElement("span");
      label.className = "label";
      label.textContent = def.label;
      btn.append(img, label);

      btn.addEventListener("pointerdown", (e) => {
        lastPointer = e.pointerType || "mouse";
        if (FLOPPY_SOUND) Floppy.unlock();
      });
      btn.addEventListener("click", (e) => {
        e.stopPropagation();
        if (e.detail === 0 || lastPointer !== "mouse") activate(def);
        else select(def.id);
      });
      btn.addEventListener("dblclick", (e) => {
        e.stopPropagation();
        if (lastPointer === "mouse") activate(def);
      });
      li.appendChild(btn);
      iconList.appendChild(li);
    }
  }

  function select(id) {
    selected = id;
    for (const b of iconList.querySelectorAll(".icon")) {
      b.classList.toggle("selected", b.dataset.id === id);
    }
  }

  function setBusy(on) {
    busy = on;
    body.classList.toggle("busy", on);
    body.setAttribute("aria-busy", on ? "true" : "false");
  }

  /* The window opens at once; the drive noise and the busy pointer only
     accompany it, they do not hold anything up. */
  let busyTimer = 0;
  function activate(def) {
    select(def.id);
    const seconds = def.href ? 0.9 : FLOPPY_SECONDS;
    if (FLOPPY_SOUND && !busy) Floppy.play(seconds);
    setBusy(true);
    clearTimeout(busyTimer);
    busyTimer = setTimeout(() => setBusy(false), seconds * 1000);
    if (def.href) window.open(def.href, "_blank", "noopener");
    else openWindow(def.id);
  }

  /* -------------------------------------------------------- windows */

  function gadget(rows, label, onClick) {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "gadget";
    b.tabIndex = onClick ? 0 : -1;
    if (label) b.setAttribute("aria-label", label);
    else b.setAttribute("aria-hidden", "true");
    b.append(...artPair(rows, "hires"));
    const press = (on) => b.classList.toggle("pressed", on);
    b.addEventListener("pointerdown", (e) => {
      press(true);
      b.setPointerCapture && b.setPointerCapture(e.pointerId);
    });
    b.addEventListener("pointerup", () => press(false));
    b.addEventListener("pointercancel", () => press(false));
    b.addEventListener("lostpointercapture", () => press(false));
    if (onClick) b.addEventListener("click", onClick);
    return b;
  }

  function createWindow(title, opts) {
    const el = document.createElement("section");
    el.className = "win active";
    el.setAttribute("role", "dialog");
    el.setAttribute("aria-label", title);

    const bar = document.createElement("div");
    bar.className = "win-title";
    const name = document.createElement("h2");
    name.className = "name";
    name.textContent = title;
    const drag = document.createElement("span");
    drag.className = "drag";
    const win = { el, opts, closeHandlers: [] };
    const close = gadget(WB.ART.close, "Close window", () => closeWin(win));
    const span = (cls) => {
      const e = document.createElement("span");
      e.className = cls;
      return e;
    };
    bar.append(
      span("edge-l"),
      span("gap"),
      close,
      span("gap"),
      name,
      drag,
      span("gap"),
      gadget(WB.ART.depthFront),
      span("gap"),
      gadget(WB.ART.depthBack),
      span("gap"),
      span("edge")
    );

    const left = document.createElement("div");
    left.className = "win-left";
    const bodyEl = document.createElement("div");
    bodyEl.className = "win-body";
    const content = document.createElement("div");
    content.className = "win-content";
    content.tabIndex = 0;
    bodyEl.appendChild(content);

    const right = document.createElement("div");
    right.className = "win-right";
    const vprop = document.createElement("div");
    vprop.className = "prop v";
    const vknob = document.createElement("div");
    vknob.className = "knob";
    vprop.appendChild(vknob);
    const line = () => 8 * l() * 2;
    right.append(
      repeatGadget(WB.ART.up, () => content.scrollBy(0, -line())),
      vprop,
      repeatGadget(WB.ART.down, () => content.scrollBy(0, line()))
    );

    const bottom = document.createElement("div");
    bottom.className = "win-bottom";
    const hprop = document.createElement("div");
    hprop.className = "prop h";
    const hknob = document.createElement("div");
    hknob.className = "knob";
    hprop.appendChild(hknob);
    bottom.append(
      gadget(WB.ART.left, opts.onLeft ? "Previous" : null, opts.onLeft || null),
      hprop,
      gadget(WB.ART.right, opts.onRight ? "Next" : null, opts.onRight || null)
    );
    const size = document.createElement("div");
    size.className = "win-size";
    size.append(gadget(WB.ART.size));

    el.append(bar, left, bodyEl, right, bottom, size);
    Object.assign(win, { content, vprop, vknob, name });
    bindScroller(win);
    return win;
  }

  /* An arrow gadget that keeps scrolling while held down. */
  function repeatGadget(rows, step) {
    const b = gadget(rows, null, null);
    b.tabIndex = -1;
    let timer = null;
    const stop = () => {
      clearTimeout(timer);
      clearInterval(timer);
      timer = null;
    };
    b.addEventListener("pointerdown", (e) => {
      e.preventDefault();
      step();
      stop();
      timer = setTimeout(() => (timer = setInterval(step, 50)), 300);
    });
    b.addEventListener("pointerup", stop);
    b.addEventListener("pointercancel", stop);
    b.addEventListener("lostpointercapture", stop);
    return b;
  }

  /* Keeps the vertical proportional gadget in step with the content. */
  function bindScroller(win) {
    const c = win.content;
    const track = win.vprop;
    const knob = win.vknob;
    const pad = () => l();

    function geometry() {
      const room = track.clientHeight - 2 * pad();
      const ratio = c.scrollHeight > 0 ? Math.min(1, c.clientHeight / c.scrollHeight) : 1;
      const kh = Math.max(Math.round(room * ratio), 6 * l());
      return { room, kh: Math.min(kh, room), max: c.scrollHeight - c.clientHeight };
    }

    function update() {
      const g = geometry();
      const top = g.max > 0 ? ((g.room - g.kh) * c.scrollTop) / g.max : 0;
      knob.style.height = g.kh + "px";
      knob.style.top = pad() + Math.round(top) + "px";
    }
    win.update = update;

    c.addEventListener("scroll", update, { passive: true });
    if (window.ResizeObserver) {
      const ro = new ResizeObserver(update);
      ro.observe(c);
      ro.observe(track);
      win.closeHandlers.push(() => ro.disconnect());
    }
    if (window.MutationObserver) {
      const mo = new MutationObserver(update);
      mo.observe(c, { childList: true, subtree: true });
      win.closeHandlers.push(() => mo.disconnect());
    }
    c.addEventListener("load", update, true);

    let drag = null;
    knob.addEventListener("pointerdown", (e) => {
      e.preventDefault();
      e.stopPropagation();
      knob.setPointerCapture(e.pointerId);
      drag = { y: e.clientY, top: c.scrollTop };
      knob.classList.add("drag");
    });
    knob.addEventListener("pointermove", (e) => {
      if (!drag) return;
      const g = geometry();
      if (g.room <= g.kh) return;
      c.scrollTop = drag.top + ((e.clientY - drag.y) * g.max) / (g.room - g.kh);
    });
    const end = () => {
      drag = null;
      knob.classList.remove("drag");
    };
    knob.addEventListener("pointerup", end);
    knob.addEventListener("pointercancel", end);
    track.addEventListener("pointerdown", (e) => {
      if (e.target !== track) return;
      const r = knob.getBoundingClientRect();
      c.scrollBy(0, (e.clientY < r.top ? -1 : 1) * c.clientHeight * 0.9);
    });
  }

  function closeWin(win) {
    if (!win) return;
    win.closeHandlers.forEach((f) => f());
    win.el.remove();
    if (win === viewerWin) {
      viewerWin = null;
      if (mainWin) {
        mainWin.el.classList.add("active");
        if (mainWin.returnFocus) mainWin.returnFocus.focus({ preventScroll: true });
      }
      return;
    }
    if (win === mainWin) {
      mainWin = null;
      closeWin(viewerWin);
      fakeMem.used = 0;
      showMemory();
      if (location.hash) history.replaceState(null, "", location.pathname + location.search);
    }
  }

  /* -------------------------------------------------------- placement */

  function desktopRect() {
    const top = 10 * l();
    return { top, w: window.innerWidth, h: window.innerHeight - top };
  }

  function placeMain(win) {
    const d = desktopRect();
    const margin = 8 * l();
    const chromeW = 18 * u() + 24 * u();
    let x, y, w, h;
    if (narrow) {
      const ib = iconList.getBoundingClientRect();
      y = Math.round(ib.bottom - d.top + 2 * l());
      x = 0;
      w = d.w;
      h = d.h - y;
    } else {
      const ir = iconList.getBoundingClientRect();
      const reserve = d.w - ir.left + 8 * u();
      // As wide as the room between the two icon-column gutters allows, up to
      // 120 columns of text so lines stay readable.
      const want = win.opts.cols * 8 * u() + chromeW;
      const room = d.w - 2 * reserve;
      if (want <= room) {
        w = Math.min(room, 120 * 8 * u() + chromeW);
        x = Math.round((d.w - w) / 2);
      } else {
        w = Math.min(want, ir.left - 16 * u());
        x = Math.max(0, Math.round((ir.left - 8 * u() - w) / 2));
      }
      h = Math.min(d.h - 2 * margin, Math.round(win.opts.maxLines ? win.opts.maxLines * 16 * S : Infinity));
      y = Math.round((d.h - h) / 2);
    }
    setRect(win, x, y, w, h);
  }

  /* Sizes a viewer around content of a given aspect ratio. */
  function placeViewer(win, aspectW, aspectH, natW) {
    const d = desktopRect();
    const margin = narrow ? 0 : 6 * l();
    const chromeW = 18 * u() + 12 * u();
    const chromeH = 10 * l() + 9 * l() + l() + 6 * l();
    const footH = 16 * S + 4 * l() + 4 * l();
    const availW = d.w - 2 * margin - chromeW;
    const availH = d.h - 2 * margin - chromeH - footH;
    let iw = availW;
    let ih = (iw * aspectH) / aspectW;
    if (ih > availH) {
      ih = availH;
      iw = (ih * aspectW) / aspectH;
    }
    if (natW && iw > natW) {
      // Enlarge by whole multiples only, so pixels stay square and sharp.
      const k = Math.max(1, Math.floor(iw / natW));
      iw = natW * k;
      ih = (iw * aspectH) / aspectW;
    }
    iw = Math.floor(iw);
    ih = Math.floor(ih);
    const w = narrow ? d.w : Math.max(iw + chromeW, 40 * 8 * u());
    const h = narrow ? d.h : ih + chromeH + footH;
    setRect(win, Math.round((d.w - w) / 2), Math.round((d.h - h) / 2), w, h);
    return { iw, ih };
  }

  function setRect(win, x, y, w, h) {
    Object.assign(win.el.style, { left: x + "px", top: y + "px", width: w + "px", height: h + "px" });
  }

  function relayout() {
    if (mainWin) placeMain(mainWin);
    if (viewerWin && viewerWin.relayout) viewerWin.relayout();
    if (mainWin && mainWin.update) mainWin.update();
  }

  /* ---------------------------------------------------------- content */

  const releases = { data: null, error: null, promise: null };

  function loadReleases() {
    if (!releases.promise) {
      releases.promise = fetch(RELEASES_API, { headers: { Accept: "application/vnd.github+json" } })
        .then((r) => {
          if (!r.ok) throw new Error("HTTP " + r.status);
          return r.json();
        })
        .then((d) => (releases.data = d.filter((x) => !x.draft)))
        .catch((e) => {
          releases.error = e;
          releases.promise = null;
        });
    }
    return releases.promise;
  }

  /* Starts any network work while the drive is still grinding. */
  function prepare(id) {
    if (id === "download") loadReleases();
    return null;
  }

  function fromTemplate(id) {
    return document.getElementById("tpl-" + id).content.cloneNode(true);
  }

  function openWindow(id) {
    const def = ICONS.find((d) => d.id === id);
    if (!def || def.href) return;
    closeWin(viewerWin);
    closeWin(mainWin);
    const win = createWindow(def.title, { cols: def.cols });
    mainWin = win;
    fill[id](win.content);
    desktop.appendChild(win.el);
    placeMain(win);
    win.update();
    fakeMem.used = 20480 + 8 * Math.floor(Math.random() * 3000);
    showMemory();
    const hash = "#" + id;
    if (location.hash !== hash) history.replaceState(null, "", hash);
    win.content.focus({ preventScroll: true });
  }

  const fill = {
    about(c) {
      c.appendChild(fromTemplate("about"));
      wireOpenLinks(c);
    },

    screenshots(c) {
      const list = window.AMIJA_SCREENSHOTS || [];
      if (!list.length) {
        c.appendChild(fromTemplate("no-screenshots"));
        return;
      }
      const intro = document.createElement("p");
      intro.textContent = `${list.length} screenshot${list.length > 1 ? "s" : ""}. Click one to enlarge it.`;
      const ul = document.createElement("ul");
      ul.className = "grid";
      list.forEach((s, i) => {
        const li = document.createElement("li");
        const b = document.createElement("button");
        b.type = "button";
        b.className = "thumb";
        b.innerHTML = `<span class="frame"><img loading="lazy" decoding="async" alt=""></span><span class="caption"></span>`;
        b.querySelector("img").src = "screenshots/" + encodeURI(s.file);
        b.querySelector(".caption").textContent = s.title || s.file;
        b.addEventListener("click", () => openScreenshot(i, b));
        li.appendChild(b);
        ul.appendChild(li);
      });
      c.append(intro, ul);
    },

    video(c) {
      const list = window.AMIJA_VIDEOS || [];
      const intro = document.createElement("p");
      intro.innerHTML =
        `AmiJa running real software, recorded from the emulator. Click a video to play it, ` +
        `or <a target="_blank" rel="noopener">watch the whole playlist on YouTube</a>.`;
      intro.querySelector("a").href = "https://www.youtube.com/playlist?list=" + (window.AMIJA_PLAYLIST || "");
      const ul = document.createElement("ul");
      ul.className = "grid";
      list.forEach((v) => {
        const li = document.createElement("li");
        const b = document.createElement("button");
        b.type = "button";
        b.className = "thumb video";
        b.innerHTML =
          `<span class="frame"><img loading="lazy" decoding="async" alt=""><span class="duration"></span></span>` +
          `<span class="caption"></span>`;
        b.querySelector("img").src = `https://i.ytimg.com/vi/${encodeURIComponent(v.id)}/mqdefault.jpg`;
        b.querySelector(".duration").textContent = v.duration || "";
        b.querySelector(".caption").textContent = shortTitle(v.title);
        b.addEventListener("click", () => openVideo(v, b));
        li.appendChild(b);
        ul.appendChild(li);
      });
      c.append(intro, ul);
    },

    download(c) {
      c.appendChild(fromTemplate("download"));
      wireOpenLinks(c);
      const box = c.querySelector("#releases");
      const render = () => {
        box.textContent = "";
        if (releases.data) renderReleases(box, releases.data);
        else if (releases.error) box.appendChild(fromTemplate("no-releases"));
        else {
          const p = document.createElement("p");
          p.innerHTML = `Reading the release list<span class="blink">_</span>`;
          box.appendChild(p);
          loadReleases().then(() => box.isConnected && render());
        }
      };
      render();
    },
  };

  function shortTitle(t) {
    return String(t || "").replace(/^AmiJa\s*-\s*Java Amiga emulator\s*/i, "").replace(/^\[[^\]]*\]\s*-\s*/, "");
  }

  function wireOpenLinks(c) {
    for (const a of c.querySelectorAll("[data-open]")) {
      a.addEventListener("click", (e) => {
        e.preventDefault();
        const def = ICONS.find((d) => d.id === a.dataset.open);
        if (def) activate(def);
      });
    }
  }

  function esc(s) {
    return String(s).replace(/[&<>"']/g, (ch) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[ch]);
  }

  /* Release notes are Markdown: headings, lists, tables and paragraphs are
     kept, inline code/bold/italic/links too; anything else stays as text. */
  /* `assets` maps a file name of the release to its asset: a file named in
     backticks becomes its download link. */
  function inlineMd(t, assets) {
    let h = esc(t.replace(/—|–/g, "-").replace(/→/g, "->"));
    h = h.replace(/`([^`]+)`/g, (m, name) => {
      const a = assets && assets.get(name.replace(/&amp;/g, "&"));
      return a ? `<a href="${esc(a.browser_download_url)}" rel="noopener"><code>${name}</code></a>` : `<code>${name}</code>`;
    });
    h = h.replace(/\*\*([^*]+)\*\*/g, "<b>$1</b>");
    h = h.replace(/(^|[\s(])[*_]([^*_]+)[*_](?=[\s).,;:!?]|$)/g, "$1<i>$2</i>");
    h = h.replace(/\[([^\]]+)\]\((https?:[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');
    return h;
  }

  /* Release notes are Markdown: headings, lists, tables and paragraphs are
     kept. A table that names release files gets a Size column. */
  function notesHtml(md, assets) {
    const md2 = (t) => inlineMd(t, assets);
    const blocks = String(md || "").replace(/\r\n?/g, "\n").split(/\n\s*\n/);
    let out = "";
    for (const block of blocks) {
      const lines = block.split("\n").map((x) => x.trim()).filter(Boolean);
      if (!lines.length) continue;
      const head = /^(#{1,6})\s+(.*)$/.exec(lines[0]);
      if (head && lines.length === 1) {
        out += `<h4>${md2(head[2])}</h4>`;
      } else if (lines.every((x) => x.startsWith("|"))) {
        const rows = lines
          .filter((x) => !/^\|[\s:|-]+\|?$/.test(x))
          .map((x) => x.replace(/^\||\|$/g, "").split("|").map((c) => c.trim()));
        const fileOf = (r) => {
          for (const c of r) {
            const m = /`([^`]+)`/.exec(c);
            if (m && assets.has(m[1])) return assets.get(m[1]);
          }
          return null;
        };
        const sized = rows.slice(1).some(fileOf);
        out += '<div class="table"><table>';
        rows.forEach((r, i) => {
          const tag = i === 0 ? "th" : "td";
          let cells = r.map((c) => `<${tag}>${md2(c)}</${tag}>`).join("");
          if (sized) {
            const f = i === 0 ? null : fileOf(r);
            cells += i === 0 ? "<th>Size</th>" : `<td>${f ? fmtSize(f.size) : ""}</td>`;
          }
          out += `<tr>${cells}</tr>`;
        });
        out += "</table></div>";
      } else if (lines.every((x) => /^[-*+]\s+/.test(x))) {
        out += "<ul>" + lines.map((x) => `<li>${md2(x.replace(/^[-*+]\s+/, ""))}</li>`).join("") + "</ul>";
      } else {
        out += `<p>${md2(lines.join(" "))}</p>`;
      }
    }
    return out;
  }

  function fmtSize(n) {
    if (n >= 1048576) return (n / 1048576).toFixed(1) + " MB";
    return Math.max(1, Math.round(n / 1024)) + " KB";
  }

  function renderReleases(box, list) {
    if (!list.length) {
      box.appendChild(fromTemplate("no-releases"));
      return;
    }
    list.forEach((r, i) => {
      const sec = document.createElement("section");
      sec.className = "release";
      const date = (r.published_at || r.created_at || "").slice(0, 10);
      const flag = i === 0 && !r.prerelease ? " (latest)" : r.prerelease ? " (pre-release)" : "";
      sec.innerHTML =
        `<h3><span class="tag">${esc(r.tag_name)}</span> ${esc(r.name && r.name !== r.tag_name ? r.name : "")}${flag}</h3>` +
        `<p>Released ${esc(date)}</p>`;
      const assets = new Map((r.assets || []).map((x) => [x.name, x]));
      if (r.body) {
        const notes = document.createElement("div");
        notes.className = "notes";
        notes.innerHTML = notesHtml(r.body, assets);
        sec.appendChild(notes);
      }
      box.appendChild(sec);
    });
  }

  /* ---------------------------------------------------------- viewers */

  function openViewer(title, opts) {
    closeWin(viewerWin);
    const win = createWindow(title, opts);
    win.content.classList.add("fill");
    viewerWin = win;
    if (mainWin) {
      mainWin.el.classList.remove("active");
      mainWin.returnFocus = opts.returnFocus || null;
    }
    win.el.style.zIndex = 6;
    return win;
  }

  function openScreenshot(index, from) {
    const list = window.AMIJA_SCREENSHOTS || [];
    const s = list[index];
    if (!s) return;
    const step = (d) => () => openScreenshot((index + d + list.length) % list.length, from);
    const multi = list.length > 1;
    const win = openViewer(s.title || s.file, {
      returnFocus: from,
      onLeft: multi ? step(-1) : null,
      onRight: multi ? step(1) : null,
    });
    win.content.innerHTML =
      `<div class="viewer"><div class="stage"><img alt=""></div>` +
      `<div class="buttons"><button type="button" class="wb-button prev">&lt; Prev</button>` +
      `<span class="caption"></span>` +
      `<button type="button" class="wb-button next">Next &gt;</button></div></div>`;
    const img = win.content.querySelector("img");
    img.alt = s.title || s.file;
    win.content.querySelector(".caption").textContent = `${index + 1} / ${list.length}`;
    const prev = win.content.querySelector(".prev");
    const next = win.content.querySelector(".next");
    prev.disabled = next.disabled = !multi;
    prev.addEventListener("click", step(-1));
    next.addEventListener("click", step(1));
    win.keys = { ArrowLeft: multi && step(-1), ArrowRight: multi && step(1) };

    const fit = () => {
      const nw = img.naturalWidth || 640;
      const nh = img.naturalHeight || 512;
      const { iw, ih } = placeViewer(win, nw, nh, nw);
      img.style.width = iw + "px";
      img.style.height = ih + "px";
      img.classList.toggle("pixelated", iw >= nw);
    };
    win.relayout = fit;
    desktop.appendChild(win.el);
    placeViewer(win, 4, 3);
    img.addEventListener("load", fit);
    img.src = "screenshots/" + encodeURI(s.file);
    if (img.complete && img.naturalWidth) fit();
    next.focus({ preventScroll: true });
  }

  function openVideo(v, from) {
    const win = openViewer(shortTitle(v.title), { returnFocus: from });
    const watch = `https://www.youtube.com/watch?v=${encodeURIComponent(v.id)}&list=${encodeURIComponent(window.AMIJA_PLAYLIST || "")}`;
    // YouTube refuses embedded playback (error 153) when the page sends no
    // referrer, which is the case for a page opened straight from disk.
    const embeddable = location.protocol === "http:" || location.protocol === "https:";
    if (embeddable) {
      win.content.innerHTML =
        `<div class="viewer"><div class="stage"><iframe allow="autoplay; encrypted-media; picture-in-picture; fullscreen" ` +
        `allowfullscreen referrerpolicy="strict-origin-when-cross-origin"></iframe></div>` +
        `<p class="caption"><a target="_blank" rel="noopener">Watch on YouTube</a></p></div>`;
      const frame = win.content.querySelector("iframe");
      frame.title = v.title;
      frame.src =
        `https://www.youtube-nocookie.com/embed/${encodeURIComponent(v.id)}` +
        `?autoplay=1&rel=0&playsinline=1&origin=${encodeURIComponent(location.origin)}`;
    } else {
      win.content.innerHTML =
        `<div class="viewer"><div class="stage"><a class="offline" target="_blank" rel="noopener">` +
        `<img alt=""><span class="note">This page was opened from disk, and YouTube only plays ` +
        `embedded videos on a page served by a web server. Click to watch it on YouTube.</span></a></div>` +
        `<p class="caption"><a target="_blank" rel="noopener">Watch on YouTube</a></p></div>`;
      const link = win.content.querySelector(".offline");
      link.href = watch;
      link.querySelector("img").src = `https://i.ytimg.com/vi/${encodeURIComponent(v.id)}/hqdefault.jpg`;
    }
    win.content.querySelector(".caption a").href = watch;
    const fit = () => {
      const { iw, ih } = placeViewer(win, 16, 9);
      const stage = win.content.querySelector(".stage");
      stage.style.width = iw + "px";
      stage.style.height = ih + "px";
      stage.style.flex = "0 0 auto";
    };
    win.relayout = fit;
    desktop.appendChild(win.el);
    fit();
  }

  /* -------------------------------------------------------- start up */

  function screenGadgets() {
    const g = document.getElementById("screen-gadgets");
    const span = (cls) => {
      const e = document.createElement("span");
      e.className = cls;
      return e;
    };
    g.append(
      span("gap"),
      gadget(WB.ART.depthFront, null, null),
      span("gap"),
      gadget(WB.ART.depthBack, null, null),
      span("gap"),
      span("edge")
    );
  }

  function init() {
    computeLayout();
    makePointers();
    makeStripes();
    buildIcons();
    screenGadgets();
    showMemory();
    setInterval(driftMemory, 1500);

    desktop.addEventListener("pointerdown", (e) => {
      if (e.target === desktop || e.target === iconList) select(null);
    });
    if (FLOPPY_SOUND) document.addEventListener("pointerdown", () => Floppy.unlock(), { once: true, capture: true });

    document.addEventListener("keydown", (e) => {
      if (e.key === "Escape") {
        if (viewerWin) closeWin(viewerWin);
        else if (mainWin) closeWin(mainWin);
        return;
      }
      if (viewerWin && viewerWin.keys && viewerWin.keys[e.key]) {
        e.preventDefault();
        viewerWin.keys[e.key]();
      }
    });

    let pending = 0;
    window.addEventListener("resize", () => {
      cancelAnimationFrame(pending);
      pending = requestAnimationFrame(() => {
        const wasNarrow = narrow;
        if (computeLayout()) repaintAll();
        if (wasNarrow !== narrow) showMemory();
        relayout();
      });
    });

    const start = (location.hash || "").slice(1);
    const first = ICONS.find((d) => d.id === start && !d.href) ? start : "about";
    const go = () => openWindow(first);
    if (document.fonts && document.fonts.load) {
      document.fonts.load(`${16 * S}px Topaz`).then(go, go);
    } else go();
  }

  init();
})();
