/* Paper Trail - the Two-Second Proof.
 *
 * Measures the real cost of capturing a spend, and contrasts it with the real
 * cost of typing one in. Both numbers are MEASURED, never asserted:
 *
 *   captured  - wall clock from the capture tap to the entry landing, plus the
 *               taps actually required
 *   manual    - wall clock from first keypress to submission, plus taps
 *
 * The manual baseline only counts sessions where the user really did type an
 * entry. Until that has happened at least once, the comparison honestly says
 * so instead of quoting a number nobody measured. A friction claim that cannot
 * be reproduced on stage is worse than no claim.
 */

var PTProof = (function () {
  'use strict';

  var STORE_KEY = 'proof';

  var data = {
    capture: { n: 0, totalMs: 0, totalTaps: 0, best: null },
    manual:  { n: 0, totalMs: 0, totalTaps: 0, best: null }
  };

  var live = null;   // in-flight measurement

  function begin(mode, taps) {
    live = { mode: mode, t0: Date.now(), taps: taps || 0 };
    return live;
  }

  function bumpTaps(n) {
    if (live) live.taps += (n || 1);
  }

  /** Close the current measurement and fold it into the running averages. */
  function end(ok) {
    if (!live) return null;
    var ms = Date.now() - live.t0;
    var rec = live; live = null;
    if (!ok) return null;
    // Ignore absurd outliers: a session left open while the phone is pocketed
    // is not a measurement of friction.
    if (ms > 5 * 60 * 1000) return null;

    var bucket = data[rec.mode];
    if (!bucket) return null;
    bucket.n++;
    bucket.totalMs += ms;
    bucket.totalTaps += rec.taps;
    if (bucket.best == null || ms < bucket.best) bucket.best = ms;
    save();
    return { mode: rec.mode, ms: ms, taps: rec.taps };
  }

  function cancel() { live = null; }

  function avg(bucket) {
    return bucket.n ? Math.round(bucket.totalMs / bucket.n) : null;
  }

  function avgTaps(bucket) {
    return bucket.n ? Math.round((bucket.totalTaps / bucket.n) * 10) / 10 : null;
  }

  function stats() {
    return {
      capture: {
        n: data.capture.n,
        avgMs: avg(data.capture),
        bestMs: data.capture.best,
        avgTaps: avgTaps(data.capture)
      },
      manual: {
        n: data.manual.n,
        avgMs: avg(data.manual),
        bestMs: data.manual.best,
        avgTaps: avgTaps(data.manual)
      }
    };
  }

  function secs(ms) {
    return ms == null ? null : (ms / 1000).toFixed(1) + 's';
  }

  /**
   * The headline. Returns null when there is nothing honest to say yet, so the
   * caller can hide the panel rather than print a made-up comparison.
   */
  function headline() {
    var s = stats();
    if (!s.capture.n) return null;
    var cap = secs(s.capture.avgMs) + ' · ' + s.capture.avgTaps + ' taps';
    if (!s.manual.n) {
      return {
        ready: false,
        capture: cap,
        note: 'type one entry by hand to measure the comparison'
      };
    }
    var man = secs(s.manual.avgMs) + ' · ' + s.manual.avgTaps + ' taps';
    var factor = s.manual.avgMs / Math.max(1, s.capture.avgMs);
    return {
      ready: true,
      capture: cap,
      manual: man,
      factor: Math.round(factor * 10) / 10,
      savedMs: Math.max(0, s.manual.avgMs - s.capture.avgMs),
      n: { capture: s.capture.n, manual: s.manual.n }
    };
  }

  // --- persistence ------------------------------------------------------

  function save() {
    try {
      var led = PTBridge.loadLedger() || {};
      led[STORE_KEY] = data;
      PTBridge.saveLedger(led);
    } catch (e) { /* telemetry must never break a capture */ }
  }

  function load() {
    try {
      var led = PTBridge.loadLedger() || {};
      var d = led[STORE_KEY];
      if (d && d.capture && d.manual) data = d;
    } catch (e) { /* ignore */ }
    return data;
  }

  function reset() {
    data = {
      capture: { n: 0, totalMs: 0, totalTaps: 0, best: null },
      manual:  { n: 0, totalMs: 0, totalTaps: 0, best: null }
    };
    save();
  }

  return {
    begin: begin, end: end, cancel: cancel, bumpTaps: bumpTaps,
    stats: stats, headline: headline, load: load, save: save, reset: reset,
    secs: secs
  };
})();

if (typeof module !== 'undefined' && module.exports) module.exports = PTProof;
