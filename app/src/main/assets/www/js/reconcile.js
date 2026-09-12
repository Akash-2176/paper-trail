/* Paper Trail - reconciliation engine.
 *
 * ADR-004: 100% deterministic. No model touches scoring. Every number here is
 * reproducible and testable.
 *
 * Matches a PendingEntry (created by the user's action) against SmsTxn rows
 * (structured, redacted objects from the native bridge - ADR-003).
 */

var PTReconcile = (function () {
  'use strict';

  var CFG = {
    autoMerge: 0.72,     // >= this: merge without asking
    confirm: 0.45,       // >= this: show "confirm?", below: no candidate
    windowMs: 10 * 60 * 1000,  // SMS arrives 0-10 min after the action (ARCHITECTURE 3.1)
    /* Grace for SMS stamped BEFORE the capture. Genuinely needed (clock skew,
     * the user photographing a receipt after the debit alert has landed), and
     * it is also what makes an inbox of older seeded messages demoable. Widen
     * for a demo; tighten to ~90s for real use. */
    graceMs: 6 * 60 * 60 * 1000,
    tipPct: 0.20,        // receipt total vs charged (tip / service)
    roundAbs: 5          // absolute rounding slack in rupees
  };

  var W = {
    amountExact: 0.40,
    amountClose: 0.22,
    time: 0.30,
    issuer: 0.12,
    merchant: 0.18
  };

  // --- helpers ---------------------------------------------------------

  function norm(s) {
    if (!s) return '';
    return String(s)
      .toUpperCase()
      .replace(/[^A-Z0-9 ]+/g, ' ')   // PAYTM*38291 -> PAYTM 38291
      .replace(/\s+/g, ' ')
      .trim();
  }

  // Noise words that carry no identity. Kept tiny and hand-written, no deps.
  var STOP = {
    'LIMITED': 1, 'LTD': 1, 'PVT': 1, 'PRIVATE': 1, 'INC': 1, 'LLP': 1,
    'CORP': 1, 'COMPANY': 1, 'CO': 1, 'THE': 1, 'AND': 1, 'INDIA': 1,
    'STORE': 1, 'STORES': 1, 'UPI': 1, 'P2A': 1, 'P2M': 1
  };

  function tokens(s) {
    var raw = norm(s).split(' ');
    var out = [];
    for (var i = 0; i < raw.length; i++) {
      var t = raw[i];
      if (!t || STOP[t]) continue;
      if (/^\d+$/.test(t)) continue;   // bare ref digits are not identity
      out.push(t);
    }
    return out;
  }

  /* Token overlap with a prefix-match fallback, so SWIGGY ~ SWIGGYLTD and
   * STARBUCK ~ STARBUCKS score. Returns 0..1. Written out, nothing vendored. */
  function merchantScore(a, b) {
    var ta = tokens(a), tb = tokens(b);
    if (!ta.length || !tb.length) return 0;
    var hits = 0;
    for (var i = 0; i < ta.length; i++) {
      for (var j = 0; j < tb.length; j++) {
        var x = ta[i], y = tb[j];
        if (x === y) { hits++; break; }
        if (x.length >= 4 && y.length >= 4 &&
            (x.indexOf(y) === 0 || y.indexOf(x) === 0)) { hits += 0.8; break; }
      }
    }
    return Math.min(1, hits / Math.min(ta.length, tb.length));
  }

  function amountScore(p, s) {
    var a = Math.abs(Number(p)), b = Math.abs(Number(s));
    if (!(a > 0) || !(b > 0)) return { score: 0, kind: 'none' };
    if (Math.abs(a - b) < 0.005) return { score: W.amountExact, kind: 'exact' };
    var diff = Math.abs(a - b);
    var tol = Math.max(CFG.roundAbs, a * CFG.tipPct);
    if (diff <= tol) {
      // Linear decay across the tolerance band.
      var frac = 1 - (diff / tol);
      return { score: W.amountClose * frac, kind: 'close' };
    }
    return { score: 0, kind: 'miss' };
  }

  function timeScore(pTs, sTs) {
    var d = sTs - pTs;                       // positive: SMS after the action
    var ad = Math.abs(d);
    // Forward (SMS after capture) is the expected case and gets the full window.
    // Backward (SMS already in the inbox when captured) is allowed out to graceMs
    // but decays much harder, so a stale message can never outrank a fresh one.
    var span = (d >= 0) ? CFG.windowMs : CFG.graceMs;
    if (ad > span) return { score: 0, kind: 'outside' };
    var frac = 1 - (ad / span);
    if (d < 0) frac *= 0.5;                  // backward matches are weaker evidence
    return { score: W.time * frac, kind: 'in-window', deltaMs: d };
  }

  // --- public ----------------------------------------------------------

  /* matchPending(pendingEntry, smsTransactions) -> scored candidates,
   * best first. Rejected rows are returned too, with why, so the UI can be
   * honest about what was considered. */
  function matchPending(pending, txns) {
    var out = [];
    if (!pending || !txns) return out;

    for (var i = 0; i < txns.length; i++) {
      var t = txns[i];
      var reasons = [];

      // HARD GATE: direction. A debit can never match a credit.
      // Credits were parsing as debits this morning; a wrong direction here
      // silently corrupts the ledger, so it rejects rather than down-weights.
      var pd = pending.direction || 'debit';
      if (t.direction && t.direction !== 'unknown' && t.direction !== pd) {
        out.push({ txn: t, confidence: 0, rejected: true,
                   reason: 'direction ' + t.direction + ' != ' + pd, reasons: [] });
        continue;
      }

      var score = 0;

      var am = amountScore(pending.amount, t.amount);
      score += am.score;
      if (am.kind === 'exact') reasons.push('amount exact');
      else if (am.kind === 'close') reasons.push('amount within tolerance');

      var tm = timeScore(pending.ts, t.ts);
      score += tm.score;
      if (tm.kind === 'in-window') {
        reasons.push('+' + Math.round((tm.deltaMs || 0) / 1000) + 's');
      }

      if (pending.issuer && t.issuer &&
          String(pending.issuer).toUpperCase() === String(t.issuer).toUpperCase()) {
        score += W.issuer;
        reasons.push('issuer ' + t.issuer);
      }

      var ms = merchantScore(pending.note || pending.merchant, t.counterparty);
      if (ms > 0) {
        score += W.merchant * ms;
        reasons.push('merchant ~' + Math.round(ms * 100) + '%');
      }

      // An amount miss AND a time miss is not a candidate at all.
      if (am.score === 0 && tm.score === 0) {
        out.push({ txn: t, confidence: 0, rejected: true,
                   reason: 'no amount or time overlap', reasons: reasons });
        continue;
      }

      var conf = Math.max(0, Math.min(1, score));
      out.push({
        txn: t,
        confidence: conf,
        rejected: false,
        action: conf >= CFG.autoMerge ? 'auto' : (conf >= CFG.confirm ? 'confirm' : 'none'),
        reasons: reasons
      });
    }

    out.sort(function (a, b) { return b.confidence - a.confidence; });
    return out;
  }

  /* Best actionable candidate, or null. Unmatched cash returning null is
   * CORRECT behaviour, not a failure. */
  function best(pending, txns) {
    var c = matchPending(pending, txns);
    for (var i = 0; i < c.length; i++) {
      if (!c[i].rejected && c[i].action !== 'none') return c[i];
    }
    return null;
  }

  return {
    matchPending: matchPending,
    best: best,
    merchantScore: merchantScore,
    CFG: CFG,
    W: W
  };
})();

if (typeof module !== 'undefined' && module.exports) module.exports = PTReconcile;
