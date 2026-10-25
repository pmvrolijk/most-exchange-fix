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
  the next logon.
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
- `BACK_PRESSURED` becomes `Action.ABORT`;
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
- **Guard:** the store keeps the highest `sequenceIndex` seen per session. If Artio starts with a
  lower one (its state was wiped), the adapter refuses to start. Derived ids would otherwise repeat.
- **FIX ClOrdID (11)** is unique per session for the trading day, and for as long as the order is
  open. A new 35=D reusing a known one is rejected with OrdRejReason 6, *duplicate order*.
- **`exchangeOrderId`** is kept from the first report (Adapters.md §2) and sent back as OrderID (37).

### 3.3 Messages

| In | Handling |
| --- | --- |
| 35=D | Validated (§5.1), written to the order store, then `newOrder`. `BACK_PRESSURED` → `Action.ABORT` |
| 35=D, PossDup=Y | If its ClOrdID is known, it is a duplicate and is dropped. Otherwise it is processed as new |
| 35=F | Looked up by OrigClOrdID (41) in the store, then `cancel` with the stored `exchangeOrderId`, security and side. Unknown order → 35=9, CxlRejReason 1 |
| 35=F before the order's first report | **Parked** (decided 2026-10-09): written onto the order's row, so it survives a restart, and sent when the first report brings `exchangeOrderId`. If that report is terminal, 35=9 with CxlRejReason 0, *too late* |
| 35=G | **Rejected:** 35=9 with CxlRejResponseTo 2 and CxlRejReason 2, Text "replace not supported" (decided 2026-10-09: the exchange has no replace, and the adapter does minimum) |
| 35=H | Answered from the order store with a 35=8, ExecType I. The exchange has no per-order status query |
| 35=AF | Answered from the order store: one 35=8 ExecType I per open order, with MassStatusReqID, TotNumReports and LastRptRequested. The store is exact, because it is reconciled by the SDK's own mass status at startup and after each reconnect |

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
| 103 OrdRejReason, 58 Text | From `RejectReason`: UNKNOWN_SECURITY → 1, MARKET_CLOSED → 2, BOOK_CAPACITY → 99… Text is always the enum name |
| 60 TransactTime | Adapter time at the report's arrival. The report carries no timestamp |
| OrdStatusUnknown | Y only on an ambiguous order (§3.4). Absent otherwise |

- A REJECTED report answering a cancel becomes **35=9**, with CxlRejResponseTo 1 and CxlRejReason
  from `RejectReason`: UNKNOWN_ORDER → 1, MARKET_CLOSED → 2.
- `enrichment = UNKNOWN` means `origQty` and `cumQty` cannot be believed. The report is sent with
  the quantities the store holds, and counted.

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
| Per order: FIX ClOrdID ↔ `clOrdId` ↔ `exchangeOrderId`, and what the client was told | Answering, cancelling, uniqueness for the day, 35=H/AF | **RocksDB** plus an in-memory working set (§6.1, decided 2026-10-09) |
| Per participant: first `reportSeq` not yet durably delivered | Resume with a resend after a restart | RocksDB, **advanced only behind the archive** (§6.2) |
| Per session: highest `sequenceIndex` | The derived-id guard (§3.2) | RocksDB |

### 6.1 The order store

Decided 2026-10-09: **RocksDB, with the working set in memory.**

- **In memory:** Agrona maps on the order-entry thread, keyed by `clOrdId` and by FIX ClOrdID. The
  ClOrdID is hashed from the message's bytes, so a lookup allocates nothing. Every read is served
  from here.
- **RocksDB:** a row per order, written on the library thread. The WAL is **not fsynced**, so a write
  survives a process crash but not a host crash. That is enough, because everything a host crash
  can lose from RocksDB can be rebuilt from Artio's fsynced archive, or errs toward re-delivery
  (R§4).
  - **When:** the row is written **before** `newOrder`. If the send is back-pressured, the
    redelivered message writes the same row again, which is harmless. The row is updated on every
    report, on a parked cancel, and on a fate.
  - **Recovery mode: point-in-time** (`WALRecoveryMode.kPointInTimeRecovery`). After a crash,
    RocksDB recovers a **prefix** of its writes, never a set with holes. The durable `reportSeq`
    (§6.2) is written after the row updates it covers, so if it survived, they did too. The
    argument that the WAL need not be fsynced depends on this (R§4). It must stay configured, and a
    test must hold it.
  - **Column families:**
    - `orders`: (`sessionId`, `clOrdId`) → a versioned row: FIX ClOrdID, `exchangeOrderId`,
      security, side, price, `origQty`, `cumQty`, cumulative notional, `expireDate`, SMP, the
      status last sent, the last `reportSeq`, a parked cancel, the ambiguous flag, and room for
      §5.5's fields;
    - `meta`: per participant, the durable first-missing `reportSeq`; per session, the highest
      `sequenceIndex`.
  - **Retention:** terminal orders until the end of their trading day, for ClOrdID uniqueness.
    Open GTC and GTD orders until terminal, across days.
- **At startup:** load open orders and today's terminal ones into memory. Hand the rows with no
  report to the SDK with `restoreInFlight` (§3.4). Then the SDK's resend and mass status reconcile
  them. If a report names a `clOrdId` the store does not have (its row was lost in a
  host crash), its 35=D is read back from Artio's archive by (`sequenceIndex`, MsgSeqNum).

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
  `FixLibrary.requestSession(sessionId, NO_MESSAGE_REPLAY, …)` returns the session offline, with
  its persisted sequence numbers: after a logout, by the session id the library had; after an
  adapter restart, found by its CompIDs (`FixEngine.lookupSessionId`). `trySend` on it archives the
  message without sending it. The adapter never numbers a message and never writes a header.
- **After a disconnect, nothing is sent on the session until `requestSession` has it back**
  (spike, 2026-10-15). With engine-owned acceptance (§2) a disconnect returns the session to the
  engine: the library closes its `Session` and only caches it. `trySend` on that object is still
  accepted, numbered by the library and archived. But when the client logs on again, the
  reconnect of a cached session skips the library's guard (`setupAcceptorSentSequenceNumber`).
  The engine numbers the Logon from its index, and the index lags what the library has written.
  Under load, 6–8 of 10 handovers reused MsgSeqNums, and 5–8 reports that were accepted and
  archived never reached the client, with no gap to show it. §6.2 would have consumed them. So:
  - from `onDisconnect` until `requestSession` replies `OK`, a report for that participant is
    refused with `ReportAction.ABORT`, the back-pressure path of §6.2. In the spike that took
    about 15 ms per disconnect. The SDK's controlled poll holds the whole report stream for
    that time, as for any back-pressure;
  - a reply other than `OK` keeps the reports held. If the client logged on meanwhile, the
    session reaches the library by the claim of §2 instead.
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
  - one Aeron media driver for Artio and the SDK's links, or two (§8.6). The Aeron **version** is
    the exchange's, pinned (R§2);
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
6. Media driver topology (§7).
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
