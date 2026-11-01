# Status

Where the adapter stands, what is open, and what to do next. **This is the session entry point** —
read this, not the handover. [`Handover.md`](Handover.md) is the archive of work records.

Last updated 2026-11-01. **Both backends are built and `bin/adapter` runs them; market data has its
own engine; the adapter has its latency tooling, a baseline and a trace ring.**

- **The priority (the user, 2026-11-01): the exchange's schema version 8** (§3 item 12, Design
  §8.30, R§17). The exchange now takes the client's ClOrdID as `char[20]` and bounds a resend with
  `toSeq`; the SDK is republished under the same `0.1.0-SNAPSHOT`, so **`master` stops building at
  the next fresh resolve**. Analysed and decided, docs written; **no code yet**.

- **Measured** (Measurements A1–A5, X1–X2, the laptop, macOS): at fileSyncLevel 1 the adapter
  answers 1,000 and 3,000 orders/s at a 15–18 ms p50 and saturates at ~4,250 requests/s; the gateway
  alone keeps up at 10,000/s under 0.2 ms; at fileSyncLevel 0 the adapter keeps up at 5,000/s at
  0.36 ms. The trace: below saturation the archive gate and the exchange stage take ~8 ms each, ~90%
  of the time; at saturation the queue is before the order-entry handler. Open as Design §8.24,
  nothing decided; the user will run it on Linux.
- **Market data on its own engine** (item 8a, Design §4, CLAUDE.md 14): its cost to order entry
  below saturation is mostly gone (A4); ~8% of capacity at saturation remains, the laptop's.
- **Docker is built** (§3 item 9, `deploy/`): participant 7's adapter beside most-exchange's dev
  stack, verified with a cross, market data, a clean restart and a crash. Its smoke run found a
  delivery defect, **fixed the same day** (item 10, Design §8.29): after a crash restart's MsgSeqNum
  skip, a 35=8 sent to a logged-out client was never resent. The skip is now 10,000 Heartbeats.
- **The GitHub Action** is green on GitHub (item 8). **The Operator's Manual and the Rules of
  Engagement** are written and built (item 11, `docs/manual/`).
- **Next: §3 item 12, schema version 8.** Then: §8.24 waits for the Linux run; several sessions per
  participant (§8.27) is a low-priority roadmap item; the control API and metrics (Design §9) are
  the largest gap the manuals list.
- **Committed** through the manuals (`cf9afcd`): Docker, §8.29, the CI action bumps and the manuals included.

The user's frame, 2026-10-31: production is Linux; 300–1,000 orders/s per connection is fine, and
3,500–5,000/s through one adapter is the target worth reaching. Each participant gets a dedicated
adapter, with a market-data session beside its order-entry one, on separate ports. Raised, not
designed: an optional order-entry throttle (Design §8.25).

Decided 2026-11-01 (the user, each the recommendation; Design §3.2, §8.30, R§17):
- **The exchange's `clOrdId` is the FIX ClOrdID as sent.** The id collision, its block and the
  operator's reset go. The startup recovery stays: a host crash still loses the store's tail, and
  the exchange still does not check `clOrdId`.
- **A ClOrdID or OrigClOrdID that does not fit** (1 to 20 of `A-Z a-z 0-9 - _ + ; : . /`) **is
  refused** with a 35=8 or 35=9, never mapped.
- **The upgrade is a fresh start** of the store and Artio's state, beside the exchange's v8 restart.
- **Artio's state lost while the store holds rows: the adapter starts** in a new epoch, logged.

Decided 2026-10-31 (the user):
- **Market data gets its own engine**, port and an archive at fileSyncLevel 0 (Design §4, superseding
  "one engine"; R§16). Built the same day.
- **One session for both roles is scrapped** (§8.26).
- **`fix perf`'s crosses carry another SelfMatchPreventionID** (2362) than its passive orders, on
  one session (Design §10.1), superseding a second participant as taker. Built the same day.
- **A dedicated adapter per participant**, which may hold several of its sessions: several
  order-entry sessions per participant is a roadmap item, low priority (§8.27).
- **The order of work**: the trace ring, then Docker, then a GitHub Action.

Decided 2026-10-31 (Design §10; with the user):
- **`fix perf` is its own command**; `load` stays the e2e's.
- **Lateness from the schedule, attributed**: queued, sender late, pushed back and send cost, and
  traced to its root as a sender share and a system share. The user asked for pacing and its
  deviation, "so we can correctly attribute".
- **The baseline in the e2e's topology, UDP** (the user's call): one machine, one cluster member,
  adapter at fileSyncLevel 1 with back-off idling.
- ~~Found, not decided: **two participants for the sender**, a maker and a taker.~~ Superseded the
  same day by SelfMatchPreventionIDs on one session (above).

2026-10-30: **Both backends are built, and `bin/adapter` runs them.** `fix-session`:
the acceptor and the FIX 4.4 dictionary, now with market data's messages. `order-entry`: the store,
inbound, outbound, durable delivery, recovery of lost rows, 35=H/35=AF, the MsgSeqNum skip after a
crash, the library's own ResendRequest, and (2026-10-30) the startup replay one session at a time
(CLAUDE.md 11, R§15), which explained the e2e's id collision. `market-data` (2026-10-30, §3 item 7):
35=x → 35=y, 35=V → 35=W/35=X from the SDK's new `DepthFeedListener`, transient sessions, a fresh
35=W after a resync or for a client that fell behind. `e2e/run-e2e.sh` drives both against
most-exchange's real processes, its market-data process included: 3 runs of 3.
**Next: §3 item 6, the paced sender and the latency report**, which also measures Design §8.22
(market data sharing order entry's fsynced archive).

Decided 2026-10-30 (Design §4; each the recommendation):
- **35=X from a listener on the SDK's assembler**, added to most-exchange's `client` and published.
- **Market-data sessions are transient**: MsgSeqNum 1 at every Logon, subscriptions in memory.
- **One engine for both backends**; the archive coupling is measured before it is relied on (§8.22).
- **A client whose 35=X Artio refuses gets a fresh 35=W** once it can; not logged out.
- **Skipped (the user's call):** reporting (h3), (h4) and the shared offline connection id to Artio;
  the three-member failover run.

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
| Licence | AGPL-3.0-or-later (`LICENSE.md`); `docs/` CC BY 4.0 (`docs/LICENSE`), as the exchange's; the SDK it links is Apache-2.0 |
| Modules | 5 — `fix-session`, `order-entry`, `market-data`, `adapter` (the process), `tools` (`bin/fix`: the e2e's client, `perf` and `report`); packages `nl.lamia.most.exchange.fixadapter.*` |
| Tests | `fix-session` 49, plus 1 in its own JVM (`testAcceptorGuard`); `order-entry` 246 (the store 26, the inbound path 72, the outbound path 34, AvgPx 5, delivery 12, the report sessions 13, the receive gaps 15, the directory index 7, the config 5, the calendar 15, the backend end to end 39, SDK wiring 1); `adapter` 10; `market-data` 35 (subscriptions 23, the backend end to end 6, the security list 5, SDK wiring 1); `tools` 25 (pacing 8, the mix and the raw reader 9, the report 7, `fix perf` end to end 1). `./gradlew build` green 2026-10-31. `e2e/run-e2e.sh`: 7 runs of 8 pass (§8.21), then 3 of 3 on 2026-10-29, 3 of 3 on 2026-10-30, and 5 of 5 on 2026-10-31 on the shared `e2e/exchange.sh`, the last two with market data on its own port; 1 of 1 with step 9 (§8.29) on 2026-10-31. `e2e/run-perf.sh`: Measurements A1–A4, X1–X2 on 2026-10-31. The 15 spikes (`:spikes:test`) last ran 2026-10-15; `RecoveryReplaySpikeTest`'s 4 on 2026-10-27; `SequenceReuseSpikeTest`'s 4 and `ResendStallSpikeTest`'s 1 on 2026-10-28; `RewindSpikeTest`'s 5 (and the 1 again) on 2026-10-29 |
| Depends on | most-exchange `client` + `sbe` 0.1.0-SNAPSHOT from GitHub Packages (`pmvrolijk/most-exchange`), republished 2026-10-30 with `DepthFeedListener` (most-exchange `3afef4f`), Artio 0.182, rocksdbjni 11.8.1 |
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

In order. Items 1–11, 6a and 8a are done and committed. **Next: 12, schema version 8** (the user's
priority, 2026-11-01). Then §8.24's investigation with the user's Linux run.

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
     - ~~**4c, recovery of lost rows**~~ **Done 2026-10-27**, committed `1f379d9` (Design §6.1, §8.17;
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
   5. ~~**35=H and 35=AF** from the store~~ **Done 2026-10-27**, committed `1f379d9` (Design §3.3, §5.4).
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
      - **First run 2026-10-27**, committed `2531ea7` (Handover §2k). Built for it: §3.6's calendar, the
        `adapter` module (`bin/adapter <node file>`), `tools`' `bin/fix`, and `e2e/run-e2e.sh`
        (one member, from `../most-exchange`'s installs). Steps 1–7 pass: rest, cross, a report to
        a logged-out client, 35=H, 35=F, 35=AF, an unknown symbol, a clean restart.
      - **Step 8, a SIGKILL under load, fails: Design §8.21, to decide.** After a crash the adapter
        reuses a MsgSeqNum the client has already seen; the client logs out, and a 35=D in flight
        at the crash is never resent.
      - **Spike (h) done 2026-10-28** (`SequenceReuseSpikeTest`, `ResendStallSpikeTest`, Design
        §8.21): the reuse is reproduced, and moving the number on fixes it; Artio forgets a gap a
        session leaves open (h3), and stalls on a resend answered short (h4).
      - **The skip is built** (CLAUDE.md non-negotiable 12, R§13): after an unclean stop each
        session moves on 10 000 before the bind. The e2e then passed 7 runs of 8.
      - **Open, Design §8.21:**
        - ~~the 8th run's id collision at the startup replay, not explained~~ **Explained and fixed
          2026-10-30** (Design §8.17, §8.21): Artio hands every offline session the same
          connection id, so with both startup requests out at once one client's archived 35=Ds
          reached the other's handler, were rebuilt for the wrong participant, and collided. The
          startup requests now go one at a time; a replayed message from another session's client
          refuses the start. Reproduced every time by a new end-to-end test (two sessions losing
          rows); two mutations, both caught. `e2e/run-e2e.sh` 3 of 3, though no run left a 35=D to
          rebuild, so the e2e did not exercise the path;
        - ~~what the adapter does about (h3), (h4) and the client-side window~~ **Decided
          2026-10-29**, each the recommendation. The client-side window is a requirement on
          clients (Design §2), reported to Artio. For (h3) and (h4), a rewind was chosen first;
          **spike (i)** (`RewindSpikeTest`, 5 tests, green 3 runs of 3) found that Artio then
          gap-fills over every report sent before it. Superseded: **the library asks for the gap
          itself** with its own ResendRequest, logged on, no new index (CLAUDE.md non-negotiable
          13, R§14).
        - **Built 2026-10-29**, committed `82a8765` (Design §2, "A gap Artio leaves open"):
          - `artio.ReceiveGaps` keeps the library's own in-order count per session. It reads
            MsgSeqNum, PossDup and a SequenceReset's fields in place.
          - The gap is stored in `meta` (`OrderStore.saveReceiveGap`).
          - The library's own ResendRequest goes out at once after a Logon whose gap is from
            before it, otherwise after 1 s without progress.
          - After 3 requests without progress, the session is logged out with Text "messages
            <from> to <to> not received; resend them".
          - Wired in the backend: every message counts, invalid and session messages included,
            but not the startup replay's.
          - **Found:** the startup replay handed back as valid a 35=D the library had dropped, and
            recovery rebuilt it in flight. The fence would then have rejected it "not sequenced",
            and its resend counted as a duplicate. This was so before the gap was tracked. Now a
            non-PossDup message within the stored gap is skipped. A host-crash corner is left
            (Design §2).
          - Tests: order-entry 243 (+22). Store +1, `ReceiveGapsTest` 15 (one of them: counting
            in order allocates nothing). End to end +6, each driven by a raw-socket client:
            - a short answer asked again, every order at the gateway once, still logged on;
            - a gap left by a disconnect asked for after the Logon;
            - the same across a restart, nothing rebuilt;
            - a stall left by a disconnect, across a restart: its dropped 35=Ds, in the archive,
              asked for and sent once;
            - a client that never answers, logged out naming the range;
            - an in-order session, with a Heartbeat and a TestRequest, never asked.
          - `./gradlew build` green, 290 tests.
          - 17 mutations, 16 caught. The one not caught counts the startup replay's messages.
            Here Artio's replay from MsgSeqNum 4 never delivered 5, the gap's first number, though
            it is in the archive. So nothing could move the count, and the guard stands as
            specified, unproven.
          - `e2e/run-e2e.sh` passed 3 runs of 3, the SIGKILL step included. The adapter sent no
            ResendRequest of its own in any of them: the e2e's client settles after a reconnect,
            so it never leaves a gap.
          - **Not done here:** no e2e step makes a gap, so the make-up is proven against Artio's
            engine only, with a raw client, not against most-exchange. An Artio bump must rerun
            `RewindSpikeTest`.
        - ~~Report (h3), (h4), the initiator's counting and the shared offline connection id (R§15)
          to Artio~~ **Skipped 2026-10-30** (the user's call). Artio is now `artiofix/artio`; in
          0.187 `Session`, `CatchupReplayer` and `ReplayQuery` are unchanged from 0.182, so all four
          still hold there.
        - The e2e's client now settles a second after a reconnect (`fix load --settle`), so it
          does not trigger (h4).
      - **Fixed on the way:** Artio's monitoring files were host-wide; now in `fix.logDir`.
      - ~~The three-member failover run (a `run-failover.sh` for the adapter, listed gateways)~~
        **Skipped 2026-10-30** (the user's call).
6. ~~**Tooling**~~ **Done 2026-10-31**, committed `0cd88a2`, `1f456e0` (Design §10; Handover §2m). `fix perf`, the
   measuring sender: a schedule fixed before the first send, a maker and a taker session, the mix of
   passive orders, crosses and cancels predicted from what it sends, and a memory-mapped log written
   without allocating per message. `fix report`: each report timed against the request that caused
   it (a resting fill against its cross), service and response time per outcome, lateness in four
   parts with its sender and system shares, throughput with gap percentiles, and a self-contained
   HTML page (inline SVG, light, dark and print, provenance first) with `.hgrm`, CSV, a comparison of
   two runs and the Measurements row. `e2e/run-perf.sh` runs the baseline beside `most load`;
   `e2e/exchange.sh` is now the startup both scripts share. 24 tests, 16 mutations, each caught.
   - **Found while building:** without a separate send cost, Artio's encode (~1 µs) read as the
     system's; and a resting fill paired with the *latest* cross was timed from the wrong one, at
     2 µs. Both fixed in the spec first, then the code. Every cross through most-exchange was
     cancelled until the taker was a second participant.
   - **Measured** (Measurements A1–A2, X1): the adapter, not the exchange, is the bottleneck, and the
     archive's fsync is most of it (Design §8.24).
   - ~~**§8.22's run**~~ **Done 2026-10-31** (Measurements A3, `PERF_MD=1`): market data on order
     entry's engine costs it ~2–2.5 ms p50, 5–8 ms p99 and ~8% of its capacity. Decided: market data
     gets its own engine (Design §4). Three earlier attempts were confounded by the machine (the
     feed clients' CPU, then IntelliJ) and are not recorded; `fix md --quiet` and an idle client
     that backs off came of them.
   - **Also 2026-10-31:** the sender is one session again, its crosses carrying another
     SelfMatchPreventionID (the user's suggestion), superseding the maker and taker.
   - **Still open:** (a) **The trace ring** (Design §9.2), to say where the adapter's time goes;
     `fix report` reads it beside the sender's log. (b) The 2.25 s gap in every stream at 10,000/s
     (A1c), unexplained, and taken on a loaded machine.
8a. ~~**Market data's own engine**~~ **Done 2026-10-31**, committed `19eedf8` (Design §2, §4; CLAUDE.md 14;
   R§16; Handover §2m). A second `FixAcceptor` in the adapter's process from `fix.md.port`,
   `fix.md.logDir`, `fix.md.archiveDir` (required with a market-data session), `fix.md.aeronDir`
   (default `fix.aeronDir-md`), `fix.md.fileSyncLevel` (default 0); emptied at every start
   (`transient`); each acceptor holds its role's sessions (`ConfiguredSessions.ofRole`), so a
   session at the other role's port is refused as unknown. The scripts' `FIX_MD_PORT` (FIX_PORT + 1).
   6 tests; 5 mutations, each caught. Measured (A4): the coupling below saturation mostly gone.
   - **Still open:** the ~8% of capacity at saturation (A4e–f), on a host that is not a laptop;
     market data's archive grows within a run (Design §8.28).
6a. ~~**The trace ring**~~ **Done 2026-10-31**, committed `358d7b5` (Design §9.3; Handover §2m). `Trace`
   (Agrona's one-to-one ring, a drainer thread, a file), off unless `fix.trace.file`; events IN,
   GATE, SENT, REPORT, OUT, DURABLE from the order-entry thread; the sender logs its MsgSeqNum (log
   format 2); `fix report --trace` cuts the service time at each stage. 7 tests; 7 mutations, each
   caught (one only after the test was strengthened). Found: Artio's receive time converts only by
   the engine's own clock instance.
   - **Measured** (A5): see the header. **Still open** (§8.24): why the exchange stage and the
     library's intake move with the fsync; the 2.25 s gap of A1c.
   - Not built: the rest of §9 (`/metrics`, `/status`, control).
   - **Fixed 2026-10-31, uncommitted** (found by item 8's Linux run): `Trace.close()` stopped draining
     at a read that found only the wrap's padding, losing up to a ring of events uncounted.
     `TraceTest`'s full-ring test now runs 20 rounds ending at different points; it failed on macOS
     too before the fix (round 2, 9 events).
7. **`market-data`:** 35=y from the directory, then 35=V/W/X from `DepthFeedAssembler`. Started
   2026-10-30. Decided first (`decision-fork`, each the recommendation, Design §4): 35=X
   from a listener added to the SDK's assembler; market-data sessions transient (MsgSeqNum 1 at every
   Logon); one engine, the archive coupling measured later (§8.22). In five slices:
   1. ~~**The dictionary**~~ **Done**: 35=x, V, y, W, X, Y added to `extract_fix44.py`, FIX 4.4's
      code sets uncut (an unserved entry type is the backend's 35=Y); 969 MinPriceIncrement taken
      from FIX 5.0 onto each 35=y entry (FIX 4.4 has no tick tag). `DictionaryTest` +7.
   2. ~~**The SDK listener**~~ **Done in `../most-exchange`** (`3afef4f`, published 2026-10-30):
      `DepthFeedListener` (depth changed on a trusted book, trade, synchronised, desynchronised),
      7 tests, 5 mutations caught; its Design and Adapters.md updated. Built against the local SDK
      until the publish; the registry's `0.1.0-SNAPSHOT` has it since 2026-10-30.
   3. ~~**The backend and 35=x**~~ **Done**: `FixAcceptor.persistenceByRole` (market data transient);
      `SessionRejects` (35=3 and 35=j, now shared with order entry); `MarketDataConfig`
      (`fix.md.l1|l2|snapshot.channel|streamId`); `SecurityLists` (fragments of 100, resumes after a
      refusal); `MarketDataBackend` (its own library, claims, the directory, the handler's filters).
      `DirectoryBroadcast` moved to `fix-session`'s test fixtures. Tests: `SequenceTest` +1,
      `DictionaryTest` +1, `SecurityListsTest` 5, `MarketDataBackendTest` 5; 9 mutations, all caught
      (two only after a test was changed: a sort hidden by HashMap order, a refusal hidden by a Logout).
   4. ~~**Subscriptions**~~ **Done 2026-10-30**, committed `c97ca06` (Design §4, "Subscriptions"). Decided
      first: a client whose 35=X Artio refuses gets a fresh 35=W once it can (the recommendation;
      rejected: logging it out). Specified, then built: `Subscriptions` (owns the SDK's assembler, is
      its listener; every 35=W from the duty cycle; FIX 4.4's MDReqRejReason; New/Change/Delete per
      level, MarketDepth 1 as the best of each side), the feeds and 35=V wired into
      `MarketDataBackend`, `FeedModel` in the tests. Tests: `SubscriptionsTest` 20,
      `MarketDataBackendTest` +1 (35=V through to 35=W and 35=X over Aeron). 11 mutations, all
      caught (one only after a trade was added to the resync test).
   5. ~~**Wiring**~~ **Done 2026-10-30**, committed `c97ca06`. `Adapter` runs the market-data backend on its
      own thread when a market-data session is configured, its `fix.md.*` keys read before anything
      opens (order entry is still required). `bin/fix` gained `securities` (35=x) and `md` (35=V),
      both logging on with 141=Y, and prints 35=y/W/X/Y with their groups. `e2e/run-e2e.sh` starts
      most-exchange's market-data process, its feeds on dynamic MDC channels to the adapter, and step
      3b checks the 35=y, one 35=W of AAPL's book and the 35=X for a new bid: 3 runs of 3.
      - **Found by the e2e:** a quiet book's repeated snapshot sent every client the same 35=W every
        500 ms. Now a book trusted again and exactly as its clients hold it resumes the 35=X with no
        35=W (Design §4). `SubscriptionsTest` +3, `AdapterTest` +2; 3 mutations, 2 caught, the third
        an equivalent guard, removed. Mutation runs now detect a compile error (`-Werror`), which
        had silently reused the last run's results.
   - **Item 7 is built**, against the published SDK. Left: Design §8.22 (the shared archive) and
     §8.23 (the SDK book's allocation).
8. **The remote and a GitHub Action that builds and tests** (go given 2026-10-31, after 9). The
   remote exists (`origin`, `github.com/pmvrolijk/most-exchange-fix`). **Decided 2026-10-31 (the
   user): the regular tests only, never most-exchange's processes** — no e2e, no perf, no spikes.
   Written, uncommitted, not yet run on GitHub: `.github/workflows/ci.yml`, one job,
   `./gradlew build --continue` on ubuntu-latest with temurin 21, the test reports uploaded. The SDK
   is read with `GITHUB_ACTOR` and the job's `GITHUB_TOKEN` (`packages: read`), or an
   `SDK_PACKAGES_TOKEN` secret (a classic token, `read:packages`) if the job token may not read
   most-exchange's package (Rationale §3).
   - **Checked locally, as the runner would:** the same command in a clean `eclipse-temurin:21-jdk`
     Linux container, an empty Gradle home, credentials from `GITHUB_ACTOR`/`GITHUB_TOKEN` only:
     the SDK resolved, 366 tests, 0 failures. The first such run found `Trace.close()`'s lost events
     (item 6a), fixed.
   - **Ran green on GitHub** (the user, 2026-10-31), with the job's own `GITHUB_TOKEN`. Its notices
     led to: checkout v7, setup-java v6, upload-artifact v7 (all Node 24), the runner pinned to
     `ubuntu-24.04` (ubuntu-latest moves to 26.04), and **gradle/actions v5, not v6** (the user's
     call: v6's caching is a proprietary component under Gradle's Terms of Use). Not yet run on
     GitHub with these versions.
9. ~~**A deployment setup with Docker**~~ **Done 2026-10-31**, uncommitted (Design §7; `deploy/`
   and its README). Decided with the user: a compose project here that joins most-exchange's
   networks as external ones; one adapter, participant 7, order entry on 9876 and market data on
   9877; the image copies the host's `installDist`. `deploy/fix` (the client as CLIENT7/FEED7) and
   `deploy/smoke.sh`. Verified against most-exchange's dev stack: a 35=D, a cross from participant
   8 through `most send`, the fill at the next Logon, 35=y and 35=W; a clean `restart` (stop marked);
   a SIGKILL of the JVM (7 mark-file refusals, back in 21 s, MsgSeqNum moved on 10,000).
   - **Found:** after that crash, no 35=8 sent offline is resent (Design §8.29), item 10.
   - Also found: `most send --qty` is most-exchange's raw `int64` (4 shares is `400000000`); and
     wiping the adapter's volume while the exchange keeps the participant's orders repeats their
     `clOrdId`s (deploy/README.md). The dev stack's `most` image predates `cancel-all`.
11. ~~**Documentation: the Operator's Manual and the Rules of Engagement**~~ **Done 2026-10-31**,
    uncommitted (the user's request; `docs/manual/` and its README). most-exchange's toolchain and
    stylesheet (markdown-it, headless Chrome), `build.mjs` generalised to two documents:
    `docs/OperatorManual.pdf` (38 pages, most-exchange's seven chapter headings: introduction,
    development and the dev stack beside most-exchange's, production, every configuration key,
    operations, troubleshooting, an index of terms, commands and keys) and
    `docs/RulesOfEngagement.pdf` (31 pages: the FIX session, every order-entry and market-data
    message and tag, order types and states, every reject code and Text, troubleshooting, an index
    of tags, messages and terms). Transcripts and raw FIX are real, from the Docker stack and its
    archive; four diagrams. Every cited section number resolves (checked by script).
    - ~~**Cover licence:**~~ **Done** (2026-10-31, the user: keep CC BY 4.0). `docs/` is CC BY 4.0,
      as most-exchange's: `docs/LICENSE` (the Creative Commons text, as the exchange's) and a
      paragraph in `README.md`; the code stays AGPL.
    - **Found while writing:** the compose file's `restart: on-failure:10` counts restarts over the
      container's life, so after a third crash it stayed down; now `unless-stopped`. And running the
      CI check in a Linux container (item 8) exhausted Docker Desktop's 8 GB VM and crashed both
      stacks (SIGSEGV in Aeron's mapped files); both restarted, the adapter's crash recovery as
      designed. Not verified in the manuals: whether the exchange rejects an off-tick price.
10. ~~**§8.29: a 35=8 sent offline after the crash skip is never resent**~~ **Fixed 2026-10-31**,
    uncommitted (Design §8.21, §8.29; CLAUDE.md 12; R§13). Cause, from Artio 0.182's source:
    `ReplayQuery.jumpPosition` assumes one replay-index record per MsgSeqNum, and the
    `lastSentMsgSeqNum` skip left a 10,000-number hole. Decided (the user, the recommendation):
    the skip is written as 10,000 Heartbeats offline, archived before the bind. Spec first:
    `e2e/run-e2e.sh` step 9 (MSFT added to the e2e) and `OrderEntryBackendTest` +1, both failing
    before the fix; the skip's own test now expects the Logon at +10,001. `./gradlew build` green,
    the e2e 9 of 9, and in Docker a SIGKILL then `deploy/smoke.sh` twice.
    - **Left:** report the lookup to Artio upstream (the user's call: a public issue).

12. **Schema version 8** (the user's priority, 2026-11-01; Design §8.30, R§17). Analysed and decided
    2026-11-01; Design §3.2, §3.3, §5.1–§5.3, §6, §6.1, §8.15, §8.30, §9.3, CLAUDE.md 8 and R§5, §9,
    §17 written. Each step builds and tests green before the next (`spec-first-test` for each):
    1. ~~**The build on v8**~~ **Done 2026-11-01.** The registry's `sbe` and `client` jars say
       `Most-Sbe-Schema-Version: 8`; `SdkSchemaTest` fails on any other. Only `order-entry` broke
       (9 call sites, the rig and one test helper). **A bridge, until steps 2–4:** the derived id
       is sent as its decimal text (`ClOrdId.of(long)`), and a report's id read back with
       `ClOrdId.parseDecimal`, so behaviour is unchanged; marked "Schema 8 bridge" in
       `ShardVenue`, `OrderEntryBackend`, `OutboundReports`, `OrderEntryRig` and
       `OutboundReportsTest`. The bridge's `ClOrdId.of` allocates per send. The rig sets
       `origClOrdId` and echoes `toSeq`. `./gradlew build` green: 367 tests, `order-entry` 247.
       No fence test failed under the SDK's new settling rule; step 6 checks they still cover it.
       Not run: the e2e (needs v8 installs, step 7).
    2. ~~**The store**~~, 3. ~~**Inbound**~~ and 4. ~~**Outbound and the SDK**~~ **Done 2026-11-01, as
       one change:** re-keying the store removed the lookup by derived id the step 1 bridge rested on,
       so none of the three builds alone.
       - **The store:** rows keyed (`participantId`, ClOrdID's 20 bytes); position and epoch on the
         row (`RowCodec` version 2); one index by the id's three words, from a FIX message, a
         report's `char[20]` or the SDK's `ClOrdId`; a cancel names its order by ClOrdID
         (`orderOf`); `ID_COLLISION`, the block and the derived-id map gone; the high-water, floor
         and replay by position within the epoch; `beginEpoch` for step 5; a format mark in `meta`,
         so a store from before schema 8 refuses the start, saying the upgrade is a fresh start.
       - **Inbound:** `ClOrdId.isValid` on 11, refused as §3.3 says (`Texts.CLORDID_NOT_FIT`); an
         unfit 41 finds no order; *same* is the ClOrdID at the same position and epoch; the 35=AF
         order by (epoch, position); the collision, its Logout and `ClientReplies.logout` gone.
       - **Outbound:** a report's row found from its `clOrdId` bytes; `onSettled` and
         `restoreInFlight` with the SDK's `ClOrdId`; `INVALID_CL_ORD_ID` is OrdRejReason 99 with its
         name, as §5.3's "every other" already said (pinned by a test).
       - **Changed from the plan:** the venue port takes the id's 20 bytes, not a `ClOrdId`, so the
         inbound path stays allocation-free and its test still measures 0; `ShardVenue` makes the
         SDK's object, the one allocation per send of §8.30. The trace ring's field is renamed
         `position` (`R_POSITION`), the same value.
       - **Tests:** `OrderStoreTest` rewritten from §3.2 and §6.1 first (30), then the inbound,
         outbound and backend tests; collision tests replaced by their opposite (a held position
         is a new request, end to end included). Mutations, each caught: *same* ignoring the
         position (store and inbound), the high-water ignoring the epoch, no format check, a NUL
         accepted in a FIX lookup, an unfit ClOrdID let through. `./gradlew build` green: 370 tests,
         `order-entry` 250.
    5. **Startup:** `StartupGuards` finds Artio's state lost as §3.2 says, and starts a new epoch.
    6. **The test fakes on v8:** `OrderEntryRig` and the spikes' `Messages` echo `toSeq` and stop at
       it; fence tests follow the SDK's new settling rule.
    7. **For real** (`verify-for-real`): `e2e/run-e2e.sh` against v8 installs of `../most-exchange`,
       with a step for a ClOrdID that does not fit; the Docker stack wiped and brought up again.
       Optionally `e2e/run-perf.sh`, under `perf-claim`.
    8. **The manuals:** the Rules of Engagement (the ClOrdID rule and its rejects; the collision's
       reject and reset gone); the Operator's Manual (the upgrade as a fresh start; the operator's
       reset gone; Artio's state lost). `deploy/README.md`'s note that wiping the adapter's volume
       repeats `clOrdId`s goes.

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


