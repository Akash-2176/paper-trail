# Paper Trail — System Design & Architecture
**Team ColdBoot · iQOO Hackathon 2026 · Chennai City Battle · Sep 12–13**
Track: FinTech and Commerce · Device: iQOO 15 (Snapdragon 8 Elite Gen 5 / `SM8850`, Android 16, OriginOS 6)

---

## 1. Overview

Every expense tracker in India sees only bank SMS. It knows `₹2,400 → PAYTM*38291` and nothing else — not the cash you handed the auto driver, not what was on the bill, not why. Paper Trail uses the phone's **camera and mic as the missing input layer**, and reconciles what they capture against bank SMS with a deterministic engine, entirely on-device on the Snapdragon NPU.

**Non-negotiable rule: the LLM never does arithmetic.** Deterministic code computes every number. The model only reads receipts and explains results. A confidently wrong number on stage is a fatal 30%.

---

## 2. System architecture

```
┌─────────────────────────────────────────────────────────┐
│                      iQOO 15                            │
│                                                         │
│  ┌───────────────── NATIVE SHELL (thin) ──────────────┐  │
│  │  Compiled ONCE in Green Block 1. Never touched.    │  │
│  │                                                    │  │
│  │  • SMS ContentProvider reader (poll, not listen)   │  │
│  │  • CameraX capture → JPEG to local path            │  │
│  │  • AudioRecord → PCM to local path                 │  │
│  │  • GenieX runtime binding (NPU inference)          │  │
│  │  • Foreground service (keep-alive)                 │  │
│  │  • WebView + JS bridge (postMessage)               │  │
│  └────────────────────┬───────────────────────────────┘  │
│                       │  structured objects only         │
│                       │  (never raw SMS bodies)          │
│  ┌────────────────────┴───────────────────────────────┐  │
│  │           PRODUCT LAYER (hot-reloadable)           │  │
│  │                                                    │  │
│  │   Ingest ──► Parse ──► Pending Store               │  │
│  │                            │                       │  │
│  │                  ┌─────────┴─────────┐             │  │
│  │                  │  RECONCILIATION   │  ← the core │  │
│  │                  │      ENGINE       │             │  │
│  │                  └─────────┬─────────┘             │  │
│  │                            │                       │  │
│  │   Ledger ──► Insights Engine ──► UI                │  │
│  └────────────────────────────────────────────────────┘  │
│                                                         │
│  Models (LOCALFS, pre-staged, no download):             │
│    • VLM  — receipt line-item extraction                │
│    • Whisper-small — speech to text                     │
└─────────────────────────────────────────────────────────┘
                    NO CLOUD. NO SERVER. NO EGRESS.
```

### Why a thin shell + hot-reloadable layer

Red Light is ~10.5 of 19 build hours, and the laptop is closed as a build machine. If product logic lives in compiled Android code, we lose those hours. So: compile the shell once in the first Green window, then iterate the product layer continuously through Office Kit remote control.

Bridge is `postMessage` / `addJavascriptInterface` — **not** a localhost HTTP server. No open port, no other app can reach our data.

---

## 3. Data flow

### 3.1 SMS is a reconciliation source, NOT a trigger

Indian bank SMS arrives 30 seconds to 10 minutes late, often while the phone is locked. **Do not build around SMS as an event.**

The user's action is the trigger:

```
User acts (photo / voice)  →  PendingEntry created, timestamped
                                        │
        Bank SMS arrives (t + 0..10min) │
                                        ▼
                            Reconciliation on (amount, time window)
                                        │
                                        ▼
                                 ConfirmedTxn
```

This makes the delay a designed-for property, not a bug. It also removes the locked-phone problem entirely.

### 3.2 Reconciliation engine (this is our technical depth score)

Match `PendingEntry` ↔ `SmsTxn`:

| Signal | Weight | Notes |
|---|---|---|
| Amount exact match | high | primary key |
| Amount ± tip/rounding | medium | receipt total vs charged |
| Time delta within window | high | 0–10 min expected |
| Merchant alias fuzzy match | medium | `PAYTM*38291` → normalised |
| Direction (debit/credit) | gate | hard filter |

Confidence score → auto-merge above threshold, prompt user below it. Unmatched cash entries stay cash-only (that's correct behaviour, not a failure).

### 3.3 What crosses the bridge

The shell parses SMS and emits **structured, redacted objects only**. Raw message bodies never enter the product layer. Account numbers masked at source. This is both good practice and a good answer when a judge asks.

---

## 4. Model layer

**Runtime: GenieX** — `implementation("com.qualcomm.qti:geniex-android:0.3.1")`

| Setting | Friday testing (Realme GT 7T) | Saturday (iQOO 15) |
|---|---|---|
| `runtime_id` | `llama_cpp` | `qairt` (fallback `llama_cpp`) |
| `compute_unit` | `cpu` / `gpu` | `npu` |
| chipset | n/a (MediaTek) | `SM8850` |
| model | Qwen3 0.6B Q4_0 | Qwen3 4B (12GB RAM allows it) |
| weights source | `HubSource.LOCALFS` | `HubSource.LOCALFS` |

**Rules:**
- GGUF must be **Q4_0**. K-quants (Q4_K_M etc.) are not optimised for Hexagon — using them silently loses NPU acceleration.
- Weights pre-downloaded and `adb push`ed. **Never** rely on venue wifi for a multi-GB pull at 1am. Carry them on a USB stick.
- **Two models maximum** (VLM + Whisper). One ML person, 8.5h of Green. Three models is the plan that dies at 4am.
- Start at 0.6B to prove the pipeline end-to-end, upgrade to 4B overnight. Never the reverse.

---

## 5. Ownership

| Person | Owns | Fallback duty |
|---|---|---|
| **Akash** | Architecture, vision pipeline, UI, Office Kit bridge, **demo script** | Calls the vision cut |
| **Core dev** | Native shell, SMS layer, reconciliation engine, insights maths | Owns the deterministic path — must never block |
| **ML** | GenieX integration, NPU, VLM + Whisper on-device | Overnight shift |

---

---

Build schedule, kill switches and the pre-event checklist live in [BUILD-PLAN.md](BUILD-PLAN.md).
