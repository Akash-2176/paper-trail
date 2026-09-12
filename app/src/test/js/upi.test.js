/* Paper Trail - UPI product-layer tests.
 *
 *   node app/src/test/js/upi.test.js
 *
 * Covers the parts that decide what a person is told about their money:
 * whether a payment enters the ledger, how it reconciles against bank SMS,
 * and when it is allowed to be called verified.
 *
 * The Kotlin UPI URI parser is exercised on the device instead - it depends on
 * android.net.Uri, and a Robolectric harness would be a heavy new dependency
 * for an offline build. See docs/UPI-DEVICE-TESTS.md.
 */

var assert = require('assert');
var path = require('path');

var WWW = path.join(__dirname, '..', '..', 'main', 'assets', 'www', 'js');

// --- harness ------------------------------------------------------------

var passed = 0, failed = 0;

function test(name, fn) {
  try { fn(); passed++; console.log('  ok   ' + name); }
  catch (e) {
    failed++;
    console.log('  FAIL ' + name);
    console.log('       ' + (e && e.message));
  }
}

function group(name) { console.log('\n' + name); }

/* The product layer expects browser globals. Provide the minimum, plus a
 * PTBridge stub whose UPI calls are observable. */
global.window = global;

var bridgeCalls = [];
global.PTBridge = {
  log: function () {},
  saveLedger: function () { return true; },
  loadLedger: function () { return {}; },
  clearLedger: function () { return true; },
  upiMarkVerified: function (id, ev) {
    bridgeCalls.push({ fn: 'upiMarkVerified', id: id, evidence: ev });
    // Mirrors the native guard: only SUBMITTED/PENDING may be promoted.
    return verifiable[id] !== false;
  }
};
var verifiable = {};

var PTReconcile = require(path.join(WWW, 'reconcile.js'));
global.PTReconcile = PTReconcile;
var PTStore = require(path.join(WWW, 'store.js'));
global.PTStore = PTStore;
var PTMemory = require(path.join(WWW, 'financial-memory.js'));
global.PTMemory = PTMemory;

// --- fixtures -----------------------------------------------------------

var NOW = Date.now();

function upiTxn(over) {
  var t = {
    id: 'u1', createdAt: NOW - 60000, initiatedAt: NOW - 50000,
    completedAt: NOW - 40000,
    vpa: 'merchant@upi', payeeName: 'AUTO RIDE', amount: 1700,
    currency: 'INR', merchantCode: '4121', refId: 'PTABC123',
    note: '', source: 'qr', state: 'SUBMITTED',
    upiTxnId: 'UPI99887766', upiRefNumber: '530112345678',
    responseCode: '00', contextText: null, contextSource: null,
    purpose: null, contextNote: null, extras: {}
  };
  for (var k in (over || {})) t[k] = over[k];
  return t;
}

function bankSms(over) {
  var t = {
    id: 's1', smsId: 1, ts: NOW - 39000, issuer: 'HDFC', amount: 1700,
    direction: 'debit', acctLast4: '4417', ref: '530112345678',
    kind: 'merchant', counterparty: 'AUTO RIDE', senderRouted: true
  };
  for (var k in (over || {})) t[k] = over[k];
  return t;
}

function reset() {
  PTStore.reset();
  bridgeCalls = [];
  verifiable = {};
}

// --- 1. which payments enter the ledger ---------------------------------

group('Ledger admission — only money that actually moved');

test('a submitted payment enters the ledger', function () {
  reset();
  assert.strictEqual(PTStore.syncUpi([upiTxn()]), 1);
  assert.strictEqual(PTStore.pending.length, 1);
  assert.strictEqual(PTStore.pending[0].amount, 1700);
});

test('a cancelled payment never becomes spending', function () {
  reset();
  assert.strictEqual(PTStore.syncUpi([upiTxn({ state: 'CANCELLED' })]), 0);
  assert.strictEqual(PTStore.pending.length, 0);
});

test('a failed payment never becomes spending', function () {
  reset();
  PTStore.syncUpi([upiTxn({ state: 'FAILED' })]);
  assert.strictEqual(PTStore.pending.length, 0);
});

test('an unknown outcome never becomes spending', function () {
  reset();
  // The honest state for "the app told us nothing". Recording it as a spend
  // would invent a debit that may not exist.
  PTStore.syncUpi([upiTxn({ state: 'UNKNOWN' })]);
  assert.strictEqual(PTStore.pending.length, 0);
});

test('a payment still being processed does enter, marked pending', function () {
  reset();
  PTStore.syncUpi([upiTxn({ state: 'PENDING' })]);
  assert.strictEqual(PTStore.pending.length, 1);
  assert.strictEqual(PTStore.pending[0].upi.state, 'PENDING');
});

test('a created-but-unpaid transaction is not spending', function () {
  reset();
  PTStore.syncUpi([upiTxn({ state: 'CREATED' })]);
  assert.strictEqual(PTStore.pending.length, 0);
});

// --- 2. idempotency ------------------------------------------------------

group('Idempotency — the page reloads on every return');

test('re-syncing the same payment does not duplicate it', function () {
  reset();
  var t = upiTxn();
  PTStore.syncUpi([t]);
  PTStore.syncUpi([t]);
  PTStore.syncUpi([t]);
  assert.strictEqual(PTStore.pending.length, 1);
});

test('re-syncing carries an updated state onto the existing entry', function () {
  reset();
  PTStore.syncUpi([upiTxn()]);
  PTStore.syncUpi([upiTxn({ state: 'VERIFIED' })]);
  assert.strictEqual(PTStore.pending.length, 1);
  assert.strictEqual(PTStore.pending[0].upi.state, 'VERIFIED');
});

test('context added later attaches to the same entry', function () {
  reset();
  PTStore.syncUpi([upiTxn()]);
  PTStore.syncUpi([upiTxn({
    contextText: 'client meeting', contextSource: 'voice', purpose: 'client meeting'
  })]);
  assert.strictEqual(PTStore.pending.length, 1);
  assert.strictEqual(PTStore.pending[0].purpose, 'client meeting');
  assert.strictEqual(PTStore.pending[0].upi.contextText, 'client meeting');
});

test('two different payments are two entries', function () {
  reset();
  PTStore.syncUpi([upiTxn({ id: 'u1' }), upiTxn({ id: 'u2', amount: 40 })]);
  assert.strictEqual(PTStore.pending.length, 2);
});

// --- 3. reconciliation against the bank ---------------------------------

group('Reconciliation — the existing engine, not a second one');

test('a UPI payment auto-merges with its bank SMS', function () {
  reset();
  PTStore.syncUpi([upiTxn()]);
  PTStore.setTxns([bankSms()]);
  var r = PTStore.reconcileAll();
  assert.strictEqual(r.merged.length, 1, 'expected an automatic merge');
  assert.strictEqual(PTStore.reconciled.length, 1);
});

test('an unrelated bank SMS does not merge', function () {
  reset();
  PTStore.syncUpi([upiTxn()]);
  PTStore.setTxns([bankSms({ amount: 25, counterparty: 'SOME DAIRY',
                             ts: NOW - 8 * 3600 * 1000 })]);
  var r = PTStore.reconcileAll();
  assert.strictEqual(r.merged.length, 0);
});

test('a credit never merges with a UPI debit', function () {
  reset();
  PTStore.syncUpi([upiTxn()]);
  PTStore.setTxns([bankSms({ direction: 'credit' })]);
  var r = PTStore.reconcileAll();
  assert.strictEqual(r.merged.length, 0, 'direction is a hard gate');
});

// --- 4. verification -----------------------------------------------------

group('Verification — an app saying success is not the bank saying it');

test('a merged payment is promoted to verified', function () {
  reset();
  PTStore.syncUpi([upiTxn()]);
  PTStore.setTxns([bankSms()]);
  PTStore.reconcileAll();
  var v = PTStore.verifyMergedUpi();
  assert.deepStrictEqual(v, ['u1']);
  assert.strictEqual(bridgeCalls.length, 1);
  assert.strictEqual(bridgeCalls[0].fn, 'upiMarkVerified');
});

test('an unmerged payment is NOT verified', function () {
  reset();
  PTStore.syncUpi([upiTxn()]);
  var v = PTStore.verifyMergedUpi();
  assert.strictEqual(v.length, 0, 'no bank evidence, no verification');
  assert.strictEqual(PTStore.pending[0].upi.state, 'SUBMITTED');
});

test('verification is not re-applied to an already verified payment', function () {
  reset();
  PTStore.syncUpi([upiTxn()]);
  PTStore.setTxns([bankSms()]);
  PTStore.reconcileAll();
  PTStore.verifyMergedUpi();
  bridgeCalls = [];
  var again = PTStore.verifyMergedUpi();
  assert.strictEqual(again.length, 0);
  assert.strictEqual(bridgeCalls.length, 0, 'should not call native again');
});

test('the native guard refusing is respected', function () {
  reset();
  PTStore.syncUpi([upiTxn()]);
  PTStore.setTxns([bankSms()]);
  PTStore.reconcileAll();
  verifiable['u1'] = false;   // native declines the transition
  var v = PTStore.verifyMergedUpi();
  assert.strictEqual(v.length, 0);
  var entry = PTStore.findByUpiId('u1');
  assert.strictEqual(entry.upi.state, 'SUBMITTED', 'must not claim verified');
});

// --- 5. evidence ---------------------------------------------------------

group('Evidence — a payment response is not an explanation');

test('a bare UPI payment shows UPI evidence', function () {
  reset();
  PTStore.syncUpi([upiTxn()]);
  var ev = PTMemory.evidenceOf({ kind: 'pending', source: 'upi',
                                 upi: PTStore.pending[0].upi });
  assert.ok(ev.indexOf('upi') >= 0);
});

test('a UPI payment with no context is NOT explained', function () {
  reset();
  PTStore.syncUpi([upiTxn()]);
  var rec = { kind: 'pending', source: 'upi', purpose: '',
              upi: PTStore.pending[0].upi };
  assert.strictEqual(PTMemory.isExplained(rec), false,
    'knowing money moved is not knowing what it was for');
});

test('voice context makes a UPI payment explained', function () {
  reset();
  PTStore.syncUpi([upiTxn({
    contextText: 'client meeting', contextSource: 'voice', purpose: 'client meeting'
  })]);
  var p = PTStore.pending[0];
  var rec = { kind: 'pending', source: p.source, purpose: p.purpose, upi: p.upi };
  assert.strictEqual(PTMemory.isExplained(rec), true);
});

test('skipped context leaves a valid but unexplained payment', function () {
  reset();
  PTStore.syncUpi([upiTxn()]);
  // Context is enrichment, not authorisation: the payment record is complete
  // and correct without it, and is surfaced as needing evidence.
  assert.strictEqual(PTStore.pending.length, 1);
  assert.strictEqual(PTStore.pending[0].amount, 1700);
  assert.strictEqual(PTStore.pending[0].upi.state, 'SUBMITTED');
  assert.ok(PTMemory.unexplained(PTStore).count >= 1);
});

// --- 6. money integrity --------------------------------------------------

group('Money integrity — the amount survives every path');

test('the ledger amount is the amount paid, never the model output', function () {
  reset();
  PTStore.syncUpi([upiTxn({ amount: 1700, purpose: 'client meeting' })]);
  assert.strictEqual(PTStore.pending[0].amount, 1700);
});

test('the bank amount is authoritative after reconciliation', function () {
  reset();
  /* The payment app and the bank can legitimately disagree - the bank is what
   * actually left the account. Give the two different figures and check the
   * ledger reports the bank's. */
  PTStore.syncUpi([upiTxn({ amount: 1700, purpose: 'client meeting' })]);
  PTStore.setTxns([bankSms({ amount: 1700 })]);
  PTStore.reconcileAll();
  assert.strictEqual(PTStore.reconciled.length, 1, 'precondition: merged');
  var q = PTMemory.spendByPurpose(PTStore, 'client meeting');
  assert.strictEqual(q.count, 1);
  assert.strictEqual(q.total, 1700);
});

test('an unexplained UPI payment is counted before any bank SMS', function () {
  reset();
  /* The window that matters: the user just paid and still remembers why.
   * Waiting for the bank to text before surfacing it wastes exactly that. */
  PTStore.syncUpi([upiTxn()]);
  var un = PTMemory.unexplained(PTStore);
  assert.strictEqual(un.count, 1);
  assert.strictEqual(un.total, 1700);
  assert.strictEqual(un.rows[0].merchant, 'AUTO RIDE');
});

test('an explained UPI payment is not counted as unexplained', function () {
  reset();
  PTStore.syncUpi([upiTxn({
    contextText: 'client meeting', contextSource: 'voice', purpose: 'client meeting'
  })]);
  assert.strictEqual(PTMemory.unexplained(PTStore).count, 0);
});

test('a reconciled UPI payment is not counted twice', function () {
  reset();
  PTStore.syncUpi([upiTxn()]);
  PTStore.setTxns([bankSms()]);
  PTStore.reconcileAll();
  var un = PTMemory.unexplained(PTStore);
  assert.strictEqual(un.count, 1, 'once via the bank row, not also via pending');
});

test('a payee name is carried as the merchant', function () {
  reset();
  PTStore.syncUpi([upiTxn()]);
  assert.strictEqual(PTStore.pending[0].note, 'AUTO RIDE');
});

test('a payment with no payee name falls back to the VPA', function () {
  reset();
  PTStore.syncUpi([upiTxn({ payeeName: '' })]);
  assert.strictEqual(PTStore.pending[0].note, 'merchant@upi');
});

// --- report --------------------------------------------------------------

console.log('\n' + passed + ' passed, ' + failed + ' failed');
process.exit(failed === 0 ? 0 : 1);
