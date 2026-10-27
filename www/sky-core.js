/* sky-core.js — محاسبات نجومی (بدون وابستگی به DOM)
 * موقعیت خورشید، ماه و سیاره‌ها با الگوریتم‌های سبک (دقت حدود ۱ دقیقهٔ قوسی تا چند دقیقه)
 * تبدیل مختصات، تقدیم اعتدالین، طلوع/غروب، قبله.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.SkyCore = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';
  const D2R = Math.PI / 180, R2D = 180 / Math.PI;
  const rev = x => x - Math.floor(x / 360) * 360;
  const sind = x => Math.sin(x * D2R), cosd = x => Math.cos(x * D2R);

  const jdFromMs = ms => ms / 86400000 + 2440587.5;
  // روز از 1999-12-31 00:00 UT
  const dayNum = jd => jd - 2451543.5;

  function gmst(jd) {
    const T = (jd - 2451545.0) / 36525;
    return rev(280.46061837 + 360.98564736629 * (jd - 2451545.0) + 0.000387933 * T * T - T * T * T / 38710000);
  }

  // تقدیم اعتدالین از J2000 به تاریخ jd (درجه)
  function precess(raDeg, decDeg, jd) {
    const T = (jd - 2451545.0) / 36525;
    const ze = (2306.2181 * T + 0.30188 * T * T) / 3600 * D2R;
    const z = (2306.2181 * T + 1.09468 * T * T) / 3600;
    const th = (2004.3109 * T - 0.42665 * T * T) / 3600 * D2R;
    const ra = raDeg * D2R, dec = decDeg * D2R;
    const A = Math.cos(dec) * Math.sin(ra + ze);
    const B = Math.cos(th) * Math.cos(dec) * Math.cos(ra + ze) - Math.sin(th) * Math.sin(dec);
    const Cc = Math.sin(th) * Math.cos(dec) * Math.cos(ra + ze) + Math.cos(th) * Math.sin(dec);
    return [rev(Math.atan2(A, B) * R2D + z), Math.asin(Math.max(-1, Math.min(1, Cc))) * R2D];
  }

  // استوایی (ra, dec بر حسب درجه) -> بردار یکه افقی [شرق، شمال، بالا]
  function eqToEnu(raDeg, decDeg, lstDeg, latDeg) {
    const H = (lstDeg - raDeg) * D2R, dec = decDeg * D2R, ph = latDeg * D2R;
    const cd = Math.cos(dec), sd = Math.sin(dec);
    return [
      -cd * Math.sin(H),
      sd * Math.cos(ph) - cd * Math.sin(ph) * Math.cos(H),
      sd * Math.sin(ph) + cd * Math.cos(ph) * Math.cos(H)
    ];
  }
  function enuToAltAz(v) {
    return { alt: Math.asin(Math.max(-1, Math.min(1, v[2]))) * R2D, az: rev(Math.atan2(v[0], v[1]) * R2D) };
  }
  function altAzToEnu(altDeg, azDeg) {
    const a = altDeg * D2R, z = azDeg * D2R;
    return [Math.cos(a) * Math.sin(z), Math.cos(a) * Math.cos(z), Math.sin(a)];
  }

  function kepler(M, e) {
    let E = M + R2D * e * sind(M) * (1 + e * cosd(M));
    for (let k = 0; k < 10; k++) {
      const dE = (E - R2D * e * sind(E) - M) / (1 - e * cosd(E));
      E -= dE;
      if (Math.abs(dE) < 1e-7) break;
    }
    return E;
  }

  const obliquity = d => 23.4393 - 3.563e-7 * d;

  function eclToEq(lonDeg, latDeg, eps) {
    const l = lonDeg * D2R, b = latDeg * D2R, e = eps * D2R;
    const x = Math.cos(l) * Math.cos(b);
    const y = Math.sin(l) * Math.cos(b) * Math.cos(e) - Math.sin(b) * Math.sin(e);
    const z = Math.sin(l) * Math.cos(b) * Math.sin(e) + Math.sin(b) * Math.cos(e);
    return [rev(Math.atan2(y, x) * R2D), Math.atan2(z, Math.hypot(x, y)) * R2D];
  }

  function sun(d) {
    const w = 282.9404 + 4.70935e-5 * d;
    const e = 0.016709 - 1.151e-9 * d;
    const M = rev(356.0470 + 0.9856002585 * d);
    const E = kepler(M, e);
    const xv = cosd(E) - e, yv = Math.sqrt(1 - e * e) * sind(E);
    const v = Math.atan2(yv, xv) * R2D, r = Math.hypot(xv, yv);
    const lon = rev(v + w);
    const eq = eclToEq(lon, 0, obliquity(d));
    return { lon, r, M, w, ra: eq[0], dec: eq[1], dist: r, xs: r * cosd(lon), ys: r * sind(lon) };
  }

  function moon(d, s) {
    const N = rev(125.1228 - 0.0529538083 * d), i = 5.1454;
    const w = rev(318.0634 + 0.1643573223 * d), a = 60.2666, e = 0.054900;
    const M = rev(115.3654 + 13.0649929509 * d);
    const E = kepler(M, e);
    const xv = a * (cosd(E) - e), yv = a * Math.sqrt(1 - e * e) * sind(E);
    const v = Math.atan2(yv, xv) * R2D;
    let r = Math.hypot(xv, yv);
    const vw = v + w;
    const xh = r * (cosd(N) * cosd(vw) - sind(N) * sind(vw) * cosd(i));
    const yh = r * (sind(N) * cosd(vw) + cosd(N) * sind(vw) * cosd(i));
    const zh = r * sind(vw) * sind(i);
    let lon = Math.atan2(yh, xh) * R2D, lat = Math.atan2(zh, Math.hypot(xh, yh)) * R2D;
    const Ms = s.M, Mm = M, Ls = s.M + s.w, Lm = M + w + N, Dm = Lm - Ls, F = Lm - N;
    lon += -1.274 * sind(Mm - 2 * Dm) + 0.658 * sind(2 * Dm) - 0.186 * sind(Ms)
      - 0.059 * sind(2 * Mm - 2 * Dm) - 0.057 * sind(Mm - 2 * Dm + Ms) + 0.053 * sind(Mm + 2 * Dm)
      + 0.046 * sind(2 * Dm - Ms) + 0.041 * sind(Mm - Ms) - 0.035 * sind(Dm) - 0.031 * sind(Mm + Ms)
      - 0.015 * sind(2 * F - 2 * Dm) + 0.011 * sind(Mm - 4 * Dm);
    lat += -0.173 * sind(F - 2 * Dm) - 0.055 * sind(Mm - F - 2 * Dm) - 0.046 * sind(Mm + F - 2 * Dm)
      + 0.033 * sind(F + 2 * Dm) + 0.017 * sind(2 * Mm + F);
    r += -0.58 * cosd(Mm - 2 * Dm) - 0.46 * cosd(2 * Dm);
    lon = rev(lon);
    const eq = eclToEq(lon, lat, obliquity(d));
    // زاویهٔ فاز و کسر روشن
    const elong = rev(lon - s.lon);
    const cosPsi = sind(eq[1]) * sind(s.dec) + cosd(eq[1]) * cosd(s.dec) * cosd(eq[0] - s.ra);
    const frac = (1 - cosPsi) / 2;
    return { lon, lat, ra: eq[0], dec: eq[1], dist: r, parallax: Math.asin(1 / r) * R2D, elong, frac, mag: -12.7 + 0.04 * Math.abs(180 - Math.acos(cosPsi) * R2D) };
  }

  const EL = {
    mercury: d => ({ N: 48.3313 + 3.24587e-5 * d, i: 7.0047 + 5.00e-8 * d, w: 29.1241 + 1.01444e-5 * d, a: 0.387098, e: 0.205635 + 5.59e-10 * d, M: 168.6562 + 4.0923344368 * d }),
    venus: d => ({ N: 76.6799 + 2.46590e-5 * d, i: 3.3946 + 2.75e-8 * d, w: 54.8910 + 1.38374e-5 * d, a: 0.723330, e: 0.006773 - 1.302e-9 * d, M: 48.0052 + 1.6021302244 * d }),
    mars: d => ({ N: 49.5574 + 2.11081e-5 * d, i: 1.8497 - 1.78e-8 * d, w: 286.5016 + 2.92961e-5 * d, a: 1.523688, e: 0.093405 + 2.516e-9 * d, M: 18.6021 + 0.5240207766 * d }),
    jupiter: d => ({ N: 100.4542 + 2.76854e-5 * d, i: 1.3030 - 1.557e-7 * d, w: 273.8777 + 1.64505e-5 * d, a: 5.20256, e: 0.048498 + 4.469e-9 * d, M: 19.8950 + 0.0830853001 * d }),
    saturn: d => ({ N: 113.6634 + 2.38980e-5 * d, i: 2.4886 - 1.081e-7 * d, w: 339.3939 + 2.97661e-5 * d, a: 9.55475, e: 0.055546 - 9.499e-9 * d, M: 316.9670 + 0.0334442282 * d })
  };

  function planet(name, d, s) {
    const el = EL[name](d);
    const M = rev(el.M);
    const E = kepler(M, el.e);
    const xv = el.a * (cosd(E) - el.e), yv = el.a * Math.sqrt(1 - el.e * el.e) * sind(E);
    const v = Math.atan2(yv, xv) * R2D, r = Math.hypot(xv, yv);
    const vw = v + el.w;
    const xh = r * (cosd(el.N) * cosd(vw) - sind(el.N) * sind(vw) * cosd(el.i));
    const yh = r * (sind(el.N) * cosd(vw) + cosd(el.N) * sind(vw) * cosd(el.i));
    const zh = r * sind(vw) * sind(el.i);
    let lon = Math.atan2(yh, xh) * R2D, lat = Math.atan2(zh, Math.hypot(xh, yh)) * R2D;
    if (name === 'jupiter' || name === 'saturn') {
      const Mj = rev(EL.jupiter(d).M), Ms = rev(EL.saturn(d).M);
      if (name === 'jupiter') {
        lon += -0.332 * sind(2 * Mj - 5 * Ms - 67.6) - 0.056 * sind(2 * Mj - 2 * Ms + 21) + 0.042 * sind(3 * Mj - 5 * Ms + 21)
          - 0.036 * sind(Mj - 2 * Ms) + 0.022 * cosd(Mj - Ms) + 0.023 * sind(2 * Mj - 3 * Ms + 52) - 0.016 * sind(Mj - 5 * Ms - 69);
      } else {
        lon += 0.812 * sind(2 * Mj - 5 * Ms - 67.6) - 0.229 * cosd(2 * Mj - 4 * Ms - 2) + 0.119 * sind(Mj - 2 * Ms - 3)
          + 0.046 * sind(2 * Mj - 6 * Ms - 69) + 0.014 * sind(Mj - 3 * Ms + 32);
        lat += -0.020 * cosd(2 * Mj - 4 * Ms - 2) + 0.018 * sind(2 * Mj - 6 * Ms - 49);
      }
    }
    const xh2 = r * cosd(lon) * cosd(lat), yh2 = r * sind(lon) * cosd(lat), zh2 = r * sind(lat);
    const xg = xh2 + s.xs, yg = yh2 + s.ys, zg = zh2;
    const ecl = obliquity(d);
    const xe = xg, ye = yg * cosd(ecl) - zg * sind(ecl), ze = yg * sind(ecl) + zg * cosd(ecl);
    const ra = rev(Math.atan2(ye, xe) * R2D), dec = Math.atan2(ze, Math.hypot(xe, ye)) * R2D;
    const R = Math.sqrt(xg * xg + yg * yg + zg * zg);
    const cosFV = Math.max(-1, Math.min(1, (r * r + R * R - s.r * s.r) / (2 * r * R)));
    const FV = Math.acos(cosFV) * R2D;
    const l5 = 5 * Math.log10(r * R);
    let mag;
    switch (name) {
      case 'mercury': mag = -0.36 + l5 + 0.027 * FV + 2.2e-13 * Math.pow(FV, 6); break;
      case 'venus': mag = -4.34 + l5 + 0.013 * FV + 4.2e-7 * Math.pow(FV, 3); break;
      case 'mars': mag = -1.51 + l5 + 0.016 * FV; break;
      case 'jupiter': mag = -9.25 + l5 + 0.014 * FV; break;
      default: mag = -9.0 + l5 + 0.044 * FV;
    }
    return { ra, dec, dist: R, helio: r, mag, phase: (1 + cosFV) / 2 };
  }

  // طلوع/عبور/غروب: ساعت‌های باقی‌مانده از لحظهٔ jd (ساعت خورشیدی)
  function riseSet(raDeg, decDeg, latDeg, lonDeg, jd, h0) {
    const lst = rev(gmst(jd) + lonDeg);
    const H = rev(lst - raDeg);
    const k = 15.04107;
    const transit = rev(360 - H) / k;
    const c = (sind(h0) - sind(latDeg) * sind(decDeg)) / (cosd(latDeg) * cosd(decDeg));
    if (c <= -1) return { circumpolar: true, transit };
    if (c >= 1) return { neverRises: true, transit };
    const H0 = Math.acos(c) * R2D;
    return { rise: rev(-H0 - H) / k, transit, set: rev(H0 - H) / k };
  }

  function qibla(latDeg, lonDeg) {
    const p1 = latDeg * D2R, p2 = 21.4225 * D2R, dl = (39.8262 - lonDeg) * D2R;
    const y = Math.sin(dl) * Math.cos(p2);
    const x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
    const az = rev(Math.atan2(y, x) * R2D);
    const a = Math.sin((p2 - p1) / 2) ** 2 + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) ** 2;
    return { az, km: 6371 * 2 * Math.asin(Math.sqrt(a)) };
  }

  // مختصات کهکشانی -> استوایی J2000 (درجه)
  function galToEq(l, b) {
    const raG = 192.85948 * D2R, decG = 27.12825 * D2R, lN = 122.93192 * D2R;
    const L = l * D2R, B = b * D2R;
    const sinDec = Math.sin(decG) * Math.sin(B) + Math.cos(decG) * Math.cos(B) * Math.cos(lN - L);
    const dec = Math.asin(sinDec);
    const y = Math.cos(B) * Math.sin(lN - L);
    const x = Math.cos(decG) * Math.sin(B) - Math.sin(decG) * Math.cos(B) * Math.cos(lN - L);
    return [rev((raG + Math.atan2(y, x)) * R2D), dec * R2D];
  }

  return { D2R, R2D, rev, jdFromMs, dayNum, gmst, precess, eqToEnu, enuToAltAz, altAzToEnu, eclToEq, obliquity, sun, moon, planet, riseSet, qibla, galToEq };
});
