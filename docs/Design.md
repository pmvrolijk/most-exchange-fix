# Design — most-exchange FIX adapter

**This document is authoritative.** Read the relevant section before implementing anything, and
change it in the same commit when the design changes. §8 is the open list.

What is decided is written as decided, with its date. What is **proposed** is marked so: it is the
intended design, still open to a `decision-fork` until code depends on it. Since 2026-10-09 only the
configuration format (§2) is still proposed. The exchange's own
contract is `../most-exchange/docs/Adapters.md`, and this document does not repeat it.

---

## 1. System overview

```
                       ┌──────────────────────────── most-exchange-fix ────────────────────────────┐
 FIX clients           │  Artio engine (acceptor)       order-entry library thread                 │
 (order entry) ─TCP──► │  framer · archiver ─────────► 35=D/F ─► OrderEntrySession (one per shard) ─┼─► gateways
                       │  archive (fsync)  ◄───────── 35=8/9 ◄── reports, fates   ◄────────────────┼── (SBE/Aeron)
                       │        ▲                       │                                          │
                       │        │ recorded position     └─► order store (RocksDB) · reportSeq store │
 FIX clients           │        │                                                                  │
 (market data) ─TCP──► │        └──────────────────── market-data library thread                   │
                       │                                35=V ─► DepthFeedAssembler ◄───────────────┼── feeds
                       │                                35=W/X, 35=y ◄ DirectoryClient ◄───────────┼── directory
                       │  control + /metrics (JDK HttpServer)                                      │
                       └───────────────────────────────────────────────────────────────────────────┘
```

- **Goals** (decided 2026-10-09):
  - **low latency**: a single-threaded, allocation-free duty cycle per backend (§7);
  - **stateful**: survives its own restart without losing track of an order or a report (§6);
  - **guaranteed delivery of execution reports**: a report is consumed from the exchange only once
    its 35=8 is durably persisted (§6.2);
  - **minimum to support every exchange feature**, and no more. Tags the exchange does not carry
    are not accepted yet (§5). Further client fields come later (§5.5).
- **One FIX engine, two backends** (decided 2026-10-03, most-exchange Adapters.md §0):
  - `fix-session` is the Artio engine and its libraries, the acceptor, logon and authentication,
    session persistence, the dictionary's codecs, the control and metrics server;
  - `order-entry` and `market-data` are the backends, and each depends on `fix-session` and on the
    SDK;
  - `tools` holds the test sender and the latency report (§10). It uses the same engine code and
    dictionary as the adapter.
- **The adapter is outside the exchange's determinism.** Nothing here is replicated. It may hold
  state, persist it, and restart; what it must not do is lose track of an order or a report.
- **What the exchange provides** comes through most-exchange's `client` SDK, pulled from
  most-exchange's public GitHub repository's package registry (decided 2026-10-09, Rationale §3):
  - `OrderEntrySession`, which is Adapters.md §1–§4;
  - `DirectoryClient`;
  - `DepthFeedAssembler`;
  - `PriceCodec`.

## 2. The FIX session layer (`fix-session`)

- **FIX engine: Artio** (decided 2026-10-03). It is built on the same Aeron, SBE and Agrona as the
  exchange, and is Apache-2.0. Pinned to 0.182, the release built on Aeron 1.53.0 (R§2).
- **FIX 4.4** (decided 2026-10-04), with the custom tags of §5.4.

### Sessions and participants

**One FIX session, one participant** (decided 2026-10-04). The adapter is configured with its
sessions, and each one names:

| Setting | Meaning |
| --- | --- |
| SenderCompID / TargetCompID | The session's identity. The adapter is the acceptor: its CompID and the client's |
| Username / Password | Checked against the Logon's 553/554 |
| role | `order-entry` or `market-data` (decided 2026-10-09): which backend's library owns the session |
| participantId | The exchange participant this session acts for (order entry only) |

- **A Logon is accepted only if its CompIDs and credentials match a configured session.** Anything
  else is refused at logon. This is the authentication Adapters.md §1 leaves to the adapter: the
  gateway enforces which participants it may act for, not who may use its endpoint.
- **A session reaches the library of its role** (spike §8.13(d), 2026-10-15). Accepted sessions
  start engine-owned, Artio's default. Every library hears of every session (`onSessionExists`)
  and claims those of its role with `requestSession`.
  - **The claim replays from the Logon's MsgSeqNum + 1.** A message the client sends between its
    Logon and the claim is otherwise never delivered, and nothing notices: the session comes over
    with its last received MsgSeqNum already past it, so there is no gap. The client is simply
    never answered.
  - A replay can bring back a message already processed before a restart. The order store drops
    it (a row already present, §6.1).
  - **A claim is taken on `OK` or `SEQUENCE_NUMBER_TOO_HIGH`.** The second is the usual answer:
    the client had sent nothing after its Logon, so there is nothing to replay, and the session is
    acquired all the same (`FixLibrary.requestSession`). Any other answer goes to the backend.
    `MISSING_MESSAGES`, for one, acquires the session without its replay.
- **The participant comes from the session, never from the message.** Every order and cancel on a
  session carries that session's `participantId`. A party identifier on the FIX message is not
  trusted for it. A client cannot act for another participant, because its session cannot name
  one.
- **Reports route back one to one:** `participantId` → session. A report for a participant whose
  session is logged out is written to the offline session (§6.3), and FIX's resend delivers it at
  the next logon. The order-entry library holds every configured session Artio knows from startup,
  online or offline (§6.3).
- **The exchange side must agree.** Every configured `participantId` must be listed for the
  gateway the adapter connects through, in the exchange's participant registry. If it is not, its
  orders are refused `UNAUTHORIZED_PARTICIPANT`.
- **MsgSeqNum is the FIX session's, owned by Artio and persisted** (decided 2026-10-15), across
  logons and adapter restarts (Artio's archive, §6). The adapter never numbers a message itself.
  - Artio's default resets sequence numbers at every logon, so the engine runs
    `SessionPersistenceStrategy.alwaysPersistent()`.
  - **A Logon must match.** A MsgSeqNum above the expected one is accepted, and the acceptor asks
    for a resend. One below is refused. The client knows its sequence numbers and is expected to.
  - **No reset at logon, unless the client asks** with ResetSeqNumFlag (141=Y). Otherwise they reset
    only by the session schedule or the control API (§9).
  - **A client that resets recovers by itself** (decided 2026-10-15). 141=Y discards the resend of
    every report it had not yet received, offline ones included. It then sends an order mass status
    request (35=AF) and is answered from the store (§3.3). The adapter owes it nothing more.
### Configuration and logon

Decided 2026-10-15. Properties files, as the exchange's processes use.

- **The node file** has `fix.*` keys, and any `fix.*` system property overrides its key:

  | Key | Meaning |
  | --- | --- |
  | `fix.bindAddress`, `fix.port` | Where the acceptor listens. `bindAddress` defaults to `0.0.0.0` |
  | `fix.logDir`, `fix.archiveDir` | Artio's logs and Aeron Archive's recordings. Required, durable (§6.4), and never deleted at start |
  | `fix.aeronDir` | The embedded media driver's directory. Defaults to Aeron's own, plus `-most-fix` |
  | `fix.fileSyncLevel` | The archive's fsync level, default 1 (§6.4). A test rig may set 0 |
  | `fix.sessionsFile` | The sessions file, below |
  | `fix.storeDir` | The order store (§6.1). Required for order entry, durable, never deleted at start |
  | `fix.directoryChannel`, `fix.directoryStreamId` | Where the exchange broadcasts its directory (most-exchange Design.md §7). Required for order entry |
  | `fix.shard.<id>.gateway.<n>.orderEntryChannel`, `…orderEntryStreamId`, `…executionReportChannel`, `…executionReportStreamId` | Optional: a shard's gateways, `n` from 0, in the order the SDK tries them. Listed, they replace the directory's one endpoint for that shard (§3.5) |

- **The sessions file** has one block of `session.<name>.*` keys per session. The name is only a
  label:

  | Key | Meaning |
  | --- | --- |
  | `senderCompId`, `targetCompId` | The adapter's CompID and the client's, as the adapter sends them |
  | `username` | The Logon's 553 |
  | `passwordSha256` | The SHA-256 of the Logon's 554, 64 hex digits |
  | `role` | `order-entry` or `market-data` |
  | `participantId` | The exchange participant, a number. Required for `order-entry`, refused for `market-data` |

  The adapter refuses to start if a key is missing or malformed, if two sessions share their
  CompIDs, or if two order-entry sessions share a `participantId` (reports route back one to one).
- **Passwords: only a digest is kept** (decided 2026-10-15). The operator issues each client a
  generated, high-entropy secret. The adapter stores its SHA-256 and nothing else, as the exchange
  keeps its gateways' secrets. A lost secret is reissued, never recovered. Without a slow hash,
  this is safe only for generated secrets, not for passwords a person chose.
- **A Logon is accepted when one configured session matches all of it:** its SenderCompID is
  the session's `targetCompId`, its TargetCompID is the session's `senderCompId`, its 553 is the
  `username`, and the SHA-256 of its 554 is the `passwordSha256`, compared in constant time.
  Credentials belong to their session: another session's username and password do not open it.
- **A refused Logon gets a Logout,** 58=`Logon refused`, and the connection is closed. The Logout
  does not say what failed. It is Artio's reject reply, at MsgSeqNum 1, outside the session's
  sequence. The session never reaches a library.
- **The acceptor** runs an embedded archiving media driver and Artio's engine on it. The archive is
  synced at `fileSyncLevel`, with `catalogFileSyncLevel` the same (§6.4). Sequence numbers are
  `alwaysPersistent`, and sessions are accepted engine-owned. FIX is not encrypted by Artio: the
  554 crosses the network as sent. TLS, if needed, is terminated in front of the adapter.
- *To specify* (§8.2): the session schedule. Until then a configured session may log on at any
  time.

## 3. Order entry backend (`order-entry`)

Decided by the exchange's contract, and therefore not open:
- every exchange-facing rule comes from `OrderEntrySession`;
- ~~`BACK_PRESSURED` becomes `Action.ABORT`~~ a `BACK_PRESSURED` send is retried, never dropped:
  from its row, in the duty cycle, since a request is sent only once the archive has its 35=D/F
  (decided 2026-10-26, §6.1, §8.20). Its FIX message is consumed once the row is stored;
- `NEVER_SEQUENCED` becomes a reject or a resend, and `AMBIGUOUS` an unknown status, never a reject;
- a mass status runs at startup and after every gateway reconnect (the SDK does it).

### 3.1 What the exchange supports, and therefore what is accepted

The exchange has **limit orders only**, on one book per security, with:
- **time in force:** day, good-till-cancelled, good-till-date (`expireDate`: the trading date, 0, or
  a date);
- **self-match prevention:** an optional `smpId` (0 means the participant) and a strategy, cancel the
  aggressor or cancel the resting order;
- **cancel**, by `origClOrdId` and `exchangeOrderId`;
- **mass status**, of every open order of a participant, on one security or all.

Everything else is rejected at the adapter with a FIX reject, never forwarded: market and stop
orders, IOC and FOK, replace (35=G), and any value the exchange cannot carry (§5).

### 3.2 Identifiers

- **The exchange's `clOrdId` is derived, not allocated** (decided 2026-10-09, R§5):

  ```
  clOrdId = (sequenceIndex << 32) | MsgSeqNum      — of the inbound 35=D or 35=F
  ```

  Artio's `sequenceIndex` counts the session's sequence resets, so the pair is unique for the
  session's lifetime, and the session is one participant. Nothing has to be persisted to allocate
  it. A PossDup resend of the same message carries the same MsgSeqNum and so derives the same
  `clOrdId`. Any 35=D can be found again in Artio's archive by its `clOrdId`.
- **Guard:** the store keeps the highest `sequenceIndex` seen per participant (its one session,
  keyed by `participantId`, not Artio's session id, which a wipe can change: decided 2026-10-25).
  If Artio starts with a lower one (its state was wiped), the adapter refuses to start. Derived ids
  would otherwise repeat.
  - **The guard misses a wipe at `sequenceIndex` 0**, the normal case for a session never reset
    (found 2026-10-25). The store catches the repeat itself: a new request whose `clOrdId` is held
    by a row with a different FIX ClOrdID is an **id collision** (§6.1). The request is rejected and
    the session logged out until an operator resets its sequence (§3.3, decided 2026-10-25).
  - **And at startup** (decided 2026-10-25): a configured order-entry session Artio does not know
    at all, while the store holds rows or a `sequenceIndex` for its participant, means Artio's state
    was wiped. The adapter refuses to start.
  - **Where the guards run** (2026-10-25, slice 2b): both read Artio's own list of the sessions it
    knows (`FixEngine.allSessions()`, loaded from its session file as the engine starts, offline
    sessions included), after the store is loaded and **before the acceptor binds**, so no Logon
    is accepted by an adapter about to refuse to start. "Holds" means any row, a stored
    `sequenceIndex` above 0, a durable first-missing `reportSeq` above 1, or a block. A session
    whose `sequenceIndex` is still unknown to Artio (-1) counts as not known. While running, the
    stored `sequenceIndex` is raised at each claim and on any message that carries a higher one.
    A claim that comes back below it (Artio's state lost while running) is treated as an id
    collision: blocked and logged out.
- **FIX ClOrdID (11)** is unique per session for the trading day, and for as long as the order is
  open. A new 35=D reusing a known one is rejected with OrdRejReason 6, *duplicate order*.
- **`exchangeOrderId`** is kept from the first report (Adapters.md §2) and sent back as OrderID (37).

### 3.3 Messages

**Every inbound application message** is decoded and `validate()`d first (§5.4). A failure is a
35=3 and goes no further. A type order entry does not handle gets 35=j, BusinessRejectReason 3,
except a 35=j, which is never answered.

| In | Handling |
| --- | --- |
| 35=D | Validated (§5.1) and written to the order store, *to send*. `newOrder` follows from the duty cycle once the archive has recorded the 35=D (§6.1, decided 2026-10-26). `BACK_PRESSURED` → retried from the row there |
| 35=D, ClOrdID known | Another of the session's rows holds the ClOrdID: 35=8, OrdRejReason 6. With PossDup=Y it is dropped instead: a resend of a request already answered |
| 35=D or 35=F, the same request again | Its derived `clOrdId` and its ClOrdID are already stored together (§6.1, *same*): a back-pressure redelivery, a PossDup resend, or a claim's replay (§2). Dropped: a row still to send is sent once, from the duty cycle (decided 2026-10-26; before, a redelivery sent a row whose own send had been back-pressured). The engine does not check `clOrdId` (most-exchange Adapters.md §2): sent twice, it would be two orders under one id |
| 35=D or 35=F, an id collision | Another ClOrdID holds the derived `clOrdId` (§3.2, §6.1). **Decided 2026-10-25** (§8.15): answered locally, 35=8 OrdRejReason 99 or 35=9 CxlRejReason 99, Text "session sequence reset required", never sent. Then a Logout with that Text. The session's Logons are refused until an operator resets its sequence, which raises its `sequenceIndex` (§9.1). Its open orders are untouched. Counted, and alertable (§9.2). **How, decided 2026-10-25** (slice 2b): the block is stored per participant as the `sequenceIndex` it was found at (`meta`, §6.1), so it survives a restart. The engine still accepts the Logon (its check runs on the framer thread, away from the store); on acquiring the session, the order-entry library sends a Logout with the same Text and disconnects. A message replayed or arriving before that is rejected with the same Text. **The block lifts when the session comes back with a higher `sequenceIndex`**: an operator's reset, or the client's own Logon with 141=Y. Either makes the derived ids new, so the collision cannot recur |
| 35=D or 35=F, before the directory | No complete directory has arrived yet, so no security can be resolved or routed (§3.5). The same reject as `NOT_READY` below, "exchange unavailable", never "unknown security" (2026-10-25, slice 2b) |
| 35=D or 35=F, the SDK cannot send | `NOT_READY` (the opening mass status has not finished) or `NO_GATEWAY`. **Decided 2026-10-25: rejected**, 35=8 OrdRejReason 99 or 35=9 CxlRejReason 99, Text "exchange unavailable". Never sent, so the client may retry with a new ClOrdID. Not held: an `ABORT` that lasts stalls every order-entry session, heartbeats included. **Since 2026-10-26** the send is the duty cycle's (§6.1), so the reject is owed on the row and sent from there, through the outbound path, as a fate's is (§5.3). `UNKNOWN_PARTICIPANT`, a shard's session not holding the participant, is a configuration error: the same reject, Text "participant not served", and logged |
| 35=F | Looked up by OrigClOrdID (41) in the store, then `cancel` with the stored `exchangeOrderId`, security and side. Unknown order, or 41 naming a cancel → 35=9, CxlRejReason 1. OrderID, Side or the security sent and not matching the order's → 35=9, CxlRejReason 1 |
| 35=F before the order's first report | **Parked** (decided 2026-10-09): written to the store as a cancel row in state *parked* (§6.1), so it survives a restart, and sent when the first report brings `exchangeOrderId`. If that report is terminal, 35=9 with CxlRejReason 0, *too late* |
| 35=F, a cancel already pending | **Decided 2026-10-25:** another cancel of the order is parked or sent → 35=9, CxlRejReason 3, *already pending*. The first stands. Once it is answered, a new cancel is accepted |
| 35=F, the order terminal | 35=9, CxlRejReason 0, *too late*, answered locally |
| 35=F, the order ambiguous | Answered locally (§3.4) |
| 35=G | **Rejected:** 35=9 with CxlRejResponseTo 2 and CxlRejReason 2, Text "replace not supported" (decided 2026-10-09: the exchange has no replace, and the adapter does minimum) |
| 35=H | Answered from the order store with a 35=8, ExecType I. The exchange has no per-order status query |
| 35=AF | Answered from the order store: one 35=8 ExecType I per open order, with MassStatusReqID, TotNumReports and LastRptRequested. The store is exact, because it is reconciled by the SDK's own mass status at startup and after each reconnect |

- **A reply Artio refuses** (back-pressured) aborts the inbound message (`Action.ABORT`), which
  Artio delivers again. Every path is safe to run again: state changes only after the reply that
  reports it was accepted, or in a form the second run recognises (a row sent, parked or still to
  be sent). A crash between a reply and the row's update errs toward a second reply, never none.
- **The participant is the session's** (§2), never the message's.
- **What reaches the inbound path** (2026-10-25, slice 2b). Artio hands the library every message
  its session layer has processed, session messages included. Session messages (35=0, 1, 2, 3, 4,
  5, A) are Artio's and go no further. Nor does a message Artio has marked invalid
  (`OnMessageInfo.isValid()` false: a MsgSeqNum gap it answers with a resend request, a message
  after a Logout, one it rejected itself). The resend brings it back, PossDup, and §3.3's rows
  handle that.
- **The adapter's own answers** (2026-10-25, slice 2b), on the request's session, numbered by Artio:
  - **35=8 reject of a 35=D:** OrderID (37) `NONE`, ExecID `A<sequenceIndex>.<MsgSeqNum>` of this
    35=8 (§5.3), ExecType 8, OrdStatus 8, LeavesQty, CumQty and AvgPx 0, OrdRejReason and Text,
    TransactTime now. Echoed from the request: ClOrdID, Side, OrderQty, Price, TimeInForce,
    ExpireDate and the security as sent (55, 48, 22).
  - **35=9:** ClOrdID and OrigClOrdID as sent. OrderID the order's `exchangeOrderId`, `NONE`
    before it is acknowledged. OrdStatus the status last sent to the client, `A` (pending new)
    before any. With CxlRejReason 1, an unknown order or one the cancel does not match, OrderID is
    `NONE` and OrdStatus `8` (FIX 4.4's rule for 35=9). Every other refusal names the order's
    status, never `8` for an order that may be open: the block, the collision and "exchange
    unavailable" included. CxlRejResponseTo 1 for a 35=F, 2 for a 35=G. With OrdStatusUnknown=Y,
    the Text is §3.4's in full, with the CumQty last stated.
  - **35=3:** RefSeqNum, RefMsgType, RefTagID (when the decoder names one) and
    SessionRejectReason. **35=j:** RefSeqNum, RefMsgType, BusinessRejectReason and Text.

| From the SDK | To the client |
| --- | --- |
| `onReport` | 35=8, or 35=9 when it rejects a cancel (§5.3). `ORDER_STATUS` reports from the SDK's own mass status update the store and are sent only when they change what the client was last told (decided 2026-10-09) |
| `onSettled(NEVER_SEQUENCED)`, an order | **A reject** (decided 2026-10-09): 35=8 ExecType 8, OrdStatus 8, OrdRejReason 99, Text "not sequenced (gateway failover)". The adapter never resends on its own. The client may, with a new ClOrdID: the original executes at most once |
| `onSettled(NEVER_SEQUENCED)`, a cancel | 35=9, CxlRejReason 99, Text "not sequenced". The order is unchanged, and may be cancelled again |
| `onSettled(AMBIGUOUS)`, an order | An **unknown status**, never a reject or any other terminal state (§3.4) |
| `onSettled(AMBIGUOUS)`, a cancel | 35=9 with OrdStatusUnknown=Y, Text "cancel status unknown". The order's own state is the mass status's to decide, which follows every truncation (§3.4) |
| `onMassStatusComplete(COMPLETE)` | Every acknowledged order the store holds open that the status did not state is now `AMBIGUOUS` (§3.4) |
| `onRequestRefused` | Logged and counted. The session's orders are not rejected for it |

### 3.4 An order whose fate is unknown

Decided 2026-10-09 (§8.10 closed, R§6). `AMBIGUOUS` means: not open at the exchange, and either
never sequenced or finished in a window no resend reaches. Whether it filled is unknown. What it
must never cause is a client re-entering the quantity, which a reject, a cancel or Done for Day
would invite.

- **An unsolicited 35=8, ExecType I, restating exactly what the client was last told**: the same
  OrdStatus (A, 0 or 1), LeavesQty, CumQty and AvgPx. The difference is
  **OrdStatusUnknown (5001, user-defined, §5.4) = Y** and Text "STATUS UNKNOWN: not open at the
  exchange; fills since CumQty=… unrecoverable until reconciled".
- **The order keeps that state** in the store, flagged. 35=H and 35=AF answer the same.
- **A cancel for it** is answered locally with 35=9, CxlRejReason 99, the flag, and the same Text.
  It is not forwarded.
- **Its ClOrdID stays reserved** for the day, like any other.
- **Operators:** a counter by participant (§9.2), and the list of ambiguous orders in `/status`
  (§9.1). Reconciling fills is manual until the exchange has a drop copy (§8.8).
- A report that does arrive for it later is processed normally, and clears the flag.
- **For an order never acknowledged**, the status last told is `A`, pending new: OrdStatus A,
  CumQty and LeavesQty as the order was entered (0 and OrderQty).

**Two routes lead here**, and the SDK covers only the first:
- **An unanswered request.** The SDK settles it `AMBIGUOUS` when a truncated resend is followed by a
  mass status (`onSettled`).
- **An acknowledged order that finished unseen.** The SDK does not track orders once their first
  report has arrived, so this one is the adapter's (decided 2026-10-09). When a mass status for all
  securities completes, every acknowledged order the store holds open that it did not state with an
  `ORDER_STATUS` is `AMBIGUOUS`. The rule needs no knowledge of what triggered the status:
  - after a **complete** resend, such an order's terminal report would already have arrived, so it
    never fires wrongly;
  - after a **truncated** one, it catches exactly the orders that finished in the lost window.

  **Only orders sequenced before the status count:** those whose last report's `reportSeq` is below
  the completion's `nextSeq`. Arrival order is not used, because after a gateway switch two streams
  interleave. The row keeps its last `reportSeq` for this.

**After a restart**, the SDK's in-flight set is gone. Store rows with no report yet are handed back
to it with `restoreInFlight` before it starts (decided 2026-10-09, an SDK change, §8.9). They then
settle by the SDK's own rules: `NEVER_SEQUENCED` if the startup resend completes, `AMBIGUOUS` if it
truncates.

### 3.5 Threading

- **One `OrderEntrySession` per shard, holding every configured order-entry participant** (decided
  2026-10-09; the SDK session is per shard). All of them run on the **order-entry library's thread**, in one
  duty cycle: `FixLibrary.poll`, each `OrderEntrySession.doWork`, the reportSeq store's advance
  (§6.2). There is no hand-off between threads on the order path.
- An order is routed to the session of its security's shard, from the directory.
- **The SDK's links run on the adapter's embedded media driver** (decided 2026-10-25, §8.6 closed):
  the archiving driver of `fix.aeronDir` (§2) carries the gateways' UDP and the directory's
  subscription too. One process, one driver directory. A driver fault takes FIX and the exchange
  links down together, as they live in one process anyway. Not covered: sharing a host-wide driver
  with other applications.
- **The directory is polled on the order-entry thread** (2026-10-25, slice 2b), in the same duty
  cycle. Until a complete directory has arrived, every order and cancel is refused "exchange
  unavailable" (§3.3). The adapter's own index of it (symbol, ISIN and `securityId` to the security
  and its shard) is rebuilt only when the directory's version changes, and looked up without
  allocating.
- **Where a shard's gateways come from** (decided 2026-10-25): the directory's one endpoint, unless
  the node file lists the shard's gateways (§2), which then replace it. A co-located shard needs
  them listed, one per node, to fail over (most-exchange Adapters.md §1). A shard's
  `OrderEntrySession` is created when the shard first appears in a complete directory, from the
  first directory on for those present at startup, and later for a shard added. **An endpoint that
  changes in a later directory is logged, not followed**: the session keeps the links it was
  created with until the adapter restarts. A shard that leaves the directory keeps its session,
  so the orders still open on it are still reported.

### 3.6 The trading date

Decided 2026-10-09 (§8.12 closed, R§7): **the adapter's own calendar.** It is used for one thing
only: the `expireDate` of a day order (59=0 or absent).

- **Configured:** a time zone, a roll time, and a holiday file. Before the roll, the date is today
  if today is a trading day. After it, the date is the next trading day. The roll time belongs
  **inside the exchange's closed window**, between the close and the next pre-open. Orders are
  refused `MARKET_CLOSED` there anyway, so the date the adapter stamps cannot matter there.
- **When it disagrees with the engine:**
  - **behind** (adapter D−1, engine D): day orders are rejected `ORDER_EXPIRED`. That is visible,
    and safe;
  - **ahead** (adapter D+1, engine D): a day order is accepted as good till D+1, and **survives
    the close**. That is silent. It happens when the operator runs an exchange session off the
    calendar: late, on a weekend, or on a holiday.
- **Mitigations:**
  - `/status` shows the adapter's trading date;
  - `POST /trading-date` overrides it for an off-calendar session, and clears at the next roll
    (§9.1);
  - `ORDER_EXPIRED` on a day order is counted and alertable (§9.2), as the sign of being behind.

  Detecting "ahead" needs the engine's date, which the adapter does not receive.
- **Not used for:** GTD validation (the engine's), or store retention (by `expireDate` and terminal
  state).

## 4. Market data backend (`market-data`)

- L1/L2 only. **MBO waits on the exchange** (per-order L3 recovery, most-exchange open issue 7).
- **Its own Artio library and thread** (decided 2026-10-09), so that fan-out to market data sessions never
  delays the order path.
- **The minimum subscription model** (decided 2026-10-09):
  - **35=x → 35=y.** SecurityListRequest with SecurityListRequestType 4, *all securities*, is
    answered from `DirectoryClient`. The fields are Symbol (55), SecurityID (48) with
    SecurityIDSource 8 (the exchange's `securityId`), the ISIN as an alternative id (455/456 = 4),
    Currency (15), MinPriceIncrement (969, the tick) and SecurityDesc (107).
  - **35=V.** SubscriptionRequestType 0 (snapshot), 1 (snapshot plus updates) or 2 (unsubscribe).
    MarketDepth 1 (top of book) or 0 (every level). MDUpdateType 1 (incremental). Entry types
    0 (bid), 1 (offer), 2 (trade). Anything else → 35=Y.
  - **35=W** from the `AggregatedBook` at subscription. **35=X** from `DepthUpdate`, `TopOfBook` and
    `LastTrade`.
  - **While a book resynchronises** (`book()` is null), incrementals stop, and a fresh 35=W follows
    once it is trusted again.

## 5. FIX dictionary and field mapping

Prices and quantities are converted between FIX decimals and fixed-point `int64` with 8 implied
decimals by `PriceCodec`. Artio decodes them as `DecimalFloat`, never `Double`. A value with more
than 8 significant decimals is **rejected, not rounded**. The conversion must be allocation-free
(§8.11).

### 5.1 NewOrderSingle (35=D) → `NewOrderSingle`

| Tag | Required | Exchange field | Conversion; refused values |
| --- | --- | --- | --- |
| 11 ClOrdID | Y | `clOrdId`, derived (§3.2) | Stored. Duplicate → OrdRejReason 6 |
| 48 SecurityID + 22 SecurityIDSource | Y, or 55 | `securityId` | 22=8: the exchange's `securityId`. 22=4: an ISIN, resolved by the directory. Unknown → OrdRejReason 1 |
| 55 Symbol | if no 48 | `securityId` | Resolved by the directory |
| 54 Side | Y | `side` | 1 → BUY, 2 → SELL. Others → reject |
| 38 OrderQty | Y | `qty` | `PriceCodec`. ≤ 0 → reject |
| 40 OrdType | Y | — | Only 2 (limit). Others → OrdRejReason 99, Text "limit orders only" |
| 44 Price | Y | `price` | `PriceCodec`. Tick and collar are the exchange's to check |
| 59 TimeInForce | N, default 0 | `expireDate` | 0 (day) → the adapter's trading date (§3.6). 1 (GTC) → 0. 6 (GTD) → 432. Others → reject |
| 432 ExpireDate | if 59=6 | `expireDate` | LocalMktDate YYYYMMDD, forwarded **unchecked**: the engine rejects a past date `ORDER_EXPIRED`. The adapter's calendar stays out of GTD |
| 2362 SelfMatchPreventionID | N | `smpId` | Must parse as a positive `int64`. Absent → 0 (the participant) |
| 2964 SelfMatchPreventionInstruction | N | `smpStrategy` | 1 → CANCEL_AGGRESSOR, 2 → CANCEL_RESTING. Others, FIX Latest's 3 included, are outside the dictionary: 35=3, SessionRejectReason 5 (§5.4). Absent → CANCEL_AGGRESSOR |
| 60 TransactTime | Y | — | Required by FIX 4.4, not forwarded |

Standard FIX 4.4 header and body fields not listed here are accepted and ignored. Account (1) is
among them until §5.5.

**The refusals' codes** (2026-10-25). Each is a 35=8, ExecType 8, OrdStatus 8, answered locally and
never sent. No row is written, so the ClOrdID is not reserved. The Text is fixed, so a client can
match on it:

| Refused | OrdRejReason | Text |
| --- | --- | --- |
| Neither 48 nor 55; 48 without 22, or 22 other than 4 and 8; a security the directory does not know | 1 | "unknown security" |
| 38 absent, not exact at 8 decimals, or ≤ 0 | 13 | "incorrect quantity" |
| 40 other than 2 | 99 | "limit orders only" |
| 44 absent | 99 | "price required" |
| 44 not exact at 8 decimals | 99 | "price not exact at 8 decimals" |
| 54 other than 1 and 2 | 99 | "side 1 or 2 only" |
| 59 other than 0, 1 and 6 | 99 | "time in force 0, 1 or 6 only" |
| 59=6 without 432, or 432 not a YYYYMMDD date | 99 | "ExpireDate required for GTD" |
| 2362 not a positive `int64` | 99 | "SelfMatchPreventionID must be a positive integer" |

The dictionary leaves 55, 38 and 44 optional, as FIX 4.4 does, so these are the backend's checks,
not `validate()`'s.

### 5.2 OrderCancelRequest (35=F) → `OrderCancelRequest`

| Tag | Exchange field | Conversion |
| --- | --- | --- |
| 11 ClOrdID | `clOrdId`, derived (§3.2) | The cancel's own id. Its report carries it |
| 41 OrigClOrdID | `origClOrdId` | Looked up in the store |
| 37 OrderID | `exchangeOrderId` | From the store. If sent, it must match |
| 54 Side, 55/48 | `side`, `securityId` | From the store. If sent, they must match, else 35=9 |

### 5.3 `ClientExecutionReport` → ExecutionReport (35=8) / OrderCancelReject (35=9)

| Tag | From |
| --- | --- |
| 37 OrderID | `exchangeOrderId` |
| 11 ClOrdID / 41 OrigClOrdID | The FIX ids stored for `clOrdId`. On a cancel's report, 11 is the cancel's and 41 the order's |
| 17 ExecID | **Deterministic** (decided 2026-10-09). A sequenced report's ExecID is its `reportSeq`, in decimal. The exchange's counter is replicated, snapshotted and never reset for the participant (most-exchange Design.md §5), and one session is one participant, so it is unique for the session's life. A report delivered twice across a restart carries the same ExecID, and the client can drop it. Everything else (`reportSeq` 0, the adapter's own 35=8s) gets `A<sequenceIndex>.<MsgSeqNum>` of the 35=8 carrying it. Those are never re-delivered by the exchange |
| 150 ExecType | NEW → 0, TRADE → F, CANCELED → 4, EXPIRED → C, REJECTED → 8, ORDER_STATUS → I |
| 39 OrdStatus | NEW → 0. TRADE → 1 if `leavesQty` > 0, else 2. CANCELED → 4. EXPIRED → C. REJECTED → 8. ORDER_STATUS → 1 if `cumQty` > 0, else 0 |
| 54, 55/48, 44, 59, 432 | Echoed from the store |
| 38 OrderQty | `origQty` |
| 31 LastPx / 32 LastQty | `price` / `lastQty`, on TRADE |
| 14 CumQty / 151 LeavesQty | `cumQty` / `leavesQty`, stated by the engine, never derived |
| 6 AvgPx | The adapter's own: cumulative notional (128-bit) over `cumQty`, kept in the store. 0 when no fill is known |
| 103 OrdRejReason, 58 Text | From `RejectReason`: UNKNOWN_SECURITY → 1, MARKET_CLOSED → 2, every other → 99 (2026-10-25). Text is always the enum name |
| 60 TransactTime | Adapter time at the report's arrival. The report carries no timestamp |
| OrdStatusUnknown | Y only on an ambiguous order (§3.4). Absent otherwise |

- A REJECTED report answering a cancel becomes **35=9**, with CxlRejResponseTo 1 and CxlRejReason
  from `RejectReason`: UNKNOWN_ORDER → 1, MARKET_CLOSED → 2.
- `enrichment = UNKNOWN` means `origQty` and `cumQty` cannot be believed. The report is sent with
  the quantities the store holds, and counted.

**How each report is applied** (2026-10-25, slice 3; the engine's own rules read in most-exchange's
`MatchingEngineService`):
- **The row is the report's (`participantId`, `clOrdId`).** A participant's cancel is answered with
  the **cancel's** `clOrdId`; an unsolicited cancel (self-match prevention, a volatility halt, a
  bulk cancel), an expiry, a trade and an order's own reject carry the **order's**.
  - **A cancel's row:** CANCELED → a 35=8 ExecType 4 of the order, 11 the cancel's ClOrdID and 41
    the order's. REJECTED → a 35=9, CxlRejResponseTo 1, CxlRejReason from the reject below, except
    that UNKNOWN_ORDER for an order the store already holds terminal is 0, *too late*: the cancel
    lost a race with a fill or an unsolicited cancel. Either way the cancel is answered and no
    longer the order's pending one.
  - **An order's row:** its 35=8, 11 its ClOrdID and no 41. An unsolicited CANCELED carries the
    RejectReason's name as Text when it is not NONE.
  - **No row** (decided 2026-10-25): its row was lost in a host crash (§6.1). Logged, counted, and
    consumed, until recovery from Artio's archive is built (§8.17). The client misses that order's
    reports until then.
- **Each report's 35=8** has the fields of the table above. 55 is the security's Symbol from the
  directory, with 48 its `securityId` and 22=8; 2362 and 2964 are echoed as sent. LastPx and LastQty
  on a TRADE only. **AvgPx** is the stored notional over CumQty, rounded half up at 8 decimals.
- **The state follows the 35=8.** The row changes only once Artio has taken the 35=8. A refused one
  aborts the report (§6.2), and nothing changed. A sequenced report at or below the row's last
  `reportSeq` was applied before (a resend after a restart): its 35=8 is sent again with the same
  ExecID, and the row is not changed again.
- **OrdStatusUnknown is cleared** by any report for an ambiguous order, an `ORDER_STATUS` included
  (§3.4).
- **A parked cancel** (§3.3) is acted on once its order's first report has been applied: sent, or,
  if that report was terminal, answered 35=9 CxlRejReason 0 *too late*. From the duty cycle, after
  the order's 35=8, so a refused send never undoes a report already taken.
- **Fates** (§3.3, §3.4) are owed on the row (§6.2) and their 35=8 or 35=9 is sent from the duty
  cycle. So are the restatements the mass status rule makes. A row changed by an answer with no
  `reportSeq` is written only once the archive has the answer (§6.2, decided 2026-10-25).
- **An `ORDER_STATUS`** updates the row and is sent, as a 35=8 ExecType I, only when it changes what
  the client was last told: OrdStatus, CumQty, LeavesQty, or the order found acknowledged.
  **A CumQty above the one told** means fills in a window no resend reached, at prices unknown
  (decided 2026-10-25): the 35=8 states the new CumQty, LeavesQty and OrdStatus, AvgPx stays that of
  the fills the adapter knows, and Text is "fills not reported individually: CumQty <a> to <b>,
  prices unknown". Counted, alertable.
- **The mass status rule** (§3.4) runs per shard: each shard's `OrderEntrySession` runs its own
  status, so a completion settles only orders on that shard's securities.
- **A participant whose session the library does not hold** has its report refused with `ABORT`, and
  its owed 35=8s wait (§6.3): from a logout under way or a disconnect until the session is back
  offline, about one `requestSession` round trip. A client logged out is written to offline, and
  holds nothing up (slice 4, 2026-10-25).

### 5.4 The dictionary

Decided 2026-10-20. **FIX 4.4, only the message types the adapter handles, plus three tags:**
- **The source is the FIX Trading Community's FIX 4.4 repository,** `FixRepository44.xml` in
  `io.fixprotocol.orchestrations:fix-standard:1.5.3` (Apache-2.0). Its only known FIX 4.4 issue,
  the type of tag 532, is outside the messages kept. Not QuickFIX/J's `FIX44.xml`: its licence
  carries an acknowledgment clause and name restrictions, the shape of Apache 1.1.
- **`fix-session/src/main/dictionary/extract_fix44.py` writes `fix44-most.xml`** from it, and the
  output is committed. It keeps the header, the trailer, the seven session messages, and 35=j, D,
  F, G, H, AF, 8 and 9, each with its complete FIX 4.4 structure, so a standard field the adapter
  does not read is accepted and ignored (§5.1). Market data messages are added with `market-data`.
  A group becomes a component of its own name, as in QuickFIX's FIX 4.4, so one decoder serves
  every message that carries it. The script is re-run, never its output edited.
- **2362 SelfMatchPreventionID**, String (FIX Latest EP211), on 35=D and echoed on 35=8. The backend
  parses it as a positive `int64` (§5.1).
- **2964 SelfMatchPreventionInstruction**, int, on 35=D and echoed on 35=8, with **1
  (CancelAggressive) and 2 (CancelPassive) only**. FIX Latest (EP280) also defines 3,
  CancelAggressivePassive, which the exchange cannot carry. It is left out of the dictionary, so a
  3 is a value the decoder refuses: a 35=3 with SessionRejectReason 5, not an order reject (decided
  2026-10-20).
- **5001 OrdStatusUnknown**, Boolean, user-defined, on 35=8 and 35=9 (§3.4; number decided
  2026-10-20).

**Artio generates the codecs** at build time (`CodecGenerationTool`, the Gradle task
`generateFixCodecs`), into `nl.lamia.most.exchange.fixadapter.codecs`. The engine's
`acceptorfixDictionary` is that package's `FixDictionaryImpl`, and the engine hands it to each
library with the session. Artio's own session codecs are not on the adapter's classpath.

**Validation: the backend's, not Artio's.** Artio validates only an application message's MsgType
and header (checked 2026-10-20 in Artio 0.182's `SessionParser`). The body reaches the library
unvalidated. So:
- the backend decodes each message and calls the decoder's `validate()`. A message that fails is
  answered with a **35=3**: RefSeqNum, RefMsgType, RefTagID the decoder's `invalidTagId`, and
  SessionRejectReason its `rejectReason`. It is never forwarded to the exchange;
- what fails: a required tag missing, a value of the wrong type, a value outside a field's code
  set (2964=3, say), a FIX 4.4 tag not defined for the message (SessionRejectReason 2), and a tag
  the dictionary does not define at all (0, invalid tag number: Artio's answer, and QuickFIX's.
  FIX's code set names 3, undefined tag, without saying when it applies instead);
- the last two need **`-Dfix.codecs.reject_unknown_field=true`**, a JVM-wide Artio switch read once.
  **The acceptor refuses to start** unless it and Artio's two other switches (codec validation,
  unknown enum values) are on.
- A message type outside the dictionary passes Artio's check (any letters and digits). The backend
  answers it with **35=j, BusinessRejectReason 3** (unsupported message type).
- The same switches apply to the session messages Artio validates itself, the Logon included.

### 5.5 Later: client fields carried to the exchange

Decided 2026-10-09 that this waits. Account (1), and other client fields on 35=D that the exchange
does not read, will be **carried by the exchange** and echoed on its reports, kept in the engine's
`ColdField` beside the order, as `origQty` is (most-exchange Design.md §3.1). That needs a schema
change in most-exchange first. Until then, the adapter accepts only the tags the exchange requires.
The order store's row is versioned so the fields can be added (§6.1).

## 6. Persistence

| State | Why | Where |
| --- | --- | --- |
| FIX session sequence numbers, every message in and out | FIX resend on reconnect, offline delivery | Artio's archive and replay index, **fsynced** (§6.4) |
| Per request (an order or a cancel): FIX ClOrdID ↔ `clOrdId` ↔ `exchangeOrderId`, and what the client was told | Answering, cancelling, uniqueness for the day, 35=H/AF, `restoreInFlight` | **RocksDB** plus an in-memory working set (§6.1, decided 2026-10-09) |
| Per participant and shard: first `reportSeq` not yet durably delivered | Resume with a resend after a restart | RocksDB, **advanced only behind the archive** (§6.2) |
| Per participant (its one session): highest `sequenceIndex` | The derived-id guard (§3.2) | RocksDB |

### 6.1 The order store

Decided 2026-10-09: **RocksDB, with the working set in memory.**

- **A row per request, an order or a cancel** (decided 2026-10-25). A cancel has its own row, which
  points at its order's `clOrdId`. Its 35=8 or 35=9 needs the cancel's own ClOrdID (§5.3), and after
  a restart a sent, unanswered cancel goes back to the SDK with `restoreInFlight` like an order.
  A **parked** cancel (§3.3) is a cancel row in state *parked*, so it still survives a restart. Both
  kinds share the session's ClOrdID namespace, so ClOrdID uniqueness covers cancels too.
- **Keyed by `participantId`**, not Artio's session id (decided 2026-10-25). It is configured one to
  one with a session (§2), it does not change when Artio's state is wiped (§3.2), and every report
  carries it.
- **In memory:** Agrona maps on the order-entry thread, keyed by (`participantId`, `clOrdId`) and by
  (`participantId`, FIX ClOrdID). The ClOrdID is hashed from the message's bytes and compared byte
  for byte along the hash's chain, so a lookup allocates nothing and a hash collision is never a
  false duplicate. Every read is served from here.
- **Inserting a request** reports one of:
  - *new*: written;
  - *same*: the same `clOrdId` with the same ClOrdID, the very request again (a redelivery after
    back-pressure, or a PossDup resend). The row is left as it is;
  - *duplicate ClOrdID*: another of the participant's rows holds the ClOrdID (§3.2, OrdRejReason 6
    for an order);
  - *id collision*: another ClOrdID holds the `clOrdId`. Artio's sequence went back without its
    `sequenceIndex` rising (§3.2). Nothing is written.

  What each means to the client is the backend's (§3.3); the store only reports it.
- **RocksDB:** a row per request, written on the library thread. The WAL is **not fsynced**, so a write
  survives a process crash but not a host crash. That is enough, because everything a host crash
  can lose from RocksDB can be rebuilt from Artio's fsynced archive, or errs toward re-delivery
  (R§4).
  - **When:** the row is written **before** `newOrder`. ~~If the send is back-pressured, the
    redelivered message writes the same row again, which is harmless.~~ **A request is sent only
    once the archive has recorded its 35=D or 35=F** (decided 2026-10-26, §8.20, R§12). The inbound
    path writes the row *to send*, with the message's position on the engine's inbound stream, and
    consumes the message. The duty cycle sends rows to send in the order they came, each once the
    `RecordingPos` of that stream has reached its position: at `fileSyncLevel=1`, once it is
    fsynced (§6.4). A back-pressured send stays to send and is tried again in the next cycle; a
    `NOT_READY`, `NO_GATEWAY` or `UNKNOWN_PARTICIPANT` owes its reject on the row (§3.3). The position
    and *to send* are in memory only: a row loaded at startup and not acknowledged is restored in
    flight (below), and the fence settles it, a reject if it never went out. The row is updated on
    every report, on a parked cancel, and on a fate.
    - **The stream's `RecordingPos`** is the engine's one inbound publication (Artio's
      `inboundPublication`, stream `inboundLibraryStream`) in the adapter's driver, found from
      Aeron's counters as for §6.2. Every message on it, replayed ones included, is in that one
      publication's position space. While it is not found, nothing is sent.
    - **A parked cancel** (§3.3) is sent the same way once its order's first report has come: put
      behind the requests waiting, at its own 35=F's position. One loaded at startup has no position
      in this process and goes at once. Its row is stored, so a resend of its 35=F finds it, and
      nothing is sent twice.
    - **A refused send** (`NOT_READY`, `NO_GATEWAY`, `UNKNOWN_PARTICIPANT`) is owed on its row as a
      `NEVER_SEQUENCED` fate with the refusal's Text ("exchange unavailable", "participant not
      served"), and answered by the outbound path (§5.3): a 35=8 OrdRejReason 99 or a 35=9
      CxlRejReason 99, as §3.3 says. The Text is in memory: after a restart the fate's own Text is
      sent. Owed, the row is no longer in flight, so a restart does not hand it to the SDK.
    - **What it costs:** every order waits for the archive's fsync before it goes out. In the spike
      the archive ran up to ~40 KB behind the library, on a laptop. The time is not measured, and no
      latency figure is stated without `perf-claim`.
  - **Recovery mode: point-in-time** (`WALRecoveryMode.kPointInTimeRecovery`). After a crash,
    RocksDB recovers a **prefix** of its writes, never a set with holes. The durable `reportSeq`
    (§6.2) is written after the row updates it covers, so if it survived, they did too. The
    argument that the WAL need not be fsynced depends on this (R§4). It must stay configured, and a
    test must hold it.
  - **Column families**, in one database, so they share one WAL and one prefix:
    - `orders`: (`participantId`, `clOrdId`) → a versioned row. Every row: its kind, FIX ClOrdID,
      the trading date it was entered on, the shard it was sent to (an order's security's, a
      cancel's order's; added 2026-10-25), the last `reportSeq`, and a fate's 35=8 owed (§6.2). An
      order: `exchangeOrderId` once acknowledged, security, side, price, `origQty`, TimeInForce
      and `expireDate`, SMP as sent, the status last sent (OrdStatus, `cumQty`, `leavesQty`), the
      cumulative notional (128-bit), the ambiguous flag, and room for §5.5's fields. A cancel: its
      order's `clOrdId`, and its state, *parked*, *sent* or *answered*;
    - `meta`: per participant and shard, the durable first-missing `reportSeq`; per participant,
      the highest `sequenceIndex`, and an id collision's block, as the `sequenceIndex` it was
      found at (§3.3; deleted when the block lifts). **Per shard** (found 2026-10-25, slice 4):
      each shard's engine numbers a participant's reports on its own (most-exchange
      `MatchingEngineService`), and each shard has its own `OrderEntrySession`. One value per
      participant would have resumed one shard from another's sequence.

    A row of an unknown version is refused, and the adapter does not start.
  - **Retention** (decided 2026-10-25 for ambiguous orders). A row is dropped when all hold:
    - it was entered before the current trading date (ClOrdID uniqueness is for the day);
    - it is finished: an order in a terminal state (OrdStatus 2, 4, C or 8) and not ambiguous, or
      a cancel answered;
    - no fate's 35=8 is owed on it;
    - its last `reportSeq` is below the durable first-missing of the participant on the row's
      shard, so no resend can bring a report for it again.

    So open GTC and GTD orders are kept until terminal, across days, and an **ambiguous order is
    kept like an open one** until a report or an operator settles it (§3.4).
- **At startup:** drop what retention allows, and load the rest into memory. Hand the requests in
  flight, orders not acknowledged and cancels *sent*, to the SDK with `restoreInFlight` (§3.4).
  Not handed back: a parked cancel, which was never sent, and a request the SDK already settled
  (a fate's 35=8 owed, or sent: an order rejected or ambiguous). Restored, it could be settled
  again, an `AMBIGUOUS` order as `NEVER_SEQUENCED` by a startup resend that completes. Then the SDK's resend and mass status
  reconcile them. **Not yet built** (§8.17, shape decided 2026-10-26): a row lost in a host crash
  is rebuilt from Artio's archive before the bind, by replaying each session's inbound messages
  past the store's last row for that participant.

### 6.2 Execution report delivery: consume a report only behind the archive

Decided 2026-10-09 (§8.9 closed): **a `reportSeq` is consumed only once its 35=8 is fsynced in
Artio's archive.** That is the guarantee of delivery. After a crash, the stored first-missing
`reportSeq` is never past a report the client cannot be resent by FIX.

- **The SDK keeps counting on receipt. The adapter's `SessionStore` lags.** The SDK's
  `SessionStore.save(participant, firstMissing)` is taken as a ceiling, not as the value to store.
  For every report it delivers (`reportSeq` > 0), the adapter records the Artio position its 35=8
  was written at: `Session.trySend`, the session online or offline (§6.3). The durable
  value advances to the highest `k` that meets all three conditions:
  - `k` ≤ the SDK's ceiling;
  - every report below `k` has a recorded position;
  - each of those positions is ≤ the **archive's recorded position** of the library's outbound
    stream (Aeron Archive's `RecordingPos` counter, read from the shared CnC).

  No SDK change is needed for this.
- **As built** (slice 4, 2026-10-25; `delivery.ReportDelivery`). Per shard and participant: each
  shard's `OrderEntrySession` gets its own `SessionStore` view, whose `save` sets the ceiling. Each
  35=8 of a report with `reportSeq` > 0 is recorded with the position `trySend` returned, the end
  of the message, a resent one included. The stored value is the lowest of the ceiling, the lowest
  `reportSeq` whose position the archive has not reached, and the floor a library reconnect left
  (below). That is the three conditions above: every report below the ceiling was either delivered,
  and so recorded, or stood in for by a completed mass status, with nothing to wait for. A report
  consumed with no 35=8 (a lost row, §8.17) has nothing to wait for either. It is stored when it
  moves, after the row writes of the reports it passes.
- **An answer with no `reportSeq` writes its row only once the archive has it** (decided
  2026-10-25). A fate's 35=8 or 35=9, a restatement (an `ORDER_STATUS` that changes what the client
  was told, or the mass status rule's), a parked cancel's answer, and a report the gateway made
  itself (`reportSeq` 0): no resend from the exchange brings these back, so a row written answered
  before its 35=8 was fsynced could, after a host crash, say the client was told what it never was.
  The row changes in memory at once; RocksDB gets it once the archive's position passes the 35=8.
  A crash before then leaves the row as it was: the fate is owed again, the cancel parked again, the
  restatement made again by the next mass status, the gateway's reject settled by the SDK. The
  client may get an answer twice, under another ExecID, never not at all. A sequenced report's row
  is written at once, since the stored `reportSeq` passes it only later (most-exchange Adapters.md
  §5).
- **Which counter** (spike §8.13(a)): the `RecordingPos` of the order-entry library's own outbound
  publication, found by that publication's Aeron session id (`RecordingPos.findCounterIdBySession`).
  The spike confirmed it counts the stream `trySend` positions are in: the counter stopped exactly
  at the last 35=8's position, and every 35=8 below it was in the segment file.
- **Finding it, from Aeron's public counters** (decided 2026-10-15; Artio 0.182 keeps the
  publication private, and reflection on it was rejected). Artio connects the library's Aeron client
  with `LibraryConfiguration.aeronContext()`, so after the connect that context's `clientId()` is
  the client's. The driver's `pub-lmt` counters (type `DRIVER_PUBLISHER_LIMIT_TYPE_ID`) carry their
  owner's client id, and the publication's session and stream id in their key (`StreamCounter`).
  The library's publication is the one owned by that client, on `outboundLibraryStream()`, that
  has a `RecordingPos`; more than one is an error. It rests on Aeron's counter layout, which the
  Aeron pin (CLAUDE.md non-negotiable 6) fixes, and fails at the lookup, not silently. Verified
  against Artio's own value, with two libraries on the stream and after a restart
  (`LibraryRecordingSpikeTest`). The proper fix is an accessor in Artio, worth proposing upstream.
- **Once per library connection.** When a library connects to an engine that has a stopped
  recording, the engine has it replace its publication with one that extends that recording, and
  the libraries need not get their own back: in one of two runs, the spike's order-entry library
  extended market-data's old recording after the restart. So the lookup runs at every library connect, a position
  is compared only with the counter of the connection it was written on, and positions written
  before a reconnect never advance the durable value.
  - **So a library reconnect freezes it until a restart** (as built, 2026-10-25): the lowest
    `reportSeq` still unconfirmed at the reconnect becomes a floor, and rows waiting for the archive
    are not written, so their answers are given again after a restart. Safe, toward a resend; how
    far it can lag is §8.18. The lookup (`artio.LibraryRecording`) is retried every millisecond
    until the counter appears, and a stopped recording confirms nothing more.
- **Off the send path.** The 35=8 goes to the client as soon as Artio's framer writes it. Only the
  stored sequence waits for the archive. A crash before the archive catches up costs a resend from
  the exchange, and the deterministic ExecID (§5.3) lets the client drop the duplicate.
- **A back-pressured 35=8 is not consumed** (decided 2026-10-09). `OrderEntryListener.onReport`
  gains a result. When Artio refuses the 35=8, the adapter returns *abort*, and the SDK leaves the
  fragment unconsumed (controlled poll, `Action.ABORT`). The ledger, the in-flight set and the gap
  check are untouched until it is accepted. This is a **change to most-exchange's `client`**
  (CLAUDE.md non-negotiable 4), to be made there before `order-entry` is built (§8.9).
  - `onSettled` fates are not sequenced. Their 35=8 is recorded on the order's row as owed, and sent
    from there on the duty cycle.

### 6.3 Reports for a logged-out session

Decided 2026-10-09, mechanism revised 2026-10-15: **sent on the session while it is offline.** The
35=8 goes into the archive like any other, numbered by Artio. At the next logon, Artio's own resend
delivers it, and §6.2 advances past it as soon as it is archived. Delivery then does not depend on
how long the exchange's report ring retains it.

- **Through Artio's own `Session`, offline** (decided 2026-10-15, with §2: MsgSeqNum is Artio's).
  `FixLibrary.requestSession` returns the session offline, with its persisted sequence numbers.
  `trySend` on it archives the message without sending it. The adapter never numbers a message
  and never writes a header. As built (slice 4, 2026-10-25; `artio.ReportSessions`):
  - **at startup**, every configured order-entry session Artio knows (`FixEngine.allSessions()`)
    is requested offline while the backend is constructed, before the acceptor binds, with
    `NO_MESSAGE_REPLAY`: no client can be logged on yet. The library then holds every session
    from the start, and a report for a client not yet back is written offline at once. A Logon
    on a session the library holds arrives as `onSessionStart`, not as a claim (§2); the
    `sequenceIndex` guard and the collision's block (§3.2, §3.3) run there as at a claim;
  - **after a disconnect**, by the session id the library had, **replaying from the last message
    received + 1, at its `sequenceIndex`** (found 2026-10-25, Artio 0.182
    `Framer.onRequestFixSession`). If the client has logged back on and the engine still owns the
    session when the request arrives, the engine hands it over online with the request's replay,
    and with `NO_MESSAGE_REPLAY` a 35=D sent before the handover would never reach the library,
    with no gap to show it. Offline there is nothing to replay: Artio answers
    `SEQUENCE_NUMBER_TOO_HIGH` and hands the session over all the same. Tested end to end: a client
    back before the library has seen its disconnect, its 35=D at the gateway.
- **After a disconnect, nothing is sent on the session until `requestSession` has it back**
  (spike, 2026-10-15). With engine-owned acceptance (§2) a disconnect returns the session to the
  engine: the library closes its `Session` and only caches it. `trySend` on that object is still
  accepted, numbered by the library and archived. But when the client logs on again, the
  reconnect of a cached session skips the library's guard (`setupAcceptorSentSequenceNumber`).
  The engine numbers the Logon from its index, and the index lags what the library has written.
  Under load, 6–8 of 10 handovers reused MsgSeqNums, and 5–8 reports that were accepted and
  archived never reached the client, with no gap to show it. §6.2 would have consumed them. So:
  - from `onDisconnect` until the library holds the session again, a report for that participant
    is refused with `ReportAction.ABORT`, the back-pressure path of §6.2. In the spike that took
    about 15 ms per disconnect. The SDK's controlled poll holds the whole report stream for
    that time, as for any back-pressure;
  - a reply other than `OK` or `SEQUENCE_NUMBER_TOO_HIGH` keeps the reports held, and the request
    is made again after 100 ms, unless the session has reached the library by the claim of §2
    meanwhile (the client logged on).
  - **Also from the moment a logged-on session stops being active** (found 2026-10-25): its
    logout under way, its disconnect not yet seen by the library. A report written then is
    numbered by the library on the session the engine is taking back. In 2 of 5 runs of a test
    that sent a report just after the client's Logout, it was archived and consumed, and the next
    Logon reused its MsgSeqNum.
  - **An offline session handed back numbered below what the library last wrote on it is not
    used** (found 2026-10-25). The engine reads an offline session's last MsgSeqNum from its
    sequence index, and before reading waits only for its own stream to be indexed, not the
    library's (`HandoverOfflineFixSession.checkLoggerUpToDate`). A message the library wrote just
    before the disconnect, even its Logout reply, can be missing from it; the next report would
    take its MsgSeqNum and the client would never see the first. Seen in 1 of 6 runs, at the
    Logout. Such a session is released to the engine and requested again after 100 ms, by when the
    index has caught up. The spike `OfflineHandoverSpikeTest` saw no MsgSeqNum reused in its three
    runs. Its holds began at `onDisconnect` and it did not check the numbering handed back, so it
    guarded against neither; under its timing they may simply not have occurred.
- **Confirmed under load** (spike `OfflineHandoverSpikeTest`, 2026-10-15). 35=8s were sent without
  pause at 5,000 a second through 10 logouts and logons, some logons immediately after the
  logout, racing the `requestSession`. That came to about 124,000 reports, most of them offline.
  Three runs: no MsgSeqNum reused, every report at the client at the MsgSeqNum Artio gave it,
  live or as a PossDup resend. A Logon arriving while the library writes offline is numbered
  after the library's last report: the guard holds for a session the library owns.
- **Confirmed** (spikes, 2026-10-15). Offline reports were numbered on from the last one sent, and
  sent nowhere. At the next Logon they came back with PossDup=Y, then a gap fill, and the live
  session carried on with no MsgSeqNum reused. After a restart, and after a crash, the offline
  `Session` came back at the MsgSeqNum the client had last received.
- **Not `SessionWriter`.** It also delivers (spike §8.13(c)), but it owns no sequence and writes no
  header. The adapter would number every message, write CompIDs and a real-clock SendingTime itself
  (a stale one is rejected, 373=10), and keep the sequence across restarts. That would be a second
  owner of MsgSeqNum, which §2 rules out.

### 6.4 Durability of Artio's archive

Decided 2026-10-09: **fsynced**, Aeron Archive `fileSyncLevel=1`, so "saved" survives a host crash.
The archive's recorded position (§6.2) then means durable.

- It is off the 35=8's send path. But a recording that falls a term-window behind flow-controls
  the IPC publication it records, and so back-pressures the library. The sustained rate at which
  that happens is measured, not assumed (`perf-claim`).
- The level is a property, so a test rig can run without fsync. Production runs fsynced.
- **Confirmed** (spikes §8.13(a)–(b), 2026-10-15):
  - **`RecordingPos` passes no unsynced byte.** In Aeron 1.53.0, `RecordingWriter.onBlock` writes,
    then calls `force(false)` at level ≥ 1, before it returns. `RecordingSession` sets the counter
    only after that. Segment files are pre-sized (`setLength`), and their directory is synced when
    a segment is created, so a data-only sync is enough. This is from the source: a test cannot
    watch an fsync.
  - **Archived reports are resent after a crash:** a SIGKILL; a SIGKILL with Artio's index files
    put back as they were before the reports (a host crash); the same for a library whose first
    messages they were; and a crash seconds after a restart. In each case the Logon followed the
    reports and every report came back.
- **Configuration:** Aeron 1.53 refuses `fileSyncLevel` 1 with a catalog synced less, so
  `catalogFileSyncLevel=1` too.
- **A restart after a crash waits ~10 s.** The archive's mark file carries a liveness timestamp, as
  on the exchange's nodes.
- **Artio's indexes lag the archive, and are rebuilt at start.** One SIGKILL left the sequence index
  at 256 against an archive at 1728. Artio's sequence index is written down and fsynced at its
  first record, every `indexFileStateFlushTimeoutInMs` (10 s), at a term roll, and at a clean close.
  At start, `Indexer.catchIndexUp` replays each recording **the written index names**, from its
  indexed position to the recording's stop position. A restarted library extends its predecessor's
  recording, so that recording is already named.
  - **Residual risk, not reproduced:** a recording new to the index, opened in an engine that has
    already flushed once, then a crash within 10 s. With the adapter's fixed two libraries, a new
    recording appears only on a first start, where the first record is flushed at once.
- **Durability is claimed for Linux.** On macOS, `fsync` does not flush the drive's cache without
  `F_FULLFSYNC`. Whether the JDK's `force` uses it was not checked; this laptop proves ordering,
  not power-loss durability.

## 7. Deployment and performance

- **Threads:**
  - the Artio engine's framer and archiver (Artio's);
  - the order-entry library thread (§3.5);
  - the market-data library thread (§4);
  - the control and metrics server (§9), which never touches the library threads' state except
    through published snapshots.

  Library threads busy-spin by default, and the idle strategy is configurable.
- **Allocation-free in the steady state** on both library threads: flyweights over FIX bytes, no
  `String` on the order path, and `PriceCodec` conversions without allocation (§8.11). Recording a
  latency allocates nothing (§9.2).
- *To specify:*
  - ~~one Aeron media driver for Artio and the SDK's links, or two (§8.6)~~ **one, the embedded
    driver** (decided 2026-10-25, §3.5). The Aeron **version** is the exchange's, pinned (R§2);
  - gateway placement (independent or co-located, most-exchange Design.md §7);
  - the native image.

## 8. Open items

1. ~~FIX version~~ **FIX 4.4** (2026-10-04). ~~Custom tags~~ **2362 and 2964** (2026-10-09, §5.4).
   ~~The exact 2964 code set~~ **1 and 2; FIX Latest's 3 is a session reject** (2026-10-20, §5.4).
2. ~~Logon authentication and the logon → participant mapping~~ **One session, one participant**
   (2026-10-04, §2). ~~Session roles~~ **order-entry or market-data** (2026-10-09).
   ~~Configuration format; where the password lives~~ **Properties files; a SHA-256 digest only**
   (2026-10-15, §2). Still open: the session schedule, and whether it follows the exchange's
   calendar.
3. ~~ClOrdID ↔ `clOrdId` mapping and its store~~ **Derived `clOrdId` (§3.2); RocksDB plus in-memory
   (§6.1)** (2026-10-09).
4. ~~35=G~~ **Rejected** (2026-10-09, §3.3).
5. ~~Threading~~ **Decided** (2026-10-09, §3.5, §4). That one Artio engine can hand order-entry
   sessions to one library and market-data sessions to another is a spike (§8.13).
6. ~~Media driver topology (§7)~~ **One driver, the adapter's embedded one** (decided 2026-10-25,
   §3.5).
7. ~~Market data subscription model~~ **The minimum model of §4** (2026-10-09).
8. Trade capture (35=AE) and historical market data wait on the exchange (drop copy, TimescaleDB).
9. ~~The store lags the ledger~~ **Decided (§6.2):** a lagging `SessionStore` in the adapter, with no
   SDK change for the lag. ~~Two SDK changes~~ **Made in most-exchange's `client` 2026-10-15**
   (its Adapters.md §2, §5), published:
   - `onReport` returns a `ReportAction`; `ABORT` leaves the report unconsumed, uncounted, and ahead
     of everything behind it on its stream (§6.2);
   - `restoreInFlight(participantId, clOrdId)` before the first `doWork`. It also owes a fence for
     the participant whatever the stored sequence says, so the opening mass status cannot skip the
     request's report (§3.4). The adapter must store a row's report before a first-missing past it.
10. ~~The FIX representation of `AMBIGUOUS`~~ **The last status restated, with OrdStatusUnknown=Y**
    (2026-10-09, §3.4). ~~The tag's number~~ **5001** (2026-10-20, §5.4).
11. ~~`PriceCodec` without allocation~~ **Made and published 2026-10-15:**
    `PriceCodec.fromDecimal(mantissa, scale)` → `int64`, or `PriceCodec.INVALID` when not exact at 8
    decimals or out of range; for prices and quantities alike. Back to FIX needs nothing:
    `DecimalFloat.set(value, 8)`.
12. ~~The current trading date~~ **The adapter's own calendar** (2026-10-09, §3.6). It
    knowingly accepts the silent "ahead" case. Revisit if the exchange can resolve a day sentinel
    at entry (R§7).
13. ~~**Artio spikes**~~ **Done 2026-10-15**, against 0.182 and a real Artio initiator, in the
    `spikes` module (`./gradlew :spikes:test`, ~5 min, not part of `build`). §6.2–§6.4 stand.
    - (a) `RecordingPos` counts the `trySend` stream, and passes no unsynced byte (§6.2, §6.4);
    - (b) archived reports are resent after a process crash and after a simulated host crash (§6.4);
    - (c) reports for a logged-out client are resent, and the next Logon follows them: through
      the offline `Session`, also after a restart and a crash (§6.3). Logons above the expected
      MsgSeqNum get a resend request, below are refused, 141=Y resets (§2);
    - (d) one engine routes sessions to two libraries by role, and the claim must replay (§2).
    - (e) a client logging on while reports go to its offline session gets every one, at its
      MsgSeqNum, under load, provided the library has requested the session back after the
      disconnect; sending on the session the disconnect took reuses MsgSeqNums and loses reports
      (§6.3).

    **Left open by them:**
    - ~~Finding the library's `RecordingPos`~~ **From Aeron's public counters**, by the library's
      Aeron client id, once per library connection (§6.2, decided 2026-10-15). Not reflection;
      not matching by stream or position, which two libraries on one stream make ambiguous.
    - ~~The first offline MsgSeqNum after a restart~~ **Artio's**, from its persisted session
      (§2, §6.3, decided 2026-10-15).
    - ~~A Logon with 141=Y discards the resend~~ **The client's to recover, with 35=AF** (§2,
      decided 2026-10-15).
14. ~~Unsolicited status after a restart~~ **Only when it changes what the client was told**
    (2026-10-09, §3.3).
15. ~~**An id collision**~~ **Rejected, then a Logout; Logons refused until an operator resets the
    session's sequence** (decided 2026-10-25, §3.3). Found 2026-10-25 (§3.2, §6.1): Artio's state
    wiped at `sequenceIndex` 0, and a client starting again from MsgSeqNum 1, derive `clOrdId`s that
    rows already hold. The store detects it on insert and writes nothing. Only rows still held
    collide, which covers every open order, because retention keeps those. A wipe of all of Artio's
    state is also refused at startup (§3.2).
16. **The SDK's `DirectoryClient` allocates on every broadcast cycle** (found 2026-10-25, slice 2b).
    It stages and commits the whole table each time, new maps, `String`s and records, even when the
    version has not changed. The broadcast repeats every 2–5 s (most-exchange
    `discovery.intervalMs`), and the order-entry thread polls it (§3.5), so that thread is not
    allocation-free in the steady state (§7). The adapter's own index is rebuilt only on a new
    version. The fix belongs in most-exchange's `client` (CLAUDE.md non-negotiable 4): skip staging a
    broadcast whose version is the live one. Not yet made.
    - **And `OrderEntrySession` allocates per request** (read in its source 2026-10-25, not
      measured): `send` puts a new `InFlight` object in the participant's map for every order and
      cancel. The inbound path's 0 bytes per order (slice 2a) was measured against a model of the
      SDK, so the whole path is not allocation-free until `client` pools them. The same change, in
      most-exchange.
17. **Rows lost in a host crash are not yet recovered** (decided 2026-10-25 to defer, slice 3). §6.1
    reads a lost row's 35=D back from Artio's archive. Until that is built, a report for a
    `clOrdId` with no row is logged, counted and consumed (§5.3), so the client misses that order's
    reports. ~~The intended shape: at startup, before the SDK starts, the archive's inbound 35=D and
    35=F past the store's last row are run through the inbound path without sending~~ **The shape,
    decided 2026-10-26** (R§12), each the recommendation; read in Artio 0.182's source and
    **verified** by spike (f), `RecoveryReplaySpikeTest` (2026-10-26): after a clean restart, an
    offline `requestSession` from (0, D3) was answered `OK`, the session was acquired *before* its
    replay arrived, then D3, D4, D5 and the client's Logout came, each `isValid()` true, at its own
    MsgSeqNum. From (0, A2) across a 141=Y reset came A2, A3, the Logout at index 0, then the Logon,
    B1, B2 and the Logout at index 1. Session messages are replayed too, so the handler's filter
    (non-negotiable 10) applies. The session is then the offline session of §6.3: written on, and
    the client's next Logon follows it. What a replayed message looks like to the handler is what a
    live one does; it is told apart by its session being offline, which no live message can be:
    - **Read back by the startup `requestSession` replay**, not `FixArchiveScanner` and not lazily per
      report. The offline request each participant's session gets before the bind (§6.3) replays
      from the store's last row for that participant, (`sequenceIndex`, MsgSeqNum) + 1, instead of
      `NO_MESSAGE_REPLAY`. Artio's engine replays archived inbound messages to an offline session
      too (`HandoverOfflineFixSession` → `CatchupReplayer`), across sequence indices, as
      `CATCHUP_REPLAY`. It sees inbound messages only, not what the adapter answered.
    - **A replayed request is never sent and never answered.** The engine does not check `clOrdId`
      uniqueness (most-exchange Adapters.md §2), so sending it again would make a second order.
    - **A replayed request that passes validation and has no row is rebuilt in flight**: an order
      as sent and not acknowledged, a cancel as *sent*, then `restoreInFlight`, so the fence settles
      it (§3.4). A sequenced one gets its reports from the resend: the durable `reportSeq` predates
      them, because RocksDB recovered a prefix (§6.1). A never-sequenced one gets its reject. A
      request first answered here, by a reject that needed the directory or the gateway ("exchange
      unavailable", "unknown security"), can so get a second reject with the same ClOrdID: accepted.
      It is never a reject of an order that may be live (non-negotiable 1).
    - **The directory is not there at startup**, so recovery is not the inbound path as it runs live.
      What is decided from the message alone (`validate()`, the §5.1 field checks) and from the
      store (a duplicate ClOrdID, the block, a cancel's order) is decided again, the same way. The
      security is kept as the message names it, and its shard is resolved once the directory
      arrives, before that shard's `restoreInFlight`.
18. **A library reconnect freezes the durable `reportSeq` until a restart** (as built 2026-10-25,
    §6.2). Positions taken on the old connection are never confirmed, so its lowest unconfirmed
    `reportSeq` stays a floor, and the rows waiting for the archive are answered again after the
    restart. Safe, but a long-running process after a reconnect resends more at its next restart, and
    keeps more rows. When the new connection extends the same recording, the old positions are in the
    same position space and could be confirmed by the new counter; not verified, so not relied on.
    Neither the reconnect nor the sessions requested back after it (`ReportSessions`) is tested end
    to end: nothing in the rig makes the library lose the engine.
19. **A client back within the engine's index lag after a dropped connection** (residual, §6.3).
    If the client logs on again before the library's request reaches the engine, and before the
    engine's sequence index has the library's last messages, the engine numbers the Logon from the
    lagging index: a MsgSeqNum the library already used, and the report that had it is never
    resent, although it was archived and consumed. The hold and the stale-handover check cannot see
    it: the session comes back online. It is detected, logged and counted when the session reaches
    the library (`ReportSessions.reusedSequence`), not prevented. Not reproduced; the window is the
    indexer's lag after a disconnect, against a client's reconnect and Logon round trip.
20. **A 35=D the exchange has, lost from both the store and the archive** (found 2026-10-26 in
    Artio 0.182's source; **confirmed** by spike (g), `RecoveryReplaySpikeTest`; R§12). R§4 assumes
    the archive holds every 35=D whose row a host crash can lose. But the library receives an inbound
    message from the engine's IPC stream, and nothing waits for the archive to fsync it first (Artio
    waits for its recordings only at close, `RecordingCoordinator.awaitRecordingsCompletion`). In the
    spike, at `fileSyncLevel=1`, with the library polling as the adapter's does, **all 500 35=Ds were
    handled before the archive's `RecordingPos` had passed them**, by up to ~40 KB, on this laptop's
    weaker `fsync` (§6.4). So every order is sent before its 35=D is durable.
    **Decided 2026-10-26** (the recommendation): **a request is sent only once the archive has
    recorded its 35=D/F** (§6.1, "When"). R§4 then holds as written: a host crash can lose a row only
    of a request whose 35=D the archive has, which recovery (§8.17) rebuilds, or of one never sent,
    which the client's resend may send. Rejected: fsyncing RocksDB per order (blocks the library
    thread, every session with it); the exchange checking `clOrdId` (a contract change in the
    matching path); fencing a PossDup with no row (an SDK change, and a lawful resend of a request
    never sent would be rejected). The cost is the archive's fsync on every order, to be measured. A host crash can then take the 35=D from
    both, after its order went to the exchange. The client sees a gap at its next Logon and resends
    the 35=D as PossDup; the store has no row, so the adapter sends it again, and the exchange makes
    a second order with the same `clOrdId`. **Decided 2026-10-26:** a spike in the recovery slice
    first. The fix is designed once the spike confirms it, because every candidate changes a failure
    mode or adds latency to every order.

## 9. Control and metrics

One JDK `HttpServer` (no web framework, native-image friendly), on a management address. Decided
2026-10-09 as written here. The endpoints carry over from the FIX↔Kafka gateway (Status §4).

### 9.1 Control

Bearer-guarded, with the token read from a file. Secrets are redacted from every response.

| Endpoint | Does |
| --- | --- |
| `GET /status` | Engine state; per session: logged on, CompIDs, sequence numbers in and out, `sequenceIndex`; per shard: the gateway in use, ready or not, switches and fences; per participant: the SDK's first missing `reportSeq` and the durable one (their gap is the archive lag), and its ambiguous orders; the trading date and whether it is overridden |
| `POST /start` | Accept logons (bind the acceptor) |
| `POST /stop` | Stop accepting logons, log every session out gracefully, then unbind. Orders already sent are still settled and reported |
| `POST /sessions/{id}/reset` | Reset a session's sequence numbers. Refused while the session is logged on |
| `POST /reset` | The same for every session: the end-of-day reset. Refused while any is logged on |
| `POST /trading-date` | Overrides the calendar's trading date (§3.6) until the next roll. Logged, and shown in `/status` |

### 9.2 Metrics: `GET /metrics`

Prometheus text format, written by hand with no metrics library. Unauthenticated, on the
management address only.

- **Counters:** messages in and out by MsgType; rejects by reason, with `ORDER_EXPIRED` on day
  orders separately (§3.6); fates by kind and participant; back-pressure
  aborts in both directions; gateway switches; fences; duplicate reports; store writes.
- **Gauges:** sessions logged on; open orders; the durable `reportSeq` lag per participant, in
  reports and in archive bytes.
- **Internal latencies**, as HdrHistograms recorded on the library thread. A `Recorder` swaps
  interval histograms with no allocation, and they are exposed as quantiles (p50, p90, p99, p99.9,
  max) and a count. The stages:

  | Stage | From → to |
  | --- | --- |
  | `fix_in` | Artio's receive timestamp → the backend's handler |
  | `order_out` | the handler → `newOrder`/`cancel` returned SENT |
  | `store_write` | the RocksDB put |
  | `exchange_rtt` | SENT → the first report, by ExecType |
  | `report_out` | the report's arrival → its 35=8 `trySend` returned |
  | `tick_to_trade` | Artio's receive timestamp of the 35=D → its first 35=8 written |
  | `archive_lag` | the 35=8 written → the archive's recorded position past it |

- **A trace ring** (carried over): per-message stage events on a ring, drained to a file
  off the library thread, for §10.2 to read beside the sender's own log.

## 10. Test tooling (`tools`)

Decided 2026-10-09 as written here.

### 10.1 The FIX test sender

- An Artio **initiator**, built from the same dictionary and engine code as the adapter. One or more
  sessions.
- **Paced:** a target rate, or a `<delayMicros> <count>` schedule. Every message has a **scheduled
  send time**, so response time is measured from the schedule and not only from the actual send.
  Coordinated omission is never hidden (`perf-claim`).
- **Order mix:** passive new orders, cancels of them, and crossing orders that trade, in configured
  proportions, so every report type is exercised.
- A busy-spinning send thread, which may be pinned. It records, per message: `clOrdId`, the
  scheduled and actual send time, and each 35=8's arrival with its ExecType and OrdStatus. These go
  to a binary, memory-mapped log, so the sender does no formatting while it runs.

### 10.2 The latency report

- Reads the sender's log, and optionally the adapter's trace ring (§9.2).
- Per outcome: new ack, partial fill, fill, cancel ack, reject, cancel reject. For each, both
  **service time** (from the actual send) and **response time** (from the scheduled send).
- **Output:** one self-contained HTML file, with no external requests, containing:
  - the HDR percentile distribution, on a log scale;
  - the CDF;
  - latency against time, as a scatter per outcome, with throughput beside it;
  - a table of percentiles.

  Also `.hgrm` and CSV, and a comparison of two runs.
- Every report carries its provenance header: commit, machine, JVM or native, rate offered and
  achieved, and exchange configuration. That is the row `docs/Measurements.md` needs.
