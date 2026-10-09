---
name: perf-claim
description: Use before stating any latency, throughput or allocation figure for most-exchange-fix — in chat, a commit message, a doc or a report — and when running the FIX sender or any load test. Enforces both-latencies reporting and records provenance so two numbers can be compared later.
---

# Claiming a number

A figure without its conditions cannot be compared with the next one.

## Read both latencies. Always.

**Service time** from the actual send and **response time** from the scheduled send. Quoting only
service time is coordinated omission. Also quote, when not zero: **pacing lateness** (the generator
was the bottleneck), rejects, and orders settled never-sequenced or ambiguous.

## State the whole path and its configuration

An adapter figure includes the exchange behind it. Say: JVM or native; the exchange's gateway
placement, egress channel and driver threading (most-exchange CLAUDE.md, "Measurement"); single
node or cluster; how many securities; FIX message mix.

## Record the provenance

Every figure that reaches a document appends a row to `docs/Measurements.md`. A figure from
another system — the FIX↔Kafka gateway's ~2,400 orders/s — is a reference point, never a
comparison, unless both were run on the same machine the same day.

## Run a discard pass first

A cold JVM stalls the generator, and the first rate wears a cost that is not a property of the rate.

## Improvements are diffs

Run the same load either side of a change and compare histograms, not a new number against a
remembered one.
