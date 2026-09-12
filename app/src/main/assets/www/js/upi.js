/* Paper Trail - the UPI payment layer.
 *
 * Paper Trail is not a payment app. It is the layer around the payment the
 * user already makes: read the merchant's existing QR, prepare a transaction,
 * hand it to whichever UPI app they already trust, and be there when they come
 * back to capture what the payment was actually for.
 *
 *   scan -> confirm -> [ UPI app ] -> result -> context
 *
 * The merchant changes nothing. No PIN, no credential and no authorisation
 * decision touches this file - that all belongs to the UPI app.
 *
 * ADR-004 holds throughout: the amount comes from the QR or the user's own
 * keystrokes, is re-validated natively, and no model ever produces or alters
 * it. The model reads only the sentence the user says afterwards.
 */

var PTUpi = (function () {
  'use strict';

  /* State names mirror UpiState.kt exactly. The distinction that matters:
   * SUBMITTED is what the payment app told us, VERIFIED is what the bank
   * confirmed. Only the second one is proof. */
  var LABEL = {
    CREATED:           'Ready to pay',
    PAYMENT_INITIATED: 'Waiting for your UPI app',
    SUBMITTED:         'Payment reported successful',
    VERIFIED:          'Payment verified',
    FAILED:            'Payment failed',
    CANCELLED:         'Payment cancelled',
    PENDING:           'Payment pending',
    UNKNOWN:           'Outcome unknown'
  };

  /* What each state means for the user, in their terms. Written to be honest:
   * SUBMITTED deliberately does not say "done". */
  var EXPLAIN = {
    SUBMITTED:  'Your UPI app reported success. Paper Trail will confirm it against your bank SMS.',
    VERIFIED:   'Your bank confirmed this payment.',
    FAILED:     'The payment did not go through. No money left your account.',
    CANCELLED:  'You cancelled before paying.',
    PENDING:    'Your bank is still processing this. The outcome is not final yet.',
    UNKNOWN:    'Your UPI app closed without telling us the outcome. Check the app or wait for your bank SMS.'
  };

  var TONE = {
    SUBMITTED: 'ok', VERIFIED: 'ok',
    FAILED: 'bad', CANCELLED: 'muted',
    PENDING: 'warn', UNKNOWN: 'warn'
  };

  var current = null;    // the transaction being prepared or shown
  var lastQr = null;     // the parsed QR behind it
  var onDone = null;     // notified when a payment reaches the ledger

  function el(id) { return document.getElementById(id); }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function inr(n) {
    return '₹' + Number(n || 0).toLocaleString('en-IN', { maximumFractionDigits: 2 });
  }

  // --- sheet ------------------------------------------------------------

  /* Reuses the app's existing sheet shape (.sheet/.sheetInner) so the payment
   * flow looks like the rest of Paper Trail rather than a bolted-on module. */
  function ensureSheet() {
    var s = el('upiSheet');
    if (s) return s;
    var d = document.createElement('div');
    d.id = 'upiSheet';
    d.className = 'sheet hidden';
    d.innerHTML =
      '<div class="sheetInner">' +
        '<div class="sheetHead"><span id="upiTitle">Pay</span>' +
          '<button class="x" id="upiClose">✕</button></div>' +
        '<div id="upiBody"></div>' +
      '</div>';
    document.body.appendChild(d);
    el('upiClose').onclick = close;
    return d;
  }

  function open(title, html, cameraMode) {
    var s = ensureSheet();
    el('upiTitle').textContent = title;
    el('upiBody').innerHTML = html;
    s.className = 'sheet';
    // Only the scanner needs the page transparent for the viewfinder.
    document.body.className = cameraMode ? 'camera' : '';
  }

  function close() {
    var s = el('upiSheet');
    if (s) s.className = 'sheet hidden';
    document.body.className = '';
    try { PTBridge.stopQrScan(); } catch (e) {}
  }

  // --- 1. scan ----------------------------------------------------------

  function startScan() {
    lastQr = null;
    current = null;
    open('Scan to pay',
      '<div id="upiScanState" class="hint">starting camera…</div>' +
      '<div class="upiReticle"><div class="upiFrame"></div></div>' +
      '<div class="hint">Point at any UPI QR code</div>' +
      '<div class="shotRow"><button id="upiScanCancel">Cancel</button></div>', true);

    el('upiScanCancel').onclick = close;

    PTBridge.startQrScan(function (cam) {
      var n = el('upiScanState');
      if (!n) return;
      if (cam && cam.ok) {
        n.textContent = cam.preview ? 'looking for a QR code…' : 'camera ready';
        n.className = 'hint ok';
      } else {
        n.textContent = 'camera unavailable: ' + ((cam && cam.error) || 'unknown');
        n.className = 'hint err';
      }
    }, onCode);
  }

  /**
   * A code came into frame.
   *
   * An unreadable code must not close the scanner: people point at posters,
   * WiFi codes and website QRs. Say what is wrong and keep looking.
   */
  function onCode(qr) {
    if (!qr || !qr.ok) {
      var n = el('upiScanState');
      if (n) {
        n.textContent = (qr && qr.error) || 'That is not a UPI payment QR';
        n.className = 'hint err';
      }
      // Native keeps scanning for a non-payable code; re-arm the deduper so
      // the same code can report again if the user lingers on it.
      PTBridge.resumeQrScan();
      return;
    }
    lastQr = qr;
    confirmScreen(qr);
  }

  // --- 2. confirm -------------------------------------------------------

  /**
   * Transaction preparation. Who, how much, and what happens next.
   *
   * Raw UPI parameters are deliberately not shown: `mc=5411` means nothing to
   * a person paying for lunch. The VPA is shown because it is the one field
   * that lets someone catch a swapped QR sticker.
   */
  function confirmScreen(qr) {
    var name = qr.payeeName || 'Unknown payee';
    var fixed = qr.amountLocked;

    open('Confirm payment',
      '<div class="upiPayee">' +
        '<div class="upiName">' + esc(name) + '</div>' +
        '<div class="upiVpa">' + esc(qr.vpa) + '</div>' +
      '</div>' +
      (fixed
        ? '<div class="upiAmountFixed">' + inr(qr.amount) + '</div>' +
          '<div class="hint">Amount set by the merchant</div>'
        : '<div class="upiAmountEntry">' +
            '<span class="upiCur">₹</span>' +
            '<input id="upiAmt" type="number" inputmode="decimal" ' +
              'placeholder="0" step="0.01" min="1"' +
              /* Carried over on a retry so the user does not retype what they
               * already entered; still editable, because a wrong amount is a
               * reason people retry. */
              (qr.amount != null ? ' value="' + Number(qr.amount) + '"' : '') + '>' +
          '</div>' +
          '<div class="hint">Enter the amount to pay</div>') +
      '<input id="upiNote" class="upiNote" type="text" maxlength="50" ' +
        'placeholder="What is this for? (optional)" value="' + esc(qr.note || '') + '">' +
      '<div id="upiErr" class="hint err" style="display:none"></div>' +
      '<div class="upiNext">You will authorise this in your own UPI app.</div>' +
      '<div class="shotRow">' +
        '<button id="upiGo" class="primary big">Continue</button>' +
        '<button id="upiBack">Cancel</button>' +
      '</div>');

    el('upiBack').onclick = function () {
      /* Back to where the user came from. Re-opening the scanner is right
       * after a scan, but wrong on the retry path - there is no QR in front
       * of them and the payee is already known. */
      if (qr.fromRetry) { close(); return; }
      PTBridge.resumeQrScan();
      startScan();
    };

    var amtInput = el('upiAmt');
    if (amtInput) setTimeout(function () { amtInput.focus(); }, 150);

    el('upiGo').onclick = function () {
      var amount = fixed ? qr.amount : Number((amtInput && amtInput.value) || 0);
      var err = el('upiErr');
      if (!(amount > 0)) {
        err.textContent = 'Enter an amount';
        err.style.display = '';
        return;
      }
      err.style.display = 'none';
      prepare(qr, amount, (el('upiNote') || {}).value || '');
    };
  }

  // --- 3. create + hand off ---------------------------------------------

  /**
   * Create the Paper Trail transaction, then choose how to pay it.
   *
   * The record is written before the UPI app opens. If the process dies while
   * the user is paying, the transaction is still on disk and the result is
   * matched back to it on return.
   */
  function prepare(qr, amount, note) {
    var res = PTBridge.upiCreate(qr, amount, note);
    if (!res || !res.ok) {
      var err = el('upiErr');
      if (err) {
        err.textContent = (res && res.error) || 'Could not prepare the payment';
        err.style.display = '';
      }
      return;
    }
    current = res.txn;
    PTBridge.log('upi: prepared ' + current.id + ' ' + inr(current.amount));

    var apps = PTBridge.upiApps() || [];
    if (!apps.length) {
      noAppScreen();
      return;
    }
    /* One UPI app: no point asking. More than one: let Android's own chooser
     * ask, because it honours the user's default and we do not want to build a
     * competing app picker that ages badly. */
    handOff(apps.length === 1 ? apps[0].packageName : '');
  }

  function handOff(packageName) {
    open('Opening your UPI app',
      '<div class="upiWait">' +
        '<div class="upiBig">' + inr(current.amount) + '</div>' +
        '<div class="upiName">' + esc(current.payeeName || current.vpa) + '</div>' +
        '<div class="hint" style="margin-top:14px">' +
          'Authorise the payment in your UPI app, then come back.</div>' +
      '</div>');

    var r = PTBridge.upiPay(current.id, packageName);
    if (!r || !r.ok) {
      open('Could not pay',
        '<div class="hint err">' + esc((r && r.error) || 'No UPI app could be opened') + '</div>' +
        '<div class="hint">The payment was not started. Nothing has left your account.</div>' +
        '<div class="shotRow"><button id="upiErrClose" class="primary big">OK</button></div>');
      el('upiErrClose').onclick = close;
      return;
    }
    // From here the UPI app owns the screen. The result arrives after resume,
    // via bridge.pendingResult - not through this page, which will be reloaded.
  }

  function noAppScreen() {
    open('No UPI app found',
      '<div class="hint err">No UPI app is installed on this phone.</div>' +
      '<div class="hint">Paper Trail does not process payments itself — it hands ' +
        'them to an app like GPay, PhonePe, Paytm or BHIM. Install one and scan again.</div>' +
      '<div class="upiSaved">The payment details are saved, so you can pay later.</div>' +
      '<div class="shotRow"><button id="upiNoAppClose" class="primary big">OK</button></div>');
    el('upiNoAppClose').onclick = close;
  }

  // --- 4. result --------------------------------------------------------

  /**
   * Show the outcome, then invite context.
   *
   * Deliberately one explicit tap rather than an automatic listening overlay:
   * the user may still be in the UPI app, the payment may be pending, the
   * process may have been killed. Opening a microphone into that uncertainty
   * is worse than asking.
   */
  function showResult(txn) {
    current = txn;
    var tone = TONE[txn.state] || 'muted';
    var paid = txn.state === 'SUBMITTED' || txn.state === 'VERIFIED';

    var mark = paid ? '✓' : (txn.state === 'FAILED' ? '✕'
                     : txn.state === 'CANCELLED' ? '–' : '!');

    open('Payment',
      '<div class="upiResult ' + tone + '">' +
        '<div class="upiMark">' + mark + '</div>' +
        '<div class="upiBig">' + inr(txn.amount) + '</div>' +
        '<div class="upiState">' + esc(LABEL[txn.state] || txn.state) + '</div>' +
        '<div class="upiName">' + esc(txn.payeeName || 'Unknown payee') + '</div>' +
        '<div class="upiVpa">' + esc(txn.vpa) + '</div>' +
      '</div>' +
      '<div class="upiExplain">' + esc(EXPLAIN[txn.state] || '') + '</div>' +
      /* Verification is a claim about money, so it is stated explicitly and
       * only when true. "Reported by your app" is not "confirmed by the bank". */
      (txn.state === 'SUBMITTED'
        ? '<div class="upiUnverified">Not yet verified against your bank</div>' : '') +
      (paid ? contextBlock() : '') +
      '<div class="shotRow">' +
        /* A failed or cancelled payment is a dead end without this: the money
         * still needs paying and the payee details are already known, so
         * making the user rescan the same QR is pointless friction. A pending
         * or unknown one is deliberately NOT retryable - paying again when the
         * first attempt may yet succeed is how people pay twice. */
        (retryable(txn.state)
          ? '<button id="upiRetry" class="primary big">Try again</button>' : '') +
        '<button id="upiDone" class="' + (paid || retryable(txn.state) ? '' : 'primary big') + '">' +
          (paid ? 'Skip' : 'Done') + '</button>' +
      '</div>');

    el('upiDone').onclick = function () { finish(txn); };
    var retry = el('upiRetry');
    if (retry) retry.onclick = function () { retryPayment(txn); };
    if (paid) wireContext(txn);
  }

  function retryable(state) {
    return state === 'FAILED' || state === 'CANCELLED';
  }

  /**
   * Pay the same payee again after a failure.
   *
   * A NEW transaction is created rather than the old one being relaunched:
   * the failed attempt is evidence and must keep its own record and id. The
   * native side refuses to launch anything that is not CREATED, so reusing it
   * would be rejected anyway - and rightly.
   */
  function retryPayment(txn) {
    PTBridge.upiClearPendingResult();
    var qr = {
      ok: true,
      vpa: txn.vpa,
      payeeName: txn.payeeName,
      merchantCode: txn.merchantCode || '',
      // The failed attempt's reference is not reused - a fresh one is minted
      // with the new transaction so the two never collide in a statement.
      refId: '',
      note: txn.note || '',
      amount: txn.amount,
      currency: txn.currency || 'INR',
      // The amount is re-confirmed rather than assumed: the user may be
      // retrying precisely because it was wrong.
      amountLocked: false,
      fromRetry: true
    };
    lastQr = qr;
    confirmScreen(qr);
  }

  function contextBlock() {
    return '<div class="upiCtx">' +
      '<div class="upiCtxHead">Add context</div>' +
      '<div class="upiCtxSub">Say what this was for, while you remember</div>' +
      '<div class="upiCtxRow">' +
        '<button id="upiMic" class="upiMic">🎙</button>' +
        '<input id="upiCtxText" type="text" placeholder="e.g. client meeting" ' +
          'maxlength="200" enterkeyhint="done">' +
        '<button id="upiCtxSave" class="upiSave">Save</button>' +
      '</div>' +
      '<div id="upiCtxState" class="hint"></div>' +
    '</div>';
  }

  function wireContext(txn) {
    var save = function () {
      var text = (el('upiCtxText') || {}).value || '';
      if (!text.trim()) return;
      attach(txn, text.trim(), 'text');
    };
    el('upiCtxSave').onclick = save;
    el('upiCtxText').addEventListener('keypress', function (e) {
      if (e.key === 'Enter') save();
    });

    el('upiMic').onclick = function () {
      var st = PTBridge.speechStatus();
      var s = el('upiCtxState');
      if (!st || !st.available) {
        s.textContent = 'Voice unavailable — type it instead';
        s.className = 'hint err';
        return;
      }
      el('upiMic').className = 'upiMic live';
      s.textContent = 'listening…';
      s.className = 'hint';

      PTBridge.startListening(function (res) {
        el('upiMic').className = 'upiMic';
        if (res && res.ok && res.text) {
          el('upiCtxText').value = res.text;
          attach(txn, res.text, 'voice');
        } else {
          s.textContent = (res && res.error) || 'Did not catch that — type it instead';
          s.className = 'hint err';
        }
      }, function (partial) {
        el('upiCtxText').value = partial;
      });

      /* Stop on the second tap. A fixed timer would cut people off mid
       * sentence, and the recogniser already ends on natural silence. */
      el('upiMic').onclick = function () {
        PTBridge.stopListening();
        el('upiMic').className = 'upiMic';
        s.textContent = 'transcribing…';
      };
    };
  }

  /**
   * Persist the context, then let the model structure it.
   *
   * The user's own words are saved first and unconditionally. The model runs
   * afterwards and can only add a purpose label - it can never change the
   * amount, the payee or the state, none of which it is even shown.
   */
  function attach(txn, text, source) {
    var s = el('upiCtxState');
    if (s) { s.textContent = 'saving…'; s.className = 'hint'; }

    PTBridge.upiAttachContext(txn.id, text, source, function (r) {
      var updated = (r && r.txn) || txn;
      updated.contextText = updated.contextText || text;
      updated.contextSource = updated.contextSource || source;
      if (s) {
        s.textContent = (r && r.structured && updated.purpose)
          ? 'saved as “' + updated.purpose + '”'
          : 'saved';
        s.className = 'hint ok';
      }
      setTimeout(function () { finish(updated); }, 700);
    });
  }

  /** Hand the finished transaction to the ledger and leave. */
  function finish(txn) {
    PTBridge.upiClearPendingResult();
    close();
    if (onDone) onDone(txn);
  }

  // --- return path ------------------------------------------------------

  /**
   * Called at boot. The page is reloaded when the UPI app hands control back,
   * so a waiting result is fetched from native rather than remembered here.
   */
  function checkPendingResult() {
    var p = PTBridge.upiPendingResult();
    if (p && p.ok && p.txn) {
      PTBridge.log('upi: showing result for ' + p.txn.id + ' ' + p.txn.state);
      showResult(p.txn);
      return true;
    }
    return false;
  }

  return {
    startScan: startScan,
    showResult: showResult,
    checkPendingResult: checkPendingResult,
    close: close,
    LABEL: LABEL,
    TONE: TONE,
    onDone: function (fn) { onDone = fn; }
  };
})();

if (typeof module !== 'undefined' && module.exports) module.exports = PTUpi;
