# Pre-Event Spike Findings

**Fri 11 Sep 2026** — verification run before the Chennai City Battle.

Throwaway spike, not committed. Purpose was to de-risk the two load-bearing
assumptions in [ARCHITECTURE.md](ARCHITECTURE.md) before the clock starts:
SMS content-provider reads, and the native↔WebView bridge with on-device hot
reload.

Camera, audio capture and GenieX inference remain **unverified** going in.

---

## 1. SMS bodies are not truncated

Longest body read was 247 characters, well past the 160-char single-segment
limit. The provider reassembles multipart messages before exposing `BODY`, and
newlines survive intact.

**Consequence:** no reassembly logic needed. Read `BODY` and trust it.

---

## 2. Sender IDs — three findings, one of which changes the parser

Observed `ADDRESS` values (delimited to check whitespace and casing):

```
[[AD-HDFCBK-S]]  [[VM-HDFCBK-S]]  [[JD-HDFCBK-S]]  [[JM-HDFCBK-S]]
[[VD-AXISBK-S]]  [[AD-ICICIT-S]]          <- real bank, 11 chars
[[+91XXXXXXXXXX]]                         <- forwarded, 13 chars
```

No leading or trailing whitespace. Consistent uppercase.

**2a. Every header carries a `-S` DLT suffix.** A naive anchored pattern such as
`^AD-HDFCBK$` matches nothing on a real device.

**2b. One bank arrives under multiple prefixes.** HDFC appeared as AD, VM, JD and
JM on a single SIM. The prefix is the telco delivery route, not the sender.

→ **Parse the middle token only, case-insensitive. Treat prefix and suffix as noise.**

**2c. Forwarded messages carry no bank identity at all.** The only issuer marker
is the signature in the body tail (e.g. a trailing `-KVB`).

This matters because seeded demo data is forwarded, so sender-based routing is
unavailable for exactly the messages used on stage. Two mitigations:

1. Inject messages directly into the provider with correct DLT headers, so the
   demo exercises the real path.
2. **Body-signature fallback** when the sender is a bare number. Built regardless
   of whether (1) works — real users also receive forwarded and aggregated
   messages, so this is a robustness feature rather than a demo workaround.
   See ADR-010.

---

## 3. Process survival — the foreground service is now evidence-backed

The app survived 5m19s backgrounded: same PID, all rows readable on resume, no
permission re-prompt. But within 90 seconds the OS trimmed RSS from 210MB to
137MB and set `oom_score_adj=700`.

It survived because the device was idle with RAM free. With a VLM resident and a
venue-loaded device, that is precisely the process that gets reaped. The standby
bucket never left ACTIVE, so a 5-minute test says nothing about an hour.

**Consequence:** the foreground service is required, not defensive. Battery-
optimisation exemption must be granted on the loaner before any long run.

---

## 4. `READ_SMS` was granted via installer exemption

The permission returned `granted=true` with `RESTRICTION_INSTALLER_EXEMPT`.
Side-loaded builds receive an exemption a store install would not, so `pm grant`
succeeded with zero taps.

Convenient during the build, but it means **the runtime prompt flow was never
actually exercised**. Revoke and retry deliberately before relying on it in a
demo.

---

## 5. Hot reload works — with three sharp edges

Verified three times: page replaced on-device, WebView reload picked up changes,
no reinstall. This is the mechanism [ADR-002](DECISIONS.md) depends on, so
confirming it was the single most valuable result of the night.

**Edge 1 — app-private storage is not directly writable.** `adb push` cannot
target app-private paths on a non-rooted device. The route is
`/data/local/tmp` + `run-as`, which only works because the build is debuggable.
**The debuggable build config is therefore load-bearing, not a convenience.**

**Edge 2 — path mangling on Windows.** Git Bash rewrites POSIX-looking remote
paths into Windows paths. Commands need a Windows-form local path and a POSIX
remote path in the same invocation.

**Edge 3 — a JS syntax error fails silently.** The reload marker updates, content
does not render, and the only signal is a `chromium:I` line in the log. A broken
push is indistinguishable from a working one without the log open.

**Rule for every Red Light block — keep this tailing in a dedicated terminal:**

```
adb logcat -c && adb logcat -s PTLAB:* chromium:I
```

---

## 6. Recurring-debit detection deferred

Detection requires months of seeded history and shares almost no code with the
reconciliation path — effectively a second product. Deferred to protect
reconciliation quality, which carries far more scoring weight.

Consequence: seeded data still needs 15–20 varied real messages across banks,
UPI and card, debits and credits, including **at least one whose amount matches
a receipt available to photograph live**. That pairing is the demo.

Pitch and walkthrough decks reference recurring detection and need updating if
this cut stands.

---

## Device caveat

All of the above was measured on a personal Android device, not the iQOO 15
loaner (Snapdragon 8 Elite Gen 5, `SM8850`, Android 16 / OriginOS 6). OriginOS
background management is expected to be at least as aggressive as what was
observed. NPU behaviour is entirely unverified — no Hexagon silicon was
available for testing.
