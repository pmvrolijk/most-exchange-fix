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
| A3a | 2026-10-31 | `0cd88a2`+ | as A1a | **yes** (IntelliJ and Firefox closed; load avg 2.7–6.9, the exchange's own spinning) | JVM 21.0.11 | as A1a; market data running, no subscriber | 1000/s | 1000/s | 15.11 / 20.56 ms | 15.11 / 20.56 ms | `PERF_MD=1 run-perf.sh`, the pair's first half; 18,000 measured. Sender late p99 3 µs |
| A3b | 2026-10-31 | `0cd88a2`+ | as A1a | yes, as A3a | JVM 21.0.11 | as A3a, and **4 market-data sessions** (`fix md --quiet`, FEED1–4) subscribed to the run's whole book, depth 0: 24,072 35=X each, ~1,000/s each | 1000/s | 1000/s | 17.14 / 26.03 ms | 17.14 / 26.03 ms | straight after A3a, on a security of its own. p99.9 40.6 ms against A3a's 23.5 |
| A3c | 2026-10-31 | `0cd88a2`+ | as A1a | yes, as A3a | JVM 21.0.11 | as A3a | 3000/s | 3000/s; keeps up | 17.71 / 24.31 ms | 17.71 / 24.32 ms | 54,000 measured |
| A3d | 2026-10-31 | `0cd88a2`+ | as A1a | yes, as A3a | JVM 21.0.11 | as A3b: 72,042 35=X each | 3000/s | 3000/s; keeps up | 20.08 / 32.06 ms | 20.08 / 32.06 ms | straight after A3c |
| A3e | 2026-10-31 | `0cd88a2`+ | as A1a | yes, load avg 4.6–7.6 | JVM 21.0.11 | as A3a | 5000/s | 5000/s sent; **4,255 requests/s answered** | saturated, 6.7 / 9.4 s | saturated | supersedes A1b's 3,530/s, taken at load avg ~10 |
| A3f | 2026-10-31 | `0cd88a2`+ | as A1a | yes, as A3e | JVM 21.0.11 | as A3b: 117,815 35=X each | 5000/s | 5000/s sent; **3,922 requests/s answered** | saturated, 6.5 / 8.2 s | saturated | straight after A3e: market data takes ~8% of the capacity |
| X2a | 2026-10-31 | most-exchange `b5d457a` | as A1a | yes, as A3a | JVM 21.0.11 | as X1a | 1000/s | 1000/s | 1.26 / 2.63 ms | 1.34 / 2.71 ms | the same run as A3a–A3d |
| X2b | 2026-10-31 | most-exchange `b5d457a` | as A1a | yes, as A3a | JVM 21.0.11 | as X1a | 3000/s | 3000/s | 458 / 843 µs | 466 / 852 µs | as X2a |
| A4a | 2026-10-31 | `1f456e0`+ | as A1a | yes (load avg 2.5–5.3) | JVM 21.0.11 | as A3a, but **market data on its own engine**: port, media driver and archive at fileSyncLevel 0 (Design §4) | 1000/s | 1000/s | 14.63 / 20.98 ms | 14.63 / 20.98 ms | the pair's first half |
| A4b | 2026-10-31 | `1f456e0`+ | as A1a | yes, as A4a | JVM 21.0.11 | as A4a, and 4 feed sessions as A3b: 24,066 35=X each | 1000/s | 1000/s | 15.08 / 20.62 ms | 15.09 / 20.62 ms | straight after A4a |
| A4c | 2026-10-31 | `1f456e0`+ | as A1a | yes, as A4a | JVM 21.0.11 | as A4a | 3000/s | 3000/s; keeps up | 17.19 / 23.94 ms | 17.19 / 23.95 ms | |
| A4d | 2026-10-31 | `1f456e0`+ | as A1a | yes, as A4a | JVM 21.0.11 | as A4b: 72,033 35=X each | 3000/s | 3000/s; keeps up | 18.04 / 26.57 ms | 18.04 / 26.57 ms | straight after A4c |
| A4e | 2026-10-31 | `1f456e0`+ | as A1a | load avg 6–8.7 | JVM 21.0.11 | as A4a | 5000/s | 5000/s sent; **4,215 requests/s answered** | saturated | saturated | |
| A4f | 2026-10-31 | `1f456e0`+ | as A1a | load avg 6–8.7 | JVM 21.0.11 | as A4b: 114,683 35=X each | 5000/s | 5000/s sent; **3,887 requests/s answered** | saturated | saturated | straight after A4e |
| A5a | 2026-10-31 | `1f456e0`+ | as A1a | yes (load avg 1.5–5.9) | JVM 21.0.11 | as A4a, the adapter's **trace ring on** (Design §9.3) | 1000/s | 1000/s | 14.72 / 20.72 ms | 14.72 / 20.72 ms | 18,000 joined to the trace. p50 by stage: client → engine 0.18 ms, engine → handler 0.76, **archive gate 7.62**, send wait 0.0001, **exchange 7.85**, report out 0.006, adapter → client 0.045; durable 8.1 ms after the 35=8 |
| A5b | 2026-10-31 | `1f456e0`+ | as A1a | yes, as A5a | JVM 21.0.11 | as A5a | 3000/s | 3000/s; keeps up | 17.20 / 23.92 ms | 17.20 / 23.92 ms | 54,000 joined. p50: engine → handler 1.58 ms, archive gate 8.00, exchange 8.02 |
| A5c | 2026-10-31 | `1f456e0`+ | as A1a | yes, as A5a | JVM 21.0.11 | as A5a | 5000/s | saturated | 7.56 / 10.26 s | 7.56 / 10.26 s | 90,000 joined. **engine → handler 7.55 s p50**: the queue is between Artio's engine and the order-entry handler; archive gate 0.15 ms, exchange 8.8 ms |
| A5d | 2026-10-31 | `1f456e0`+ | as A1a | yes | JVM 21.0.11 | as A5a, the adapter's driver and archive **DEDICATED** | 1000/s | 1000/s | 15.24 / 20.67 ms | 15.24 / 20.67 ms | 10 s run, a diagnostic: archive gate 7.60 ms, **exchange 7.90 ms**, as SHARED |
| A5e | 2026-10-31 | `1f456e0`+ | as A1a | yes | JVM 21.0.11 | as A5a, adapter **fileSyncLevel=0** (a diff only) | 1000/s | 1000/s | 2.22 / 6.36 ms | 2.22 / 6.36 ms | 10 s run, a diagnostic: archive gate 0.024 ms, **exchange 1.68 ms**; trace taken before the clock fix, engine → handler not read |

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

### What A3 says (2026-10-31): market data on order entry's engine (Design §8.22)

Each pair ran back to back on the same processes, on a quiet machine. **Market data on the same
engine costs order entry**: four feed sessions taking ~4,000 35=X/s, about twice the order path's own
traffic through the archive, add ~2–2.5 ms at p50 and 5–8 ms at p99 (A3b against A3a, A3d against
A3c), and take ~8% of the saturated capacity (A3f against A3e). The feeds kept up: every 35=X arrived.

- **Three earlier attempts are not recorded.** In the first, the four feed clients printed every
  message; in the second they spun a core each while idle; in the third IntelliJ took ~4 cores. Each
  showed the same direction, larger. `fix md --quiet` and an idle client that backs off came of them.
- **A1's saturation was the loaded machine's**: 3,000/s keeps up when quiet (A3c), and 5,000/s
  answers 4,255 requests/s (A3e), not 3,530.
- Artio archives an engine's messages all or none (`logInboundMessages`/`logOutboundMessages`), so
  market data cannot be left out of order entry's archive while they share an engine.

### What A4 says (2026-10-31): market data on its own engine

The A3 pairs again, with market data on its own engine (Design §4), the same machine, quiet, the same
hour. **Below saturation the coupling is mostly gone**: the four feed sessions' cost to order entry
falls from +2.0 ms p50 and +5.5 ms p99 to +0.45 ms and none at 1,000/s (A4b against A4a), and from
+2.4 and +7.7 ms to +0.85 and +2.6 ms at 3,000/s (A4d against A4c). **At saturation it is not**:
4,215 against 3,887 requests/s, the same ~8% as A3 (A4f against A4e). So that share was never the
shared archive. On this laptop it is the machine: market data's engine threads and four feed JVMs
beside the exchange's spinning processes (load average to 8.7, the sender's own lateness doubled), or
its archive's writes on the disk order entry fsyncs. These runs do not separate the two.

### What A5 says (2026-10-31): the adapter's own stages

The trace ring (Design §9.3) joined to the sender's log, every request found, nothing dropped.

- **Below saturation, two waits of ~8 ms each make ~90% of the time**: the archive gate (a 35=D
  waits for the fsynced archive before `newOrder`, §6.1) and the exchange stage (`SENT` → the first
  report). Everything the adapter does itself is well under a millisecond at p50.
- **The exchange stage moves with the adapter's fsync, not its threads**: 7.9 ms with the driver and
  archive on dedicated threads (A5d), 1.7 ms at fileSyncLevel 0 (A5e), ~1.1 ms for `most load`
  straight to the gateway (X1a, X2a). The timeline shows requests leaving in bursts when the gate
  opens and their reports coming back as a burst ~8 ms later. Not explained: most-exchange's cluster
  archive does not fsync (Aeron's default level 0), so "its writes queue behind ours" is not shown.
  A RAM disk for one side, or the exchange on another host, would separate it.
- **At saturation the queue is between Artio's engine and the order-entry handler** (A5c): the
  library thread takes messages in more slowly than they arrive, and nothing pushes back on the
  client. At fileSyncLevel 0 the same rate keeps up (A2b), so the fsync slows the library thread's
  intake too, by a path not yet found.
- **engine → handler, 0.76 ms at 1,000/s**, is the e2e's back-off idle strategy as much as anything;
  the adapter's default is busy-spin (§7).

