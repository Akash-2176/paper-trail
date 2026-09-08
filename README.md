# Paper Trail

**An on-device financial system that sees the half of your spending bank SMS can't.**

Team **ColdBoot** · iQOO Hackathon 2026 · Chennai City Battle, 12–13 Sep · Track: FinTech and Commerce
Built by [EzuraArc Pvt. Ltd.](https://www.ezuraarc.com)

---

> ⚠️ **Pre-event repository.** Per the hackathon build rules, all application code is written inside the event window (Sat 10:00 → Sun 12:00). This repo currently contains **planning and architecture documents only**. Implementation lands here during the battle.

---

## The problem

Every expense tracker in India reads exactly one input: bank SMS.

```
HDFC Bank: Rs 2,400.00 debited from a/c XX4417
on 08-09-26 to PAYTM*38291.
```

That's all it will ever know. It cannot see:

- **Cash.** The ₹200 handed to an auto driver generates no message at all.
- **Context.** `PAYTM*38291` is not a memory. What was bought, for whom, and why is nowhere in the record.
- **Category.** Merchant strings get bucketed by keyword matching, so the report is confidently wrong and the user stops trusting it.

A large share of everyday Indian spending is either cash or context-free. That's the half no tracker can see — and it's why people abandon these apps within weeks.

## The approach

Use the phone's **camera and microphone as the missing input layer**, and reconcile what they capture against bank SMS with a deterministic engine — entirely on-device.

| Input | Captures | Runs on |
|---|---|---|
| 📷 Camera | Receipt line items, real merchant name | On-device VLM |
| 🎙️ Microphone | Cash spends, the reason for a transaction | On-device ASR (Whisper) |
| 💬 Bank SMS | Amount, merchant code, timestamp | Local parser, no model |

**Core rule: the LLM never does arithmetic.** Deterministic code computes every number. The model only reads receipts and phrases explanations. A confidently wrong number in a financial product is worse than no number.

## Architecture at a glance

```
┌───────────────── NATIVE SHELL (thin, compiled once) ─────────────────┐
│  SMS ContentProvider · CameraX · AudioRecord                         │
│  GenieX runtime binding (NPU) · Foreground service · WebView bridge  │
└──────────────────────────────┬───────────────────────────────────────┘
                               │  structured, redacted objects only
┌──────────────────────────────┴───────────────────────────────────────┐
│                  PRODUCT LAYER (hot-reloadable)                      │
│   Ingest → Parse → Pending Store → RECONCILIATION → Ledger → UI      │
└──────────────────────────────────────────────────────────────────────┘

                     NO CLOUD · NO SERVER · NO EGRESS
```

Full detail: **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**

### Two design decisions worth calling out

**1. SMS is a reconciliation source, not a trigger.**
Indian bank SMS arrives 30 seconds to 10 minutes late, usually while the phone is locked. Building around it as a real-time event is a trap. Instead, the *user's action* creates a timestamped pending entry, and the message reconciles against it whenever it arrives. The delay becomes a designed-for property rather than a bug.

**2. The bridge is `postMessage`, not a local HTTP server.**
A localhost server exposing SMS would be readable by any other app on the device. The JS bridge means no open port and no cross-app surface. The shell also parses SMS itself and emits only structured, redacted objects — raw message bodies never cross into the product layer.

## Target device

| | |
|---|---|
| Device | iQOO 15 (hackathon loaner) |
| SoC | Snapdragon 8 Elite Gen 5 — `SM8850` |
| OS | Android 16 · OriginOS 6 |
| Runtime | [GenieX](https://github.com/quic/ai-hub-apps) — `com.qualcomm.qti:geniex-android` |
| Inference | Hexagon NPU via `qairt`; `llama_cpp` + GGUF `Q4_0` fallback |
| Models | One VLM (receipts) + Whisper (speech). Weights staged locally, `HubSource.LOCALFS` |

## Scope

**In:** receipt capture → line items · voice cash capture · SMS parse · reconciliation engine · recurring-debit detection · spend view

**Deliberately out:** accounts/login · cloud backup or sync · accessibility-service access · currency-note counting by vision · budgets, goals, dashboards

End product quality is 30% of the rubric; novelty is 20%. A small thing that works flawlessly beats an ambitious thing that stutters on stage.

## Team

| Role | Owns |
|---|---|
| Architecture & Vision | Vision pipeline, Office Kit bridge, integration, demo |
| Core Systems | Native shell, SMS layer, reconciliation engine, insights maths |
| On-Device ML | GenieX, NPU inference, VLM + Whisper |

## Repository layout

```
.
├── README.md                 # you are here
└── docs/
    ├── ARCHITECTURE.md       # system design, data flow, model layer
    ├── BUILD-PLAN.md         # Red/Green schedule, kill switches, checklist
    └── DECISIONS.md          # architecture decision records
```

## Licence

MIT — see [LICENSE](LICENSE).
