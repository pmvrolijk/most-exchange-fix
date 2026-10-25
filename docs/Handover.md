# Handover

The archive: one work record per piece of work, in order. `Status.md` is the entry point; this is
where the narrative lives.

---

## 1. How to read this

Each `§2x` record says what the problem was, what was decided, what was built, and what the check is.
Records are not edited after the fact. A reversal is a new record that names the one it reverses.

## 2. Work records

### 2a. The repository

2026-10-04, from a most-exchange session (most-exchange Handover §2p, where the SDK was built).

**Decided** (with the user, 2026-10-03/04):
- the FIX adapter is its own repository, beside `most-exchange`;
- it is Kotlin, in packages `nl.lamia.most.exchange.fixadapter.*`;
- it is built on most-exchange's `client` SDK;
- Artio is the FIX engine;
- one FIX session layer serves an order entry backend and a market data backend;
- it works the way most-exchange works: the same documents, rules-only CLAUDE.md and skills.

**Built:**
- the Gradle wrapper (9.7.1, copied from most-exchange);
- a composite build on `../most-exchange`;
- the modules `fix-session`, `order-entry` and `market-data`;
- Artio 0.182;
- warnings as errors;
- the docs and the six skills.

**Found on the way:**
- **Artio and the exchange's Aeron must be pinned together** (Rationale §2). Maven Central's
  metadata maps Artio 0.182 to Aeron 1.53.0. The FIX↔Kafka gateway's 0.177 is on 1.51.0, and the
  latest Artio, 0.185, is on 1.53.3. `DependencyAlignmentTest` was checked by bumping Artio to 0.185,
  which made it fail.

**The check:** `./gradlew build` passes 4 tests. The composite build substitutes most-exchange's
`client` and `sbe`, and every Aeron artifact resolves to 1.53.0, with Agrona 2.6.0.

### 2b. Licence, FIX 4.4, one session per participant, and the SDK from the registry

2026-10-04.

**Decided** (with the user):
- AGPL-3.0-or-later, like the exchange;
- FIX 4.4;
- **one FIX session, one participant**, each session configured with its CompIDs, username/password
  and `participantId` (Design.md §2);

The most- projects are expected to move to public GitHub later, so the registry is one property
(`mostSdk.url`).

**Built:**
- `LICENSE.md`, identical to the exchange's.
- `settings.gradle.kts` resolves `nl.lamia.most.exchange:*` only from the registry, through an
  `exclusiveContent` filter. Credentials are the job token in CI and `gitlabToken` locally.

**Design.md §8.9:** the stored `reportSeq` must advance
only behind Artio's durable persistence of the 35=8. That is probably an `OrderEntrySession` change
in most-exchange.


### 2c. The design: order entry, persistence, delivery, control, tooling

2026-10-09. One design session, with the user. **Documents only. Nothing was built, run or
measured.**

**The problem.** Design.md was a template. The user set the goals:
- low latency, stateful, one FIX engine with two backends;
- the minimum that supports every exchange feature, with client fields such as Account later;
- the SDK from most-exchange's public GitHub;
- REST control and metrics, with internal latencies exposed;
- a pacing sender and an HTML latency report;
- **guaranteed delivery of execution reports**: a `reportSeq` is consumed only once its 35=8 is
  saved.

**Read first.** These facts shaped the design:
- the SBE schema: limit orders only; `expireDate` 0 means GTC; `smpId` 0 means the participant; no
  timestamp on `ClientExecutionReport`;
- `OrderEntrySession`: `SessionStore.save` is called as the ledger moves; `onReport` returns
  nothing; the in-flight set lives only in memory; at startup the resend runs before the mass
  status;
- most-exchange Design §5: `reportSeq` is replicated, snapshotted and never reset;
- the Artio 0.182 jar: `FixLibrary.sessionWriter` exists. Its behaviour is not verified.

**Decided** (with the user; each was the recommendation unless noted):
- **The order store:** RocksDB plus in-memory maps, WAL not fsynced, point-in-time recovery
  (Design §6.1).
- **The exchange `clOrdId`:** `(sequenceIndex << 32) | MsgSeqNum` of the 35=D/F, guarded against
  Artio's state being wiped (§3.2, R§5).
- **Delivery:** the adapter's `SessionStore` treats the SDK's `save()` as a ceiling, and advances
  only behind the archive's recorded position, fsynced at `fileSyncLevel=1` (§6.2, §6.4, R§4).
  **This reverses §2b's expectation** that the lag needed an `OrderEntrySession` change. It does
  not.
- **SDK changes that are still needed:**
  - `onReport` may abort, so a back-pressured 35=8 is not consumed;
  - `restoreInFlight`, so requests unanswered at a restart are settled by the SDK's rules.
- **A logged-out session's reports** go through Artio's `SessionWriter` (§6.3).
- **35=G is rejected.** Claude took this from the "minimum" principle, and the user did not object.
- **`AMBIGUOUS`** restates the last status with `OrdStatusUnknown=Y`, never a terminal state
  (§3.4, R§6). CLAUDE.md non-negotiable 1 was extended to say so.
- **The trading date is the adapter's own calendar. This was against the recommendation,** which
  was for the engine to resolve a day sentinel. The silent failure (an adapter ahead of the engine
  makes day orders two-day orders) is accepted. It is mitigated by `POST /trading-date`, and GTD is
  kept off the calendar (§3.6, R§7).
- **Accepted as proposed:**
  - the 35=D/F/8 mapping, with ExecID = `reportSeq`;
  - `NEVER_SEQUENCED` → reject;
  - early cancels parked on the row;
  - threading and session roles;
  - the market data model;
  - control and metrics;
  - the test tooling (§3–§5, §9, §10).

**Found in review** (Claude's own first draft was wrong or short):
- An acknowledged order that finished in a truncated window was never declared. Now: open in the
  store, absent from a completed mass status, and its last `reportSeq` below `nextSeq`. A first
  version compared arrival order, which a gateway switch breaks.
- The unsynced-WAL argument silently depended on RocksDB recovering a prefix. It is now a stated
  requirement, with a test owed.
- Cancel fates were missing, and a parked cancel was not persisted.

**Written:**
- `docs/Design.md`, §1–§10 rewritten;
- `docs/Rationale.md`: §3 superseded in part (GitHub), §4–§7 new;
- `CLAUDE.md`: non-negotiable 1 extended, 7 and 8 new;
- `docs/Status.md`.

**The check:** none possible yet. The design's load-bearing assumptions about Artio and Aeron
Archive are listed as spikes (Design §8.13), and are unverified.

### 2d. The order store (`order-entry`, slice 1), 2026-10-25

**The problem.** Every other part of `order-entry` writes to the store: the inbound path, reports,
fates, delivery, 35=H/AF. Design §6.1 described it, but going through it before writing code
turned up three places where it was underspecified or would defeat itself.

**Decided with the user** (each the recommendation, Design §3.2, §6.1):
- **Keyed by `participantId`, not Artio's session id.** A wipe of Artio's state, which the
  `sequenceIndex` guard exists to catch, can also change the session id. A guard keyed by it then
  finds nothing stored, and lets the adapter start.
- **A cancel has its own row.** A sent cancel needs its own ClOrdID for 11 on its 35=8/35=9, and
  after a restart it goes back to the SDK with `restoreInFlight` like an order. Parked is now a
  state of the cancel row.
- **An ambiguous order is retained like an open one.**

**Found without needing a decision:**
- **The `sequenceIndex` guard misses a wipe at index 0,** the normal case for a session never
  reset. The store reports an *id collision* on insert and writes nothing. What the client sees is
  open (Design §8.15), for slice 2.
- **A settled request is not restored in flight.** Handing back an order whose `AMBIGUOUS` 35=8
  is owed could get it settled `NEVER_SEQUENCED` by a startup resend that completes.
- **Retention also waits for the reports:** a row is dropped only once its last `reportSeq` is
  below the participant's durable first-missing, so a resend cannot bring a report for a dropped
  row.

**Built:** `nl.lamia.most.exchange.fixadapter.orderentry.store`:
- `OrderStore`: RocksDB with `orders` and `meta` in one database (one WAL), WAL unsynced,
  `PointInTimeRecovery`, direct-buffer puts;
- the in-memory maps per participant, by `clOrdId`, and by ClOrdID hash with a byte-compared chain;
- `RequestRow`, pooled; `RowCodec`, version 1 with explicit codes; `ClOrdIdHasher` (FNV-1a plus
  Murmur's finaliser).

rocksdbjni 11.8.1.

**The check.** `OrderStoreTest`, 16 tests, written from the clauses first. All passed on the first
run, so 13 mutations followed. One was not caught at first: `SkipAnyCorruptedRecords` passed the
torn-WAL test. RocksDB's log reader drops the rest of a 32 KiB block after a bad checksum in every
mode, so the third row, written in the same block, was lost either way. The test now writes 100 KB
between the torn record and the last one, and catches both weaker modes tried
(`SkipAnyCorruptedRecords`, `TolerateCorruptedTailRecords`). The allocation
test measured 0 bytes over 10,000 lookups and updates (`ThreadMXBean`, after a 20,000-cycle warm-up)
on this laptop. That is a count of bytes, not a latency.
