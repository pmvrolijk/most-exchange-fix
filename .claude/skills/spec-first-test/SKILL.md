---
name: spec-first-test
description: Use when writing or changing a test for behaviour docs/Design.md, the FIX specification or most-exchange's docs/Adapters.md specifies, and always when writing a test for code that already exists. A test written after the implementation encodes the implementation, including its bugs.
---

# Writing the test from the specification

most-exchange's `cumQty` defect reached working code because its test was named for what the code
did ("reports the filled portion") and asserted the buggy value.

## The procedure

1. **Find the clause first** — `docs/Design.md` here, `../most-exchange/docs/Adapters.md` or its
   Design.md §5 for exchange behaviour, the FIX specification for FIX behaviour. Cite it on the test.
2. **Write the expected value from the clause**, by hand, before running the code or reading it.
3. **If the code disagrees, the code is the suspect** until the clause is shown wrong. Say which you
   concluded is at fault and why; never silently change the expectation.
4. **No clause covering it? That is the finding.** Write the clause into Design.md, get it confirmed
   if it is a real decision, then test against it.

## Fakes

When a test needs a stand-in for the exchange, use or extend a **model written from the
specification**, never one written from the adapter — most-exchange's `ExchangeModel` is the
pattern. Model the awkward parts: back-pressure, a gateway that refuses, a gateway that dies
without a word, a report that never arrives. For FIX, prefer a real Artio initiator over a fake
session.

## Prove the test can fail

Break the behaviour on purpose (a mutation) and watch the test fail before trusting it. Record the
mutations that were tried.

## Naming

Name the test for the specified behaviour: "an ambiguous order is reported to the client as unknown,
never as rejected", not "handles AMBIGUOUS".
