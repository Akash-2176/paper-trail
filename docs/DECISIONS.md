# Architecture Decision Records

Short records of the non-obvious choices, and what we rejected. Written before the event so the reasoning is fixed while we're calm, not at 3am.

---

## ADR-001 — SMS is a reconciliation source, not a trigger

**Status:** Accepted

**Context.** Indian bank SMS is delivered 30 seconds to 10 minutes after the transaction, frequently while the device is locked. A design that treats SMS arrival as the event that starts a capture flow inherits every one of those failure modes.

**Decision.** The user's action creates a timestamped `PendingEntry`. Bank SMS reconciles against it whenever it arrives, matched on amount and time window.

**Consequences.** The delay becomes a designed-for property. No real-time SMS listener, no wake locks, no doze-mode battle, and no dependency on the screen being unlocked. Polling the content provider on a modest interval is sufficient.

**Rejected:** `RECEIVE_SMS` broadcast listener as the primary trigger — couples the product's core loop to the least reliable part of the stack.

---

## ADR-002 — Thin native shell, hot-reloadable product layer

**Status:** Accepted

**Context.** Red Light is roughly 10.5 of 19 build hours, during which the laptop is closed as a build machine and Office Kit is the only route to the phone. Any product logic living in compiled Android code cannot be iterated during those hours.

**Decision.** Compile a small native shell once, in the first Green Light window. It exposes SMS, camera, microphone and NPU inference to a WebView. All product logic — parser, reconciliation, insights, UI — lives in the hot-reloadable layer.

**Consequences.** Continuous iteration through all Red Light blocks, driven from the laptop keyboard via Office Kit remote control. The shell must be right early, because we don't get to recompile it freely.

**Rejected:** Full native Android — would mean stacking up hours of unverified changes between Green windows. Pure PWA — cannot read SMS (WebOTP only handles one-time codes), Web Speech API ships audio off-device, and WebNN is not production-available for NPU targeting.

---

## ADR-003 — JS bridge, not a localhost HTTP server

**Status:** Accepted

**Context.** The obvious way to connect a native shell to a web layer is a local HTTP server. But a server on `127.0.0.1` exposing bank messages is readable by any other app on the device.

**Decision.** Bridge via `postMessage` / `addJavascriptInterface`. No listening port. Additionally, the shell parses SMS itself and emits only structured, redacted objects — raw message bodies and full account numbers never cross into the product layer.

**Consequences.** No token-exchange dance, no port, no cross-app read surface.

---

## ADR-004 — The model never does arithmetic

**Status:** Accepted

**Context.** It is tempting to hand a transaction list to an LLM and ask for totals and insights. In a financial product, a confidently wrong number is worse than no number — and on a live demo stage, it is fatal.

**Decision.** Deterministic code computes every figure: reconciliation scoring, totals, recurring-debit detection, runway. The local model is restricted to two jobs — extracting structured fields from a receipt image, and phrasing an explanation of numbers it was handed.

**Consequences.** Every number on screen is reproducible and testable. It also concentrates the interesting engineering in the reconciliation engine rather than in prompt tuning.

---

## ADR-005 — Two models, not three

**Status:** Accepted

**Context.** The natural design wants a vision model for receipts, a speech model for voice, and a general LLM for analysis. That is three sets of weights, three loads, three memory profiles and three failure modes, owned by one ML engineer across roughly 8.5 hours of Green Light.

**Decision.** One VLM (receipt extraction, and any reasoning) plus Whisper (speech). Start at Qwen3 0.6B to prove the pipeline end to end, upgrade to 4B in the overnight window if time allows.

**Consequences.** Fewer moving parts, and a working system exists early rather than late. Upgrading model size at 3am is a config change; debugging memory pressure at 3am is not.

---

## ADR-006 — Weights staged locally, never downloaded at the venue

**Status:** Accepted

**Context.** The GenieX SDK pulls model weights from Hugging Face on first use. A multi-gigabyte download over venue wifi shared by hundreds of participants, at 1am, is not a risk we are willing to carry.

**Decision.** Pull weights before the event, `adb push` to the device, load with `HubSource.LOCALFS`. Carry a USB copy of everything, including the AI Hub `SM8850` bundle.

---

## ADR-007 — No cloud backup, no accessibility service

**Status:** Accepted

**Context.** Both were considered. Cloud backup (Drive-style) is a familiar feature; an accessibility service would give broader access to on-screen content.

**Decision.** Neither ships.

**Reasoning.** Cloud backup directly contradicts the product's central claim and invites the question "so it isn't really on-device then?" Accessibility service is the exact permission SMS-forwarder malware abuses, and requesting it during a live demo is a bad look regardless of intent. Encrypted local export covers the legitimate need.

---

## ADR-008 — Track choice: FinTech and Commerce, Chennai only

**Status:** Accepted, with known cost

**Context.** FinTech and Commerce runs at city battles only; the Grand Finale drops it. Building here means the product cannot be carried into October without a track change.

**Decision.** Accept the cost. Chennai is the goal for this cycle.

**Consequences.** If we advance, the Finale build is a separate decision — the reconciliation engine and on-device stack are portable to Productivity or Smart Living, but the framing would change.
