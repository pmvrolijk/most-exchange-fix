---
name: session-open
description: Use at the start of any work session on most-exchange-fix — when the user opens with "shall we continue", "next on the list", "what's next", references docs/Status.md or docs/Handover.md, or names a work item to pick up. Rebuilds context from the written state and verifies the machine is clean before anything is started.
---

# Opening a session

Context comes from the written state, not from memory and not from reading source. This is
most-exchange's procedure, where it is why sessions resumed cleanly.

## 1. Read the state, in this order

1. **`docs/Status.md`** — where things stand, the open decisions, what to do next. Read all of it.
2. **`CLAUDE.md`** — the rules. Already in context; consult the section for the area being touched.
3. **Only if the work needs it:** `docs/Design.md` for the normative clause; `docs/Rationale.md` for
   why a rule exists; `docs/Handover.md` for a past change. And **`../most-exchange/docs/Adapters.md`**
   for anything touching order entry — it is the contract this repo implements.

## 2. Verify the machine before starting

```sh
pgrep -f "nl.lamia.most.exchange" || echo "clean"     # adapter and exchange processes alike
git status --short && git log --oneline -3
git -C ../most-exchange status --short && git -C ../most-exchange log --oneline -1
```

- **`../most-exchange` is part of this build** (composite). Uncommitted work there changes what
  builds here; say so rather than absorbing it.
- Uncommitted work from a previous session is a question for the user.
- Stop any surviving process **before** deleting an Aeron or Artio directory, never after.

## 3. State the scope back

Before writing anything, say in one or two sentences what this session will do and which item of
`docs/Status.md` §3 it closes. If it is not on that list, say that too.

If the work needs a decision that changes a FIX-facing behaviour, a persistence guarantee, a failure
mode or where state lives, stop and use `decision-fork`.
