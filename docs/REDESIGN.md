# Paper Trail — Redesign Assessment

**13 Sep 2026.** Written against the actual repository at `1ba6f3c`, after NPU
inference was proven working on device. Not a generic template.

---

## 1. What exists today

**Shell (Kotlin, ~1,400 lines).** One Activity hosting a WebView. Native owns
sensors and models; nothing product-shaped lives here (ADR-002).

| Component | Does |
|---|---|
| `SmsReader` | Polls `Telephony.Sms.Inbox`, parses + redacts natively (ADR-003/010) |
| `SmsWatcher` | ContentObserver, 500ms debounce, re-read on change |
| `CameraCapture` | CameraX + PreviewView viewfinder |
| `ImagePrep` | 12MP → 1024px; deletes originals; caps `captures/` by count and bytes |
| `OcrEngine` | ML Kit, ~110ms, the working receipt path |
| `VisionEngine` | Qwen2.5-VL Q4_K_M on llama.cpp **CPU**, ~35s |
| `LlmEngine` | **Qwen3-1.7B W4A16 on Hexagon NPU**, ~430ms |
| `SpeechEngine` | Android on-device ASR, locale fallback chain |
| `LocalStore` | `ledger.json`, temp-file + rename |
| `Bridge` | `addJavascriptInterface`, two-way |

**Product layer (plain JS, ~2,650 lines, no build step — ADR-011).**
`reconcile.js` (scoring), `store.js` (ledger), `financial-memory.js` (5 queries
+ clarity), `intent.js` (routing), `extract.js` (parsing), `proof.js`
(telemetry), `capture-ui.js` (sheets), `index.html` (**645 lines — everything
else**).

**Verified on device.** Reconciliation 5/5; OCR → ₹300 / VANGALAMMAN in 173ms;
NPU 3/3 intents at ~430ms; ledger survives force-stop.

---

## 2. The product

**Paper Trail sees the half of spending bank SMS cannot** — cash, and the
*reason* for a payment.

**User:** someone in India whose bank SMS says `PAYTM*38291` and who cannot
remember, a week later, what that was.

**Core loop:** capture (photo/voice/manual) → bank SMS arrives → they merge into
one record with evidence attached.

That loop **works**. The problems are everything around it.

---

## 3. Major problems

### P0-1 · `index.html` is 645 lines doing six jobs
Rendering, event wiring, clarity metrics, proof telemetry, intent dispatch and
ledger mutation in one file with no boundaries. Every new feature enlarges it.
**This is the single biggest obstacle to the coming feature expansion.**

### P0-2 · The LLM barely participates
It classifies intent in 430ms on the NPU — then the *deterministic* router runs
first and answers ≥0.8-confidence cases itself, so the model is consulted almost
never. We ship a 1.5GB NPU model that the product mostly does not use.

### P0-3 · Natural-language answers are thin
`"₹666 across 3 payments"` is a number, not an answer. The model that could
phrase a real explanation is idle while a `switch` writes the sentence.

### P1-1 · No navigation
One scrolling column: clarity bar, capture box, ask box, three stacked lists.
With 8 pending and 5 reconciled it already requires scrolling past the controls
to see anything. There is no way to reach one transaction.

### P1-2 · Evidence is shown, but not usable
Chips say `RECEIPT` — the photo cannot be opened. The path is stored; nothing
renders it. The product's central claim is evidence, and evidence is not
viewable.

### P1-3 · Clarity reads 20% with 9 unexplained
Honest, but the UI offers no route from "9 unexplained" to explaining one. The
number is a scoreboard, not a workflow.

### P2-1 · Three lists with no relationship
Reconciled / Pending / Transactions are the *engine's* internal states exposed
as UI. A user thinks in payments, not queue states.

### P2-2 · The VLM is dead weight
35s on CPU vs OCR's 110ms, so it never wins. 1.9GB + 1.3GB mmproj resident.

---

## 4. Vibe-coded tells

| Tell | Why it hurts |
|---|---|
| Three parallel lists | Exposes implementation state as information architecture |
| Ask box wired to a `switch` | Looks like AI, is a keyword matcher |
| Clarity tiles that only display | Metrics with no action are decoration |
| Proof panel measuring itself | Instrumentation shipped as a feature |
| `+ Spend / Photo / Voice / Read SMS / Reconcile` — 5 equal buttons | No primary action |
| "Read SMS" and "Reconcile" as user buttons | Internal operations the app should do itself |

---

## 5. Target structure

**Three tabs. One primary action.**

```
┌────────────────────────────────────────┐
│  Paper Trail            NPU · Qwen3    │  ← runtime badge stays: it is evidence
├────────────────────────────────────────┤
│  [ Ledger ]   Explain 9   Ask          │  ← tabs
├────────────────────────────────────────┤
│                                        │
│   one unified, reverse-chronological   │
│   list of PAYMENTS                     │
│   each row: amount · merchant ·        │
│   evidence chips · state               │
│                                        │
│                        ( ⊕ Capture )   │  ← single FAB
└────────────────────────────────────────┘
```

- **Ledger** — every payment, one list. Pending/reconciled become a *state* on a
  row, not a separate list. Tap a row → detail sheet with the receipt image, the
  SMS-derived fields, the reasons the match scored, and "not this".
- **Explain (n)** — the work queue. Only unexplained payments, newest first,
  each with one-tap attach. This is what turns the clarity number into a task.
- **Ask** — natural language over the ledger, with the LLM phrasing the answer.

**Capture is one button.** Photo / voice / manual become choices *inside* the
sheet, not three top-level buttons competing for attention.

**Removed:** "Read SMS" and "Reconcile" buttons. The ContentObserver already
re-reads; reconciliation already runs on change. Exposing them is exposing
plumbing.

---

## 6. Where the LLM earns its place

The rule stays absolute (ADR-004): **deterministic code computes every number.**
The model works on *language*, never arithmetic.

| Job | Model does | Code does |
|---|---|---|
| **Intent routing** | classify capture/context/query | re-parse the amount |
| **Answer phrasing** | turn a result object into a sentence | compute every figure in it |
| **Merchant naming** | `PAYTM*38291` + receipt → "Vangalamman petrol" | never touches amount |
| **Explain a match** | why these two are the same payment | scores stay deterministic |
| **Anomaly wording** | phrase a duplicate warning | detection is `didIPayTwice` |

**Change to make now:** stop letting the deterministic router short-circuit the
model. Route *every* utterance to the NPU, and use the rules as the **fallback**
when the model is unavailable or returns something invalid — the inverse of
today. At 430ms that is affordable, and it is the difference between shipping a
model and using one.

---

## 7. NPU architecture (settled — do not revisit)

```
JS  →  Bridge  →  LlmEngine  →  GenieX qairt  →  QnnHtp  →  Hexagon v81
                       ↓ (bundle missing / load fails)
                  PTIntent deterministic rules
```

**Evidence it is real:** `loaded on NPU`, `intent=... on npu`, FastRPC/cDSP
session logs, `compute_unit = NPU`.

**Four bugs fixed to get here** (see `60c45ec`): `ModelConfig` defaults rejected
by the plugin; loader takes the *parent* of `model_path`; JNI natives
unregistered at warm-up; and a SIGSEGV from the VLM and LLM contending for the
DSP.

**LiteRT/MediaPipe is rejected.** Its Android LLM path runs CPU/GPU; Hexagon is
reachable only through QNN/qairt. Adopting it moves *away* from NPU.

---

## 8. Order of work

| # | Change | Why first |
|---|---|---|
| 1 | Split `index.html` → `ui/*.js` modules | Everything else lands on top of it |
| 2 | LLM-first routing, rules as fallback | Makes the NPU model actually used |
| 3 | Unified Ledger + tabs + single FAB | Fixes the information architecture |
| 4 | Payment detail sheet with receipt image | Makes evidence real |
| 5 | Explain queue | Turns the clarity number into a workflow |
| 6 | LLM answer phrasing in Ask | Real natural language |
| 7 | Empty / loading / error states | Currently missing throughout |

**Keep, do not touch:** `reconcile.js`, `SmsReader`, `ImagePrep`, `LocalStore`,
`OcrEngine`. These are tested and correct.

---

## 9. Risks

- **Splitting `index.html` risks regressing the working demo path.** Mitigate by
  moving code without rewriting it, and re-running the 5× merge check after.
- **LLM-first adds ~430ms to every utterance.** Acceptable for voice; keep the
  rules path warm so a model failure is invisible.
- **The VLM should probably be cut.** It costs 3.2GB and never beats OCR. Left
  in for now because it is the fallback for handwritten receipts, but it earns
  removal if that case is not demoed.
- **Only device A has the NPU bundle.** B and C have empty SMS inboxes and no
  bundle; anything demoed there behaves differently.
