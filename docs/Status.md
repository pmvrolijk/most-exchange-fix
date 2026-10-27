# Status

Where the adapter stands, what is open, and what to do next. **This is the session entry point** —
read this, not the handover. [`Handover.md`](Handover.md) is the archive of work records.

Last updated 2026-10-27. **`fix-session` is implemented: the acceptor (2026-10-15) and the FIX
dictionary (2026-10-20). `order-entry` has its order store, its inbound path, its outbound path and
durable delivery (2026-10-25, slices 1–4 of §3 item 5): a client's 35=D reaches its shard's gateway
over Aeron, the exchange's reports and fates come back as 35=8 and 35=9, logged on or not, and a
`reportSeq` is consumed only once the archive has its 35=8. Since 2026-10-27 a request goes out only
once the archive has its 35=D/F (4b), rows a host crash lost are rebuilt from the archive at
startup (4c), and 35=H and 35=AF are answered from the store (slice 5). `market-data` is still to
build, and there is no runnable adapter yet (no `main`).** 2026-10-20: the dictionary is FIX 4.4's own,
cut to the handled message types, and Artio was found not to validate application messages, so the
backend must (CLAUDE.md non-negotiable 9, Design §5.4, Rationale §8).

Decided 2026-10-26–27 (Design §6.1, §8.17, §8.20; Rationale §12), each the recommendation:
- **A request is sent only once the archive has its 35=D/F** (CLAUDE.md non-negotiables 2, 11).
- **Lost rows are read back by the startup `requestSession` replay** from the participant's
  high-water + 1. A replayed request is never sent or answered; one that passes is rebuilt in flight.
- **A floor in `meta`**: the highest `clOrdId` retention dropped (confirmed 2026-10-27).
- **A recovered order whose security the directory does not know** is refused, OrdRejReason 99
  "unknown security", with no 48 (confirmed 2026-10-27).
- As built 2026-10-27: a request rejected here raises the high-water, so a clean restart replays
  nothing already answered; a rebuilt row is stored *unresolved* until the directory comes.

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
- **The SDK runs on the adapter's embedded media driver** (§8.6 closed, Design §3.5).
- **A shard's gateways are the directory's**, unless the node file lists them, as a co-located
  shard needs. An endpoint that moves later is logged, not followed (Design §3.5).
- **The collision's block** is stored, the Logon accepted and then logged out by the library, and
  lifted by any reset that raises the `sequenceIndex`, the client's 141=Y included (Design §3.3).
- **A report for a row the store lost** (a host crash) is logged, counted and consumed (Design
  §5.3, Rationale §10). Since recovery (2026-10-27) that is left for a request the archive does not
  have, which never reached the exchange either.
- **Fills a mass status reveals** are a 35=8 ExecType I with the new CumQty, AvgPx over the fills
  known, and a Text saying what is missing (Design §5.3).
- **An answer with no `reportSeq`** (a fate, a restatement, a parked cancel's answer, a gateway's
  own reject) writes its row only once the archive has its 35=8 or 35=9. A crash before then gives
  it again; it is never lost (Design §6.2, Rationale §11).

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
| Modules | 5 — `fix-session`, `order-entry`, `market-data`, `adapter` (the process), `tools` (`bin/fix`); packages `nl.lamia.most.exchange.fixadapter.*` |
| Tests | `fix-session` 35, plus 1 in its own JVM (`testAcceptorGuard`); `order-entry` 218 (the store 24, the inbound path 72, the outbound path 34, AvgPx 5, delivery 12, the report sessions 13, the directory index 7, the config 5, the calendar 15, the backend end to end 30, SDK wiring 1); `adapter` 4; `market-data` 1; `tools` none (the e2e drives it). `./gradlew build` green 2026-10-27. `e2e/run-e2e.sh`: steps 1–7 pass, step 8 fails (§8.21) The 15 spikes (`:spikes:test`) last ran 2026-10-15; `RecoveryReplaySpikeTest`'s 4 on 2026-10-27 |
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
      - ~~**2a, the logic**~~ **Done 2026-10-25** (`d26fe46`; Design §3.3, §5.1, §5.2):
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
      - ~~**2b, the wiring**~~ **Done 2026-10-25** (`02b29a0`; Design §2, §3.2, §3.3, §3.5;
        Rationale §9). `OrderEntryBackend`, one duty cycle: the order-entry library, `RoleClaims`,
        the directory's subscription, and an `OrderEntrySession` per shard (`ShardVenue`), all on
        the embedded driver. `ArtioReplies`, the 35=3/j/8/9 on Artio's `Session`. `DirectoryIndex`,
        symbol, ISIN and id to the security and its shard, rebuilt per version and read without
        allocating. `OrderEntryConfig`: `fix.storeDir`, `fix.directoryChannel`/`StreamId`, and a
        shard's listed gateways. `StartupGuards`, run before the acceptor binds (`FixAcceptor`
        gained `bindAtStartup` and `bind()`). The store keeps the collision's block.
        fix-session's test rig moved to `testFixtures`, so order-entry's tests drive a real
        initiator. 16 end-to-end tests, a real Artio initiator through to a gateway model on
        Aeron IPC, among them:
        - a 35=D reaches its security's shard, for the session's participant, with the derived id;
        - every reply as the client reads it, ExecID `A<sequenceIndex>.<MsgSeqNum>` included;
        - the collision: a reject and a Logout, logged out at every Logon across a restart, until
          a 141=Y;
        - both startup refusals, with nothing listening;
        - a message Artio rejected itself never reaching the gateway.

        23 mutations, each caught. Two were caught only after a fix: the `sequenceIndex` raised at
        the claim was also raised by the first message, so the test now waits for it before the
        client sends; and one mutation first failed to compile, which proves nothing, and was
        rewritten. Among them: Artio's invalid messages or session messages passed on, the blocked
        session not logged out, the block never lifted or lifted at the same index, either startup
        guard skipped, the acceptor bound before them, every order to one shard, listed gateways
        ignored, the ExecID off by one, a mismatched cancel stating the row.
        - **Found:** Artio hands the library session messages and messages it has already answered
          (`isValid()` false), so a Heartbeat would have drawn a 35=j and a 35=D Artio rejected
          would have been forwarded (CLAUDE.md non-negotiable 10). Artio's encoders wrap the bytes
          they are given, so each reply resets its encoder. Before the directory every order would
          have been "unknown security"; it is now "exchange unavailable". An initiator closed while
          logged on waits ~10 s, so the tests log out first.
        - **Not done here:** reports (`listener` is slice 3's), the lagging `SessionStore` (slice 4;
          the SDK's in-memory one stands in), `restoreInFlight` at a shard session's creation
          (slice 3), the trading calendar (§3.6; still a supplier), and a `main` that reads the
          node file and runs the backend on a thread. The SDK allocates per directory cycle and per
          order (Design §8.16): a change to most-exchange's `client`. The replies' allocation was not
          measured.
   3. ~~**Outbound**~~ **Done 2026-10-25** (`c83d486`; Design §3.3, §3.4, §5.3; Rationale §10).
      `...orderentry.outbound`: `OutboundReports` (a report, a fate or a mass status's completion,
      to the client and the store), `Notional` (128 bits, AvgPx half up), and the port
      `ClientReports` with its two reused messages. `artio.ArtioReports` writes them on each
      participant's `Session`. The backend gives each shard's SDK session its own listener, so a
      mass status settles only that shard, and calls `restoreInFlight` as it creates one. A
      report changes the store only once its 35=8 was taken; fates, the mass status rule's
      restatements and parked cancels are owed on their rows and worked off in the duty cycle.
      25 tests against encoded reports, 5 for the arithmetic, 6 end to end. Among them:
      - every ExecType's 35=8, a cancel's CANCELED naming both ClOrdIDs, a lost race answered too
        late;
      - an ambiguous order restated as last told, never rejected or terminal (non-negotiable 1);
      - a refused 35=8 changes nothing, a resent report is not applied twice;
      - the mass status rule's four exclusions;
      - an unanswered order restored after a restart, fenced, and rejected not sequenced;
      - a report held while the client is logged out arrives live, not as a resend;
      - a truncated resend on one shard, and its mass status settling nothing on the other.

      One window of 2,000 reports applied and sent (to a recorder) allocates 0 bytes. 25 mutations,
      24 caught. The one not caught is equivalent: without the disconnect hook, a disconnected
      `Session` is still not written to, because it is no longer active. Two were caught only
      after a fix. A shard filter removed went unseen, because the other shard's order was
      sequenced after the status and excluded by the `nextSeq` rule first. And no test ran a mass
      status after startup, so one now drives a gap through fence, truncation and status.
      - **Read in the engine** (`MatchingEngineService`) before writing: a participant's cancel is
        answered with the cancel's `clOrdId`, an unsolicited cancel with the order's.
      - **Not done here:** a client logged out still holds its gateway's whole report stream
        (`ABORT`) until slice 4. A row lost in a host crash is not recovered (§8.17). The 35=8's
        own allocation through Artio was not measured.
   4. ~~**Delivery**~~ **Done 2026-10-25** (`e80df98`; Design §6.1–§6.3, §8.18–§8.19; Rationale
      §11; CLAUDE.md non-negotiable 7). `delivery.ReportDelivery`, the lagging `SessionStore`, one
      view per shard: the stored first-missing is the lowest of the SDK's ceiling, the lowest
      `reportSeq` whose 35=8 the archive has not reached, and a library reconnect's floor.
      `artio.LibraryRecording`, the `RecordingPos` lookup at each library connect.
      `artio.ReportSessions`: every session held offline from startup (before the bind), requested
      back after a disconnect replaying from the last received + 1, never written to once it stops
      being active, and an offline session handed back numbered below the library's last MsgSeqNum
      released and asked for again. Decided: an answer with no `reportSeq` writes its row only once
      archived. 41 new tests, among them, end to end: a logged-out client's report archived and
      consumed while it is away, then resent as PossDup at its Logon; after a restart, a fate
      written offline before the client is back; a client that logs on again before the library
      has seen its disconnect, its 35=D not lost (the race forced by not polling the adapter).
      33 mutations, all caught; five only after a test or the code was changed, two rewritten.
      - **Found:** `reportSeq` is per shard, and the store kept one per participant: now (participant,
        shard), and each row keeps its shard. The request after a disconnect must replay, or a
        client back first loses its 35=D (Artio's source). A report written just after the client's
        Logout was lost in 2 runs of 5: the library still wrote on the session, and the engine's
        offline handover reads an index that can lag the library's stream (seen once in 6 runs,
        on the Logout reply). `FixLibrary.connect` returns before the library is connected.
      - **Not done here:** a library reconnect freezes the stored `reportSeq` until a restart, and
        that path is not tested end to end (§8.18). A client back within the indexer's lag after a
        dropped connection can still have a MsgSeqNum reused: counted, not prevented (§8.19). The
        35=8's allocation through Artio was not measured. A row lost in a host crash is still not
        recovered (§8.17), which is next.
   - **Then recovery of lost rows** (Design §8.17, §8.20; Rationale §12). Shape decided
     2026-10-26, each the recommendation: the startup offline `requestSession` replays from the
     store's last row + 1; a replayed request is never sent or answered; one that passes validation
     and has no row is rebuilt in flight and settled by the fence (a second reject is possible for a
     request first rejected here); its shard resolved once the directory arrives.
     **Spiked 2026-10-26** (`RecoveryReplaySpikeTest`, 3 tests): (f) the offline replay works as
     read, across a 141=Y reset, acquired before its messages; (g) **the gap in R§4 is real**: all
     500 35=Ds were handled before the fsynced archive had them, so after a host crash a client's
     PossDup resend of a lost 35=D is sent to the exchange a second time (Design §8.20).
     **Decided 2026-10-26: a request is sent only once the archive has its 35=D/F** (Design §6.1,
     §8.20; R§12; CLAUDE.md non-negotiable 2 changed, 11 new). The send moves into the duty cycle,
     from the row. Built in two parts:
     - ~~**4b, send behind the archive**~~ **Done 2026-10-26**, committed `6cf4adb` (Design §3.3, §6.1,
       §8.20). `inbound.ArchivedSends`: rows to send, in arrival order, from the duty cycle, once
       `artio.InboundRecording` (the `RecordingPos` of the engine's one inbound publication) has
       passed the message's position; a back-pressured send holds those behind it; a refused one
       goes to `OutboundReports.refuse`, a `NEVER_SEQUENCED` fate with the refusal's Text (in
       memory). `InboundOrders` takes the position and no longer sends. A parked cancel goes through
       the same queue at its own 35=F's position; one loaded at startup goes at once. Tests 164
       (+13 new, 3 rewritten, 1 removed: a refused reject no longer aborts the 35=D). 14 mutations,
       13 caught; the 14th was a guard that could never fire, now removed. Spike (f) also checks
       the startup replay is complete when its reply is.
       - **Not done here:** the latency it adds is not measured (`perf-claim`). most-exchange's
         Adapters.md (line 49) still describes this adapter's back-pressure as `Action.ABORT`.
         No end-to-end test can hold the archive back, so the gate is proven in unit tests and by
         the mutations, and the wiring by orders still flowing end to end.
     - ~~**4c, recovery of lost rows**~~ **Done 2026-10-27**, uncommitted (Design §6.1, §8.17;
       Rationale §12; non-negotiable 11 extended). Both points to confirm were confirmed. As built:
       - `OrderStore`: the high-water per participant (rows, the floor of dropped rows written in
         their drop batch, and requests rejected here), `replayFrom`, the floor in `holdsState`.
       - `ReportSessions.requestAtStartup` replays from it; `isReplaying` until the request has
         completed with the session held; the backend waits for every replay before the bind.
       - `InboundOrders.onReplayed`: recovery mode, never sent or answered. It skips what lies at or
         below the high-water with no row, decides again only what a lost write cannot have changed,
         and rebuilds the rest *unresolved*. `resolveRecovered` runs at the first directory, before
         any shard's session: an unknown security, a cancel's mismatched security, a cancel of an
         order with no shard, each refused through the outbound path.
       - The backend routes a message on an offline session during the replay to recovery, lifting
         a block a higher `sequenceIndex` shows was lifted. A 35=8 for a security unknown states no 48.
       - Spike (f) gained a case: a replay from past the end of index 0 brings index 1's messages.
       - Tests 186 in order-entry (+22, 1 rewritten): store +3, inbound +14, report sessions +2,
         end to end +3 (a lost 35=D rebuilt, never sent again, rejected by the fence; a recovered
         order whose security is gone refused, no 48; a clean restart rejects nothing twice).
         20 mutations, all caught; one first did not compile and was rewritten. `./gradlew build`
         green; `RecoveryReplaySpikeTest` 4 of 4.
       - **Not done here:** no end-to-end test loses a 35=F's row, or replays across a block's
         lift (the lift in the handler is untested). The startup gives up after 10 s, so a replay
         from a high-water of 0, a wiped store beside Artio's state, could refuse the start. The four
         restart tests take ~37 s each, as before 4c: the SDK's fence, not looked into.
     - *(The plan as it stood before building, kept for the record:)*
       - `requestAtStartup` replays from each participant's high-water + 1: the highest `clOrdId`
         it has held, (`sequenceIndex`, MsgSeqNum). Retention drops rows, so a participant whose
         rows were all dropped would otherwise replay its whole history: **confirmed 2026-10-27**, the
         highest `clOrdId` dropped kept in `meta` as a floor, written at `load` before the drops
         (a prefix keeps it below the truth). `SEQUENCE_NUMBER_TOO_HIGH` (nothing past it) is a
         success here, as after a disconnect.
       - While a participant's startup request is out, its messages go to the inbound path in
         recovery mode: never sent, never answered (a `ClientReplies` that swallows). Spike (f)
         checks that the reply completes only after the replay.
       - Recovery mode re-decides what the message and the store decide (`validate()`, §5.1's
         field checks, a duplicate ClOrdID, the block, the cancel's order, its Side and OrderID).
         The rest is rebuilt in flight: an order not acknowledged, a cancel *sent* whatever its
         order's state, so a recovered request is never sent again.
       - The security is resolved once the directory arrives, from a copy of the message kept in
         memory, before the shard sessions are created and `restoreInFlight` runs. **Confirmed 2026-10-27**:
         a recovered order whose security the directory does not know is refused, OrdRejReason
         99 "unknown security", through `refuse`.
   5. ~~**35=H and 35=AF** from the store~~ **Done 2026-10-27**, uncommitted (Design §3.3, §5.4).
      Decided with the user, each the recommendation:
      - a 35=H for a ClOrdID not held gets FIX 4.4's unknown-order 35=8 (I, 8, OrdRejReason 5);
      - 35=AF serves MassStatusReqType 7 and 1, with Side as a filter. 585 is cut in
        `extract_fix44.py`, so any other type is a 35=3;
      - no matching order gets a 35=j, "no open orders";
      - a refused answer resumes where it stopped.

      Built:
      - `InboundOrders` answers both from the store;
      - `artio.ExecutionReportWriter` is the 35=8 encoding, now shared by `ArtioReports` and
        `ArtioReplies`;
      - `OutgoingExecution` echoes 790 and 584/911/912.

      **Found:** Artio's `MutableAsciiBuffer.putAscii(String)` allocates a byte array. Every Text,
      §3.4's included, allocated on the path that claimed to allocate nothing; now
      `putStringWithoutLengthAscii`. Tests 203 in order-entry (+17): inbound +14, one of them that a
      35=H allocates nothing; end to end +3. 16 mutations, all caught, the dictionary's cut
      among them. `./gradlew build` green.
      - **Not done here:** the outbound path's own allocation is still not measured; a 35=AF
        lists the session's orders through `store.forEach`, which allocates.
   6. **This repo's e2e**, against most-exchange's real
      processes. That is the first test of `OrderEntrySession` through a real failover
      (most-exchange Status §3, item 13(a)).
      - **First run 2026-10-27**, uncommitted (Handover §2k). Built for it: §3.6's calendar, the
        `adapter` module (`bin/adapter <node file>`), `tools`' `bin/fix`, and `e2e/run-e2e.sh`
        (one member, from `../most-exchange`'s installs). Steps 1–7 pass: rest, cross, a report to
        a logged-out client, 35=H, 35=F, 35=AF, an unknown symbol, a clean restart.
      - **Step 8, a SIGKILL under load, fails: Design §8.21, to decide.** After a crash the adapter
        reuses a MsgSeqNum the client has already seen; the client logs out, and a 35=D in flight
        at the crash is never resent.
      - **Fixed on the way:** Artio's monitoring files were host-wide; now in `fix.logDir`.
      - **Still to do:** the three-member failover run (a `run-failover.sh` for the adapter, listed
        gateways), after §8.21.
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


