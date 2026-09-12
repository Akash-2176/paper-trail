/* Paper Trail - pending store. In-memory only, no persistence. */

var PTStore = (function () {
  'use strict';

  var pending = [];     // user actions awaiting an SMS
  var txns = [];        // structured rows from the bridge
  var reconciled = [];  // merged records
  var seq = 1;

  function addPending(o) {
    var p = {
      id: 'p' + (seq++),
      ts: o.ts || Date.now(),
      amount: Number(o.amount) || 0,
      direction: o.direction || 'debit',
      note: o.note || '',
      source: o.source || 'manual',   // manual | photo | voice
      path: o.path || null,
      issuer: o.issuer || null
    };
    pending.push(p);
    return p;
  }

  function setTxns(list) {
    txns = (list || []).slice();
    // newest first
    txns.sort(function (a, b) { return (b.ts || 0) - (a.ts || 0); });
    return txns;
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
      savedAt: Date.now()
    });
  }

  function load() {
    var d = PTBridge.loadLedger() || {};
    if (!d || d.v !== 1) return false;
    pending = Array.isArray(d.pending) ? d.pending : [];
    reconciled = Array.isArray(d.reconciled) ? d.reconciled : [];
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
    reconcileAll: reconcileAll,
    confirmMerge: confirmMerge,
    isMerged: isMerged,
    get pending() { return pending; },
    get txns() { return txns; },
    get reconciled() { return reconciled; },
    /** In-memory only, for tests. Disk is untouched. */
    reset: function () { pending = []; txns = []; reconciled = []; seq = 1; },
    /** Clears memory AND disk, so a wipe survives a restart. */
    wipe: function () {
      pending = []; txns = []; reconciled = []; seq = 1;
      PTBridge.clearLedger();
    }
  };
})();

if (typeof module !== 'undefined' && module.exports) module.exports = PTStore;
