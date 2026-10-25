# Status

Where the adapter stands, what is open, and what to do next. **This is the session entry point** —
read this, not the handover. [`Handover.md`](Handover.md) is the archive of work records.

Last updated 2026-10-25. **`fix-session` is implemented: the acceptor (2026-10-15) and the FIX
dictionary (2026-10-20). `order-entry` has its order store and its inbound logic (2026-10-25,
slices 1 and 2a of §3 item 5), not yet wired to Artio or the SDK; the rest of it and `market-data`
are still to build.** 2026-10-20: the dictionary is FIX 4.4's own,
cut to the handled message types, and Artio was found not to validate application messages, so the
backend must (CLAUDE.md non-negotiable 9, Design §5.4, Rationale §8).

Decided 2026-10-25 (Design §3.2, §6.1), each the recommendation:
- **The store is keyed by `participantId`,** not Artio's session id, which a wipe of Artio's state
  can change, and the `sequenceIndex` guard with it.
- **A cancel has its own row,** pointing at its order. Parked is a state of that row.
- **An ambiguous order is retained like an open one** until a report or an operator settles it.
- **An id collision** (§8.15) is rejected, then the session is logged out, and its Logons are
  refused until an operator resets its sequence. A configured session Artio does not know, while
  the store holds rows for its participant, refuses the start.
- **`NOT_READY` and `NO_GATEWAY` reject** a 35=D or 35=F, "exchange unavailable". They are not
  held: a lasting `ABORT` stalls every order-entry session, heartbeats included.
- **A second cancel while one is pending** is a 35=9, CxlRejReason 3.

Decided 2026-10-09 (Design.md, Rationale §3–§7, CLAUDE.md non-negotiable 1 extended, 7–8 new):
- **Goals:** low latency, stateful, guaranteed delivery of execution reports, the minimum that
  supports every exchange feature. Client fields such as Account (1) wait until the exchange can
  carry them in its `ColdField` (Design §5.5).
- **The SDK comes from most-exchange's public GitHub** (GitHub Packages). The build reads it since
  2026-10-15 (§3, item 1).
- **Order store: RocksDB plus in-memory maps**, WAL not fsynced (Design §6.1).
- **The exchange `clOrdId` is derived:** `(sequenceIndex << 32) | MsgSeqNum` (Design §3.2).
- **A `reportSeq` is consumed only once its 35=8 is fsynced in Artio's archive.** This is a lagging
  `SessionStore` in the adapter (Design §6.2). The archive runs at `fileSyncLevel=1` (§6.4).
- **A back-pressured 35=8 aborts the report.** `onReport` gains a result: a change to most-exchange's
  `client` (Design §8.9).
- **Reports for a logged-out session** go through Artio's `Session`, offline (Design §6.3; revised
  2026-10-15 from `SessionWriter`: MsgSeqNum is Artio's alone, §2).
- **35=G is rejected** (Design §3.3).
- **An `AMBIGUOUS` order is restated unchanged, with OrdStatusUnknown=Y.** Never a reject or another
  terminal state (Design §3.4).
- **The trading date for day orders is the adapter's own calendar** (Design §3.6). The silent case,
  an adapter ahead of the engine, is accepted knowingly. It is mitigated by `POST /trading-date`.
- **Reviewed and decided:**
  - the 35=8 mapping, with ExecID = `reportSeq`;
  - `NEVER_SEQUENCED` → reject;
  - an early cancel is parked on the row;
  - an acknowledged order missing from a completed mass status is `AMBIGUOUS`;
  - the threading and session roles;
  - the market data model;
  - control and metrics;
  - the test tooling.
- **Only the configuration format is still proposed** (Design §2).

Decided 2026-10-04:
- **Licence:** AGPL-3.0-or-later, like the exchange.
- **FIX 4.4.**
- **One FIX session, one participant.** Each session's CompIDs, username/password and
  `participantId` are configured (Design.md §2).

In place:
- Three modules: `fix-session`, `order-entry` and `market-data`.
- Artio 0.182, pinned to the exchange's Aeron 1.53.0 and guarded by a test.
- The docs and skills in most-exchange's way of working.

There is no remote yet.

---

## 1. Where things stand

| | |
| --- | --- |
| Branch | `master`; the user commits |
| Licence | AGPL-3.0-or-later (`LICENSE.md`); the SDK it links is Apache-2.0 |
| Modules | 3 — `fix-session`, `order-entry`, `market-data`; packages `nl.lamia.most.exchange.fixadapter.*` |
| Tests | `fix-session` 34, plus 1 in its own JVM (`testAcceptorGuard`); `order-entry` 51 (the store 18, the inbound path 32); `market-data` 1. `./gradlew build` green 2026-10-25. The 15 spikes (`:spikes:test`) last ran 2026-10-15 |
| Depends on | most-exchange `client` + `sbe` 0.1.0-SNAPSHOT (`20261015.110512`) from GitHub Packages (`pmvrolijk/most-exchange`), Artio 0.182, rocksdbjni 11.8.1 |
| Specification | [`Design.md`](Design.md) — §1–§10 decided, except the configuration format (proposed); §8 is the open list |
| Remote | none yet. |

```sh
./gradlew build                              # SDK from GitHub Packages (githubUser + githubPackagesToken)
```

## 2. Decisions to take before code

Each changes what gets built (`decision-fork`):

1. ~~**This repository's licence**~~ **AGPL-3.0-or-later** (2026-10-04).
2. ~~FIX version; logon → participant~~ **FIX 4.4; one session, one participant** (2026-10-04,
   Design.md §2).
3. ~~Custom tags; the ClOrdID store; 35=G~~ **2362/2964; RocksDB with a derived `clOrdId`;
   reject** (2026-10-09).
4. Still open, from Design.md §8:
   - ~~`AMBIGUOUS` in FIX; the trading date~~ **restated with a flag; the adapter's calendar**
     (2026-10-09);
   - the configuration format and where the password lives (§8.2, proposed);
   - the session schedule, and whether it follows the exchange's calendar (§8.2);
   - the media driver topology (§8.6);
   - ~~the `OrdStatusUnknown` tag number and the exact 2964 code set~~ **5001; 1 and 2 only, 3 a
     session reject** (2026-10-20, §5.4).
   - ~~an id collision~~ **rejected, Logout, Logons refused until reset** (2026-10-25, §8.15).
   - ~~Unverified assumptions the design rests on~~ **All four hold** (spikes, 2026-10-15, §8.13).
     They left three decisions, all made:
     - ~~how the adapter finds its library's `RecordingPos`~~ **from Aeron's public counters**, by
       the library's Aeron client id, at every library connect (decided 2026-10-15, Design §6.2);
     - ~~the first offline MsgSeqNum after a restart~~ **Artio's** (decided 2026-10-15: MsgSeqNum
       is the FIX session's, persisted; a Logon matches or gets a resend; reset only on 141=Y);
     - ~~what a client that logs on with 141=Y is owed~~ **nothing: it sends 35=AF** (decided
       2026-10-15, Design §2).

## 3. To do next

In order. The SDK changes and the spikes come before code, because they decide whether
`order-entry`'s persistence design stands. `fix-session` does not depend on them, so it may run in
parallel with item 2.

1. ~~**Switch the build to GitHub Packages**~~ **Done 2026-10-15.** `mostSdk.url` points at
   `maven.pkg.github.com/pmvrolijk/most-exchange`; `githubUser` + `githubPackagesToken` (classic,
   `read:packages`) in `~/.gradle/gradle.properties`, `GITHUB_ACTOR`/`GITHUB_TOKEN` in CI. Verified:
   `--refresh-dependencies` fetched the metadata from GitHub, 4 tests pass. The GitLab CA step is gone.
2. ~~**Three changes to most-exchange's `client`**~~ **Done 2026-10-15**: most-exchange `1ac8017`,
   published to GitHub Packages as `0.1.0-SNAPSHOT` `20261015.110512` and pulled here
   (Design §8.9, §8.11; most-exchange Adapters.md §2, §5):
   - `onReport` returns `ReportAction`; `ABORT` leaves the report on its stream (controlled poll);
   - `restoreInFlight(participantId, clOrdId)` before the first `doWork`, which also owes a fence;
   - `PriceCodec.fromDecimal(mantissa, scale)`, allocation-free, `INVALID` when not exact.

   Verified there: the client suite with seven new spec-first tests, two mutations each caught,
   `./gradlew build` and `run-e2e.sh` green.
3. ~~**Artio spikes**~~ **Done 2026-10-15** (`415fe3a`): the `spikes` module, 15 tests against a
   real Artio engine, library and initiator, with SIGKILLed child JVMs for the crashes
   (`./gradlew :spikes:test`, ~5 min, kept out of `build`). §6.2–§6.4 stand. What they found is in
   Design §2, §6.2–§6.4 and §8.13. What changes the build:
   - a library's claim **must replay from the Logon + 1**, or a 35=D sent before the claim is lost
     with no gap to show it;
   - `SessionPersistenceStrategy.alwaysPersistent()`, because Artio resets sequences by default;
   - `catalogFileSyncLevel=1` beside `fileSyncLevel=1`;
   - reports for a logged-out client go through the offline `Session`, never a `SessionWriter`;
   - the library's `RecordingPos` is found from Aeron's public counters at every library connect,
     by its Aeron client id; a restart can hand a library another library's old recording;
   - **after a disconnect, reports are held (ABORT) until `requestSession` has the session back
     offline**: sent on the session the disconnect took, they reused MsgSeqNums and were lost
     under load (Design §6.3, spike `OfflineHandoverSpikeTest`).
4. **`fix-session`:** an Artio FIX 4.4 acceptor that accepts a Logon only from a configured session
   (CompIDs plus 553/554), with the engine's archive and logs on durable paths. Then the
   dictionary's code generation. Tested spec-first against a real Artio initiator.
   - ~~**The acceptor**~~ **Done 2026-10-15** (`36081b8`; Design §2, "Configuration and
     logon"): `FixNodeConfig` and `ConfiguredSessions` (properties files, a SHA-256 digest per
     password), `LogonAuthentication`, `FixAcceptor` (an embedded archiving driver plus the engine)
     and `RoleClaims`. 22 tests. The Logon tests use a real initiator, and the refusals are read
     byte for byte off a raw socket. Five mutations, each caught: password unchecked, any session's
     username, claim without replay, role ignored, transient sequence numbers.
   - ~~**The dictionary**~~ **Done 2026-10-20** (`bf23666`; Design §5.4, Rationale §8):
     `fix44-most.xml`, cut from the FIX Trading Community's FIX 4.4 repository (Apache-2.0) by
     `extract_fix44.py`, and generated into `...fixadapter.codecs` by the Gradle task
     `generateFixCodecs`. Artio's session codecs are off `fix-session`'s classpath. 2362 String,
     2964 1 and 2 only, OrdStatusUnknown is 5001. The acceptor refuses to start without
     `-Dfix.codecs.reject_unknown_field=true`. 13 new tests. Four mutations, each caught: 2964=3
     let in, standard 35=D fields dropped, the guard not called, the dictionary not set.
   - **Found:** Artio checks only an application message's MsgType and header. The backend must
     `validate()` every message and answer a failure with 35=3 (CLAUDE.md non-negotiable 9). And a
     Logon with an unknown tag but good credentials gets a 35=3 *in* the session's sequence: both
     sides' MsgSeqNum 1 is used, so the client's next Logon is 2.
   - **Left for later:** the session schedule (§8.2); market data messages join the dictionary
     with item 7.
5. **`order-entry`**, in slices, each tested spec-first. The store first, because every other
   slice writes to it:
   1. ~~**The order store**~~ **Done 2026-10-25** (`9304363`; Design §6.1, §3.2):
      `...orderentry.store`: `OrderStore` (RocksDB, `orders` and `meta` column families in one
      database, WAL unsynced, point-in-time recovery), `RequestRow` (pooled), `RowCodec`
      (version 1) and `ClOrdIdHasher`. 16 tests. Among them:
      - a WAL torn in the middle comes back as a prefix;
      - 10,000 lookups and updates allocate 0 bytes, measured with `ThreadMXBean`;
      - a forced hash collision is never a false duplicate.

      13 mutations, each caught: two weaker WAL recovery modes, the ClOrdID byte compare skipped,
      four retention conditions, settled requests restored in flight, a collision reported as
      *same*, the guard off by one, an unknown row version read, an insert not written, one
      ClOrdID namespace for all participants. Found: RocksDB drops the rest of a 32 KiB WAL block
      after a bad checksum in every mode, so the WAL test had to write past it before it could tell
      the modes apart.
      - **Not done here:** retention runs at `load` only. Dropping at the trading-date roll waits
        for the calendar (§3.6). Nothing reads Artio's `sequenceIndex` into the guard yet: that is
        the wiring, slice 2. `fix.*` has no store path yet: it joins the node file with slice 2.
   2. **Inbound**, in two parts:
      - ~~**2a, the logic**~~ **Done 2026-10-25**, uncommitted (Design §3.3, §5.1, §5.2):
        `...orderentry.inbound`: `InboundOrders` (a FIX message → a request on its way, or an
        answer given here), `FixParse`, and the ports it is wired through, `OrderVenue`,
        `SecurityLookup` and `ClientReplies`. The store gained two in-memory fields, `sendPending`
        and `pendingCancel` (rebuilt at load). 32 tests, against a model of the SDK's `SendResult`s
        and of the directory. Among them: every §5.1 refusal with its code and Text; the same
        request sent once only, across back-pressure, PossDup and a restart; a reject Artio refuses
        never leaving the client with both a reject and an order; the id collision; every cancel
        case. One window of 2,000 orders decoded, validated, stored and sent allocates 0 bytes.
        22 mutations, 21 caught. The one not caught is equivalent: an order not acknowledged has
        `exchangeOrderId` 0, which no parsed OrderID equals.
        - **Found:** order-entry's test JVM lacked `-Dfix.codecs.reject_unknown_field=true`, so a
          tag outside the dictionary passed `validate()`. The adapter's JVM has it, because the
          acceptor refuses to start without it; the test task now sets it too. Artio's `decode()`
          does not reset a decoder, so the path resets before each one.
        - **Not right yet:** 35=H and 35=AF get a 35=j "unsupported message type" until slice 5
          answers them from the store. Design §3.3 says they are answered.
      - **2b, the wiring.** `ClientReplies` on Artio's `Session`: the 35=3, 35=j, 35=8 and 35=9
        encoders, ExecID `A<sequenceIndex>.<MsgSeqNum>` and OrderID for a local reject. The
        order-entry library's `SessionHandler` calling `InboundOrders`. `OrderVenue` over one
        `OrderEntrySession` per shard. `SecurityLookup` over `DirectoryClient`, with an
        allocation-free byte index of symbols and ISINs (its own maps allocate a `String`). The
        store path in the node file. The two guards: `requireSequenceIndex` at each claim, and the
        startup refusal for a session Artio does not know (§3.2). The id collision's Logon refusal,
        persisted. Tested against a real Artio initiator. The trading calendar (§3.6) is still to
        build: the inbound path takes the trading date as a supplier.
   3. **Outbound:** `onReport` → 35=8/35=9 (§5.3), and the fates per CLAUDE.md non-negotiable 1.
      Also AvgPx from the 128-bit notional, the "missing from a completed mass status" rule
      (§3.4), and `restoreInFlight` from `OrderStore.forEachInFlight`.
   4. **Delivery** (§6.2–§6.3): the lagging `SessionStore` over `OrderStore.saveFirstMissing`,
      the `RecordingPos` lookup at each library connect, and reports held from a disconnect until
      `requestSession` is back.
   5. **35=H and 35=AF** from the store. Then this repo's **e2e**, against most-exchange's real
      processes. That is the first test of `OrderEntrySession` through a real failover
      (most-exchange Status §3, item 13(a)).
6. **Tooling** (Design §10): the paced FIX sender and the HTML latency report, with service and
   response time per outcome. Then a baseline measurement (`perf-claim`).
7. **`market-data`:** 35=y from the directory, then 35=V/W/X from `DepthFeedAssembler`.
8. **The remote:** GitHub and CI, which needs a go. In CI the job token reads the SDK, once
   most-exchange's job token allowlist includes this project. The move to GitHub changes
   `mostSdk.url`, the credentials and the pipelines (Rationale §3).

## 4. What is that is worth having here based on other Fix gateways using Aeron

| What                                                                                                                 |  Here |
|----------------------------------------------------------------------------------------------------------------------|  --- |
| Artio FIX dictionary code generation (`CodecGenerationTool` as a Gradle `JavaExec`)                                  |  **Carries over.** The same build step in `fix-session` |
| FIX test sender with pacing (`<delayMicros> <count>`)                                                                |  **Carries over**, with response time from the scheduled send added beside service time (`perf-claim`) |
| Trace ring and offline analysis: per-stage events on a ring, drained to a file, decoded, then percentiles and charts |  **Carries over; the stages change.** `KAFKA_EGRESS`/`KAFKA_SENT`/`EXEC_REPORT_IN` become the offer to the gateway and the report's arrival. The exchange's own stages are in its histograms |
| Prometheus `/metrics` on the JDK `HttpServer`, with no metrics library (native-image friendly)                       | **Carries over** |
| Admin start/stop/reset/status on the metrics server, bearer-guarded, with secrets redacted                           |  **Carries over** |
| Session schedule (open/close/reset, edge-triggered, reconciles at startup)                                           | **Carries over.** Whether it follows the exchange's calendar (control plane) is open |
| Zero-allocation FIX-thread rules and flyweights over raw FIX bytes                                                   |  **Carries over** |
| Native-image findings: the Agrona init split, the `--add-opens` Artio needs, `--install-exit-handlers`               |  **Carries over**, re-checked against Artio 0.182 |
| PossDup deduplication                                                                                                |  **Carries over** |
| RocksDB order-context store (key layout, the duplicate check is the row's presence)                                  | **Adapted.** It becomes the ClOrdID ↔ `clOrdId` ↔ `exchangeOrderId` map (Design §8.3). It is no longer an enrichment source for reports, which carry their own fields |
| SMP tags 2362/2964                                                                                                   |  **Adapted.** The exchange's `smpId` is an `int64`, not a 24-byte string. Values map with `SmpStrategy` |
| Execution report durability: commit only once Artio has persisted the 35=8                                           | **The principle carries over, the mechanism does not.** Advance the stored `reportSeq` only past reports Artio has persisted (Design §6) |


