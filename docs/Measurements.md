# Measurements

Every figure that reaches a document has a row here, with what makes two numbers comparable: the
machine, its state, the build, the load and the rate (`perf-claim` skill). `e2e/run-perf.sh` writes a draft row per run.

Quote **both** latencies:
- **service time**, measured from the actual send;
- **response time**, measured from the scheduled send.

Quoting only the first hides the queueing at exactly the rates worth measuring.

| id | date | commit | machine | idle? | JVM/native | exchange config (placement, egress, driver threading) | offered rate | achieved | service p50/p99 | response p50/p99 | notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A1a | 2026-10-31 | `a70ed65`+ | Apple M4 Pro, macOS, 14 (10P+4E) | no (load avg ~10) | JVM 21.0.11 | e2e topology on one machine (1 cluster member, 1 gateway, UDP over localhost); adapter fileSyncLevel=1, idle backoff, SHARED driver and archive; market data running, no subscriber | 1000/s | 1000/s | 15.30 / 24.19 ms | 15.30 / 24.19 ms | `run-perf.sh`, `fix perf` mix 60/20/20, maker CLIENT1 + taker CLIENT2, 18,000 measured; new ack. Pacing lateness p99 18 µs, all the sender's; 0 refusals, 0 unanswered |
| A1b | 2026-10-31 | `a70ed65`+ | as A1a | no (load avg ~10) | JVM 21.0.11 | as A1a | 5000/s | 5000/s sent; **3,530 requests/s answered** | 10.9 / 11.6 s | 10.9 / 11.6 s | as A1a, 90,000 measured. **Saturated**: the delay grows through the run |
| A1c | 2026-10-31 | `a70ed65`+ | as A1a | no (load avg ~10) | JVM 21.0.11 | as A1a | 10000/s | 10000/s sent; **5,400 requests/s answered** | 16.5 / 22.4 s | 16.5 / 22.4 s | as A1a, 180,000 measured. Saturated; one 2.25 s gap in every stream |
| A2a | 2026-10-31 | `a70ed65`+ | as A1a | no (load avg ~10) | JVM 21.0.11 | as A1a but adapter **fileSyncLevel=0** (a diff only: CLAUDE.md 7 and 11 do not hold) | 1000/s | 1000/s | 1.73 / 5.26 ms | 1.73 / 5.27 ms | as A1a |
| A2b | 2026-10-31 | `a70ed65`+ | as A1a | no (load avg ~10) | JVM 21.0.11 | as A2a | 5000/s | 5000/s; keeps up | 364 µs / 3.60 ms | 365 µs / 3.64 ms | as A1b |
| X1a | 2026-10-31 | most-exchange `b5d457a` | as A1a | no (load avg ~10) | JVM 21.0.11 | as A1a, the same processes; **no adapter**: most-exchange's `most load` straight to the gateway, participants 20–21 | 1000/s | 1000/s | 1.09 / 3.37 ms | 1.29 / 3.68 ms | `run-perf.sh` direct leg, 18,000 measured; ack. Its pacing p99 436 µs |
| X1b | 2026-10-31 | most-exchange `b5d457a` | as A1a | no (load avg ~10) | JVM 21.0.11 | as X1a | 5000/s | 5000/s | 219 / 575 µs | 227 / 590 µs | as X1a, 90,000 measured |
| X1c | 2026-10-31 | most-exchange `b5d457a` | as A1a | no (load avg ~10) | JVM 21.0.11 | as X1a | 10000/s | 10000/s | 158 µs / 1.17 ms | 158 µs / 1.25 ms | as X1a, 180,000 measured |

### What A1–A2 and X1 say (2026-10-31)

All three sets ran the same day on the same processes, so they compare. **The adapter is the
bottleneck, and within it the archive's fsync.** Straight to the gateway (X1), the exchange in this
topology keeps up at 10,000 orders/s with a p50 under 0.2 ms. Through the adapter at fileSyncLevel 1
(A1), the p50 at 1,000/s is 15 ms, and the adapter saturates between 3,500 and 5,400 requests/s.
At fileSyncLevel 0 (A2), and nothing else changed, the p50 at 1,000/s falls to 1.7 ms, and 5,000/s
keeps up at 0.36 ms.

- The sender is not in it. Its lateness p99 is under 25 µs at every rate, all owed to the sender
  (the system share is 0), with no refusals. The client never saw back-pressure, even saturated: the
  queue builds inside the adapter's engine, behind the archive, not in TCP.
- At 1,000/s, A1's acks arrive in bursts: gap p50 5 µs, p99 12 ms. That looks like one fsync releasing
  a batch, every ~12–15 ms. This is macOS APFS on a laptop, and Java's `force` here is `fsync`, not
  `F_FULLFSYNC`; a Linux host with an NVMe drive will differ. The figure is this machine's.
- A2 is not a configuration to run: below fileSyncLevel 1 a host crash can lose a 35=8 the client has,
  or send a 35=D twice. It bounds what the fsync costs here. What fills the rest of the gap to X1
  (0.2 ms at p50, 3 ms at p99 at 5,000/s) needs the adapter's trace ring (Design §9.2), which is not
  built.
- The 2.25 s gap in every stream at 10,000/s (A1c) is not explained.
