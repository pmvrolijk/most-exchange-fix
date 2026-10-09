---
name: verify-for-real
description: Use when a change to most-exchange-fix touches session lifecycle, logon, reconnect, failover, back-pressure, persistence across a restart, or anything a test double stands in for — and when behaviour is inexplicable and the code looks right.
---

# Verifying against the real thing

Every defect most-exchange's unit tests missed lived in a category the suite did not cover, or
behind a fake easier than reality. The same categories apply here, plus FIX's own.

## A convenient fake validates nothing

- A stand-in for the exchange must model what the real one does awkwardly: back-pressure,
  `GATEWAY_UNAVAILABLE`, a gateway that dies silently, a dropped report, a `TRUNCATED` resend.
- For FIX, a real Artio initiator against the real acceptor beats any fake session: logon, sequence
  numbers, resend requests and `PossDup` are where FIX sessions break.
- **The e2e runs most-exchange's real processes** (`../most-exchange/e2e/` starts them). Prefer one
  assertion there to three on doubles.

## The categories that are absent, not badly tested

- **idle** — heartbeats on the FIX side; and on the exchange side, a session left idle.
- **shutdown** — SIGTERM must log FIX sessions out and drain; a native image needs
  `--install-exit-handlers` or it dies outright.
- **startup ordering** — the opening mass status must complete before a client's first order is
  accepted; reports can arrive before a subscription is live.
- **restart** — what an adapter restart recovers: FIX sequence numbers from Artio, the report
  sequence from the `SessionStore`, the ClOrdID map from its store. Only a real restart proves it.
- **failover** — the exchange's leader dying under load. `OrderEntrySession` has never been through
  a real one; this repo's e2e is where it first will be.

## Silent failure is the expensive kind

Counters must distinguish causes. "Sent", "answered", "settled never-sequenced", "ambiguous" and
"refused" are different counts, and a run is read from them.

## When behaviour is inexplicable, verify the environment first

```sh
pgrep -f "nl.lamia.most.exchange"     # adapter and exchange; stop them before deleting directories
```

## Documentation counts as verification

Do not write a command into a doc that has not been run.
