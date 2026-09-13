/* Paper Trail - pending store. In-memory only, no persistence. */

var PTStore = (function () {
  'use strict';

  var pending = [];     // user actions awaiting an SMS
  var txns = [];        // structured rows from the bridge
  var reconciled = [];  // merged records
  var seq = 1;
  /* Bank rows the user deleted, by SMS id. SMS is re-read from the provider on
   * every launch (ADR-003 keeps it at the source and never caches it), so a
   * deleted row can only be remembered as a suppression - otherwise it would
   * silently return on the next sync. */
  var dismissed = {};

  function addPending(o) {
    var p = {
      id: 'p' + (seq++),
      ts: o.ts || Date.now(),
      amount: Number(o.amount) || 0,
      direction: o.direction || 'debit',
      note: o.note || '',
      source: o.source || 'manual',   // manual | photo | voice
      path: o.path || null,
      issuer: o.issuer || null,
      // P0-3: the purpose a voice utterance attached, e.g. "college project".
      purpose: o.purpose || ''
    };
    pending.push(p);
    return p;
  }

  /**
   * Fold UPI transactions into the ledger as pending entries.
   *
   * A UPI payment is a spend the user made and the bank has not confirmed yet
   * - which is exactly what a pending entry already models. Representing it
   * this way means PTReconcile matches it against bank SMS with no new
   * matching code, and the clarity metrics count it like any other payment.
   *
   * The UPI block rides along on the entry so the detail view and later
   * reconciliation can see the response, the reference and the state.
   *
   * Only payments the UPI app reported as done enter the ledger. A cancelled
   * or failed payment moved no money and must not appear as spending.
   */
  var LEDGER_STATES = { SUBMITTED: 1, VERIFIED: 1, PENDING: 1 };

  function syncUpi(list) {
    var rows = list || [];
    var added = 0;
    for (var i = 0; i < rows.length; i++) {
      var t = rows[i];
      if (!t || !LEDGER_STATES[t.state]) continue;
      // A UPI entry the user deleted stays deleted; syncUpi runs on every boot
      // and would otherwise resurrect it from the native store.
      if (dismissed['upi:' + t.id]) continue;
      var existing = findByUpiId(t.id);
      if (existing) { applyUpi(existing, t); continue; }
      var p = addPending({
        amount: t.amount,
        // The payee name is what the person recognises; the VPA is the fallback.
        note: t.payeeName || t.vpa || 'UPI payment',
        purpose: t.purpose || '',
        source: 'upi',
        ts: t.completedAt || t.initiatedAt || t.createdAt,
        direction: 'debit'
      });
      applyUpi(p, t);
      added++;
    }
    return added;
  }

  function applyUpi(entry, t) {
    entry.upi = {
      id: t.id,
      state: t.state,
      vpa: t.vpa,
      payeeName: t.payeeName,
      refId: t.refId,
      merchantCode: t.merchantCode,
      upiTxnId: t.upiTxnId,
      upiRefNumber: t.upiRefNumber,
      responseCode: t.responseCode,
      contextText: t.contextText,
      contextSource: t.contextSource,
      contextNote: t.contextNote
    };
    if (t.purpose) entry.purpose = t.purpose;
    /* Voice context is evidence in its own right - PTMemory reads `source` to
     * decide whether a payment is explained, and a spoken note is exactly the
     * evidence the product is asking for. */
    if (t.contextSource === 'voice') entry.source = 'voice';
    if (t.contextText && !entry.note) entry.note = t.contextText;
  }

  function findByUpiId(upiId) {
    var i;
    for (i = 0; i < pending.length; i++) {
      if (pending[i].upi && pending[i].upi.id === upiId) return pending[i];
    }
    for (i = 0; i < reconciled.length; i++) {
      var p = reconciled[i].pending;
      if (p && p.upi && p.upi.id === upiId) return p;
    }
    return null;
  }

  /**
   * A UPI payment that has been merged with a bank SMS is corroborated by
   * evidence the payment app did not produce. That, and only that, is what
   * promotes SUBMITTED to VERIFIED.
   */
  function verifyMergedUpi() {
    var out = [];
    for (var i = 0; i < reconciled.length; i++) {
      var r = reconciled[i];
      var u = r.pending && r.pending.upi;
      if (!u || u.state !== 'SUBMITTED') continue;
      if (!r.txn) continue;
      if (PTBridge.upiMarkVerified(u.id, 'bank sms ' + (r.txn.ref || r.txn.id))) {
        u.state = 'VERIFIED';
        out.push(u.id);
      }
    }
    return out;
  }

  function setTxns(list) {
    txns = (list || []).filter(function (t) {
      // A bank row the user deleted must not reappear on the next sync.
      return !dismissed[String(t.id)];
    });
    // newest first
    txns.sort(function (a, b) { return (b.ts || 0) - (a.ts || 0); });
    return txns;
  }

  /**
   * Remove an entry the user says was a mistake.
   *
   * Three shapes live in the ledger and each needs different handling:
   *
   *   waiting  a capture awaiting an SMS - drop it outright.
   *   done     a reconciled pair - drop the merge, and dismiss the bank row
   *            with it. Keeping the row would resurrect the payment as an
   *            unexplained debit, which is not what "delete" means to anyone.
   *   bare     a bank SMS - it is re-read from the provider every launch, so
   *            it can only be suppressed, never deleted. The message itself is
   *            never touched: ADR-003 keeps SMS at the source.
   */
  function remove(state, rec) {
    if (!rec) return false;
    var i;
    if (state === 'waiting') {
      for (i = 0; i < pending.length; i++) {
        if (pending[i].id === rec.id) {
          if (pending[i].upi) dismissed['upi:' + pending[i].upi.id] = 1;
          pending.splice(i, 1);
          save();
          return true;
        }
      }
      return false;
    }
    if (state === 'done') {
      for (i = 0; i < reconciled.length; i++) {
        if (reconciled[i].id === rec.id) {
          var t = reconciled[i].txn;
          if (t) dismissed[String(t.id)] = 1;
          var pd = reconciled[i].pending;
          if (pd && pd.upi) dismissed['upi:' + pd.upi.id] = 1;
          reconciled.splice(i, 1);
          txns = txns.filter(function (x) { return !dismissed[String(x.id)]; });
          save();
          return true;
        }
      }
      return false;
    }
    if (state === 'bare') {
      dismissed[String(rec.id)] = 1;
      txns = txns.filter(function (x) { return String(x.id) !== String(rec.id); });
      save();
      return true;
    }
    return false;
  }

  function isMerged(txnId) {
    for (var i = 0; i < reconciled.length; i++) {
      if (String(reconciled[i].txn.id) === String(txnId)) return true;
    }
    return false;
  }

  function merge(p, cand) {
    reconciled.push({
      id: 'r' + (seq++),
      ts: Date.now(),
      pending: p,
      txn: cand.txn,
      confidence: cand.confidence,
      reasons: cand.reasons || [],
      auto: cand.action === 'auto'
    });
    var i = pending.indexOf(p);
    if (i >= 0) pending.splice(i, 1);
    return reconciled[reconciled.length - 1];
  }

  /* Try to reconcile every pending entry against current txns.
   * Returns {merged:[], confirm:[]}. Unmatched cash simply stays pending. */
  function reconcileAll() {
    var merged = [], confirm = [];
    // iterate a copy: merge() mutates pending
    var snapshot = pending.slice();
    for (var i = 0; i < snapshot.length; i++) {
      var p = snapshot[i];
      var avail = txns.filter(function (t) { return !isMerged(t.id); });
      var cand = PTReconcile.best(p, avail);
      if (!cand) continue;
      if (cand.action === 'auto') merged.push(merge(p, cand));
      else if (cand.action === 'confirm') confirm.push({ pending: p, cand: cand });
    }
    return { merged: merged, confirm: confirm };
  }

  function confirmMerge(pendingId, txnId) {
    var p = null, t = null, i;
    for (i = 0; i < pending.length; i++) if (pending[i].id === pendingId) p = pending[i];
    for (i = 0; i < txns.length; i++) if (String(txns[i].id) === String(txnId)) t = txns[i];
    if (!p || !t) return null;
    var c = PTReconcile.matchPending(p, [t])[0];
    return merge(p, { txn: t, confidence: c ? c.confidence : 0, reasons: c ? c.reasons : [], action: 'manual' });
  }

  /* Bounded retention. Storage must stay flat however long the app runs, so the
   * ledger keeps only the most recent rows. Reconciled records are the product's
   * output and outlive pending ones. */
  var MAX_RECONCILED = 200;
  var MAX_PENDING = 50;

  function trim() {
    if (reconciled.length > MAX_RECONCILED) {
      reconciled = reconciled.slice(-MAX_RECONCILED);
    }
    if (pending.length > MAX_PENDING) {
      pending = pending.slice(-MAX_PENDING);
    }
  }

  /* Persist to local disk. SMS is deliberately NOT stored - it is re-read from
   * the provider each launch, and ADR-003 keeps redaction at the source, so
   * caching it would duplicate personal data for no gain. */
  function save() {
    trim();
    return PTBridge.saveLedger({
      v: 1,
      seq: seq,
      pending: pending,
      reconciled: reconciled,
      dismissed: dismissed,
      savedAt: Date.now()
    });
  }

  function load() {
    var d = PTBridge.loadLedger() || {};
    if (!d || d.v !== 1) return false;
    pending = Array.isArray(d.pending) ? d.pending : [];
    reconciled = Array.isArray(d.reconciled) ? d.reconciled : [];
    // Absent in ledgers written before delete existed; an empty map is correct.
    dismissed = (d.dismissed && typeof d.dismissed === 'object') ? d.dismissed : {};
    seq = Number(d.seq) || (pending.length + reconciled.length + 1);
    trim();
    PTBridge.log('store: restored pending=' + pending.length +
                 ' reconciled=' + reconciled.length);
    return true;
  }

  return {
    save: save,
    load: load,
    addPending: addPending,
    setTxns: setTxns,
    syncUpi: syncUpi,
    findByUpiId: findByUpiId,
    verifyMergedUpi: verifyMergedUpi,
    reconcileAll: reconcileAll,
    confirmMerge: confirmMerge,
    remove: remove,
    isMerged: isMerged,
    get pending() { return pending; },
    get txns() { return txns; },
    get reconciled() { return reconciled; },
    /** In-memory only, for tests. Disk is untouched. */
    reset: function () {
      pending = []; txns = []; reconciled = []; seq = 1; dismissed = {};
    },
    /** Clears memory AND disk, so a wipe survives a restart. */
    wipe: function () {
      pending = []; txns = []; reconciled = []; seq = 1; dismissed = {};
      PTBridge.clearLedger();
    }
  };
})();

if (typeof module !== 'undefined' && module.exports) module.exports = PTStore;
