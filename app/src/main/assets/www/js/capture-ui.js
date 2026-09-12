/* Paper Trail - capture UI: camera preview sheet + voice listening state.
 *
 * Plain JS, no framework (ADR-011). Owns only presentation and the capture
 * lifecycle; extraction lives in extract.js and scoring in reconcile.js.
 */

var PTCapture = (function () {
  'use strict';

  var onResult = null;     // function(kind, {amount, note, path, extracted})
  var voiceTimer = null;
  var voiceStart = 0;
  var transcriptTimer = null;

  function el(id) { return document.getElementById(id); }

  // --- shared sheet -----------------------------------------------------

  function ensureSheet() {
    if (el('ptSheet')) return el('ptSheet');
    var d = document.createElement('div');
    d.id = 'ptSheet';
    d.className = 'sheet hidden';
    d.innerHTML =
      '<div class="sheetInner">' +
        '<div class="sheetHead"><span id="ptSheetTitle">Capture</span>' +
          '<button id="ptSheetClose" class="x">✕</button></div>' +
        '<div id="ptSheetBody"></div>' +
      '</div>';
    document.body.appendChild(d);
    el('ptSheetClose').onclick = closeSheet;
    return d;
  }

  function openSheet(title, bodyHtml, cameraMode) {
    var s = ensureSheet();
    el('ptSheetTitle').textContent = title;
    el('ptSheetBody').innerHTML = bodyHtml;
    s.className = 'sheet';
    // Only the photo sheet needs the page transparent for the viewfinder.
    document.body.className = cameraMode ? 'camera' : '';
  }

  function closeSheet() {
    var s = el('ptSheet');
    if (s) s.className = 'sheet hidden';
    document.body.className = '';
    // Always tear the camera down; leaving it bound holds the sensor open.
    try { PTBridge.closeCamera(); } catch (e) {}
    if (voiceTimer) { clearInterval(voiceTimer); voiceTimer = null; }
  }

  // --- photo ------------------------------------------------------------

  function startPhoto() {
    openSheet('Receipt',
      '<div id="ptPreviewNote" class="hint">starting camera…</div>' +
      '<div class="shotRow">' +
        '<button id="ptShoot" class="primary big">◉ Capture</button>' +
        '<button id="ptCancel">Cancel</button>' +
      '</div>' +
      '<div id="ptShotResult"></div>', true);

    el('ptCancel').onclick = closeSheet;

    PTBridge.openCamera(function (r) {
      var n = el('ptPreviewNote');
      if (!n) return;
      if (r && r.ok) {
        n.textContent = r.preview
          ? 'viewfinder live — frame the receipt'
          : 'camera ready (no viewfinder) — point and capture';
        n.className = 'hint ok';
      } else {
        n.textContent = 'camera unavailable: ' + ((r && r.error) || 'unknown');
        n.className = 'hint err';
      }
    });

    el('ptShoot').onclick = function () {
      var btn = el('ptShoot');
      btn.disabled = true; btn.textContent = '… capturing';
      PTBridge.capturePhoto(function (r) {
        btn.disabled = false; btn.textContent = '◉ Capture';
        if (!r || !r.ok) {
          el('ptShotResult').innerHTML =
            '<div class="hint err">capture failed: ' + esc((r && r.error) || '?') + '</div>';
          return;
        }
        el('ptShotResult').innerHTML =
          '<div class="hint ok">saved · ' + Math.round((r.bytes || 0) / 1024) + ' KB</div>' +
          '<div id="ptExtract" class="hint">reading receipt…</div>';
        // Hand off to extraction. Falls back to manual amount entry if the
        // model is unavailable - the demo path must not depend on it.
        PTExtract.fromImage(r.path, function (ex) {
          var box = el('ptExtract');
          if (box) {
            box.innerHTML = ex.ok
              ? 'found ' + (ex.amount != null ? ('₹' + ex.amount) : 'no amount') +
                (ex.merchant ? (' · ' + esc(ex.merchant)) : '') +
                ' <span class="src">' + esc(ex.source) + '</span>'
              : '<span class="err">' + esc(ex.error || 'no extraction') +
                '</span> — type the amount below';
          }
          if (onResult) onResult('photo', {
            amount: ex.amount, note: ex.merchant || 'receipt',
            path: r.path, extracted: ex
          });
          setTimeout(closeSheet, ex.ok && ex.amount != null ? 900 : 2600);
        });
      });
    };
  }

  // --- voice ------------------------------------------------------------

  function startVoice() {
    var r = PTBridge.startRecording();
    if (!r || !r.ok) {
      openSheet('Voice', '<div class="hint err">mic unavailable: ' +
        esc((r && r.error) || '?') + '</div>');
      setTimeout(closeSheet, 2000);
      return;
    }
    voiceStart = Date.now();
    openSheet('Listening',
      '<div class="listening"><span class="dot"></span><span class="dot"></span>' +
        '<span class="dot"></span></div>' +
      '<div id="ptVoiceTime" class="timer">0.0s</div>' +
      '<div class="hint">speak the amount and what it was for</div>' +
      '<div class="shotRow">' +
        '<button id="ptStopRec" class="primary big">⏹ Stop</button>' +
        '<button id="ptCancelRec">Cancel</button>' +
      '</div>' +
      '<div id="ptTranscript"></div>');

    voiceTimer = setInterval(function () {
      var t = el('ptVoiceTime');
      if (t) t.textContent = ((Date.now() - voiceStart) / 1000).toFixed(1) + 's';
    }, 100);

    el('ptCancelRec').onclick = function () {
      try { PTBridge.stopRecording(); } catch (e) {}
      closeSheet();
    };

    el('ptStopRec').onclick = function () {
      if (voiceTimer) { clearInterval(voiceTimer); voiceTimer = null; }
      var s = PTBridge.stopRecording();
      if (!s || !s.ok) {
        el('ptTranscript').innerHTML =
          '<div class="hint err">recording failed: ' + esc((s && s.error) || '?') + '</div>';
        setTimeout(closeSheet, 2000);
        return;
      }
      el('ptTranscript').innerHTML =
        '<div id="ptTrx" class="hint">transcribing…</div>';
      PTExtract.fromAudio(s.path, function (ex) {
        var box = el('ptTrx');
        if (box) {
          box.innerHTML = ex.ok
            ? '<div class="transcript">“' + esc(ex.text || '') + '”</div>' +
              '<div class="hint ok">' +
                (ex.amount != null ? ('₹' + ex.amount) : 'no amount found') +
                ' <span class="src">' + esc(ex.source) + '</span></div>'
            : '<span class="err">' + esc(ex.error || 'no transcript') +
              '</span> — type the amount below';
        }
        if (onResult) onResult('voice', {
          amount: ex.amount, note: ex.text || 'voice note',
          path: s.path, extracted: ex
        });
        // Keep the transcript on screen briefly so it is readable, as asked.
        if (transcriptTimer) clearTimeout(transcriptTimer);
        transcriptTimer = setTimeout(closeSheet, ex.ok ? 3200 : 3600);
      });
    };
  }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  return {
    startPhoto: startPhoto,
    startVoice: startVoice,
    close: closeSheet,
    onResult: function (fn) { onResult = fn; }
  };
})();
