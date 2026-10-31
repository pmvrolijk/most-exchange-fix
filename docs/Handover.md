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

### 2e. Order entry's inbound logic (slice 2a), 2026-10-25

**The problem.** Slice 2 turns a FIX 35=D, 35=F or 35=G into a request on its way to the exchange,
or an answer given here. Design §3.3 and §5.1 left four things open:
- what a client sees on an id collision (§8.15);
- what it sees when the SDK cannot send;
- what a second cancel gets;
- the codes for the §5.1 refusals.

**Decided with the user** (each the recommendation, Design §3.2, §3.3):
- **An id collision** is rejected (35=8 OrdRejReason 99, or 35=9 CxlRejReason 99, "session sequence
  reset required"), then the session is logged out. Its Logons are refused until an operator
  resets its sequence. Also refused: a start with a configured session Artio does not know while
  the store holds rows for its participant.
- **`NOT_READY` and `NO_GATEWAY` reject**, "exchange unavailable". Holding them with `ABORT` would
  stall the library's poll for every order-entry session, heartbeats included, for as long as the
  exchange is unreachable.
- **A second cancel while one is pending** gets CxlRejReason 3.

**Taken without asking**, written into Design §5.1 for review:
- the refusal codes: 1 for the security, 13 for the quantity, 99 with a fixed Text otherwise;
- `UNKNOWN_PARTICIPANT` is answered "participant not served";
- a cancel whose OrderID, Side or security does not match its order gets CxlRejReason 1.

**From the contract** (most-exchange Adapters.md §2): the engine does not check `clOrdId`. So the
same request delivered again is forwarded only when this process's own send of it did not go out
(`RequestRow.sendPending`, in memory only). A replay after a restart, and a PossDup resend, are
dropped.

**Built:** `nl.lamia.most.exchange.fixadapter.orderentry.inbound`:
- `InboundOrders`;
- `FixParse` (a positive `int64` and a LocalMktDate, read in place);
- the ports `OrderVenue`, `SecurityLookup`, `ClientReplies`, and `Texts`.

The store gained `sendPending` and `pendingCancel`. The second, an order's link to its pending
cancel, is rebuilt at load.

**The check.** `InboundOrdersTest`, 32 tests, against a model of the SDK's `SendResult`s, a
directory model, and a reply recorder that can refuse as a back-pressured Artio does. Two failed on
the first run:
- a tag outside the dictionary passed `validate()`. Order-entry's test JVM lacked Artio's
  `reject_unknown_field` switch; the test task now sets it;
- the allocation test measured 72 bytes per order. That was the test's own directory model, which
  builds a `String` per lookup. With a model that allocates nothing, the JVM still allocated in
  rare bursts (640 bytes once in 10,000 orders), so the test now asserts that one window of 2,000
  orders in five allocates 0 bytes. An allocation per order would show in every window.

22 mutations, 21 caught. The one not caught (dropping `!order.acknowledged` from the OrderID
match) is equivalent: an order not acknowledged has `exchangeOrderId` 0, which no parsed OrderID
equals.

**Not real yet:** nothing here has met Artio's `Session` or the SDK's `OrderEntrySession`. That is
2b, with a real initiator. 35=H and 35=AF answer 35=j until slice 5.

### 2f. Order entry wired to Artio and the SDK (slice 2b), 2026-10-25

**The problem.** Slice 2a's inbound path ran against models only. 2b puts it between a real Artio
session and the SDK's `OrderEntrySession`: replies on Artio's `Session`, the library's handler,
one SDK session per shard, the directory, the node file's keys, and the two startup guards.

**Decided with the user** (each the recommendation; Design §3.3, §3.5, Rationale §9):
- **The SDK runs on the adapter's embedded media driver**, §8.6 closed;
- **a shard's gateways are the directory's**, unless the node file lists them, as a co-located
  shard needs. An endpoint that moves later is logged, not followed;
- **the collision's block** is stored as the `sequenceIndex` it was found at. The engine accepts
  the Logon, and the library logs it out with the Text. Any reset that raises the index lifts it,
  the client's 141=Y included.

**Taken without asking**, written into Design §3.3 for review:
- a local reject's OrderID is `NONE`;
- a 35=9 with CxlRejReason 1 says OrdStatus 8 and OrderID `NONE`, FIX 4.4's rule;
- every other 35=9 states the order's last status, `A` before any, never 8 for an order that may
  be open;
- before the first complete directory, orders and cancels get "exchange unavailable";
- the acceptor binds only after the startup guards.

**Built:** in `...orderentry`:
- `OrderEntryBackend`, `OrderEntryConfig` and `StartupGuards`;
- `artio.ArtioReplies` and `StatusUnknownText`;
- `exchange.DirectoryIndex` (with `BytesTable`) and `exchange.ShardVenue`.

The store gained `block`, `isBlocked`, `liftBlock` and `holdsState`. `FixAcceptor` gained
`bindAtStartup` and `bind()`, and `FixNodeConfig` gained `loadProperties`. fix-session's
`AcceptorRig` and `Initiator` moved to its `testFixtures`.

**The check.** End-to-end tests in `OrderEntryBackendTest`: a real Artio initiator, the acceptor,
the library, the SDK's session over Aeron IPC on the adapter's driver, and a gateway model that
answers the opening mass status and records what it is sent. A directory publisher repeats its
broadcast on a cycle, as discovery does. Unit tests cover the index, the config, the store's block
and the inbound path's new rows. What failed on the way:
- every test took ~10 s: an initiator closed while logged on waits that long. The tests now log out
  first;
- the collision test found the first life's Logout. The client's session id is the same at every
  logon, so its messages accumulate; the waits now look past the last MsgSeqNum seen;
- the test asserted the client's `sequenceIndex`, which counts the initiator's own resets (4), not
  the acceptor's (1). The adapter's is what the ids carry.

Read in Artio's source before writing: the handler gets session messages, and messages already
answered are flagged invalid. Encoders wrap the bytes they are given. A raw `trySend` advances
MsgSeqNum. `FixEngine.allSessions()` lists offline sessions with their `sequenceIndex` as the
engine starts.

16 end-to-end tests, 85 in order-entry in all, `./gradlew build` green. 23 mutations, each caught.
Two needed work first. Raising the `sequenceIndex` at the claim was masked, because the first
message raises it too; the test now waits for it before the client sends. And one mutation that
did not compile had been counted as caught. It was rewritten, then caught.

**Not real yet:** there is no `main`. Reports go to a listener that slice 3 writes, and the
`SessionStore` is the SDK's in-memory one until slice 4. The SDK allocates on every directory
cycle and for every order (Design §8.16).

### 2g. Order entry's outbound path (slice 3), 2026-10-25

**The problem.** The exchange's reports, the SDK's fates and the completion of a mass status must
reach the client as 35=8 and 35=9, and the store, without ever presenting an ambiguous order as
rejected (CLAUDE.md non-negotiable 1). Before writing, the engine's own rules were read in
most-exchange's `MatchingEngineService`. A participant's cancel is answered with the **cancel's**
`clOrdId`, an unsolicited cancel (self-match prevention, a halt, a bulk cancel) with the
**order's**. TRADE's `price` is the fill price, and ORDER_STATUS carries `reportSeq` 0.

**Decided with the user** (each the recommendation; Design §5.3, §8.17; Rationale §10):
- **A report for a row the store lost** is logged, counted and consumed until recovery from the
  archive is built, its own slice after delivery;
- **fills a mass status reveals** are an ExecType I with the new CumQty, AvgPx over the fills known,
  and a Text saying what is missing.

**Taken without asking**, written into Design §5.3 for review:
- OrdRejReason and CxlRejReason: UNKNOWN_SECURITY or UNKNOWN_ORDER → 1, MARKET_CLOSED → 2, the rest
  99, Text the reason's name;
- a cancel rejected UNKNOWN_ORDER for an order the store already holds terminal is CxlRejReason 0,
  too late;
- 55 is the directory's Symbol, with 48 and 22=8;
- AvgPx is rounded half up at 8 decimals;
- a resent report is sent again and not applied twice.

**Built:** `...orderentry.outbound`: `OutboundReports`, `Notional`, `ClientReports` with
`OutgoingExecution` and `OutgoingCancelReject`. `artio.ArtioReports`. In the backend: a listener
per shard, `restoreInFlight` at a shard session's creation, and participants online from acquire
to disconnect. `DirectoryIndex.symbolOf` and `ShardVenue.shardOf`. The row gained `statedAt`, in
memory. The gateway model in the tests now sends reports, numbered per participant, and its resend
and mass status answers state that numbering's `nextSeq`.

**The check.** 25 tests of the outbound path against reports encoded as the gateway sends them, a
recording client that can be offline or refuse, and a venue model for parked cancels. 5 for the
arithmetic, against `BigInteger`. 5 new end-to-end tests through a real initiator. One failure on
the first run, in the code: Agrona's `Long2LongHashMap` refuses to store its own missing value, and
0 had been both. The end-to-end test of a held report was then made to check the 35=8 is no PossDup
resend: sent on the session a logout took, it would still have reached the client at the next
logon, as Artio's resend, which is the path §6.3 rules out.

`./gradlew build` green, 121 tests in order-entry. 25 mutations, 24 caught; the other is equivalent
(a disconnected `Session` is not active, so it is not written to with or without the disconnect
hook). Two needed a test fixed first. The shard filter of the mass status rule went unseen, because
the other shard's order was also excluded by the `nextSeq` rule. And the backend's per-shard
listener had no test, since no end-to-end test ran a mass status after startup. The gateway model
now states its open orders and can lose a report and truncate a resend, so one test drives a gap on
shard 1 through the SDK's fence, truncation and mass status.

**Not real yet:** a client logged out holds its gateway's report stream until slice 4. A host
crash's lost rows stay lost (§8.17).

### 2h. Durable delivery (slice 4), 2026-10-25

**The problem.** Design §6.2–§6.3: a `reportSeq` consumed only once the archive has its 35=8, and
reports for a client not logged on written to its offline session instead of holding the gateway's
stream. Until now the SDK's in-memory `SessionStore` stood in, and a logged-out client's reports
were refused with `ABORT`.

**Decided with the user** (the recommendation; Design §6.2, Rationale §11): an answer with no
`reportSeq` (a fate, a restatement, a parked cancel's answer, a gateway's own reject) writes its row
only once the archive has its 35=8 or 35=9, so a host crash can make the client get it twice, never
not at all.

**Found before building:**
- **`reportSeq` is per shard.** Each shard's engine numbers a participant's reports on its own, and
  the store had one first-missing per participant, which every shard's SDK session would have
  shared. The store's first-missing is now keyed by (participant, shard), and each row records its
  shard, so retention compares it with its own shard's (the directory is not there at load). The
  row layout of version 1 changed rather than adding a version 2: no store exists outside tests.
- **The request after a disconnect must replay** (Artio 0.182 `Framer.onRequestFixSession`). A
  client back before the request arrives is handed over online with the request's replay, so
  `NO_MESSAGE_REPLAY` would skip its first 35=D. It replays from the last received + 1.

**Found by a flaky test, while building:** a report sent just after the client's Logout was lost
in 2 runs of 5, consumed and archived, its MsgSeqNum reused by the next Logon. The library still
held the session after the Logout and wrote on it; and the engine, handing the session back offline,
read its last MsgSeqNum from an index that waits only for the engine's own stream. Now a session
that stops being active is not written to, and an offline session handed back below the library's
own last MsgSeqNum is released and requested again. The second fired once in six runs, on the
library's Logout reply. What neither can see, a client back within the indexer's lag, is counted
(Design §8.19).

**Built:**
- `delivery.ReportDelivery`: the lagging `SessionStore`, one view per shard, positions in a ring
  per (shard, participant) with a rising ring of indices for the lowest pending, rows waiting for
  the archive in a ring of their own. A library reconnect leaves a floor until restart.
- `artio.LibraryRecording`: the spike's lookup of the library's `RecordingPos` from Aeron's public
  counters, at every library connect.
- `artio.ReportSessions`: each participant's session, held offline from startup (requested before
  the bind), requested back after a disconnect with the replay, the two handover checks, retries.
  Generic over a session view, so its rules are tested against a model of Artio's handover.
- `ClientReports.send` returns Artio's position; `canSend` replaces `isOnline`; the port `Delivery`.
  `OutboundReports` hands each sequenced 35=8 to it and defers the unsequenced rows. The backend: a
  `LibraryConnectHandler`, the startup acquisition, `onSessionStart` running the logon guards for a
  session held offline, and `restoreInFlight` by the row's shard.

**The check.** 12 tests of the lagging store (against the store as reopened), 9 of the sessions
against the model of Artio's handover, 4 new outbound tests, 2 store, 1 inbound, and 3 end-to-end
through a real initiator: a report for a logged-out client archived and consumed while it is away
and resent as PossDup at its Logon; after a restart, a fate written offline before the client is
back; and a client that logs back on before the library has seen its disconnect, its 35=D at the
gateway (the adapter is not polled while the engine accepts the Logon, so the race is forced).

The slice-3 test that a held report arrives live and not as a PossDup resend is **reversed**: the
offline delivery §6.3 decided means the 35=8 comes as Artio's resend. What §6.3 rules out is a
35=8 written on a session the engine is taking back, which the two handover checks now guard.

`./gradlew build` green, 152 tests in order-entry. 33 mutations, all caught. 24 at the first try,
and the replay after a disconnect by hand against the race test. Five only after a change. The
lowest pending `reportSeq` and the ring keeping it went unseen (D3, D8): in the gap test the oldest
waiting report was also the lowest by the time anything was checked, so it now checks with the
gap-filler waiting behind higher ones. One `SessionStore` for every shard (B5) and every row restored
on every shard (B7) went unseen end to end: one test now checks the second shard's stored sequence,
another one restore and one reject. And the backend's call marking a held session logged on (B6)
closed a window no test could force: `ReportSessions` now notices a session become active itself,
and that is tested (R7). Two were rewritten, one that did not compile and one whose text did not
match. The 35=8's allocation through Artio was not measured.

### 2i. Send behind the archive, and recovery of lost rows (slices 4b, 4c), 2026-10-26–27

**Found by reading Artio 0.182, confirmed by spike (g):** the library handles an inbound 35=D
before the fsynced archive has it (500 of 500, up to ~40 KB ahead). R§4 assumed the archive holds
every 35=D whose row a host crash can lose. It did not, so a client's PossDup resend of a lost
35=D would have been a second order. **Decided** (the recommendation, R§12): a request is sent only
once the archive's `RecordingPos` on the engine's inbound stream has passed its 35=D/F.

**Slice 4b, committed `6cf4adb`:** `inbound.ArchivedSends` sends rows in arrival order from the
duty cycle. `artio.InboundRecording` finds the inbound stream's `RecordingPos`. A back-pressured
send is retried from the row, and a refused one is answered by `OutboundReports.refuse`.
Non-negotiable 2 changed, 11 new.

**Slice 4c, recovery (Design §8.17), decided 2026-10-26 and confirmed 2026-10-27:** the startup
offline `requestSession` replays each session's archive from the participant's high-water + 1. A
replayed request is never sent or answered, and one that passes is rebuilt in flight for the fence
to settle. Its security is resolved when the directory arrives, and an unknown one is refused,
OrdRejReason 99 "unknown security". The floor of dropped rows is kept in `meta`.

**Found while building, and built (as built, Design §8.17):**
- **A local reject must raise the high-water.** Otherwise every clean restart replays the last
  requests rejected here, and "exchange unavailable" before the directory is decided again as
  sent, then rejected twice. It shares the floor's `meta` entry.
- **A rebuilt row's security must survive a stop before the directory.** It is stored
  *unresolved*, and the next replay starts no later than it.
- **Recovery cannot decide again what a lost write may have changed**: an acknowledgement, an
  ambiguity, a pending cancel. Those checks are skipped, so it errs towards *sent*.
- **The replay must be over before the bind.** A failed startup request kept the old loop
  finished; it now waits for every replay to complete with its session held.
- Spike (f) gained a case: a replay from past the end of an old `sequenceIndex` brings the next
  index's messages.

**The check.** 186 tests in order-entry, 22 of them new and 1 rewritten:
- the store +3: the high-water, the floor across a drop, where the replay starts;
- the inbound path +14: recovery's decisions, resolution, local rejects raising the high-water;
- the report sessions +2: the replay lasts until the session is held, and a failed one retries;
- end to end +3: a lost 35=D rebuilt, never sent again, rejected by the fence; a recovered order
  whose security is gone refused with no 48; a clean restart rejects nothing twice.

20 mutations, all caught (one first written so it did not compile, then rewritten). `./gradlew
build` green, the recovery spike 4 of 4. Not tested: a lost 35=F's row end to end, and a block
lifted during a replay.

### 2j. 35=H and 35=AF from the store (slice 5), 2026-10-27

**Decided with the user** (each the recommendation; Design §3.3, §5.4):
- a 35=H naming a ClOrdID not held is FIX 4.4's unknown-order 35=8: ExecType I, OrdStatus 8,
  OrdRejReason 5. A cancel's ClOrdID gets its order's status, 11 the cancel's and 41 the order's;
- 35=AF serves MassStatusReqType 7 and 1, with Side narrowing either. `extract_fix44.py` cuts 585 to
  those two, so the decoder answers any other with a 35=3, as for 2964=3;
- no matching open order gets a 35=j, reason 0, "no open orders", which invents no Side;
- an answer Artio refuses part way aborts the 35=AF and resumes from the next order on
  redelivery, so nothing is sent twice.

**Built:**
- `InboundOrders.onStatusRequest` and `onMassStatusRequest`. A status states the row as the client
  was last told it (§3.3, "What a status states"). The ambiguous flag and §3.4's Text come from the
  same code as the outbound restatement.
- `artio.ExecutionReportWriter`: the 35=8 encoding from an `OutgoingExecution`, moved out of
  `ArtioReports` so `ArtioReplies` writes the same 35=8, plus 790, 584, 911 and 912.
- The dictionary was regenerated from the verified FIX 4.4 repository jar. The script reproduced
  the committed XML byte for byte before the cut.

**Found:** Artio 0.182's `MutableAsciiBuffer.putAscii(String)` calls `String.getBytes`, so every
Text written with it allocated: §3.4's status Text (128 bytes), the fates' Texts, the reject
replies. The new 35=H allocation test found it. All 14 uses in `order-entry` now use Agrona's
`putStringWithoutLengthAscii`.

**The check.** 203 tests in order-entry, 17 of them new:
- 14 in the inbound path, one of them that a 35=H allocates nothing;
- 3 end to end through a real initiator: a 35=H for an order known and unknown, a 35=AF after a
  141=Y Logon, and a 35=AF with nothing open.

16 mutations, all caught, the dictionary's cut of 585 among them. `./gradlew build` green.

### 2k. The first end-to-end run against most-exchange's processes, 2026-10-27

**Decided with the user** (each the recommendation): the FIX client is a first cut of Design §10.1
in a new `tools` module, driven by bash like most-exchange's `run-failover.sh`; the trading date is
§3.6's calendar, built now; the `main` lives in a new `adapter` module.

**Built:**
- `orderentry.TradingCalendar` (§3.6, "As built"): zone, roll time, weekdays and a holiday file, read
  without allocating between rolls. 15 tests, 6 mutations caught (one only after a zone ahead of
  UTC was added: the first tests could not tell the UTC date from the local one).
- `adapter`: `Adapter` and `main`. The backend runs on an `AgentRunner`, and the acceptor binds
  only after it is constructed. 4 tests, one of them a real initiator's 35=D refused "exchange
  unavailable" before the directory, which shows the duty cycle runs on its own thread.
- `tools`: `bin/fix` (`send`, `cancel`, `status`, `mass-status`, `listen`, `load`). No unit tests;
  the e2e drives it.
- `e2e/run-e2e.sh`: one member, an independent gateway and discovery from `../most-exchange`'s
  installs, then the adapter over UDP, its own driver (§3.5). Discovery and the gateway's reports
  are on dynamic multi-destination channels, so the adapter and `most` both subscribe.
  `E2E_PAUSE_ON_FAIL=SECONDS` keeps the processes up to inspect them.
- The backend logs "shard N ready" when its SDK session has finished the opening mass status.

**Passes, steps 1–7:** a 35=D rests; a cross trades both sides, and the maker's fill reaches it at
its next Logon; 35=H for known and unknown ClOrdIDs; 35=F; 35=AF with nothing open (35=j); an
unknown symbol; a clean restart keeps the store, and a new order rests after it.

**Found:**
- **Artio's monitoring files are host-wide by default** (`$TMPDIR/fix-engine/monitoring`): any
  other Artio engine replaced the adapter's as it started, and two starting together failed. Now in
  `fix.logDir`, one per engine and library (`ArtioFilesTest`, seen failing first). The test
  fixture's `Initiator` and the CLI too.
- **After a SIGKILL, a restart is refused until the archive's mark file ages out** (~10 s, as §6.4
  said). The script retries, as `run-failover.sh` does for a member.
- **Step 8 fails: after a crash the adapter reuses a MsgSeqNum the client has seen** (Design §8.21,
  to decide). One order per client was lost with it, and in one run ~170, probably for the same
  cause. Read from the archives with Artio's `FixArchivePrinter --offline-archive-dir`.
- Two bugs in the client, not the adapter, on the way: its disconnect flag was never cleared after a
  reconnect, and the script's cleanup killed `fix`'s shell wrapper, not its JVM.

**The check.** `./gradlew build` green: fix-session 35 (+1), order-entry 218 (+15), adapter 4,
market-data 1. `e2e/run-e2e.sh` passes steps 1–7, and fails step 8 on §8.21.

**Spike (h), 2026-10-28** (`SequenceReuseSpikeTest`, `StreamingAcceptor`; Design §8.21, §8.13):
4 tests, all passing, each asserting its finding.
- (h1) a child acceptor SIGKILLed while writing a 35=8 every 2 ms: 1–3 messages reached the client
  and not the archive, and the restarted Logon reused the next number. Flat out, the archive ran
  ahead of the socket instead, which is why the pacing matters.
- (h2) `Session.lastSentMsgSeqNum(lastSent + 10 000)` on the session the library holds offline
  before the Logon: the Logon is numbered past it, the client asks for a resend, one gap fill
  answers, and the session stays up. With a Heartbeat written offline after the setter (h2b),
  Artio's replayer logs no error and the skip survives a second crash.
- (h3) no crash: a gap the acceptor asked for, in a session the client ended at once, is not asked
  for again at the next Logon. Artio had already counted the Logon as received.

**The skip, built 2026-10-28** (decided with the user, each the recommendation; Design §8.21,
CLAUDE.md non-negotiable 12, Rationale §13): after an unclean stop only, by a fixed 10 000, the
setter then a Heartbeat archived before the bind. `OrderStore.stoppedCleanly`/`markStoppedCleanly`
(a node-wide `meta` key, cleared at load with a synced write); `Adapter.close` marks after the
acceptor has closed; `OrderEntryBackend.moveSequencesOn`. Tests: store +1, backend +2, adapter +1;
five mutations caught. The collision log now names what collided.

**Spike (h4)** (`ResendStallSpikeTest`, a raw-socket client; Design §8.21): with a resend answered
up to the client's last message, every order arrives once under each Artio setting; answered short,
the session stalls under each. The e2e's Artio initiator answered short, having sent new orders
straight after its Logon: that was the ~170 unanswered. `fix load` now settles a second after a
reconnect. A probe showed the same stall on the client's side, when the adapter writes before its
gap fill.

**The e2e after the skip:** 7 runs of 8 pass. The 8th: an id collision raised by the startup
replay after the crash blocked CLIENT1, and its load looped on reconnects (now capped at 5). Not
explained: afterwards the store and the archive agreed one for one.

### 2l. The startup replay's routing, and market data (Status §3 item 7), 2026-10-30

**The e2e's unexplained collision** (Design §8.17, §8.21; Rationale §15; CLAUDE.md 11).
- *Problem:* one run in eight blocked CLIENT1 with an id collision raised by the startup replay,
  though the store and the archive agreed one for one.
- *Found* by reading Artio 0.182: every offline session is handed over with `NO_CONNECTION_ID`, the
  catch-up replay is rewritten to it, and the library routes by connection id to the subscriber
  stored last. With both startup requests out at once, CLIENT2's archived 35=Ds reached CLIENT1's
  handler and were rebuilt as participant 1001's. Artio's replay span is also filtered by session
  only, not by sequence number.
- *Built:* the startup requests one at a time; a replayed message whose SenderCompID is not its
  session's client refuses the start.
- *The check:* a test with two sessions losing rows failed every time before the fix, with the 8th
  run's log line; two mutations caught; `run-e2e.sh` 3 of 3, though no run left a 35=D to rebuild.

**Market data**, five slices (Design §4, §5.4, §8.22, §8.23).
- *Decided first* (`decision-fork`, each the recommendation): 35=X from a listener added to the SDK;
  market-data sessions transient; one engine, the archive coupling measured later; a client whose
  35=X Artio refuses gets a fresh 35=W.
- *Built:*
  - the dictionary's 35=x/V/y/W/X/Y, with 969 from FIX 5.0 (FIX 4.4 has no tick tag);
  - `DepthFeedListener` in most-exchange's `client` (`3afef4f`, published);
  - `FixAcceptor.persistenceByRole`; `SessionRejects`, shared by both backends;
  - `SecurityLists`, `Subscriptions` and `MarketDataBackend`;
  - the backend on its own thread in `bin/adapter`;
  - `bin/fix securities` and `bin/fix md`;
  - most-exchange's market-data process and step 3b in `run-e2e.sh`.
- *Found:*
  - an empty directory is indistinguishable from none (`isReady`), so 560=2 is never sent;
  - a 35=V instrument entry must start with Symbol, the group's delimiter;
  - the e2e showed every client getting the same 35=W every 500 ms, because a quiet book's repeated
    snapshot was installed over the trusted one. Now a book trusted again and exactly as its clients
    hold it resumes the 35=X, with no fresh 35=W.
- *The check:* tests from the clause before the code, except `SecurityLists` (written first, then
  tested from the clause):
  - `SubscriptionsTest` 23, `MarketDataBackendTest` 6, `SecurityListsTest` 5, `AdapterTest` +2,
    `SequenceTest` +1, `DictionaryTest` +8, the SDK's assembler +7;
  - every mutation caught or shown equivalent (one guard removed as dead);
  - `run-e2e.sh` 3 of 3 with one 35=W per subscription.
- *A process defect, found and fixed:* the mutation script read stale results when a mutation, or
  a test, failed to compile under `-Werror`. It now detects compile errors. The results above are
  from the corrected script.
- *Not done:* nothing measured, neither latency nor the archive coupling (§8.22) nor the SDK book's
  allocation (§8.23); a market-data-only adapter (order entry is still required).

### 2m. The latency tooling and the first baseline (Status §3 item 6), 2026-10-31

**The tooling** (Design §10; decided with the user: `fix perf` is its own command, and pacing and its
deviation are measured "so we can correctly attribute"; the FIX↔Kafka gateway's `analyze_trace.py`
as the report's model).
- *Specified first* in Design §10: a schedule fixed before the first send; lateness as queued, sender
  late and pushed back, traced to its root as a sender share and a system share; the order mix and
  the sender's predicted book; the log's records; the report's attribution and output.
- *Built:* `tools`' `perf` package (`Schedule`, `Pacer`, `Lateness`, `OrderMix`, `RawFix`,
  `PerfLog`, `Perf`) and `report` package (`Analysis`, `Report`, `Html`, `Charts`); HdrHistogram
  2.2.2 for the `.hgrm` files, `tools` only. `FixClient` gained a raw handler, a busy-spin option
  and several sessions.
- *The check:* 24 tests, the expected values worked out by hand from the clauses; 16 mutations, each
  caught (one first "caught" by a compile error, rewritten). Among them: the schedule re-based on the
  send, the first attempt taken at the last try, queueing all owed to the sender, a resting fill
  timed from its own send, the discard pass left in the throughput, a full log overwriting its last
  record, an allocating receive path, a CDN script, crosses on the maker's session. The mix and the
  raw reader allocate 0 bytes per message (measured after the JIT's two tier changes, which cost a few
  hundred bytes each); the paced send thread ~10 KB per run whatever its length, Artio's first use.
  The report was looked at, light, dark and at 500 px.

**Found by looking at a rendered report, and fixed in the spec first:**
- Every message showed ~1 µs "pushed back" with no refusal: Artio's encode in the `trySend` that took
  it. Now a fourth part, **send cost**, owed to the sender. The clean run's 35% "system" became 0%.
- Service times of 2 µs, below any round trip: a resting fill paired with the *latest* cross before
  it was timed from the second of two crosses on one order. Now paired in order (the analyzer's
  greedy pairing).

**The baseline** (`e2e/run-perf.sh`; `e2e/exchange.sh` now holds the startup both e2e scripts share,
and `run-e2e.sh` passed on it twice).
- *Found:* every cross came back CANCELED. most-exchange's SMP id defaults to the participant, so a
  participant never trades with itself. The sender now has a maker (CLIENT1) and a taker (CLIENT2),
  and the test venue model applies the same rule. Also found: the engine checks an ISIN's check
  digit, so the extra securities' ISINs carry one.
- *Measured* (Measurements A1–A2, X1, same day, same processes, machine not idle): through the
  adapter at fileSyncLevel 1, 15 ms p50 at 1,000/s, saturated at 3,500–5,400 requests/s with no
  back-pressure seen by the client; `most load` straight to the gateway, 0.16–1.1 ms p50 up to
  10,000/s; the adapter at fileSyncLevel 0, 1.7 ms at 1,000/s and 0.36 ms at 5,000/s. The sender's
  own lateness p99 stayed under 25 µs. Recorded as Design §8.24; nothing decided.
- *Not done:* §8.22's run with a market-data subscriber; the trace ring; the 2.25 s gap at 10,000/s.

**§8.22's run, the same day** (Measurements A3, X2; Design §4, §8.22).
- *Built:* `run-perf.sh`'s `PERF_MD=1` leg, each rate again on its own security while FEED1–4
  subscribe to its whole book, and its comparison report; `fix md --quiet`.
- *Three attempts not recorded, each confounded by the machine:* the feed clients printed every
  message; then each spun a core while idle (the `fix` client now backs off unless asked to spin);
  then IntelliJ took ~4 cores. The user closed IntelliJ and Firefox.
- *Measured, quiet:* market data on order entry's engine adds ~2–2.5 ms p50 and 5–8 ms p99 at 1,000
  and 3,000/s, and takes ~8% of the saturated capacity (4,255 → 3,922 requests/s). Also: A1's
  saturation figures were the loaded machine's; 3,000/s keeps up.
- *Decided (the user):* market data gets its own engine, port and an archive at fileSyncLevel 0;
  one session for both roles is scrapped; a dedicated adapter per participant (§8.27, a decision
  first); the sender's crosses carry another SelfMatchPreventionID instead of a taker session.
- *The check of the last:* a mutation giving crosses the passive orders' SMP id is caught; a real run
  at 1,000/s filled every cross on one session. The one or two fills a run that no cross targeted
  are the adapter holding a cancel of an unacknowledged order (Design §10.1), traced in the log.

**Market data's own engine, the same day** (Status §3 item 8a; Design §2, §4; CLAUDE.md 14; R§16).
- *Decided:* by the user, on A3 (above).
- *Specified first:* the `fix.md.*` keys in Design §2's table; in §4, a session at the other role's
  port refused as unknown, and the engine emptied at start; §8.28, its archive's growth within a run.
- *Built:* `FixNodeConfig.parseMarketData` (port and directories required, none order entry's,
  fileSyncLevel 0 by default), `ConfiguredSessions.ofRole`, `FixAcceptor(transient = true)`, and the
  adapter's second acceptor, bound after the backends with the first. `FixAcceptor`'s new parameter
  went before `configureDriver`, which callers pass as a trailing lambda.
- *The check:* 6 tests (the keys, their clashes, the roles' sessions, the refusal at the other port,
  the emptying, the missing port); 5 mutations, each caught. The e2e passed with FEED1 on 29881.
- *Measured* (A4, quiet): +0.45 ms p50 and nothing at p99 at 1,000/s, where shared it was +2.0 and
  +5.5 ms; at saturation still ~8% of capacity, as shared, so that share was never the archive's.

**The trace ring, the same day** (Status §3 item 6a; Design §9.3, §8.24).
- *Specified first* in Design §9.3: on by `fix.trace.file`, a one-to-one ring written on the
  order-entry thread and never waited for, a drainer thread, six events, a file whose times are the
  host's `nanoTime`, and `fix report --trace` joining by MsgSeqNum.
- *Built:* `fix-session`'s `trace` package (`Trace`, `TraceFormat`, `TraceLog`); hooks in
  `InboundOrders` (IN, with Artio's receive time from the handler), `ArchivedSends` (GATE, SENT),
  `OutboundReports` (REPORT, OUT, with the shard), `ReportDelivery` (DURABLE, with the shard); the
  backend's `trace` property, set by the adapter. The sender's log is format 2 (64-byte records, the
  MsgSeqNum). `TraceJoin` in the report, a section in the page and on the terminal.
- *The check:* 7 tests (the ring's round trip, a full ring counted, no allocation per event, off by
  default; the six events in order through a real acceptor and SDK; the join's cuts by hand). 7
  mutations, each caught; the participant filter's only after its test put another participant's
  event first. The full build, every test rerun, green; one earlier combined run failed once and did
  not again.
- *Found:* Agrona's ring takes a message of at most an eighth of its size (512 bytes the least);
  Artio's receive time converts only by the engine's own `EpochNanoClock` (another instance put it
  after the handler); one trace for an adapter's life, the runs told apart by MsgSeqNum.
- *Measured* (A5): below saturation the archive gate and the exchange stage ~8 ms each; at
  saturation the queue before the handler. Two diagnostics (A5d, A5e) showed the exchange stage
  moves with the fsync, not the threads.


### 2n. Docker, and the crash skip that broke resends (Status §3 items 9, 10), 2026-10-31

**Docker** (Design §7, `deploy/` and its README; decided with the user: a compose project here that
joins most-exchange's `most-aeron` and `most-edge` as external networks; one adapter, participant 7;
the image copies the host's `installDist`).
- *Built:* `deploy/Dockerfile` (eclipse-temurin 21 JRE, uid 10001), `docker-compose.yml` (named
  volumes for order entry's and market data's state, 1 GB `/dev/shm` for the two embedded drivers,
  `init`, a 30 s stop grace, `restart: on-failure:10`, a TCP health check), `config/` (the node file
  on `shard0`'s dynamic MDC endpoints, a UTC every-day calendar to match the seed's
  `LocalDate.now()`), `deploy/fix` (the host client as CLIENT7 and FEED7), `deploy/smoke.sh`, and a
  root `.dockerignore` (the checkout's `build/` is 9 GB).
- *Verified* against most-exchange's dev stack, its installs of 2026-10-15: a 35=D, a cross from
  participant 8 through `most send`, the fill at the next Logon, 35=y and 35=W; `docker compose
  restart` (`adapter: stopped`, no skip); a SIGKILL of the JVM (7 mark-file refusals, healthy in
  21 s, MsgSeqNum moved on 10,000).
- *Found on the way:* `most send --qty` is the raw `int64` (4 shares is `400000000`); the stack's
  `most` image predates `cancel-all`; and wiping the adapter's volume while the exchange keeps
  participant 7's orders repeats their `clOrdId`s, with the guard gone with the store (README).

**§8.29: after the crash skip, a 35=8 written offline was never resent.** Found by the smoke run
straight after the SIGKILL.
- *Seen* (`FixArchivePrinter` in the container): the 35=8 at 10 020 archived; the client's
  ResendRequest 7=10020 answered without it; the same after a clean restart.
- *Reproduced outside Docker, spec first:* `e2e/run-e2e.sh` step 9 (a fill for a logged-out client
  after step 8's crash; MSFT added, a book step 8's load never touches) failed, steps 1–8 passing.
  `OrderEntryBackendTest` "after an unclean stop a report written offline is still resent at the
  client's next Logon" failed at the resend.
- *Cause, from Artio 0.182's source:* `ReplayQuery.skipToStart`/`jumpPosition` jumps through the
  replay index by `(begin − seq)` records, assuming one per MsgSeqNum. `lastSentMsgSeqNum(+10 000)`
  left a hole, so every later lookup ran off the end and Artio gap-filled. Not shown by spike (h2),
  whose one resend landed on the Heartbeat by a nine-record jump.
- *Decided* (the user, the recommendation): the skip is 10 000 Heartbeats written offline and
  archived before the bind. Rejected: patching Artio (pinned release); a new `sequenceIndex`
  (gap-fills every archived report from before the crash). CLAUDE.md 12, R§13 and Design §8.21
  updated, the old mechanism struck through.
- *The check:* both tests pass; the skip's own test now expects the Logon at +10 001, one gap fill
  still covering it. `./gradlew build` green (order-entry 246). `e2e/run-e2e.sh` 9 of 9. In Docker,
  on fresh volumes: a SIGKILL, then `deploy/smoke.sh` twice.
- *Not done:* an index that already has a hole is not repaired (only the dev volume had one, now
  removed); reporting the lookup to Artio upstream is the user's call.

### 2o. The GitHub Action (Status §3 item 8), 2026-10-31

- *Decided* (the user): the regular tests only; nothing of most-exchange runs in CI.
- *Built:* `.github/workflows/ci.yml`, one job: temurin 21, `gradle/actions/setup-gradle`,
  `./gradlew build --continue`, the test reports uploaded. The SDK read with `GITHUB_ACTOR` and
  `GITHUB_TOKEN` (`packages: read`), or an `SDK_PACKAGES_TOKEN` secret.
- *The check:* not yet run on GitHub. The same command in a clean Linux container, credentials from
  the environment only: 366 tests, 0 failures, after one fix it found.
- *Found:* `TraceTest`'s full-ring test failed on Linux, 2 runs of 3: 9 events neither written nor
  counted. `Trace.close()` drained until a read returned 0, and Agrona's read returns 0 for a block
  that is only the wrap's padding. Now it drains until the ring is empty; the test runs 20 rounds
  and failed on macOS too before the fix (Design §9.3).
