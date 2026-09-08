# Paper Trail — Build Plan

**Team ColdBoot · iQOO Hackathon 2026 · Chennai City Battle**

Companion to [ARCHITECTURE.md](ARCHITECTURE.md).

---

## Schedule (mapped to Red/Green)

| Window | Mode | Work |
|---|---|---|
| Sat 11:00–13:00 | 🟢 GREEN | **Compile the shell. Pair Office Kit. Stage weights. Prove hot reload.** No features. |
| 13:00–15:30 | 🔴 RED | SMS parser, PendingEntry store, data models |
| 15:30–16:30 | 🟢 GREEN | 1 hour only — build/deploy anything that needs a compiler. Precious. |
| 16:30–19:00 | 🔴 RED | Reconciliation engine. **Get something on screen.** |
| 19:00–22:00 | ⬜ EVAL 1 | **Scored checkpoint.** Demo, don't build. |
| 22:00–01:00 | 🔴 RED | UI, voice capture flow, demo script v1 |
| 01:00–06:30 | 🟢 GREEN | **The big one (5.5h).** All model work: NPU, VLM, Whisper, quantisation |
| 06:30–09:00 | 🔴 RED | Polish. Rehearse. **No new features.** |
| Sun 09:00 | ⬜ EVAL 2 | Judged at tables, demo on phone |
| 13:45 | 🏆 | Top 10 pitches, 3–5 min |

**Critical:** Eval Round 1 at 19:00 Saturday is *scored* and stacks toward the Top 10. A working ugly slice by 19:00 beats a beautiful dark one.

**ML shift is overnight.** Whoever owns models sleeps Saturday evening, not Sunday morning.

---


## Scoring map — build for the rubric

| Dimension | % | How we earn it |
|---|---|---|
| End product quality | 30% | Narrow scope, one flawless happy path |
| Novelty & impact | 20% | Cash + receipt context = the unseen half of Indian spending |
| **Creative phone use** | **15%** | *Telemetry, not opinion.* Camera + mic + NPU as core inputs |
| Technical depth | 15% | Reconciliation engine, deterministic/model split, on-device |
| **Office Kit usage** | **10%** | *Telemetry.* Use remote control + clipboard + file transfer **constantly**, every Red block |
| Demo & presentation | 10% | Rehearsed 5×, physical moment on stage |

25% is measured by HackTracker, not judged. Most teams will forfeit it. We won't.

---

## Kill switches — decide these NOW, not at 2am

| Trigger | Time | Action |
|---|---|---|
| Vision not producing structured output | **06:00 Sun** | Cut vision, go voice-only. Not 09:00 — we need 3h to rehearse. |
| NPU (`qairt`) not loading | 03:00 Sun | Fall back to `llama_cpp` on GPU. Still on-device. |
| SMS layer not working | Sat 17:00 | Ship with pre-loaded SMS dataset on device. **Disclose it in the pitch.** Nobody docks honesty; everyone docks a thin demo. |
| Model too slow at 4B | any | Drop to 0.6B. Speed > smart in a live demo. |

**Timebox the entire SMS/Android plumbing layer to 4 hours.** Background services, doze mode, permission models — none of it scores. A jury never sees plumbing.

---

---

## Pre-event checklist (before Friday)

- [ ] Test SMS ContentProvider read on Android 15/16 — **Realme UI + OriginOS both kill background processes aggressively**
- [ ] Forward real bank SMS to spare SIM — **verify format survives**: sender ID changes from `AD-HDFCBK` to a plain number, and forwards can truncate at 160 chars
- [ ] Ensure spare SIM has **months of history** incl. recurring debits (needed for subscription detection)
- [ ] Build + test the native shell → WebView bridge on GT 7T
- [ ] Termux from **F-Droid/GitHub** (Play Store build is abandoned) — have APK on USB
- [ ] GenieX sample chat app running on any device
- [ ] Pull GGUF weights (Qwen3 0.6B + 4B, **Q4_0**) + AI Hub `SM8850` bundle → USB stick
- [ ] Install Office Kit on all three laptops (Windows 10+ / macOS 10.14.6+) from pc.vivoglobal.com
- [ ] Locate OriginOS battery-optimisation exemption setting **before** we need it

---
