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
  var voiceFinished = false;
  var recordingWav = false;
  var onVoiceIntent = null;   // context/query utterances route here

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

  var st = null;

  function startVoice() {
    st = PTBridge.speechStatus();
    voiceStart = Date.now();
    voiceFinished = false;

    /* ONE microphone client at a time.
     *
     * Recording our own WAV while the recogniser also listens makes two clients
     * contend for the mic - ours opens AudioSource.MIC, the recogniser opens
     * VOICE_RECOGNITION. The first attempt after launch wins the race and every
     * later one fails, which is exactly the "worked once, then stopped" symptom
     * seen on device.
     *
     * When a recogniser is available it owns the mic and the transcript is the
     * artifact we actually want. The WAV is only recorded when there is no
     * recogniser, so a voice note is still captured either way. */
    var r = null;
    if (!st || !st.available) {
      r = PTBridge.startRecording();
      if (!r || !r.ok) {
        openSheet('Voice', '<div class="hint err">mic unavailable: ' +
          esc((r && r.error) || '?') + '</div>');
        setTimeout(closeSheet, 2000);
        return;
      }
    }
    recordingWav = !!(r && r.ok);
    openSheet('Listening',
      '<div class="listening"><span class="dot"></span><span class="dot"></span>' +
        '<span class="dot"></span></div>' +
      '<div id="ptVoiceTime" class="timer">0.0s</div>' +
      '<div class="hint">speak the amount and what it was for</div>' +
      '<div id="ptLive" class="transcript" style="display:none"></div>' +
      '<div class="shotRow">' +
        '<button id="ptStopRec" class="primary big">⏹ Stop</button>' +
        '<button id="ptCancelRec">Cancel</button>' +
      '</div>' +
      '<div id="ptTranscript"></div>');

    voiceTimer = setInterval(function () {
      var t = el('ptVoiceTime');
      if (t) t.textContent = ((Date.now() - voiceStart) / 1000).toFixed(1) + 's';
    }, 100);

    // Live on-device recognition. Words appear as they are spoken.
    if (st && st.available) {
      PTBridge.startListening(function (res) {
        finishVoice(res);
      }, function (partialText) {
        var live = el('ptLive');
        if (live) {
          live.style.display = '';
          live.textContent = partialText;
        }
      });
    }

    el('ptCancelRec').onclick = function () {
      try { PTBridge.cancelListening(); } catch (e) {}
      if (recordingWav) { try { PTBridge.stopRecording(); } catch (e) {} }
      recordingWav = false;
      closeSheet();
    };

    el('ptStopRec').onclick = function () {
      if (voiceTimer) { clearInterval(voiceTimer); voiceTimer = null; }
      var live = el('ptLive');
      if (live) live.style.display = 'none';
      el('ptTranscript').innerHTML = '<div id="ptTrx" class="hint">transcribing…</div>';
      if (st && st.available) {
        // The final transcript arrives through the callback set in startVoice.
        PTBridge.stopListening();
        // Safety net: if the recogniser never reports, do not hang the sheet.
        if (transcriptTimer) clearTimeout(transcriptTimer);
        transcriptTimer = setTimeout(function () {
          finishVoice({ ok: false, error: 'recogniser did not respond' });
        }, 6000);
      } else {
        finishVoice({ ok: false, error: 'on-device recogniser unavailable' });
      }
    };
  }

  /* One exit point for the voice sheet, whether the transcript arrived, failed,
   * or timed out. Always stops the WAV recording so the mic is released. */
  function finishVoice(res) {
    if (voiceFinished) return;
    voiceFinished = true;
    if (transcriptTimer) { clearTimeout(transcriptTimer); transcriptTimer = null; }
    if (voiceTimer) { clearInterval(voiceTimer); voiceTimer = null; }

    var secs = ((Date.now() - voiceStart) / 1000).toFixed(1);
    var wav = {};
    if (recordingWav) {
      try { wav = PTBridge.stopRecording() || {}; } catch (e) {}
      recordingWav = false;
    }

    var text = (res && res.text) || '';

    /* P0-3: one utterance can mean three different things. Route it before
     * assuming it is a new spend - "this is for my college project" labels the
     * last capture, and "how much on my project?" is a question, neither of
     * which should create a transaction. The amount is still parsed by
     * deterministic code either way (ADR-004). */
    /* Route through the model, not the rules. classify() is synchronous and
     * regex-only; route() consults the NPU first and falls back to the same
     * rules if it is unavailable or slow. */
    if (!text) { finishVoiceWith({ intent: 'capture' }, '', res); return; }
    PTIntent.route(text, function (routed) { finishVoiceWith(routed, text, res); });
  }

  function finishVoiceWith(routed, text, res) {
    var box = el('ptTrx');
    var secs = ((Date.now() - voiceStart) / 1000).toFixed(1);
    var wav = {};
    if (recordingWav) {
      try { wav = PTBridge.stopRecording() || {}; } catch (e) {}
      recordingWav = false;
    }
    if (text && routed.intent !== 'capture') {
      if (box) {
        box.innerHTML =
          '<div class="transcript">“' + esc(text) + '”</div>' +
          '<div class="hint ok">' +
          (routed.intent === 'query' ? 'question' : 'label: ' + esc(routed.purpose)) +
          ' <span class="src">' +
          (res && res.onDevice === false ? 'system asr' : 'on-device') +
          '</span></div>';
      }
      if (onVoiceIntent) onVoiceIntent(routed, text);
      transcriptTimer = setTimeout(closeSheet, 2600);
      return;
    }

    var amount = text ? PTExtract.parseAmount(text) : null;
    var note = text ? (PTExtract.noteFromSpeech(text) || text) : '';
    var purpose = routed.purpose || '';

    if (box) {
      if (text) {
        box.innerHTML =
          '<div class="transcript">“' + esc(text) + '”</div>' +
          '<div class="hint ok">' +
            (amount != null ? ('₹' + amount) : 'no amount heard') +
            ' <span class="src">' +
            (res && res.onDevice === false ? 'system asr' : 'on-device') +
            (res && res.partial ? ' · partial' : '') + '</span></div>';
      } else {
        box.innerHTML =
          '<div class="hint">recorded ' + secs + 's · 16kHz mono WAV</div>' +
          '<div class="hint err">' + esc((res && res.error) || 'no transcript') + '</div>' +
          '<div class="hint">type the amount — the clip is kept with the entry</div>';
      }
    }

    if (onResult) {
      onResult('voice', {
        amount: amount,
        note: note || ('voice note ' + secs + 's'),
        purpose: purpose,
        path: wav.path || null,
        extracted: { ok: !!text, text: text, amount: amount, source: 'android-ondevice-asr' }
      });
    }
    // Leave the transcript readable for a moment, as asked.
    transcriptTimer = setTimeout(closeSheet, text ? 3000 : 4000);
  }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  return {
    startPhoto: startPhoto,
    startVoice: startVoice,
    close: closeSheet,
    onResult: function (fn) { onResult = fn; },
    /** Called when a spoken utterance was a label or a question, not a spend. */
    onIntent: function (fn) { onVoiceIntent = fn; }
  };
})();
