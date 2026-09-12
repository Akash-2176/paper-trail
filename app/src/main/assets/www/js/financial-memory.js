/* Paper Trail - financial memory: structured queries over the ledger.
 *
 * ADR-004 is absolute here: EVERY number returned by this file is computed by
 * deterministic JavaScript. The LLM never sees a figure and never produces one.
 * It is allowed to classify what a person MEANT (intent, purpose label); the
 * answer itself is always read out of the ledger by the code below.
 *
 * TRUTH HIERARCHY, applied consistently:
 *   1. Bank SMS   - the bank actually moved this money. Authoritative for amount.
 *   2. Voice      - the user said what it was for. Authoritative for purpose.
 *   3. OCR        - the receipt shows line items. Authoritative for what was bought.
 * Where two sources disagree on an amount, SMS wins. Where they disagree on
 * meaning, voice wins. OCR never overrides either on money.
 */

var PTMemory = (function () {
  'use strict';

  var DAY = 24 * 60 * 60 * 1000;

  // --- shared helpers ---------------------------------------------------

  function norm(s) {
    return String(s == null ? '' : s).toLowerCase().replace(/\s+/g, ' ').trim();
  }

  function startOfDay(ts) {
    var d = new Date(ts);
    d.setHours(0, 0, 0, 0);
    return d.getTime();
  }

  /** Every reconciled record plus every still-pending capture, as one list. */
  function allRecords(store) {
    var out = [];
    (store.reconciled || []).forEach(function (r) {
      out.push({
        kind: 'reconciled',
        id: r.id,
        ts: (r.txn && r.txn.ts) || r.ts,
        amount: authoritativeAmount(r),
        merchant: (r.txn && r.txn.counterparty) || (r.pending && r.pending.note) || '',
        purpose: (r.pending && r.pending.purpose) || '',
        note: (r.pending && r.pending.note) || '',
        source: (r.pending && r.pending.source) || 'manual',
        ref: (r.txn && r.txn.ref) || null,
        issuer: (r.txn && r.txn.issuer) || null,
        direction: (r.txn && r.txn.direction) || 'debit',
        rec: r
      });
    });
    (store.pending || []).forEach(function (p) {
      out.push({
        kind: 'pending',
        id: p.id,
        ts: p.ts,
        amount: Number(p.amount) || 0,
        merchant: p.note || '',
        purpose: p.purpose || '',
        note: p.note || '',
        source: p.source || 'manual',
        ref: null,
        issuer: p.issuer || null,
        direction: p.direction || 'debit',
        rec: p
      });
    });
    return out;
  }

  /**
   * TRUTH HIERARCHY for money: the bank SMS is what actually left the account,
   * so it wins over anything OCR read off paper or a person said aloud. A
   * receipt total can legitimately differ (tip added at the terminal, rounding),
   * and in that case the charged figure is the true one.
   */
  function authoritativeAmount(r) {
    if (r.txn && Number(r.txn.amount) > 0) return Number(r.txn.amount);
    if (r.pending && Number(r.pending.amount) > 0) return Number(r.pending.amount);
    return 0;
  }

  /** What evidence backs this record. Drives the evidence chips in the UI. */
  function evidenceOf(rec) {
    var ev = [];
    if (rec.kind === 'reconciled') ev.push('sms');
    var src = rec.source;
    if (src === 'photo') ev.push('ocr');
    if (src === 'voice') ev.push('voice');
    if (rec.purpose) { if (ev.indexOf('voice') < 0) ev.push('purpose'); }
    return ev;
  }

  /** A transaction is "explained" when a human action is attached to it. */
  function isExplained(rec) {
    var ev = evidenceOf(rec);
    return ev.indexOf('ocr') >= 0 || ev.indexOf('voice') >= 0 ||
           ev.indexOf('purpose') >= 0;
  }

  // --- QUERY 1: where did I buy this? -----------------------------------

  /**
   * "Where did I buy X?" / "Where did this go?"
   * Returns the merchant, location text from the receipt if any, and what
   * evidence proves it.
   */
  function whereDidIBuy(store, term) {
    var q = norm(term);
    var rows = allRecords(store).filter(function (r) {
      if (!q) return true;
      return norm(r.merchant).indexOf(q) >= 0 ||
             norm(r.note).indexOf(q) >= 0 ||
             norm(r.purpose).indexOf(q) >= 0;
    });
    rows.sort(function (a, b) { return b.ts - a.ts; });
    return {
      query: 'whereDidIBuy',
      term: term || '',
      count: rows.length,
      total: rows.reduce(function (s, r) { return s + r.amount; }, 0),
      rows: rows.map(function (r) {
        return {
          id: r.id, ts: r.ts, amount: r.amount,
          merchant: r.merchant, purpose: r.purpose,
          evidence: evidenceOf(r)
        };
      })
    };
  }

  // --- QUERY 2: did I pay twice? ----------------------------------------

  /**
   * Duplicate detection. Same amount, same merchant, close in time is the
   * classic double-charge - a tap that did not register, then a retry.
   *
   * Deliberately conservative: an identical amount to the SAME merchant within
   * the window. Two ₹40 autos on the same day are not a duplicate, so merchant
   * identity is required, not just the figure.
   */
  function didIPayTwice(store, windowMs) {
    var win = windowMs || (30 * 60 * 1000);

    /* Include bank debits that have no capture attached yet. A double charge is
     * most likely to be spotted BEFORE either half is reconciled, so limiting
     * this to reconciled records would hide the case it exists to catch. */
    var seen = {};
    var rows = allRecords(store).filter(function (r) {
      if (r.direction === 'credit') return false;
      if (r.rec && r.rec.txn) seen[String(r.rec.txn.id)] = true;
      return true;
    });
    (store.txns || []).forEach(function (t) {
      if (t.direction === 'credit') return;
      if (seen[String(t.id)]) return;
      rows.push({
        kind: 'txn', id: t.id, ts: t.ts, amount: Number(t.amount) || 0,
        merchant: t.counterparty || '', purpose: '', note: '',
        source: 'sms', ref: t.ref || null, issuer: t.issuer || null,
        direction: t.direction || 'debit', rec: t
      });
    });
    rows.sort(function (a, b) { return a.ts - b.ts; });

    var pairs = [];
    for (var i = 0; i < rows.length; i++) {
      for (var j = i + 1; j < rows.length; j++) {
        var a = rows[i], b = rows[j];
        if (b.ts - a.ts > win) break;
        if (Math.abs(a.amount - b.amount) > 0.005) continue;
        if (!(a.amount > 0)) continue;
        var sim = PTReconcile.merchantScore(a.merchant, b.merchant);
        // Different bank reference means two genuinely distinct authorisations,
        // which is what a real double-charge looks like.
        var sameRef = a.ref && b.ref && a.ref === b.ref;
        if (sim >= 0.6 && !sameRef) {
          pairs.push({
            amount: a.amount,
            merchant: a.merchant || b.merchant,
            gapMs: b.ts - a.ts,
            first: { id: a.id, ts: a.ts, ref: a.ref },
            second: { id: b.id, ts: b.ts, ref: b.ref },
            confidence: sim
          });
        }
      }
    }
    return {
      query: 'didIPayTwice',
      count: pairs.length,
      duplicateTotal: pairs.reduce(function (s, p) { return s + p.amount; }, 0),
      pairs: pairs
    };
  }

  // --- QUERY 3: what was the order reference? ---------------------------

  /**
   * "What was the reference for that payment?" - the number you need when
   * disputing a charge or chasing a refund. Comes from the bank SMS (ADR-003
   * keeps it structured and redacted), never from free text.
   */
  function orderReference(store, term) {
    var q = norm(term);
    var rows = allRecords(store).filter(function (r) {
      if (!r.ref) return false;
      if (!q) return true;
      return norm(r.merchant).indexOf(q) >= 0 ||
             norm(r.note).indexOf(q) >= 0 ||
             String(r.ref).indexOf(q) >= 0;
    });
    rows.sort(function (a, b) { return b.ts - a.ts; });
    return {
      query: 'orderReference',
      term: term || '',
      count: rows.length,
      rows: rows.map(function (r) {
        return {
          id: r.id, ts: r.ts, amount: r.amount, merchant: r.merchant,
          ref: r.ref, issuer: r.issuer, evidence: evidenceOf(r)
        };
      })
    };
  }

  // --- QUERY 4: how much on a purpose / project? ------------------------

  /**
   * "How much have I spent on my college project?"
   * Purpose comes from voice (truth hierarchy: voice owns meaning); the SUM is
   * computed here, never by a model.
   */
  function spendByPurpose(store, purpose) {
    var q = norm(purpose);
    var rows = allRecords(store).filter(function (r) {
      if (r.direction === 'credit') return false;
      if (!q) return !!r.purpose;
      return norm(r.purpose).indexOf(q) >= 0 || norm(r.note).indexOf(q) >= 0;
    });
    rows.sort(function (a, b) { return b.ts - a.ts; });
    var total = rows.reduce(function (s, r) { return s + r.amount; }, 0);
    return {
      query: 'spendByPurpose',
      purpose: purpose || '(any labelled)',
      count: rows.length,
      total: total,
      rows: rows.map(function (r) {
        return {
          id: r.id, ts: r.ts, amount: r.amount, merchant: r.merchant,
          purpose: r.purpose, evidence: evidenceOf(r)
        };
      })
    };
  }

  // --- QUERY 5: what is still unexplained? ------------------------------

  /**
   * The money that moved with no human context attached - the number the whole
   * product exists to drive to zero. A bank debit with no receipt, no voice
   * note and no purpose is exactly the spend a normal tracker cannot see.
   */
  function unexplained(store, sinceMs) {
    var since = sinceMs || 0;
    var merged = {};
    (store.reconciled || []).forEach(function (r) {
      if (r.txn) merged[String(r.txn.id)] = r;
    });

    var rows = [];
    (store.txns || []).forEach(function (t) {
      if (t.direction === 'credit') return;
      if (t.ts < since) return;
      var rec = merged[String(t.id)];
      if (!rec) {
        rows.push({
          id: t.id, ts: t.ts, amount: Number(t.amount) || 0,
          merchant: t.counterparty || '', reason: 'no capture attached',
          evidence: ['sms']
        });
        return;
      }
      var asRecord = {
        kind: 'reconciled', source: (rec.pending && rec.pending.source) || 'manual',
        purpose: (rec.pending && rec.pending.purpose) || ''
      };
      if (!isExplained(asRecord)) {
        rows.push({
          id: t.id, ts: t.ts, amount: Number(t.amount) || 0,
          merchant: t.counterparty || '', reason: 'matched but no evidence',
          evidence: ['sms']
        });
      }
    });
    rows.sort(function (a, b) { return b.ts - a.ts; });
    return {
      query: 'unexplained',
      count: rows.length,
      total: rows.reduce(function (s, r) { return s + r.amount; }, 0),
      rows: rows
    };
  }

  // --- clarity metrics (P0-2) -------------------------------------------

  /**
   * Clarity score: the share of debited money that has human evidence attached.
   * Percentages are of MONEY, not of row counts - one unexplained ₹5,000 matters
   * more than five unexplained ₹20 autos, and a count would hide that.
   */
  function clarityScore(store, sinceMs) {
    var since = sinceMs || 0;
    var explainedAmt = 0, totalAmt = 0, explainedRows = 0, totalRows = 0;
    var merged = {};
    (store.reconciled || []).forEach(function (r) {
      if (r.txn) merged[String(r.txn.id)] = r;
    });

    (store.txns || []).forEach(function (t) {
      if (t.direction === 'credit' || t.ts < since) return;
      var amt = Number(t.amount) || 0;
      totalAmt += amt; totalRows++;
      var rec = merged[String(t.id)];
      if (rec && isExplained({
        kind: 'reconciled',
        source: (rec.pending && rec.pending.source) || 'manual',
        purpose: (rec.pending && rec.pending.purpose) || ''
      })) {
        explainedAmt += amt; explainedRows++;
      }
    });

    return {
      pct: totalAmt > 0 ? Math.round((explainedAmt / totalAmt) * 100) : 100,
      explainedAmount: explainedAmt,
      totalAmount: totalAmt,
      explainedRows: explainedRows,
      totalRows: totalRows
    };
  }

  /**
   * Clarity streak: consecutive days, counting back from today, on which EVERY
   * debit that day carried evidence. A day with no spending does not break the
   * streak - there was nothing to explain, so it is neither a win nor a failure
   * and the run simply continues through it.
   */
  function clarityStreak(store) {
    var merged = {};
    (store.reconciled || []).forEach(function (r) {
      if (r.txn) merged[String(r.txn.id)] = r;
    });

    var byDay = {};
    (store.txns || []).forEach(function (t) {
      if (t.direction === 'credit') return;
      var day = startOfDay(t.ts);
      if (!byDay[day]) byDay[day] = { total: 0, explained: 0 };
      byDay[day].total++;
      var rec = merged[String(t.id)];
      if (rec && isExplained({
        kind: 'reconciled',
        source: (rec.pending && rec.pending.source) || 'manual',
        purpose: (rec.pending && rec.pending.purpose) || ''
      })) byDay[day].explained++;
    });

    var streak = 0, cursor = startOfDay(Date.now()), guard = 0;
    while (guard++ < 400) {
      var d = byDay[cursor];
      if (d) {
        if (d.explained < d.total) break;   // a gap on a spending day ends it
        streak++;
      }
      // No spend that day: neither breaks nor extends. Keep walking back.
      cursor -= DAY;
      // Stop once we are earlier than any data we hold.
      if (!hasDataBefore(byDay, cursor)) break;
    }

    var today = byDay[startOfDay(Date.now())];
    return {
      days: streak,
      todayTotal: today ? today.total : 0,
      todayExplained: today ? today.explained : 0,
      atRisk: !!(today && today.explained < today.total)
    };
  }

  function hasDataBefore(byDay, cursor) {
    for (var k in byDay) {
      if (Object.prototype.hasOwnProperty.call(byDay, k) && Number(k) <= cursor) return true;
    }
    return false;
  }

  return {
    // the five structured queries
    whereDidIBuy: whereDidIBuy,
    didIPayTwice: didIPayTwice,
    orderReference: orderReference,
    spendByPurpose: spendByPurpose,
    unexplained: unexplained,
    // clarity metrics
    clarityScore: clarityScore,
    clarityStreak: clarityStreak,
    // shared helpers, exported for the UI and for tests
    evidenceOf: evidenceOf,
    isExplained: isExplained,
    allRecords: allRecords,
    authoritativeAmount: authoritativeAmount
  };
})();

if (typeof module !== 'undefined' && module.exports) module.exports = PTMemory;
