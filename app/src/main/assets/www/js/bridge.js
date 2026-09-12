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

  /* Bridge.push() injects raw JSON into evaluateJavascript, so PTOnEvent receives
   * a live object, not a string. readSms() by contrast returns a string. Accept
   * either rather than assuming. */
  function asObj(v, fallback) {
    if (v == null) return fallback;
    if (typeof v === 'object') return v;
    try { return JSON.parse(v); } catch (e) { return fallback; }
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
      return asObj(r, []);
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
    var r = safe(function () { return asObj(native.capturePhoto(), { ok: false }); }, { ok: false });
    if (r && r.ok && !r.pending) { window.__ptCaptureCb = null; cb(r); }
    // Safety net: if the push never lands, do not leave the UI waiting forever.
    setTimeout(function () {
      if (window.__ptCaptureCb === cb) {
        window.__ptCaptureCb = null;
        cb({ ok: false, error: 'capture timed out' });
      }
    }, 6000);
  }

  function startRecording() {
    if (!isDevice || typeof native.startRecording !== 'function') {
      return { ok: true, stub: true };
    }
    return safe(function () { return asObj(native.startRecording(), { ok: false }); }, { ok: false });
  }

  function stopRecording() {
    if (!isDevice || typeof native.stopRecording !== 'function') {
      return { ok: true, stub: true, path: '/desktop/fake-audio.wav' };
    }
    return safe(function () { return asObj(native.stopRecording(), { ok: false }); }, { ok: false });
  }

  function hasSms() {
    if (!isDevice || typeof native.hasSmsPermission !== 'function') return true;
    return safe(function () { return !!native.hasSmsPermission(); }, false);
  }

  function requestSms() {
    if (isDevice && native.requestSmsPermission) safe(function () { native.requestSmsPermission(); });
  }

  /* Camera viewfinder. Result arrives as a 'cameraOpen' push. */
  function openCamera(cb) {
    if (!isDevice || typeof native.openCamera !== 'function') {
      cb({ ok: true, preview: false, stub: true });
      return;
    }
    window.__ptCameraCb = cb;
    safe(function () { native.openCamera(); });
    setTimeout(function () {
      if (window.__ptCameraCb === cb) {
        window.__ptCameraCb = null;
        cb({ ok: false, error: 'camera open timed out' });
      }
    }, 8000);
  }

  function closeCamera() {
    if (isDevice && native.closeCamera) safe(function () { native.closeCamera(); });
  }

  /* Receipt -> text via the on-device VLM. Result arrives as a 'vision' push.
   * Generous timeout: a multi-GB model on CPU is not fast. */
  function visionExtract(path, cb) {
    if (!isDevice || typeof native.visionExtract !== 'function') {
      cb({ ok: false, error: 'no vision on desktop', source: 'none' });
      return;
    }
    window.__ptVisionCb = cb;
    safe(function () { native.visionExtract(path); });
    setTimeout(function () {
      if (window.__ptVisionCb === cb) {
        window.__ptVisionCb = null;
        cb({ ok: false, error: 'vision timed out', source: 'none' });
      }
    }, 60000);
  }

  function visionStatus() {
    if (!isDevice || typeof native.visionStatus !== 'function') return { ok: false };
    return safe(function () { return asObj(native.visionStatus(), { ok: false }); }, { ok: false });
  }

  /* Audio -> text. Currently always fails: no ASR runtime on device. */
  function transcribe(path, cb) {
    if (!isDevice || typeof native.transcribe !== 'function') {
      cb({ ok: false, error: 'no transcription available', source: 'none' });
      return;
    }
    window.__ptTranscriptCb = cb;
    safe(function () { native.transcribe(path); });
    setTimeout(function () {
      if (window.__ptTranscriptCb === cb) {
        window.__ptTranscriptCb = null;
        cb({ ok: false, error: 'transcription timed out', source: 'none' });
      }
    }, 60000);
  }

  /* Live on-device speech. onPartial fires as words are recognised; the final
   * transcript (or an error) arrives once via cb. */
  function startListening(cb, onPartial) {
    if (!isDevice || typeof native.startListening !== 'function') {
      setTimeout(function () {
        cb({ ok: true, stub: true, text: 'two hundred fifty for coffee',
             source: 'desktop-stub' });
      }, 1200);
      return;
    }
    window.__ptTranscriptCb = cb;
    window.__ptPartialCb = onPartial || null;
    safe(function () { native.startListening(); });
  }

  function stopListening() {
    if (isDevice && native.stopListening) safe(function () { native.stopListening(); });
  }

  function cancelListening() {
    window.__ptTranscriptCb = null;
    window.__ptPartialCb = null;
    if (isDevice && native.cancelListening) safe(function () { native.cancelListening(); });
  }

  function speechStatus() {
    if (!isDevice || typeof native.speechStatus !== 'function') {
      return { available: false, stub: true };
    }
    return safe(function () {
      return asObj(native.speechStatus(), { available: false });
    }, { available: false });
  }

  // --- persistence ------------------------------------------------------

  function loadLedger() {
    if (!isDevice || typeof native.loadLedger !== 'function') {
      try { return JSON.parse(localStorage.getItem('pt.ledger') || '{}'); }
      catch (e) { return {}; }
    }
    return safe(function () { return asObj(native.loadLedger(), {}); }, {});
  }

  function saveLedger(obj) {
    var json;
    try { json = JSON.stringify(obj); } catch (e) { return false; }
    if (!isDevice || typeof native.saveLedger !== 'function') {
      try { localStorage.setItem('pt.ledger', json); return true; }
      catch (e) { return false; }
    }
    return safe(function () { return !!native.saveLedger(json); }, false);
  }

  function clearLedger() {
    if (!isDevice || typeof native.clearLedger !== 'function') {
      try { localStorage.removeItem('pt.ledger'); } catch (e) {}
      return true;
    }
    return safe(function () { return !!native.clearLedger(); }, false);
  }

  function storageUsage() {
    if (!isDevice || typeof native.storageUsage !== 'function') return {};
    return safe(function () { return asObj(native.storageUsage(), {}); }, {});
  }

  function simulateSmsChange() {
    if (isDevice && native.simulateSmsChange) safe(function () { native.simulateSmsChange(); });
  }

  return {
    asObj: asObj,
    isDevice: isDevice,
    openCamera: openCamera,
    closeCamera: closeCamera,
    visionExtract: visionExtract,
    visionStatus: visionStatus,
    transcribe: transcribe,
    startListening: startListening,
    stopListening: stopListening,
    cancelListening: cancelListening,
    speechStatus: speechStatus,
    loadLedger: loadLedger,
    saveLedger: saveLedger,
    clearLedger: clearLedger,
    storageUsage: storageUsage,
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
