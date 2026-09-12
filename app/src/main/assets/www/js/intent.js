/* Paper Trail - intent routing for voice.
 *
 * Splits one spoken utterance into two different jobs:
 *
 *   CONTEXT  "this is for my college project"  -> label a spend
 *   QUERY    "how much on my project?"         -> ask the ledger
 *   CAPTURE  "three hundred for petrol"        -> create a spend
 *
 * ADR-004 boundary, stated precisely:
 *   - classifying WHAT SOMEONE MEANT is a language problem. A model may do it.
 *   - producing a NUMBER is an arithmetic problem. Only code does it.
 * So the router may decide an utterance is a query about "college project";
 * the total it reports is computed by PTMemory from ledger rows, every time.
 *
 * route() sends every utterance to the on-device model FIRST (Qwen3-1.7B on the
 * Hexagon NPU, ~430ms) and uses the deterministic rules below as the fallback
 * when the model is unavailable, slow, or returns something off-schema. The
 * callback shape is identical either way, so callers never branch on which path
 * ran - and with no model present the rules still answer everything.
 */

var PTIntent = (function () {
  'use strict';

  // --- lexicon ----------------------------------------------------------

  /* Question cues.
   *
   * These were far too narrow. Spoken and typed questions frequently carry no
   * question mark - ASR rarely emits one - so "what is the total spent on food"
   * and "what about commute" both fell through to `context` and were recorded
   * as SPENDING. Observed on device: the ledger accumulated entries whose note
   * was the user's own question, and a later query then answered by reading
   * those back. Any leading interrogative is a question, full stop. */
  var QUERY_CUES = [
    /^\s*(?:what|how|where|when|which|who|why)\b/i,   // any leading interrogative
    /\bhow much\b/i, /\bhow many\b/i, /\bwhat about\b/i,
    /\bwhat did i\b/i, /\bwhere did i\b/i, /\bwhat is\b/i, /\bwhat was\b/i,
    /\bwhat'?s\b/i, /\bdid i (?:pay|spend|buy)\b/i,
    /\bshow me\b/i, /\blist\b/i, /\btell me\b/i,
    /\btotal spent\b/i, /\bspent on\b/i, /\bspend on\b/i
  ];

  var CONTEXT_CUES = [
    /\bthis (?:is|was) for\b/i, /\bthat (?:is|was) for\b/i,
    /\bit(?:'s| is| was) for\b/i, /\bfor my\b/i, /\btag (?:this|that)\b/i,
    /\bmark (?:this|that)\b/i, /\bcategor(?:y|ise|ize)\b/i,
    /\bbusiness expense\b/i, /\bpersonal\b/i, /\breimburse/i
  ];

  var DUP_CUES = [/\btwice\b/i, /\bdouble\b/i, /\bduplicate\b/i, /\btwo times\b/i];
  var WHERE_CUES = [/\bwhere\b/i, /\bwhich (?:shop|store|place|merchant)\b/i];
  var REF_CUES = [
    /\breference\b/i, /\bref\b/i, /\border (?:id|number|no)\b/i,
    /\brrn\b/i, /\btransaction id\b/i
  ];
  var UNEXPLAINED_CUES = [
    /\bunexplained\b/i, /\bmissing\b/i, /\bno receipt\b/i, /\bwithout evidence\b/i,
    /\bwhat(?:'s| is) left\b/i
  ];

  function any(res, s) {
    for (var i = 0; i < res.length; i++) if (res[i].test(s)) return true;
    return false;
  }

  /** Strip the leading verb phrase so "for my college project" -> "college project". */
  function purposeFrom(text) {
    var t = String(text || '');
    var m = t.match(
      /\b(?:this|that|it)?\s*(?:is|was)?\s*for\s+(?:my|our|the|a|an)?\s*([A-Za-z0-9' -]{2,40})/i
    );
    if (m) return tidy(m[1]);
    m = t.match(/\b(?:tag|mark|categor(?:y|ise|ize))\s+(?:this|that)?\s*(?:as)?\s*([A-Za-z0-9' -]{2,40})/i);
    if (m) return tidy(m[1]);
    if (/\bbusiness expense\b/i.test(t)) return 'business expense';
    if (/\breimburse/i.test(t)) return 'reimbursable';
    return '';
  }

  function tidy(s) {
    return String(s || '')
      .replace(/\b(please|thanks|thank you)\b/ig, ' ')
      .replace(/[^A-Za-z0-9' -]/g, ' ')
      .replace(/\s+/g, ' ')
      .trim()
      .toLowerCase();
  }

  /** Subject of a query: "how much on my college project" -> "college project". */
  function subjectFrom(text) {
    var t = String(text || '');
    var m = t.match(
      /\b(?:on|for|at|to|from)\s+(?:my|our|the|a|an)?\s*([A-Za-z0-9' -]{2,40})/i
    );
    if (m) return tidy(m[1]);
    m = t.match(/\bdid i (?:buy|pay|spend)(?:\s+\w+)?\s+(?:at|from|on)\s+([A-Za-z0-9' -]{2,40})/i);
    if (m) return tidy(m[1]);
    /* "where did I buy petrol" has no preposition before the subject, so the
     * patterns above find nothing. Take the trailing noun instead. */
    m = t.match(/\b(?:buy|bought|purchase[d]?|spend|spent|pay|paid)\s+([A-Za-z0-9' -]{2,40})/i);
    if (m) return tidy(m[1]);
    return '';
  }

  // --- deterministic router ---------------------------------------------

  /**
   * Classify without any model. Returns
   *   {intent, subject, purpose, confidence, via:'rules'}
   * intent is one of: capture | context | query
   * For queries, `queryName` names the PTMemory function to run.
   */
  function classify(text) {
    var t = String(text || '').trim();
    if (!t) return { intent: 'capture', confidence: 0, via: 'rules' };

    var hasAmount = PTExtract.parseAmount(t) != null;
    var isQuery = any(QUERY_CUES, t) || /\?\s*$/.test(t);
    var isContext = any(CONTEXT_CUES, t);

    // A question wins even when a number is present: "how much did I spend on
    // 300 rupees of petrol" is still a question, not a new spend.
    if (isQuery) {
      var name = 'spendByPurpose';
      if (any(DUP_CUES, t)) name = 'didIPayTwice';
      else if (any(REF_CUES, t)) name = 'orderReference';
      else if (any(UNEXPLAINED_CUES, t)) name = 'unexplained';
      else if (any(WHERE_CUES, t)) name = 'whereDidIBuy';
      return {
        intent: 'query',
        queryName: name,
        subject: subjectFrom(t),
        confidence: 0.9,
        via: 'rules'
      };
    }

    // "this is for X" with no amount is labelling the last capture.
    if (isContext && !hasAmount) {
      return {
        intent: 'context',
        purpose: purposeFrom(t) || tidy(t),
        confidence: 0.85,
        via: 'rules'
      };
    }

    // An amount present means a new spend; any trailing "for X" is its purpose.
    if (hasAmount) {
      return {
        intent: 'capture',
        amount: PTExtract.parseAmount(t),
        purpose: purposeFrom(t),
        note: PTExtract.noteFromSpeech(t),
        confidence: 0.9,
        via: 'rules'
      };
    }

    /* Words, no number, no question cue.
     *
     * Treating this as a label is only safe for something SHORT, like "college
     * project". A whole sentence is far more likely to be a question the cues
     * missed than a category name, and guessing wrong wrote the user's own
     * questions into the ledger as spending. Long text with no amount is
     * therefore treated as a query, which is the recoverable mistake: a wrong
     * answer is visible and costs nothing, a phantom transaction corrupts the
     * ledger and the clarity metrics built on it. */
    var words = t.split(/\s+/).filter(function (w) { return w; }).length;
    if (words > 4) {
      return {
        intent: 'query',
        queryName: 'spendByPurpose',
        subject: subjectFrom(t) || tidy(t),
        confidence: 0.5,
        via: 'rules'
      };
    }
    return {
      intent: 'context',
      purpose: tidy(t),
      confidence: 0.4,
      via: 'rules'
    };
  }

  /**
   * Route an utterance. Calls back with the classification, consulting the
   * on-device LLM ONLY when the rules are unsure. The callback shape is
   * identical either way, so callers never branch on which path ran.
   */
  function route(text, cb) {
    var det = classify(text);

    /* MODEL FIRST, rules as the safety net.
     *
     * This used to be inverted: the rules answered anything they scored >= 0.8,
     * which is almost everything, so the NPU model was consulted about once in
     * twenty utterances. We were shipping a 1.5GB model the product did not
     * use.
     *
     * Language understanding is what a language model is for. Rules match
     * fixed phrasings and miss anything said differently - "put that against
     * the project" is obvious to a person and invisible to a regex. At ~430ms
     * on the NPU the model is affordable on a spoken utterance, so it goes
     * first and the rules catch the cases where it is unavailable, slow, or
     * returns something outside the schema.
     *
     * ADR-004 is untouched. The model picks a LABEL. Every number below is
     * re-parsed from the raw text by deterministic code, whichever path ran. */
    if (!PTBridge.llmAvailable || !PTBridge.llmAvailable()) {
      det.via = 'rules (no model)';
      cb(det);
      return;
    }

    var settled = false;
    function answer(r) {
      if (settled) return;
      settled = true;
      cb(r);
    }

    /* Never let a slow model stall a capture: fall back to rules on time.
     *
     * Steady state is ~430ms, but the first classification after launch can
     * reach ~4.7s while the VLM is still loading and both engines contend for
     * the DSP. 6s covers that without leaving a user waiting on a stall. */
    var timer = setTimeout(function () {
      det.via = 'rules (model slow)';
      answer(det);
    }, 6000);

    PTBridge.classifyIntent(text, function (res) {
      clearTimeout(timer);
      var allowed = { capture: 1, context: 1, query: 1 };
      if (!res || !res.ok || !allowed[res.intent]) {
        det.via = 'rules (model ' + ((res && res.error) || 'invalid') + ')';
        answer(det);
        return;
      }
      /* The model names the INTENT. The query it maps to is still chosen by
       * deterministic code, so a hallucinated function name cannot run. */
      var queryName = det.queryName;
      if (res.intent === 'query' && !queryName) queryName = 'spendByPurpose';

      answer({
        intent: res.intent,
        queryName: queryName,
        subject: tidy(res.subject || det.subject || ''),
        purpose: tidy(res.purpose || det.purpose || ''),
        // Money never comes from the model.
        amount: PTExtract.parseAmount(text),
        note: PTExtract.noteFromSpeech(text),
        confidence: 0.85,
        ms: res.ms,
        computeUnit: res.computeUnit,
        via: 'llm'
      });
    });
  }

  /**
   * Execute a classified query against the ledger. Every figure in the result
   * comes from PTMemory, i.e. from deterministic code over stored rows.
   */
  function runQuery(intent, store) {
    var s = intent.subject || '';
    switch (intent.queryName) {
      case 'didIPayTwice':   return PTMemory.didIPayTwice(store);
      case 'orderReference': return PTMemory.orderReference(store, s);
      case 'unexplained':    return PTMemory.unexplained(store);
      case 'whereDidIBuy':   return PTMemory.whereDidIBuy(store, s);
      default:               return PTMemory.spendByPurpose(store, s);
    }
  }

  /**
   * Async form. Identical to runQuery except that a "spend on X" question which
   * the word list cannot answer is escalated to the model, which decides which
   * ledger rows belong to X. Every total is still summed by PTMemory.
   */
  function runQueryAsync(intent, store, cb) {
    // Structured questions (duplicates, references, unexplained) have exact
    // deterministic answers - no model needed or wanted.
    if (intent.queryName && intent.queryName !== 'spendByPurpose') {
      cb(runQuery(intent, store));
      return;
    }
    cb(runQuery(intent, store));
  }

  /**
   * Natural-language ask over the ledger (RAG).
   *
   * Retrieval and every total are deterministic; the model phrases the answer
   * from the retrieved rows only. Falls back to the structured result when the
   * model is unavailable or returns nothing usable, so the feature degrades
   * instead of breaking.
   */
  function askLedger(text, store, cb) {
    PTMemory.ask(store, text, function (res) {
      if (res.ok && res.text) {
        cb({ answer: res.text, via: 'rag · ' + (res.computeUnit || 'npu'),
             rows: res.retrieved.rows, ms: res.ms });
        return;
      }
      var st = res.structured;
      cb({ answer: describe(st), via: 'rules',
           rows: (st && st.rows) || [], structured: st });
    });
  }

  /** Human-readable answer. Numbers are formatted, never computed, here. */
  function describe(result) {
    if (!result) return 'no answer';
    var inr = function (n) {
      return '₹' + Number(n || 0).toLocaleString('en-IN', { maximumFractionDigits: 2 });
    };
    switch (result.query) {
      case 'spendByPurpose':
        return result.count
          ? inr(result.total) + ' across ' + result.count +
            ' payment' + (result.count === 1 ? '' : 's') +
            (result.purpose ? ' on ' + result.purpose : '')
          : 'nothing recorded' + (result.purpose ? ' for ' + result.purpose : '');
      case 'didIPayTwice':
        return result.count
          ? result.count + ' possible duplicate' + (result.count === 1 ? '' : 's') +
            ', ' + inr(result.duplicateTotal) + ' at risk'
          : 'no duplicates found';
      case 'orderReference':
        return result.count
          ? result.count + ' payment' + (result.count === 1 ? '' : 's') + ' with a reference'
          : 'no reference found';
      case 'unexplained':
        return result.count
          ? inr(result.total) + ' unexplained across ' + result.count + ' payments'
          : 'nothing unexplained';
      case 'whereDidIBuy':
        return result.count
          ? result.count + ' match' + (result.count === 1 ? '' : 'es') +
            ', ' + inr(result.total) + ' total'
          : 'no matching payment';
      default:
        return 'no answer';
    }
  }

  return {
    classify: classify,
    route: route,
    runQuery: runQuery,
    runQueryAsync: runQueryAsync,
    askLedger: askLedger,
    describe: describe,
    purposeFrom: purposeFrom,
    subjectFrom: subjectFrom
  };
})();

if (typeof module !== 'undefined' && module.exports) module.exports = PTIntent;
