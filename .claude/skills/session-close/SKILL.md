---
name: session-close
description: Use when a piece of work on most-exchange-fix is finished and verified, or when the user signals the session is ending ("nice work", "let's wrap up", "update the status"). Writes the state back down so the next session can resume from prose. Never commits.
---

# Closing a session

Resumability comes from every session producing a written state, not just consuming one.

## Never commit

**Do not run `git commit`, `git push` or `git add` unless the user explicitly asks in that turn** —
in this repo or in `../most-exchange`. Summarise what changed, in files; propose a commit message
per repository; stop.

A proposed message states what was **measured**, not only what was written.

## What to write, in this order

1. **`docs/Status.md` — always.** Where things stand (counts if they moved), open decisions, to do
   next in order with the reason for the order. Strike through what closed with **Done**, and say
   what is still open underneath it.
2. **`CLAUDE.md` — only for load-bearing traps.** Imperative, one or two sentences, no history; the
   reasoning goes in `docs/Rationale.md` with a `→ R§n` pointer.
3. **`docs/Design.md` — if behaviour changed**, in the same commit as the code. Strike through what §8
   closed; add what opened.
4. **`docs/Handover.md` — for a substantial piece of work**: a new `§2x` record — problem, decision,
   what was built, the check.
5. **`docs/Measurements.md` — if a number was produced** (`perf-claim`).
6. **`../most-exchange`** — if the SDK changed there, its own Status/Handover/Design too, by its own
   `session-close`.

## The honesty rules

- Say **sent** where only sent is known, **applied** only where something acknowledged.
- "Builds" is not "runs"; "runs" is not "measured". An unverified claim is written as unverified.
- Record reversals as reversals.
- Write the embarrassing open item down.
