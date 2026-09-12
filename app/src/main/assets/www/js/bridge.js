/* Paper Trail - bridge facade + desktop shim.
 *
 * On device: delegates to window.PT (addJavascriptInterface).
 * On desktop Chrome: window.PT is absent, so serve fixtures instead.
 * Degrade, never throw - a missing native method must not break the UI.
 */

var PTBridge = (function () {
  'use strict';

  var native = (typeof window !== 'undefined') ? window.PT : null;
  var isDevice = !!(native && typeof native.ping === 'function');

  // --- desktop fixtures -------------------------------------------------
  function fixtures() {
    var now = Date.now();
    return [
      { id: 'f1', smsId: 901, ts: now - 2 * 60000, issuer: 'HDFC', amount: 2400,
        direction: 'debit', acctLast4: '4417', ref: '998877665544',
        kind: 'merchant', counterparty: 'PAYTM*38291', senderRouted: true },
      { id: 'f2', smsId: 902, ts: now - 26 * 60000, issuer: 'KVB', amount: 166,
        direction: 'debit', acctLast4: '4760', ref: '610284732960',
        kind: 'merchant', counterparty: 'SWIGGY LIMITED', senderRouted: false },
      { id: 'f3', smsId: 903, ts: now - 55 * 60000, issuer: 'KVB', amount: 3000,
        direction: 'credit', acctLast4: '4760', ref: '610254432802',
        kind: 'p2p', counterparty: 'Goutham M.', senderRouted: false },
      { id: 'f4', smsId: 904, ts: now - 90 * 60000, issuer: 'KVB', amount: 20,
        direction: 'debit', acctLast4: '4760', ref: '617750246982',
        kind: 'merchant', counterparty: 'ABHISTA DAIRY FARM', senderRouted: false }
    ];
  }

  function safe(fn, fallback) {
    try { return fn(); } catch (e) { log('bridge fallback: ' + e); return fallback; }
  }

  function log(msg) {
    if (isDevice && native.log) { try { native.log(String(msg)); } catch (e) {} }
    if (typeof console !== 'undefined') console.log('[PT] ' + msg);
  }

  function readSms(limit) {
    if (!isDevice) return fixtures();
    return safe(function () {
      var r = native.readSms(limit || 200);
      return typeof r === 'string' ? JSON.parse(r) : (r || []);
    }, []);
  }

  function capturePhoto(cb) {
    if (!isDevice || typeof native.capturePhoto !== 'function') {
      cb({ ok: true, stub: true, path: '/desktop/fake-receipt.jpg' });
      return;
    }
    // Native returns immediately with a pending marker; completion arrives as a
    // PTOnEvent('capture') push. Fall back to the sync result if it is final.
    window.__ptCaptureCb = cb;
    var r = safe(function () { return JSON.parse(native.capturePhoto()); }, { ok: false });
    if (r && r.ok && !r.pending) { window.__ptCaptureCb = null; cb(r); }
  }

  function startRecording() {
    if (!isDevice || typeof native.startRecording !== 'function') {
      return { ok: true, stub: true };
    }
    return safe(function () { return JSON.parse(native.startRecording()); }, { ok: false });
  }

  function stopRecording() {
    if (!isDevice || typeof native.stopRecording !== 'function') {
      return { ok: true, stub: true, path: '/desktop/fake-audio.wav' };
    }
    return safe(function () { return JSON.parse(native.stopRecording()); }, { ok: false });
  }

  function hasSms() {
    if (!isDevice || typeof native.hasSmsPermission !== 'function') return true;
    return safe(function () { return !!native.hasSmsPermission(); }, false);
  }

  function requestSms() {
    if (isDevice && native.requestSmsPermission) safe(function () { native.requestSmsPermission(); });
  }

  function simulateSmsChange() {
    if (isDevice && native.simulateSmsChange) safe(function () { native.simulateSmsChange(); });
  }

  return {
    isDevice: isDevice,
    readSms: readSms,
    capturePhoto: capturePhoto,
    startRecording: startRecording,
    stopRecording: stopRecording,
    hasSms: hasSms,
    requestSms: requestSms,
    simulateSmsChange: simulateSmsChange,
    log: log
  };
})();
