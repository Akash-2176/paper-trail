/* Paper Trail - extraction from photo and voice.
 *
 * ADR-004 still holds: the model NEVER computes. It reads a receipt or
 * transcribes speech; every number is then pulled out by deterministic code
 * here and re-validated. If the model is unavailable the flow degrades to
 * manual entry rather than failing.
 */

var PTExtract = (function () {
  'use strict';

  // --- deterministic number/merchant parsing ----------------------------

  /* Spoken and printed amounts. Handles "rupees 250", "250 rupees", "Rs.250",
   * "₹1,250.50", and bare numbers as a last resort. */
  var AMOUNT_PATTERNS = [
    /(?:₹|rs\.?|inr|rupees?)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)/i,
    /([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*(?:₹|rs\.?|inr|rupees?)/i,
    /\b(?:total|amount|grand\s*total|paid|bill)\b[^0-9]{0,12}([0-9][0-9,]*(?:\.[0-9]{1,2})?)/i
  ];

  var WORD_NUM = {
    zero:0, one:1, two:2, three:3, four:4, five:5, six:6, seven:7, eight:8,
    nine:9, ten:10, eleven:11, twelve:12, thirteen:13, fourteen:14, fifteen:15,
    sixteen:16, seventeen:17, eighteen:18, nineteen:19, twenty:20, thirty:30,
    forty:40, fifty:50, sixty:60, seventy:70, eighty:80, ninety:90
  };

  /* "two hundred fifty" -> 250. Small and hand-written; spoken amounts in a
   * demo are short. Returns null when nothing parses. */
  function wordsToNumber(text) {
    var words = String(text).toLowerCase().replace(/[^a-z ]/g, ' ').split(/\s+/);
    var total = 0, cur = 0, seen = false;
    for (var i = 0; i < words.length; i++) {
      var w = words[i];
      if (WORD_NUM[w] != null) { cur += WORD_NUM[w]; seen = true; }
      else if (w === 'hundred') { cur = (cur || 1) * 100; seen = true; }
      else if (w === 'thousand') { total += (cur || 1) * 1000; cur = 0; seen = true; }
      else if (w === 'lakh' || w === 'lakhs') { total += (cur || 1) * 100000; cur = 0; seen = true; }
      else if (w === 'and') { /* skip */ }
      else if (seen && (total + cur) > 0) { break; }
    }
    var v = total + cur;
    return (seen && v > 0) ? v : null;
  }

  function parseAmount(text) {
    if (!text) return null;
    for (var i = 0; i < AMOUNT_PATTERNS.length; i++) {
      var m = String(text).match(AMOUNT_PATTERNS[i]);
      if (m) {
        var v = parseFloat(m[1].replace(/,/g, ''));
        if (v > 0) return v;
      }
    }
    var w = wordsToNumber(text);
    if (w) return w;
    var bare = String(text).match(/\b([0-9][0-9,]*(?:\.[0-9]{1,2})?)\b/);
    if (bare) {
      var b = parseFloat(bare[1].replace(/,/g, ''));
      if (b > 0) return b;
    }
    return null;
  }

  /* Merchant from a receipt: the first line that looks like a name rather than
   * an address, a number or a label. */
  function parseMerchant(text) {
    if (!text) return null;
    var lines = String(text).split(/[\r\n]+/);
    for (var i = 0; i < lines.length && i < 8; i++) {
      var l = lines[i].trim();
      if (l.length < 3 || l.length > 40) continue;
      if (/[0-9]{4,}/.test(l)) continue;
      if (/\b(gst|gstin|tin|invoice|bill|receipt|date|time|tel|phone)\b/i.test(l)) continue;
      if (/^[0-9₹rs.,\s-]+$/i.test(l)) continue;
      return l;
    }
    return null;
  }

  /* Strip a spoken amount out of the note so it reads naturally:
   * "250 rupees for coffee" -> "coffee" */
  var NUM_WORDS_RE = new RegExp(
    '\\b(?:' + Object.keys(WORD_NUM).join('|') +
    '|hundred|thousand|lakh|lakhs|and)\\b', 'ig');

  function noteFromSpeech(text) {
    if (!text) return '';
    return String(text)
      .replace(/(?:₹|rs\.?|inr|rupees?)\s*[0-9][0-9,]*(?:\.[0-9]{1,2})?/ig, ' ')
      .replace(/[0-9][0-9,]*(?:\.[0-9]{1,2})?\s*(?:₹|rs\.?|inr|rupees?)/ig, ' ')
      .replace(NUM_WORDS_RE, ' ')          // spoken numerals, e.g. "two hundred fifty"
      .replace(/\b[0-9][0-9,]*(?:\.[0-9]{1,2})?\b/g, ' ')  // bare digits
      .replace(/\s+/g, ' ')
      .replace(/^\s*(?:i\s+)?(?:spent|paid|gave|bought)\b\s*/i, '')
      .replace(/^\s*(?:for|on|to|at)\s+/i, '')
      .replace(/^\s*the\s+/i, '')
      .replace(/\s+/g, ' ')
      .trim();
  }

  // --- public -----------------------------------------------------------

  /* Receipt -> {ok, amount, merchant, text, source}. Tries the on-device VLM,
   * falls back cleanly so the capture still produces a pending entry. */
  function fromImage(path, cb) {
    if (!path) { cb({ ok: false, error: 'no image path' }); return; }
    PTBridge.visionExtract(path, function (r) {
      if (!r || !r.ok) {
        cb({ ok: false, error: (r && r.error) || 'vision unavailable',
             amount: null, merchant: null, source: 'none' });
        return;
      }
      // The model returns TEXT. Numbers are parsed here, deterministically.
      var text = r.text || '';
      cb({
        ok: true,
        text: text,
        amount: parseAmount(text),
        merchant: parseMerchant(text),
        source: r.source || 'vlm',
        ms: r.ms
      });
    });
  }

  /* Audio -> {ok, text, amount, source}. */
  function fromAudio(path, cb) {
    if (!path) { cb({ ok: false, error: 'no audio path' }); return; }
    PTBridge.transcribe(path, function (r) {
      if (!r || !r.ok) {
        cb({ ok: false, error: (r && r.error) || 'transcription unavailable',
             amount: null, text: '', source: 'none' });
        return;
      }
      var text = r.text || '';
      cb({
        ok: true,
        text: text,
        amount: parseAmount(text),
        merchant: noteFromSpeech(text) || null,
        source: r.source || 'asr',
        ms: r.ms
      });
    });
  }

  return {
    fromImage: fromImage,
    fromAudio: fromAudio,
    parseAmount: parseAmount,
    parseMerchant: parseMerchant,
    noteFromSpeech: noteFromSpeech,
    wordsToNumber: wordsToNumber
  };
})();

if (typeof module !== 'undefined' && module.exports) module.exports = PTExtract;
