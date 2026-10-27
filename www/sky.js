/* sky.js — آسمان‌نمای قطب‌نما
 * نقشهٔ زندهٔ آسمان: ستاره‌ها، صورت‌های فلکی، سیارات، خورشید، ماه، راه شیری، قبله.
 * دو حالت: «نقشه» (کشیدن با انگشت) و «رو به آسمان» (با حسگر جهت گوشی).
 */
(function () {
  'use strict';
  const C = window.SkyCore, DATA = window.SKY_DATA;
  const D2R = Math.PI / 180, R2D = 180 / Math.PI;
  const $ = id => document.getElementById(id);
  const clamp = (x, a, b) => Math.min(b, Math.max(a, x));
  const lerp = (a, b, t) => a + (b - a) * t;
  const norm = v => { const l = Math.hypot(v[0], v[1], v[2]) || 1; return [v[0] / l, v[1] / l, v[2] / l]; };
  const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
  const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];

  /* ---------- فارسی‌سازی ---------- */
  const FD = '۰۱۲۳۴۵۶۷۸۹';
  const fa = s => String(s).replace(/\d/g, d => FD[d]).replace(/\./g, '٫').replace(/-/g, '−');
  const DIRS = ['شمال', 'شمال‌شرق', 'شرق', 'جنوب‌شرق', 'جنوب', 'جنوب‌غرب', 'غرب', 'شمال‌غرب'];
  const dir8 = az => DIRS[Math.round(C.rev(az) / 45) % 8];
  const normText = s => String(s).toLowerCase().replace(/ي/g, 'ی').replace(/ك/g, 'ک').replace(/[\u200c\u064b-\u065f]/g, '').replace(/[ةۀ]/g, 'ه').replace(/ؤ/g, 'و').replace(/[أإآ]/g, 'ا').trim();

  let fmtDate, fmtTime;
  try { fmtDate = new Intl.DateTimeFormat('fa-IR-u-ca-persian', { year: 'numeric', month: 'long', day: 'numeric' }); } catch (e) { fmtDate = new Intl.DateTimeFormat('fa-IR', { dateStyle: 'medium' }); }
  try { fmtTime = new Intl.DateTimeFormat('fa-IR', { hour: '2-digit', minute: '2-digit', hour12: false }); } catch (e) { fmtTime = { format: d => d.toTimeString().slice(0, 5) }; }

  /* ---------- تنظیمات ---------- */
  const DEFAULTS = { lines: true, names: true, starNames: true, grid: false, eqEcl: true, dso: true, milky: true, qibla: true, dayLight: true, red: false };
  const store = {
    get(k) { try { return JSON.parse(localStorage.getItem(k)); } catch (e) { return null; } },
    set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* حافظه غیرفعال */ } }
  };
  const S = Object.assign({}, DEFAULTS, store.get('sky.settings') || {});
  // موقعیت (pos) و انحراف مغناطیسی (decl) با برنامهٔ قطب‌نما مشترک است؛ تغییر در یکی در دیگری هم اعمال می‌شود
  const numOf = v => { const n = Number(v); return v !== null && v !== '' && isFinite(n) ? n : null; };
  const sp = store.get('pos');
  S.posKnown = !!(sp && numOf(sp.lat) !== null && numOf(sp.lon) !== null && Math.abs(sp.lat) <= 90 && Math.abs(sp.lon) <= 180);
  S.lat = S.posKnown ? Number(sp.lat) : 35.6892;
  S.lon = S.posKnown ? Number(sp.lon) : 51.3890;
  const sd = numOf(store.get('decl'));
  S.decl = sd === null ? 0 : clamp(sd, -40, 40);
  const save = () => { const o = {}; Object.keys(DEFAULTS).forEach(k => { o[k] = S[k]; }); store.set('sky.settings', o); };
  const savePos = () => { S.posKnown = true; store.set('pos', { lat: S.lat, lon: S.lon }); };
  const saveDecl = () => store.set('decl', S.decl);

  /* ---------- اجرام ---------- */
  const parseRA = s => { const p = s.trim().split(/\s+/).map(Number); return (p[0] + p[1] / 60) * 15; };
  const parseDec = s => { const t = s.trim(), sg = t[0] === '-' ? -1 : 1; const p = t.replace(/^[+-]/, '').split(/\s+/).map(Number); return sg * (p[0] + p[1] / 60); };

  const stars = DATA.stars.map(a => ({ type: 'star', name: a[0], ra0: parseRA(a[1]), dec0: parseDec(a[2]), mag: a[3], con: a[4], fa: a[5] || '', ci: a[6] == null ? 0.2 : a[6], v: [0, 0, 1] }));
  const byName = new Map(stars.map(s => [s.name, s]));
  const conFa = k => (DATA.constellations[k] && DATA.constellations[k].fa) || DATA.conNames[k] || '';
  const dsos = DATA.dso.map(a => ({ type: 'dso', name: a[0], fa: a[1], ra0: parseRA(a[2]), dec0: parseDec(a[3]), mag: a[4], kind: a[5], size: a[6], v: [0, 0, 1] }));
  const cons = Object.keys(DATA.constellations).map(k => {
    const c = DATA.constellations[k];
    const ids = new Set(); c.lines.forEach(ch => ch.forEach(n => ids.add(n)));
    return { key: k, type: 'con', name: c.en, fa: c.fa, zodiac: !!c.zodiac, members: [...ids].map(n => byName.get(n)), lines: c.lines.map(ch => ch.map(n => byName.get(n))), v: [0, 0, 1] };
  });
  const SOLAR = {
    sun: { type: 'sun', name: 'Sun', fa: 'خورشید', v: [0, 0, 1], mag: -26.7 },
    moon: { type: 'moon', name: 'Moon', fa: 'ماه', v: [0, 0, 1], mag: -10 },
    mercury: { type: 'planet', id: 'mercury', name: 'Mercury', fa: 'عطارد', v: [0, 0, 1], mag: 0, color: '#c9c2b8' },
    venus: { type: 'planet', id: 'venus', name: 'Venus', fa: 'زهره (ناهید)', v: [0, 0, 1], mag: -4, color: '#fff6dc' },
    mars: { type: 'planet', id: 'mars', name: 'Mars', fa: 'مریخ (بهرام)', v: [0, 0, 1], mag: 1, color: '#ff8a5c' },
    jupiter: { type: 'planet', id: 'jupiter', name: 'Jupiter', fa: 'مشتری (برجیس)', v: [0, 0, 1], mag: -2, color: '#f4e3c3' },
    saturn: { type: 'planet', id: 'saturn', name: 'Saturn', fa: 'زحل (کیوان)', v: [0, 0, 1], mag: 0.5, color: '#e8d49a' }
  };
  const planets = [SOLAR.mercury, SOLAR.venus, SOLAR.mars, SOLAR.jupiter, SOLAR.saturn];
  const DSO_KIND = { open: 'خوشهٔ باز', galaxy: 'کهکشان', nebula: 'سحابی', globular: 'خوشهٔ کروی', center: 'مرکز کهکشان' };

  // صفحهٔ راه شیری (b=0) و محدودهٔ آن
  const MW = [];
  for (let l = 0; l < 360; l += 3) { const e = C.galToEq(l, 0); const dl = Math.min(l, 360 - l); MW.push({ l, ra: e[0], dec: e[1], w: 0.3 + 0.7 * Math.exp(-Math.pow(dl / 65, 2)), v: [0, 0, 1] }); }

  /* ---------- حالت برنامه ---------- */
  const view = { az: 180, alt: 38, fov: 85 };
  let mode = 'manual';
  const T = { ms: Date.now(), live: true, rate: 1, play: false };
  const W = { ms: 0, sunAlt: -30, moonFrac: 0, qibla: C.qibla(S.lat, S.lon) };
  let selected = null, target = null, hits = [], dirty = true, cam = null;
  const sens = { f: null, u: null, abs: false, rel: false, iosOff: null, active: false };

  /* ---------- بوم ---------- */
  const canvas = $('sky'), ctx = canvas.getContext('2d');
  let CW = 0, CH = 0, DPR = 1;
  function resize() {
    DPR = Math.min(window.devicePixelRatio || 1, 3);
    CW = window.innerWidth; CH = window.innerHeight;
    canvas.width = Math.round(CW * DPR); canvas.height = Math.round(CH * DPR);
    dirty = true;
  }

  /* ---------- محاسبهٔ دنیا (هر چند ثانیه یا با تغییر زمان) ---------- */
  function recompute() {
    const jd = C.jdFromMs(T.ms), d = C.dayNum(jd), lst = C.rev(C.gmst(jd) + S.lon);
    W.ms = T.ms; W.jd = jd; W.lst = lst;
    for (const s of stars) { const p = C.precess(s.ra0, s.dec0, jd); s.ra = p[0]; s.dec = p[1]; s.v = C.eqToEnu(p[0], p[1], lst, S.lat); }
    for (const o of dsos) { const p = C.precess(o.ra0, o.dec0, jd); o.ra = p[0]; o.dec = p[1]; o.v = C.eqToEnu(p[0], p[1], lst, S.lat); }
    for (const m of MW) m.v = C.eqToEnu(m.ra, m.dec, lst, S.lat);
    const sn = C.sun(d), mn = C.moon(d, sn);
    W.sun = sn; W.moon = mn;
    Object.assign(SOLAR.sun, { ra: sn.ra, dec: sn.dec, dist: sn.dist });
    SOLAR.sun.v = C.eqToEnu(sn.ra, sn.dec, lst, S.lat);
    Object.assign(SOLAR.moon, { ra: mn.ra, dec: mn.dec, dist: mn.dist, frac: mn.frac, elong: mn.elong });
    let mv = C.eqToEnu(mn.ra, mn.dec, lst, S.lat);
    const aa = C.enuToAltAz(mv); // اختلاف منظر: ماه نزدیک است
    SOLAR.moon.v = C.altAzToEnu(aa.alt - mn.parallax * Math.cos(aa.alt * D2R), aa.az);
    for (const p of planets) { const q = C.planet(p.id, d, sn); p.ra = q.ra; p.dec = q.dec; p.dist = q.dist; p.mag = q.mag; p.phase = q.phase; p.v = C.eqToEnu(q.ra, q.dec, lst, S.lat); }
    for (const c of cons) { const a = [0, 0, 0]; c.members.forEach(s => { a[0] += s.v[0]; a[1] += s.v[1]; a[2] += s.v[2]; }); c.v = norm(a); }
    W.sunAlt = Math.asin(SOLAR.sun.v[2]) * R2D;
    W.qibla = C.qibla(S.lat, S.lon);
    dirty = true;
  }

  /* ---------- دوربین ---------- */
  function buildCam() {
    let f, u;
    if (mode === 'sensor' && sens.f) { f = sens.f; u = sens.u; }
    else {
      const az = view.az * D2R, al = view.alt * D2R;
      f = [Math.cos(al) * Math.sin(az), Math.cos(al) * Math.cos(az), Math.sin(al)];
      u = [-Math.sin(al) * Math.sin(az), -Math.sin(al) * Math.cos(az), Math.cos(al)];
    }
    const r = cross(f, u), span = Math.min(CW, CH);
    cam = { f, u, r, scale: (span / 2) / (2 * Math.tan(view.fov * D2R / 4)), cx: CW / 2, cy: CH / 2 };
  }
  // تصویر برجستهٔ استریوگرافیک؛ مقدار null یعنی پشت دوربین
  function proj(v) {
    const z = dot(v, cam.f);
    if (z < -0.35) return null;
    const k = cam.scale * 2 / (1 + z);
    return [cam.cx + dot(v, cam.r) * k, cam.cy - dot(v, cam.u) * k, z, k];
  }
  function camAltAz() { return C.enuToAltAz(cam.f); }

  /* ---------- رنگ‌ها ---------- */
  const PAL = {
    n: { line: 'rgba(132,170,235,', conName: 'rgba(165,195,245,', star: 'rgba(220,230,250,', grid: 'rgba(130,160,220,0.13)', eq: 'rgba(110,205,190,0.38)', ecl: 'rgba(230,185,74,0.5)', hor: 'rgba(230,185,74,0.9)', card: '#EDF0E4', qibla: '#FF5B3A', dso: 'rgba(180,215,255,', gold: '#E6B94A', sunC: '#ffe9a8' },
    r: { line: 'rgba(255,90,70,', conName: 'rgba(255,120,100,', star: 'rgba(255,120,100,', grid: 'rgba(255,90,70,0.14)', eq: 'rgba(255,90,70,0.34)', ecl: 'rgba(255,140,100,0.5)', hor: 'rgba(255,122,98,0.9)', card: '#ff9a88', qibla: '#ff9a88', dso: 'rgba(255,130,110,', gold: '#ff7a62', sunC: '#ff9a88' }
  };
  const pal = () => S.red ? PAL.r : PAL.n;
  function starRGB(ci) {
    if (S.red) return [255, 112, 92];
    if (ci < 0) return [170, 197, 255];
    if (ci < 0.4) return [238, 243, 255];
    if (ci < 0.8) return [255, 241, 205];
    if (ci < 1.3) return [255, 208, 160];
    return [255, 176, 132];
  }
  function skyColors() {
    const a = W.sunAlt;
    if (!S.dayLight) return ['#030a10', '#0A1B27'];
    const t = clamp((a + 18) / 20, 0, 1), tt = t * t * (3 - 2 * t);
    const night = [3, 10, 16], dayc = [58, 112, 190], tw = [26, 44, 80];
    const mix = (x, y, k) => x.map((c, i) => Math.round(lerp(c, y[i], k)));
    const mid = tt < 0.5 ? mix(night, tw, tt * 2) : mix(tw, dayc, (tt - 0.5) * 2);
    const top = mix(night, mid, 0.55); if (tt <= 0.01) return ['rgb(3,10,16)', 'rgb(10,27,39)'];
    return ['rgb(' + top.join(',') + ')', 'rgb(' + mid.join(',') + ')'];
  }
  const nightness = () => S.dayLight ? clamp((-W.sunAlt - 2) / 16, 0, 1) : 1;

  /* ---------- رسم ---------- */
  function polyline(pts, style, width, dash) {
    ctx.beginPath();
    let pen = false, last = null;
    for (const v of pts) {
      const p = proj(v);
      if (!p || (last && Math.hypot(p[0] - last[0], p[1] - last[1]) > CW * 0.9)) { pen = false; last = p; continue; }
      if (pen) ctx.lineTo(p[0], p[1]); else ctx.moveTo(p[0], p[1]);
      pen = true; last = p;
    }
    ctx.strokeStyle = style; ctx.lineWidth = width; ctx.setLineDash(dash || []); ctx.stroke(); ctx.setLineDash([]);
  }
  function circlePts(fn, n) { const out = []; for (let i = 0; i <= n; i++) out.push(fn(i / n)); return out; }

  function drawMilky(nn) {
    if (!S.milky) return;
    const sc = cam.scale;
    ctx.save(); ctx.globalCompositeOperation = 'lighter';
    for (const m of MW) {
      const p = proj(m.v); if (!p) continue;
      const radDeg = 8 + 7 * m.w;
      const R = radDeg * D2R * p[3];
      if (R < 4 || p[0] < -R || p[0] > CW + R || p[1] < -R || p[1] > CH + R) continue;
      const core = Math.min(m.l, 360 - m.l) < 28;
      const a = (0.045 + 0.06 * m.w) * nn;
      const rgb = S.red ? '255,70,50' : (core ? '210,185,150' : '150,172,225');
      const g = ctx.createRadialGradient(p[0], p[1], 0, p[0], p[1], R);
      g.addColorStop(0, 'rgba(' + rgb + ',' + a + ')'); g.addColorStop(1, 'rgba(' + rgb + ',0)');
      ctx.fillStyle = g; ctx.fillRect(p[0] - R, p[1] - R, R * 2, R * 2);
    }
    ctx.restore();
  }

  function drawGrid(nn) {
    if (S.grid) {
      for (let alt = 15; alt < 90; alt += 15) polyline(circlePts(t => C.altAzToEnu(alt, t * 360), 120), pal().grid, 1);
      for (let az = 0; az < 360; az += 30) polyline(circlePts(t => C.altAzToEnu(-5 + t * 95, az), 24), pal().grid, 1);
    }
    if (S.eqEcl) {
      const eq = circlePts(t => C.eqToEnu(t * 360, 0, W.lst, S.lat), 180);
      polyline(eq, pal().eq, 1, [5, 5]);
      const eps = C.obliquity(C.dayNum(W.jd));
      const ec = circlePts(t => { const e = C.eclToEq(t * 360, 0, eps); return C.eqToEnu(e[0], e[1], W.lst, S.lat); }, 180);
      polyline(ec, pal().ecl, 1.2);
      const ncp = C.eqToEnu(0, 90, W.lst, S.lat), p = proj(ncp);
      if (p) { ctx.fillStyle = pal().eq; ctx.beginPath(); ctx.arc(p[0], p[1], 2.5, 0, 7); ctx.fill(); label('قطب شمال سماوی', p[0], p[1] - 10, pal().eq, 11); }
    }
  }

  function label(text, x, y, color, size, align, weight) {
    ctx.font = (weight || 400) + ' ' + size + 'px Vazirmatn, "Noto Sans Arabic", Tahoma, sans-serif';
    ctx.textAlign = align || 'center'; ctx.textBaseline = 'middle';
    ctx.lineWidth = 3; ctx.strokeStyle = 'rgba(2,3,10,0.75)'; ctx.lineJoin = 'round';
    ctx.strokeText(text, x, y); ctx.fillStyle = color; ctx.fillText(text, x, y);
  }

  function drawLines(nn) {
    if (!S.lines) return;
    const alpha = clamp(0.12 + 0.36 * nn, 0, 0.5);
    ctx.lineWidth = 1.1; ctx.strokeStyle = pal().line + alpha + ')';
    ctx.beginPath();
    for (const c of cons) for (const ch of c.lines) {
      let pen = false;
      for (const s of ch) {
        const p = proj(s.v);
        if (!p) { pen = false; continue; }
        if (pen) ctx.lineTo(p[0], p[1]); else ctx.moveTo(p[0], p[1]);
        pen = true;
      }
    }
    ctx.stroke();
  }

  function drawDSO(nn) {
    if (!S.dso) return;
    for (const o of dsos) {
      if (o.kind === 'center') continue;
      const p = proj(o.v); if (!p) continue;
      const R = Math.max(5, o.size * D2R * p[3] * 0.5);
      const a = (0.55 * nn + 0.1) * (o.v[2] < 0 ? 0.4 : 1);
      ctx.strokeStyle = pal().dso + a + ')'; ctx.fillStyle = pal().dso + (a * 0.18) + ')'; ctx.lineWidth = 1;
      ctx.beginPath();
      if (o.kind === 'galaxy') ctx.ellipse(p[0], p[1], R * 1.6, R * 0.7, -0.6, 0, 7); else ctx.arc(p[0], p[1], R, 0, 7);
      ctx.fill();
      if (o.kind === 'open') ctx.setLineDash([2, 3]);
      ctx.stroke(); ctx.setLineDash([]);
      if (o.kind === 'globular') { ctx.beginPath(); ctx.moveTo(p[0] - R, p[1]); ctx.lineTo(p[0] + R, p[1]); ctx.moveTo(p[0], p[1] - R); ctx.lineTo(p[0], p[1] + R); ctx.stroke(); }
      hits.push({ o, x: p[0], y: p[1], r: Math.max(R, 14), pri: 1 });
      if (view.fov < 95) label(o.fa, p[0], p[1] + R + 11, pal().dso + (a + 0.2) + ')', 11);
    }
  }

  function drawStars(nn) {
    const lim = S.dayLight ? lerp(-1.5, 6.6, Math.pow(nn, 0.7)) : 6.6;
    const zoom = clamp(Math.sqrt(85 / view.fov), 0.85, 1.9);
    const lblMag = clamp(2.2 + (60 - view.fov) / 24, 1.2, 4.7);
    for (const s of stars) {
      const p = proj(s.v); if (!p) continue;
      if (p[0] < -20 || p[0] > CW + 20 || p[1] < -20 || p[1] > CH + 20) continue;
      let a = clamp((lim - s.mag) / 1.2, 0, 1); if (a <= 0.01) continue;
      if (s.v[2] < 0) a *= 0.3;
      const rgb = starRGB(s.ci);
      const r = clamp(0.55 + (5.6 - s.mag) * 0.5, 0.6, 5.2) * zoom;
      if (s.mag < 2.2) {
        const g = ctx.createRadialGradient(p[0], p[1], 0, p[0], p[1], r * 4.2);
        g.addColorStop(0, 'rgba(' + rgb + ',' + 0.4 * a + ')'); g.addColorStop(1, 'rgba(' + rgb + ',0)');
        ctx.fillStyle = g; ctx.fillRect(p[0] - r * 4.2, p[1] - r * 4.2, r * 8.4, r * 8.4);
      }
      ctx.fillStyle = 'rgba(' + rgb + ',' + a + ')';
      ctx.beginPath(); ctx.arc(p[0], p[1], r, 0, 7); ctx.fill();
      if (s.mag < 3.6) hits.push({ o: s, x: p[0], y: p[1], r: Math.max(r + 8, 16), pri: 0 });
      if (S.starNames && s.mag < lblMag && a > 0.4 && !/^\d|^[α-ωΑ-Ω]/.test(s.name)) label(s.fa || s.name, p[0] + r + 4, p[1] - r - 3, pal().star + (0.72 * a) + ')', 11, 'start');
    }
  }

  function drawConNames(nn) {
    if (!S.names || nn < 0.15) return;
    for (const c of cons) {
      const p = proj(c.v); if (!p) continue;
      if (p[0] < 10 || p[0] > CW - 10 || p[1] < 70 || p[1] > CH - 10) continue;
      const a = (c.v[2] < 0 ? 0.35 : 0.75) * nn;
      label(c.fa, p[0], p[1], pal().conName + a + ')', 12.5, 'center', c.zodiac ? 600 : 400);
      hits.push({ o: c, x: p[0], y: p[1], r: 22, pri: 3 });
    }
  }

  function discGlow(x, y, r, rgb, a) {
    const g = ctx.createRadialGradient(x, y, r * 0.3, x, y, r);
    g.addColorStop(0, 'rgba(' + rgb + ',' + a + ')'); g.addColorStop(1, 'rgba(' + rgb + ',0)');
    ctx.fillStyle = g; ctx.fillRect(x - r, y - r, r * 2, r * 2);
  }

  function drawSolar() {
    // سیارات
    for (const p of planets) {
      const q = proj(p.v); if (!q) continue;
      const below = p.v[2] < 0;
      const r = clamp(3 + (1.6 - p.mag) * 0.85, 3, 7.5);
      const col = S.red ? '#ff7a62' : p.color;
      ctx.globalAlpha = below ? 0.35 : 1;
      discGlow(q[0], q[1], r * 3.4, S.red ? '255,110,90' : '255,240,210', 0.28);
      ctx.fillStyle = col; ctx.beginPath(); ctx.arc(q[0], q[1], r, 0, 7); ctx.fill();
      ctx.strokeStyle = pal().gold; ctx.lineWidth = 1; ctx.beginPath(); ctx.arc(q[0], q[1], r + 3, 0, 7); ctx.stroke();
      label(p.fa.split(' ')[0], q[0], q[1] + r + 14, pal().gold, 12, 'center', 600);
      ctx.globalAlpha = 1;
      hits.push({ o: p, x: q[0], y: q[1], r: 22, pri: -1 });
    }
    // خورشید
    const s = proj(SOLAR.sun.v);
    if (s) {
      const below = SOLAR.sun.v[2] < 0, R = Math.max(7, 0.27 * D2R * s[3]);
      ctx.globalAlpha = below ? 0.45 : 1;
      discGlow(s[0], s[1], R * 5, S.red ? '255,90,70' : '255,220,140', 0.5);
      ctx.fillStyle = pal().sunC; ctx.beginPath(); ctx.arc(s[0], s[1], R, 0, 7); ctx.fill();
      label('خورشید', s[0], s[1] + R + 14, pal().gold, 12, 'center', 600);
      ctx.globalAlpha = 1;
      hits.push({ o: SOLAR.sun, x: s[0], y: s[1], r: Math.max(R + 8, 24), pri: -2 });
    }
    // ماه با فاز و جهت درست
    const m = proj(SOLAR.moon.v);
    if (m) {
      const below = SOLAR.moon.v[2] < 0, R = Math.max(9, 0.26 * D2R * m[3]);
      ctx.globalAlpha = below ? 0.45 : 1;
      discGlow(m[0], m[1], R * 3.2, S.red ? '255,110,90' : '220,230,255', 0.18 + 0.2 * SOLAR.moon.frac);
      let ang = 0; const sp = s || proj(SOLAR.sun.v);
      if (sp) ang = Math.atan2(sp[1] - m[1], sp[0] - m[0]);
      else { // خورشید پشت دوربین: جهت را از بردارهای سه‌بعدی بگیر
        const dv = SOLAR.sun.v; ang = Math.atan2(-dot(dv, cam.u), dot(dv, cam.r));
      }
      ctx.save(); ctx.translate(m[0], m[1]); ctx.rotate(ang);
      ctx.fillStyle = S.red ? '#2a0a08' : '#1a2132'; ctx.beginPath(); ctx.arc(0, 0, R, 0, 7); ctx.fill();
      const k = SOLAR.moon.frac, rx = R * Math.abs(2 * k - 1);
      ctx.fillStyle = S.red ? '#ffb4a4' : '#f4efe0'; ctx.beginPath();
      ctx.arc(0, 0, R, -Math.PI / 2, Math.PI / 2, false);
      if (k < 0.5) ctx.ellipse(0, 0, rx, R, 0, Math.PI / 2, -Math.PI / 2, true);
      else ctx.ellipse(0, 0, rx, R, 0, Math.PI / 2, Math.PI * 1.5, false);
      ctx.closePath(); ctx.fill();
      ctx.restore();
      label('ماه', m[0], m[1] + R + 14, pal().gold, 12, 'center', 600);
      ctx.globalAlpha = 1;
      hits.push({ o: SOLAR.moon, x: m[0], y: m[1], r: Math.max(R + 8, 24), pri: -2 });
    }
  }

  function drawHorizon() {
    const hz = circlePts(t => C.altAzToEnu(0, t * 360), 180);
    polyline(hz, pal().hor, 1.6);
    // درجه‌بندی هر ۱۰ درجه، شمارهٔ هر ۳۰ درجه
    for (let az = 0; az < 360; az += 10) {
      const p = proj(C.altAzToEnu(0, az)), p2 = proj(C.altAzToEnu(az % 30 === 0 ? 1.8 : 1, az));
      if (!p || !p2) continue;
      ctx.strokeStyle = pal().hor; ctx.lineWidth = 1; ctx.beginPath(); ctx.moveTo(p[0], p[1]); ctx.lineTo(p2[0], p2[1]); ctx.stroke();
      if (az % 90 !== 0 && az % 30 === 0 && view.fov < 120) { const t = proj(C.altAzToEnu(-3.2, az)); if (t) label(fa(az) + '°', t[0], t[1], pal().hor.replace('0.9', '0.6'), 10.5); }
    }
    const names = { 0: 'شمال', 90: 'شرق', 180: 'جنوب', 270: 'غرب', 45: 'ش‌ق', 135: 'ج‌ش', 225: 'ج‌غ', 315: 'ش‌غ' };
    for (const az of Object.keys(names)) {
      const a = +az, major = a % 90 === 0, p = proj(C.altAzToEnu(major ? -4.5 : -3.4, a)); if (!p) continue;
      label(names[az], p[0], p[1], pal().card, major ? 17 : 11, 'center', major ? 700 : 400);
    }
    // سمت‌الرأس
    const zp = proj([0, 0, 1]);
    if (zp) { ctx.strokeStyle = pal().grid; ctx.beginPath(); ctx.arc(zp[0], zp[1], 4, 0, 7); ctx.stroke(); }
  }

  function drawQibla() {
    if (!S.qibla) return;
    const az = W.qibla.az, p = proj(C.altAzToEnu(0, az)); if (!p) return;
    const col = pal().qibla;
    ctx.save(); ctx.translate(p[0], p[1]);
    ctx.strokeStyle = col; ctx.lineWidth = 1.2;
    ctx.beginPath(); ctx.moveTo(0, 0); ctx.lineTo(0, -34); ctx.stroke();
    ctx.fillStyle = '#0c0f14'; ctx.strokeStyle = col; ctx.lineWidth = 1.5;
    ctx.beginPath(); ctx.rect(-9, -52, 18, 18); ctx.fill(); ctx.stroke();
    ctx.fillStyle = col; ctx.fillRect(-9, -47, 18, 2.5);
    ctx.restore();
    label('قبله ' + fa(Math.round(az)) + '°', p[0], p[1] - 64, col, 13, 'center', 600);
    hits.push({ o: { type: 'qibla', name: 'Qibla', fa: 'قبله', v: C.altAzToEnu(0, az) }, x: p[0], y: p[1] - 40, r: 26, pri: -3 });
  }

  function targetVec() {
    if (!target) return null;
    return target.v;
  }
  function drawTarget() {
    const v = targetVec(); if (!v) return;
    const z = dot(v, cam.f), x = dot(v, cam.r), y = dot(v, cam.u);
    const col = pal().gold, p = proj(v);
    const onScreen = p && p[0] > 24 && p[0] < CW - 24 && p[1] > 90 && p[1] < CH - 24;
    if (onScreen) {
      const t = (performance.now() % 1600) / 1600;
      ctx.strokeStyle = col; ctx.lineWidth = 1.5; ctx.globalAlpha = 1 - t * 0.7;
      ctx.beginPath(); ctx.arc(p[0], p[1], 14 + t * 14, 0, 7); ctx.stroke(); ctx.globalAlpha = 1;
      ctx.beginPath(); ctx.arc(p[0], p[1], 12, 0, 7); ctx.stroke();
      dirty = true;
    } else {
      // پیکان لبهٔ صفحه به سمت هدف
      let dx = x, dy = -y; if (z < 0 && Math.hypot(dx, dy) < 0.05) dx = 1;
      const ang = Math.atan2(dy, dx), mx = CW / 2 - 40, my = CH / 2 - 120;
      const k = Math.min(Math.abs(mx / (Math.cos(ang) || 1e-6)), Math.abs(my / (Math.sin(ang) || 1e-6)));
      const px = CW / 2 + Math.cos(ang) * k, py = CH / 2 + Math.sin(ang) * k;
      ctx.save(); ctx.translate(px, py); ctx.rotate(ang);
      ctx.fillStyle = col; ctx.beginPath(); ctx.moveTo(16, 0); ctx.lineTo(-8, -11); ctx.lineTo(-3, 0); ctx.lineTo(-8, 11); ctx.closePath(); ctx.fill();
      ctx.restore();
      const sep = Math.acos(clamp(z, -1, 1)) * R2D;
      label(fa(Math.round(sep)) + '°', px - Math.cos(ang) * 30, py - Math.sin(ang) * 30, col, 12, 'center', 600);
    }
  }

  function draw() {
    buildCam();
    hits = [];
    const nn = nightness(), [c1, c2] = skyColors();
    const g = ctx.createRadialGradient(CW / 2, CH * 0.45, 0, CW / 2, CH * 0.45, Math.hypot(CW, CH) * 0.65);
    g.addColorStop(0, S.red ? '#1a0606' : c2); g.addColorStop(1, S.red ? '#080202' : c1);
    ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
    ctx.fillStyle = g; ctx.fillRect(0, 0, CW, CH);
    drawMilky(nn); drawGrid(nn); drawLines(nn); drawDSO(nn); drawStars(nn); drawConNames(nn); drawSolar(); drawHorizon(); drawQibla(); drawTarget();
    updateHud();
  }

  /* ---------- نوشته‌های بالای صفحه ---------- */
  let lastHud = '';
  function updateHud() {
    const aa = camAltAz();
    let txt;
    if (mode === 'sensor') {
      txt = 'جهت ' + fa(Math.round(aa.az)) + '° ' + dir8(aa.az) + ' · ارتفاع ' + fa(Math.round(aa.alt)) + '°' + (sens.rel && !sens.abs ? ' · جهت‌یابی تقریبی' : '');
    } else txt = 'سمت ' + fa(Math.round(aa.az)) + '° ' + dir8(aa.az) + ' · ارتفاع ' + fa(Math.round(aa.alt)) + '° · میدان دید ' + fa(Math.round(view.fov)) + '°';
    if (txt !== lastHud) { $('hud').textContent = txt; lastHud = txt; }
  }
  function updateWhere() {
    const d = new Date(T.ms);
    $('whereDate').textContent = fmtDate.format(d) + ' · ' + fmtTime.format(d);
    const ns = S.lat >= 0 ? 'ش' : 'ج', ew = S.lon >= 0 ? 'ر' : 'غ';
    const sunTxt = W.sunAlt > 0 ? 'روز' : W.sunAlt > -6 ? 'گرگ‌ومیش مدنی' : W.sunAlt > -12 ? 'گرگ‌ومیش دریایی' : W.sunAlt > -18 ? 'گرگ‌ومیش نجومی' : 'شب';
    $('whereMeta').textContent = fa(Math.abs(S.lat).toFixed(2)) + '°' + ns + ' ' + fa(Math.abs(S.lon).toFixed(2)) + '°' + ew + ' · ' + sunTxt + (T.live ? '' : ' · زمان دستی');
    $('tNow').setAttribute('aria-pressed', T.live ? 'true' : 'false');
  }

  /* ---------- زمان ---------- */
  let lastFrame = performance.now(), lastClock = 0;
  function tick(now) {
    const dt = now - lastFrame; lastFrame = now;
    if (T.play) { T.ms += dt * 600; T.live = false; }
    else if (T.live) T.ms = Date.now();
    if (Math.abs(T.ms - W.ms) > (T.play ? 1000 : 4000)) recompute();
    if (now - lastClock > 1000) { lastClock = now; updateWhere(); if (selected) renderCard(selected, true); }
    if (mode === 'sensor' || dirty || T.play) { dirty = false; draw(); }
    requestAnimationFrame(tick);
  }
  function setTime(ms, live) { T.ms = ms; T.live = !!live; T.play = false; setPlayUI(); recompute(); updateWhere(); }
  function setPlayUI() { $('tPlay').setAttribute('aria-pressed', T.play ? 'true' : 'false'); }

  /* ---------- حسگر جهت ---------- */
  function rotZXY(aD, bD, gD) {
    const a = aD * D2R, b = bD * D2R, g = gD * D2R;
    const ca = Math.cos(a), sa = Math.sin(a), cb = Math.cos(b), sb = Math.sin(b), cg = Math.cos(g), sg = Math.sin(g);
    return [
      [ca * cg - sa * sb * sg, -sa * cb, ca * sg + sa * sb * cg],
      [sa * cg + ca * sb * sg, ca * cb, sa * sg - ca * sb * cg],
      [-cb * sg, sb, cb * cg]
    ];
  }
  const mulv = (R, v) => [R[0][0] * v[0] + R[0][1] * v[1] + R[0][2] * v[2], R[1][0] * v[0] + R[1][1] * v[1] + R[1][2] * v[2], R[2][0] * v[0] + R[2][1] * v[1] + R[2][2] * v[2]];
  function applyDecl(v) { // شمال مغناطیسی -> شمال جغرافیایی
    const d = S.decl * D2R, c = Math.cos(d), s = Math.sin(d);
    return [v[0] * c + v[1] * s, -v[0] * s + v[1] * c, v[2]];
  }
  function onOrientation(alpha, beta, gamma) {
    const R = rotZXY(alpha, beta, gamma);
    const ang = screenAng();
    pushVectors(norm(mulv(R, [0, 0, -1])), norm(mulv(R, [Math.sin(ang), Math.cos(ang), 0])));
  }
  const onAbs = e => { if (e.alpha == null) return; sens.abs = true; sens.got = true; onOrientation(e.alpha, e.beta, e.gamma); };
  const onRel = e => {
    if (sens.abs || e.alpha == null) return;
    sens.got = true;
    if (e.webkitCompassHeading != null) { // آی‌اواس
      if (Math.abs(e.beta) < 45) sens.iosOff = C.rev(-e.webkitCompassHeading - e.alpha);
      if (sens.iosOff != null) { sens.abs = true; onOrientation(C.rev(e.alpha + sens.iosOff), e.beta, e.gamma); }
      return;
    }
    sens.rel = true; onOrientation(e.alpha, e.beta, e.gamma);
  };
  /* پلاگین بومی اندروید (همان NativeCompass قطب‌نما): ماتریس چرخش دستگاه -> شرق/شمال/بالا */
  const nativeCache = {};
  const isNativeApp = () => { try { const c = window.Capacitor; return !!(c && typeof c.isNativePlatform === 'function' && c.isNativePlatform()); } catch (e) { return false; } };
  function nativePlugin(name) {
    try {
      if (!isNativeApp()) return null;
      if (nativeCache[name]) return nativeCache[name];
      const cap = window.Capacitor; let pl = null;
      if (typeof cap.registerPlugin === 'function') { try { pl = cap.registerPlugin(name); } catch (e) { pl = null; } }
      if (!pl && cap.Plugins && cap.Plugins[name]) pl = cap.Plugins[name];
      if (pl) nativeCache[name] = pl;
      return pl;
    } catch (e) { return null; }
  }
  let lastSensT = 0;
  function pushVectors(f, u) {
    f = applyDecl(f); u = applyDecl(u);
    const t = performance.now();
    if (sens.f) {
      const k = 1 - Math.exp(-clamp(t - lastSensT, 1, 200) / 90);
      f = norm([lerp(sens.f[0], f[0], k), lerp(sens.f[1], f[1], k), lerp(sens.f[2], f[2], k)]);
      u = norm([lerp(sens.u[0], u[0], k), lerp(sens.u[1], u[1], k), lerp(sens.u[2], u[2], k)]);
    }
    lastSensT = t; sens.f = f; sens.u = u; dirty = true;
  }
  const screenAng = () => ((screen.orientation && typeof screen.orientation.angle === 'number') ? screen.orientation.angle : (typeof window.orientation === 'number' ? window.orientation : 0)) * D2R;
  function onNativeMatrix(e) {
    const R = e && e.matrix; if (!R || R.length < 9) return;
    sens.got = true; sens.abs = true;
    const a = screenAng(), sn = Math.sin(a), cs = Math.cos(a);
    // پشت گوشی = -ستون سوم؛ بالای صفحه = ترکیب ستون‌های اول و دوم بر اساس چرخش صفحه
    pushVectors(norm([-R[2], -R[5], -R[8]]), norm([R[0] * sn + R[1] * cs, R[3] * sn + R[4] * cs, R[6] * sn + R[7] * cs]));
  }
  let wakeLock = null;
  async function keepAwake(on) {
    try {
      if (on && 'wakeLock' in navigator && document.visibilityState === 'visible' && !wakeLock) {
        wakeLock = await navigator.wakeLock.request('screen'); wakeLock.addEventListener('release', () => { wakeLock = null; });
      } else if (!on && wakeLock) { await wakeLock.release(); wakeLock = null; }
    } catch (e) { wakeLock = null; }
  }
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible' && mode === 'sensor') keepAwake(true); });

  async function startSensor() {
    sens.f = null; sens.got = false; sens.abs = false; sens.rel = false;
    const compass = nativePlugin('NativeCompass');
    if (compass) {
      try {
        if (!sens.nativeHandle) sens.nativeHandle = await compass.addListener('orientation', onNativeMatrix);
        const info = await compass.start();
        if (info && info.available) { sens.native = true; setMode('sensor'); keepAwake(true); toast('گوشی را رو به آسمان بگیر. اگر جهت کج بود، گوشی را به شکل ۸ بچرخان'); return; }
      } catch (e) { /* ادامه با حسگر وب */ }
    }
    try {
      if (typeof DeviceOrientationEvent !== 'undefined' && typeof DeviceOrientationEvent.requestPermission === 'function') {
        const r = await DeviceOrientationEvent.requestPermission();
        if (r !== 'granted') { toast('اجازهٔ دسترسی به حسگر داده نشد'); return; }
      }
    } catch (e) { /* ادامه */ }
    if (!sens.active) {
      if ('ondeviceorientationabsolute' in window) window.addEventListener('deviceorientationabsolute', onAbs, true);
      window.addEventListener('deviceorientation', onRel, true);
      sens.active = true;
    }
    setMode('sensor'); keepAwake(true);
    setTimeout(() => { if (mode === 'sensor' && !sens.got) { toast('حسگر جهت پاسخ نمی‌دهد. به حالت نقشه برگشتیم'); setMode('manual'); } }, 2500);
    toast('گوشی را رو به آسمان بگیر. اگر جهت کج بود، گوشی را به شکل ۸ بچرخان');
  }
  function setMode(m) {
    mode = m;
    $('modeManual').setAttribute('aria-pressed', m === 'manual');
    $('modeSensor').setAttribute('aria-pressed', m === 'sensor');
    if (m === 'sensor') view.fov = Math.min(view.fov, 65);
    else { keepAwake(false); const c = nativePlugin('NativeCompass'); if (c && sens.native) { c.stop().catch(() => {}); sens.native = false; } }
    dirty = true;
  }

  /* ---------- لمس ---------- */
  const ptrs = new Map(); let pinch0 = null, moved = 0, downAt = 0;
  canvas.addEventListener('pointerdown', e => {
    canvas.setPointerCapture(e.pointerId); ptrs.set(e.pointerId, [e.clientX, e.clientY]);
    moved = 0; downAt = performance.now();
    if (ptrs.size === 2) { const [a, b] = [...ptrs.values()]; pinch0 = { d: Math.hypot(a[0] - b[0], a[1] - b[1]), fov: view.fov }; }
  });
  canvas.addEventListener('pointermove', e => {
    if (!ptrs.has(e.pointerId)) return;
    const prev = ptrs.get(e.pointerId), dx = e.clientX - prev[0], dy = e.clientY - prev[1];
    ptrs.set(e.pointerId, [e.clientX, e.clientY]); moved += Math.abs(dx) + Math.abs(dy);
    if (ptrs.size === 2 && pinch0) {
      const [a, b] = [...ptrs.values()], d = Math.hypot(a[0] - b[0], a[1] - b[1]);
      view.fov = clamp(pinch0.fov * pinch0.d / Math.max(d, 1), 6, 140); dirty = true; return;
    }
    if (ptrs.size === 1 && mode === 'manual') {
      const k = view.fov / Math.min(CW, CH) * 1.05; // درجه بر پیکسل (تقریبی)
      view.az = C.rev(view.az - dx * k / Math.max(Math.cos(view.alt * D2R), 0.25));
      view.alt = clamp(view.alt + dy * k, -89.5, 89.5); dirty = true;
    }
  });
  const endPtr = e => {
    const wasTap = ptrs.size === 1 && moved < 8 && performance.now() - downAt < 500;
    ptrs.delete(e.pointerId); if (ptrs.size < 2) pinch0 = null;
    if (wasTap && e.type === 'pointerup') tap(e.clientX, e.clientY);
  };
  canvas.addEventListener('pointerup', endPtr); canvas.addEventListener('pointercancel', endPtr);
  canvas.addEventListener('wheel', e => { e.preventDefault(); view.fov = clamp(view.fov * Math.exp(e.deltaY * 0.0012), 6, 140); dirty = true; }, { passive: false });

  function tap(x, y) {
    let best = null, bd = 1e9;
    for (const h of hits) { const d = Math.hypot(h.x - x, h.y - y); if (d <= h.r) { const sc = d + h.pri * 8; if (sc < bd) { bd = sc; best = h.o; } } }
    if (best) openCard(best); else closeSheets();
  }

  /* ---------- کارت اطلاعات ---------- */
  function typeText(o) {
    switch (o.type) {
      case 'star': return 'ستاره' + (o.con ? ' · ' + conFa(o.con).replace(/\s*\(.*\)/, '') : '');
      case 'planet': return 'سیاره'; case 'sun': return 'ستارهٔ منظومهٔ شمسی'; case 'moon': return 'قمر زمین';
      case 'dso': return DSO_KIND[o.kind] || 'جرم عمیق آسمان'; case 'con': return 'صورت فلکی' + (o.zodiac ? ' (منطقة‌البروج)' : '');
      case 'qibla': return 'جهت کعبه روی افق'; default: return '';
    }
  }
  function timeAt(hoursAhead) {
    const d = new Date(T.ms + hoursAhead * 3600000), same = d.toDateString() === new Date(T.ms).toDateString();
    return fa(fmtTime.format(d)) + (same ? '' : ' (فردا)');
  }
  function moonPhaseName(e) {
    if (e < 10 || e > 350) return 'محاق (ماه نو)'; if (e < 80) return 'هلال رو به رشد'; if (e < 100) return 'تربیع اول';
    if (e < 170) return 'ماه رو به کامل'; if (e < 190) return 'بدر (ماه کامل)'; if (e < 260) return 'ماه رو به نقصان';
    if (e < 280) return 'تربیع آخر'; return 'هلال رو به محاق';
  }
  function renderCard(o, soft) {
    const el = $('card');
    if (o.type === 'con') { renderConCard(o); return; }
    const aa = C.enuToAltAz(o.v);
    const rows = [];
    if (o.type !== 'qibla' && o.mag != null && o.mag < 90) rows.push(['قدر ظاهری', fa((+o.mag).toFixed(1))]);
    if (o.type === 'moon') { rows.push(['فاز', moonPhaseName(o.elong) + ' · ' + fa(Math.round(o.frac * 100)) + '٪ روشن']); }
    if (o.type === 'planet') rows.push(['فاصله از زمین', fa(o.dist.toFixed(2)) + ' واحد نجومی']);
    if (o.type === 'sun') rows.push(['فاصله', fa(o.dist.toFixed(3)) + ' واحد نجومی']);
    if (o.type === 'qibla') rows.push(['فاصله تا کعبه', fa(Math.round(W.qibla.km).toLocaleString('en')) + ' کیلومتر']);
    rows.push(['سمت', fa(Math.round(aa.az)) + '° ' + dir8(aa.az)]);
    rows.push(['ارتفاع', aa.alt < 0 ? fa(Math.abs(Math.round(aa.alt))) + '° زیر افق' : fa(Math.round(aa.alt)) + '° بالای افق']);
    if (o.ra != null && o.type !== 'qibla') {
      const h0 = o.type === 'sun' ? -0.833 : o.type === 'moon' ? 0.125 : -0.567;
      const rs = C.riseSet(o.ra, o.dec, S.lat, S.lon, W.jd, h0);
      if (rs.circumpolar) rows.push(['وضعیت', 'همیشه بالای افق (دور قطب)']);
      else if (rs.neverRises) rows.push(['وضعیت', 'در این عرض هرگز بالا نمی‌آید']);
      else { rows.push(['طلوع', timeAt(rs.rise)]); rows.push(['اوج (نصف‌النهار)', timeAt(rs.transit)]); rows.push(['غروب', timeAt(rs.set)]); }
    }
    el.innerHTML = '<div class="sheet-head"><div class="card-title"><h2>' + esc(o.fa || o.name) + '</h2>' + (o.fa && o.name !== o.fa ? '<small>' + esc(o.name) + '</small>' : '') + '</div><button class="x" data-close aria-label="بستن">×</button></div>'
      + '<p class="sub">' + esc(typeText(o)) + '</p><dl class="kv">' + rows.map(r => '<dt>' + r[0] + '</dt><dd>' + r[1] + '</dd>').join('') + '</dl>'
      + '<div class="row"><button class="btn" id="cardGuide">راهنمایی تا این هدف</button></div>';
    $('cardGuide').onclick = () => { setTarget(o); closeSheets(); };
    bindClose(el);
  }
  function renderConCard(c) {
    const el = $('card'), aa = C.enuToAltAz(c.v);
    const named = c.members.filter(s => s.fa || !/^\d|^[α-ωΑ-Ω]/.test(s.name)).sort((a, b) => a.mag - b.mag).slice(0, 4).map(s => s.fa || s.name);
    el.innerHTML = '<div class="sheet-head"><div class="card-title"><h2>' + esc(c.fa) + '</h2><small>' + esc(c.name) + '</small></div><button class="x" data-close aria-label="بستن">×</button></div>'
      + '<p class="sub">' + esc(typeText(c)) + '</p><dl class="kv"><dt>ستاره‌های برجسته</dt><dd>' + esc(named.join('، ')) + '</dd>'
      + '<dt>ارتفاع مرکز</dt><dd>' + (aa.alt < 0 ? fa(Math.abs(Math.round(aa.alt))) + '° زیر افق' : fa(Math.round(aa.alt)) + '° بالای افق') + '</dd><dt>سمت</dt><dd>' + fa(Math.round(aa.az)) + '° ' + dir8(aa.az) + '</dd></dl>'
      + '<div class="row"><button class="btn" id="cardGuide">راهنمایی تا این هدف</button></div>';
    $('cardGuide').onclick = () => { setTarget(c); closeSheets(); };
    bindClose(el);
  }
  const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  function openCard(o) { selected = o; showSheet('card'); renderCard(o); }

  /* ---------- برگه‌ها ---------- */
  function showSheet(id) { for (const s of ['card', 'menu', 'search']) $(s).hidden = s !== id; document.body.classList.add('sheet-open'); }
  function closeSheets() { for (const s of ['card', 'menu', 'search']) $(s).hidden = true; document.body.classList.remove('sheet-open'); selected = null; dirty = true; }
  function bindClose(root) { root.querySelectorAll('[data-close]').forEach(b => b.onclick = closeSheets); }
  bindClose(document);

  const TOGGLES = [['lines', 'خطوط صورت‌های فلکی'], ['names', 'نام صورت‌های فلکی'], ['starNames', 'نام ستاره‌ها'], ['grid', 'شبکهٔ ارتفاع و سمت'], ['eqEcl', 'استوا و دایرة‌البروج'], ['dso', 'خوشه‌ها و سحابی‌ها'], ['milky', 'راه شیری'], ['qibla', 'جهت قبله'], ['dayLight', 'روشنایی آسمان روز'], ['red', 'حالت شب (قرمز)']];
  function buildMenu() {
    $('toggles').innerHTML = TOGGLES.map(t => '<label><input type="checkbox" data-k="' + t[0] + '"' + (S[t[0]] ? ' checked' : '') + '> ' + t[1] + '</label>').join('');
    $('toggles').querySelectorAll('input').forEach(i => i.onchange = () => { S[i.dataset.k] = i.checked; if (i.dataset.k === 'red') document.body.classList.toggle('red', S.red); save(); dirty = true; });
    syncFields();
  }
  function syncFields() {
    $('fLat').value = S.lat.toFixed(4); $('fLon').value = S.lon.toFixed(4); $('fDecl').value = S.decl;
    const d = new Date(T.ms - new Date(T.ms).getTimezoneOffset() * 60000);
    $('fTime').value = d.toISOString().slice(0, 16);
    const n = [];
    if (!S.posKnown) n.push('موقعیت هنوز ذخیره نشده و تهران فرض شده است. «موقعیت من» را بزن.');
    n.push('موقعیت و انحراف مغناطیسی با قطب‌نما مشترک است. در بیشتر نقاط ایران انحراف حدود ۴ تا ۶ درجه شرقی است؛ تا آن را وارد نکنی، جهت آسمان‌نما چند درجه کج می‌ماند.');
    $('sharedNote').hidden = false; $('sharedNote').textContent = n.join(' ');
  }
  function applyFields() {
    const lat = parseFloat($('fLat').value), lon = parseFloat($('fLon').value), dc = parseFloat($('fDecl').value);
    if (isFinite(lat) && Math.abs(lat) <= 90 && isFinite(lon) && Math.abs(lon) <= 180 && (lat !== S.lat || lon !== S.lon)) { S.lat = lat; S.lon = lon; savePos(); }
    if (isFinite(dc) && Math.abs(dc) <= 40 && dc !== S.decl) { S.decl = dc; saveDecl(); }
    recompute(); updateWhere(); syncFields();
  }
  ['fLat', 'fLon', 'fDecl'].forEach(id => $(id).addEventListener('change', applyFields));
  $('fTime').addEventListener('change', e => { const t = Date.parse(e.target.value); if (isFinite(t)) setTime(t, false); });
  function onPos(p) { S.lat = p.coords.latitude; S.lon = p.coords.longitude; savePos(); recompute(); updateWhere(); syncFields(); toast('موقعیت به‌روز شد'); }
  function onPosErr() { toast('موقعیت در دسترس نیست. دسترسی موقعیت یا GPS را بررسی کن، یا مختصات را دستی وارد کن'); }
  $('btnGps').onclick = async () => {
    toast('در حال یافتن موقعیت…');
    const geo = nativePlugin('Geolocation');
    if (geo) {
      try { try { await geo.requestPermissions({ permissions: ['location'] }); } catch (e) { /* دوباره پایین می‌پرسد */ }
        onPos(await geo.getCurrentPosition({ enableHighAccuracy: false, timeout: 20000, maximumAge: 600000 })); } catch (e) { onPosErr(); }
      return;
    }
    if (!navigator.geolocation) { toast('این مرورگر موقعیت‌یابی ندارد'); return; }
    navigator.geolocation.getCurrentPosition(onPos, onPosErr, { enableHighAccuracy: false, timeout: 20000, maximumAge: 600000 });
  };
  $('btnReset').onclick = () => { Object.assign(S, DEFAULTS); save(); document.body.classList.toggle('red', S.red); buildMenu(); dirty = true; };
  $('btnMenu').onclick = () => { syncFields(); showSheet('menu'); };

  /* ---------- جست‌وجو ---------- */
  const TYPE_ORDER = { planet: 0, sun: 0, moon: 0, con: 1, star: 2, dso: 3 };
  function searchIndex() {
    return [SOLAR.sun, SOLAR.moon, ...planets, ...cons, ...stars.filter(s => s.mag < 4.2 && !/^\d/.test(s.name)), ...dsos.filter(o => o.kind !== 'center' || true)];
  }
  function runSearch(q) {
    const ul = $('results'); ul.innerHTML = '';
    const n = normText(q);
    let list = searchIndex();
    if (n) list = list.filter(o => normText(o.fa || '').includes(n) || normText(o.name).includes(n) || (o.type === 'star' && normText(conFa(o.con)).includes(n)));
    else list = list.filter(o => o.type !== 'star' || o.mag < 1.5);
    list.sort((a, b) => (TYPE_ORDER[a.type] - TYPE_ORDER[b.type]) || ((a.mag || 0) - (b.mag || 0)));
    list = list.slice(0, 40);
    if (!list.length) { ul.innerHTML = '<li class="none">چیزی پیدا نشد. نام فارسی یا انگلیسی را امتحان کن.</li>'; return; }
    for (const o of list) {
      const li = document.createElement('li'), b = document.createElement('button');
      b.innerHTML = '<span>' + esc(o.fa || o.name) + ' <small>' + esc(typeText(o)) + '</small></span><small>' + esc(o.name) + '</small>';
      b.onclick = () => { setTarget(o); closeSheets(); };
      li.appendChild(b); ul.appendChild(li);
    }
  }
  $('btnSearch').onclick = () => { showSheet('search'); $('q').value = ''; runSearch(''); setTimeout(() => $('q').focus(), 50); };
  $('q').addEventListener('input', e => runSearch(e.target.value));

  function setTarget(o) {
    target = o;
    $('targetChip').hidden = false; $('targetName').textContent = 'هدف: ' + (o.fa || o.name);
    if (mode === 'manual') {
      const aa = C.enuToAltAz(o.v); view.az = aa.az; view.alt = clamp(aa.alt, -80, 85);
      if (view.fov > 70) view.fov = 60;
    } else toast('گوشی را به سمت پیکان بچرخان');
    dirty = true;
  }
  $('targetClear').onclick = () => { target = null; $('targetChip').hidden = true; dirty = true; };

  /* ---------- دکمه‌های پایین ---------- */
  $('modeManual').onclick = () => setMode('manual');
  $('modeSensor').onclick = () => { if (mode !== 'sensor') startSensor(); };
  document.querySelectorAll('.timebar [data-d]').forEach(b => b.onclick = () => setTime(T.ms + (+b.dataset.d), false));
  $('tNow').onclick = () => setTime(Date.now(), true);
  $('tPlay').onclick = () => { T.play = !T.play; if (!T.play) T.live = false; setPlayUI(); };

  let toastT;
  function toast(msg) { const t = $('toast'); t.textContent = msg; t.hidden = false; clearTimeout(toastT); toastT = setTimeout(() => { t.hidden = true; }, 4200); }

  /* ---------- شروع ---------- */
  document.body.classList.toggle('red', S.red);
  window.addEventListener('resize', resize);
  window.addEventListener('orientationchange', () => setTimeout(resize, 200));
  buildMenu(); resize(); recompute(); updateWhere();
  requestAnimationFrame(tick);
  // برای عیب‌یابی در کنسول
  window.__sky = { S, view, stars, cons, SOLAR, recompute, draw };
})();
