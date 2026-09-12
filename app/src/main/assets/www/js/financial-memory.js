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

  /* Category vocabulary.
   *
   * A substring test alone answers "nothing recorded for food" when the ledger
   * holds tomato, varuval and Red Bull - all obviously food. Asking a question
   * in the user's own words and being told there is no data, when there is, is
   * worse than a wrong number: it teaches them the feature does not work.
   *
   * Deliberately a small hand-written map, not a model: category membership is
   * a lookup, and ADR-004 keeps deterministic work deterministic. Extend it as
   * real data demands rather than speculatively. */
  var CATEGORIES = {
    food: ['tomato', 'varuval', 'biryani', 'dosa', 'meal', 'lunch', 'dinner',
           'breakfast', 'snack', 'juice', 'fruit', 'bakery', 'cafe', 'coffee',
           'tea', 'swiggy', 'zomato', 'restaurant', 'hotel', 'dairy', 'milk',
           'red bull', 'drink', 'food', 'grocer', 'vegetable', 'canteen'],
    travel: ['auto', 'petrol', 'fuel', 'diesel', 'bunk', 'uber', 'ola', 'cab',
             'taxi', 'bus', 'train', 'metro', 'ticket', 'travel', 'commute',
             'ride', 'transport', 'parking', 'toll'],
    shopping: ['mouse', 'keyboard', 'amazon', 'flipkart', 'store', 'mart',
               'shop', 'clothes', 'shopping'],
    entertainment: ['movie', 'cinema', 'game', 'netflix', 'spotify', 'theatre',
                    'entertainment'],
    bills: ['recharge', 'bill', 'electricity', 'water', 'gas', 'rent',
            'subscription', 'emi', 'insurance']
  };

  /** Terms that mean the same category, so "commute" finds travel rows. */
  var SYNONYMS = {
    commute: 'travel', transport: 'travel', fuel: 'travel', petrol: 'travel',
    eating: 'food', meals: 'food', groceries: 'food',
    fun: 'entertainment', entertainment: 'entertainment'
  };

  /**
   * Does this record match what the user asked about?
   *
   * Three ways, in order of confidence: the word appears in the row's own text;
   * the word names a category and the row belongs to it; or the query is itself
   * a category member (asking "tomato" should still match a row labelled
   * "food"). All deterministic.
   */
  function matchesTerm(rec, q) {
    if (!q) return true;
    var hay = norm(rec.purpose) + ' ' + norm(rec.note) + ' ' + norm(rec.merchant);
    if (hay.indexOf(q) >= 0) return true;

    var cat = SYNONYMS[q] || (CATEGORIES[q] ? q : null);
    if (cat && CATEGORIES[cat]) {
      for (var i = 0; i < CATEGORIES[cat].length; i++) {
        if (hay.indexOf(CATEGORIES[cat][i]) >= 0) return true;
      }
    }

    // Multi-word questions: match if ANY meaningful word hits.
    var parts = q.split(' ').filter(function (w) {
      return w.length > 2 && ['the','and','for','what','much','how','was','are',
        'spent','spend','total','about'].indexOf(w) < 0;
    });
    for (var j = 0; j < parts.length; j++) {
      var p = parts[j];
      if (hay.indexOf(p) >= 0) return true;
      var c2 = SYNONYMS[p] || (CATEGORIES[p] ? p : null);
      if (c2 && CATEGORIES[c2]) {
        for (var k = 0; k < CATEGORIES[c2].length; k++) {
          if (hay.indexOf(CATEGORIES[c2][k]) >= 0) return true;
        }
      }
    }
    return false;
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
      return matchesTerm(r, q);
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
      return matchesTerm(r, q);
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

  /* ---------------------------------------------------------------------
   * RAG retrieval
   *
   * Deterministic code retrieves and totals; the model only phrases the answer
   * from what it was given. This replaced a semantic index-selection scheme
   * that let the model choose rows - it copied digits from its own example and
   * matched categories the ledger did not contain, so totals silently included
   * unrelated payments.
   * ------------------------------------------------------------------- */

  /** Score a row against the question. Higher is more relevant. */
  /**
   * Which category does this word belong to?
   *
   * A term can be the category NAME ("food") or one of its MEMBERS
   * ("vegetable", which lives inside food). Only checking names meant
   * "vegetables" scored zero against a ledger full of tomato, because
   * `vegetable` is a member of food rather than a key of CATEGORIES.
   */
  function categoryOf(term) {
    if (!term) return null;
    if (SYNONYMS[term]) return SYNONYMS[term];
    if (CATEGORIES[term]) return term;
    for (var k in CATEGORIES) {
      if (!Object.prototype.hasOwnProperty.call(CATEGORIES, k)) continue;
      if (CATEGORIES[k].indexOf(term) >= 0) return k;
    }
    return null;
  }

  function relevance(rec, terms) {
    var hay = (norm(rec.purpose) + ' ' + norm(rec.note) + ' ' +
               norm(rec.merchant));
    var score = 0;
    for (var i = 0; i < terms.length; i++) {
      var t = terms[i];
      if (!t) continue;
      if (hay.indexOf(t) >= 0) score += 3;
      var cat = categoryOf(t);
      if (cat && CATEGORIES[cat]) {
        for (var j = 0; j < CATEGORIES[cat].length; j++) {
          if (hay.indexOf(CATEGORIES[cat][j]) >= 0) { score += 2; break; }
        }
      }
    }
    return score;
  }

  var STOPWORDS = ['the','and','for','what','how','much','was','are','is','my',
    'on','in','at','to','did','do','i','spent','spend','total','about','of',
    'a','an','me','show','tell','all','any'];

  /**
   * Retrieve rows for a question and render them as grounded context.
   *
   * Relevant rows first; if nothing scores, the most recent rows are sent so
   * the model can answer "the records do not show that" truthfully instead of
   * guessing from an empty page. Amounts are computed HERE.
   */
  /** Questions about everything, where no term should narrow the ledger. */
  var WHOLE_LEDGER = /(?:total|altogether|overall|in all|everything|all my|so far|this month|today)/i;

  function retrieve(store, question, limit) {
    var all = allRecords(store).filter(function (r) {
      return r.direction !== 'credit';
    });
    if (!all.length) {
      return { rows: [], total: 0, matched: false,
               context: 'RECORDS: none. The ledger is empty.' };
    }

    var terms = norm(question).split(' ').filter(function (w) {
      return w.length > 2 && STOPWORDS.indexOf(w) < 0;
    });

    /* Expand a plural or synonym to its category stem before scoring, so
     * "vegetables" reaches the food vocabulary that contains "tomato". The
     * model is not reliable at inferring that a tomato is a vegetable from a
     * bare list, and it does not have to be: category membership is a lookup,
     * and ADR-004 keeps that kind of work in code. */
    var expanded = terms.slice();
    terms.forEach(function (t) {
      /* Try BOTH plural forms. A single /(?:es|s)$/ turned "vegetables" into
       * "vegetabl" - the "es" branch won - so it never reached the "vegetable"
       * entry in the food vocabulary and the query scored zero. */
      if (/s$/.test(t)) expanded.push(t.slice(0, -1));        // vegetables -> vegetable
      if (/es$/.test(t)) expanded.push(t.slice(0, -2));       // boxes -> box
    });
    terms = expanded.filter(function (t) { return t && t.length > 2; });

    /* "how much did I spend in total" is about the whole ledger, not about the
     * word "total". Without this it scored zero against every row and the model
     * was handed a page headed "no payment matched", then answered from one
     * arbitrary line. */
    var wholeLedger = WHOLE_LEDGER.test(question) || terms.length === 0;

    var chosen, matched;
    if (wholeLedger) {
      chosen = all.slice().sort(function (a, b) { return b.ts - a.ts; });
      matched = true;
    } else {
      var scored = all.map(function (r) { return { r: r, s: relevance(r, terms) }; });
      var hits = scored.filter(function (x) { return x.s > 0; });
      matched = hits.length > 0;
      chosen = (matched ? hits : scored)
        .sort(function (a, b) {
          return matched ? (b.s - a.s || b.r.ts - a.r.ts) : (b.r.ts - a.r.ts);
        })
        .map(function (x) { return x.r; });
    }
    chosen = chosen.slice(0, limit || 12);

    var total = chosen.reduce(function (sum, r) { return sum + r.amount; }, 0);

    var lines = chosen.map(function (r) {
      var when = new Date(r.ts).toLocaleDateString('en-IN',
        { day: 'numeric', month: 'short' });
      return '- Rs ' + r.amount + ' | ' +
             (r.purpose || r.note || r.merchant || 'unlabelled') +
             (r.merchant && r.merchant !== r.note ? ' | at ' + r.merchant : '') +
             ' | ' + when;
    });

    /* Never tell the model "nothing matched" while also showing it rows - it
     * obeyed the sentence and ignored the data, answering "no payment matched"
     * about a list containing two tomato entries. State the facts; let the
     * model judge relevance from the rows themselves. */
    var header = 'These are the recorded payments (' + chosen.length +
                 (wholeLedger || matched
                   ? '), combined total Rs ' + total + ':'
                   : ', shown for reference), combined total Rs ' + total + ':');

    return {
      rows: chosen,
      total: total,
      matched: matched,
      wholeLedger: wholeLedger,
      context: header + '\n' + lines.join('\n')
    };
  }

  /**
   * Ask the ledger in natural language.
   *
   * 1. deterministic retrieval + totalling (here)
   * 2. the model phrases an answer from ONLY those rows
   * 3. if the model is unavailable or unusable, fall back to the structured
   *    query, so the feature degrades to the old behaviour rather than failing
   */
  function ask(store, question, cb) {
    var ctx = retrieve(store, question, 12);
    var fallback = spendByPurpose(store, question);

    if (!PTBridge.llmAvailable || !PTBridge.llmAvailable() || !ctx.rows.length) {
      cb({ ok: false, via: 'rules', structured: fallback, retrieved: ctx });
      return;
    }

    PTBridge.answerFromContext(question, ctx.context, function (res) {
      if (!res || !res.ok || !res.text) {
        cb({ ok: false, via: 'rules', structured: fallback, retrieved: ctx });
        return;
      }
      cb({
        ok: true,
        via: 'rag',
        text: res.text,
        ms: res.ms,
        computeUnit: res.computeUnit,
        retrieved: ctx,
        structured: fallback
      });
    });
  }

  return {
    ask: ask,
    retrieve: retrieve,
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
    matchesTerm: matchesTerm,
    evidenceOf: evidenceOf,
    isExplained: isExplained,
    allRecords: allRecords,
    authoritativeAmount: authoritativeAmount
  };
})();

if (typeof module !== 'undefined' && module.exports) module.exports = PTMemory;
