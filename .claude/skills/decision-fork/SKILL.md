---
name: decision-fork
description: Use when a choice while working on most-exchange-fix would change FIX-facing behaviour, a persistence guarantee, a failure mode, where state lives, or the contract with most-exchange. Presents the fork with options and a recommendation instead of guessing, and records the answer where the next session will find it.
---

# Escalating a decision

In most-exchange, dozens of questions were asked this way and nearly all were answered by taking the
recommendation. The question is a decision record being written before the code exists.

## When to stop and ask

When the answer changes:

- **what a FIX client sees** — a message accepted or refused, a field's meaning, an order's status
- **a persistence guarantee** — what survives an adapter restart, and what is learned only by a mass
  status
- **a failure mode** — what is rejected, retried, held or reported as unknown
- **where state lives** — this repo, Artio's archive, a store, or the exchange's SDK
- **the contract with most-exchange** — anything that needs `client` to change belongs there, and is
  asked as such

Do **not** ask about conventional defaults or facts checkable in either codebase.

## How to ask

- Two to four concrete options, the recommended one first and marked **(Recommended)**.
- The trade-off of each in this system's terms: what a client sees, latency on the library thread,
  what a restart recovers, what an operator must do.
- What the recommended option leaves **out of scope**.

## After the answer

The answer is not recorded by having been said in chat:

- **`docs/Design.md`** if normative, with the reasoning;
- **`CLAUDE.md`** if a future session could violate it unknowingly, reasoning in `docs/Rationale.md`;
- what it left open in **Design.md §8** and `docs/Status.md`.

## When a decision reverses

Mark the old record **superseded**; never rewrite it away. The reasons for a reversal are the most
valuable part of it.
