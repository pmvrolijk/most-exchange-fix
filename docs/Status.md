# Status

Where the adapter stands, what is open, and what to do next. **This is the session entry point** —
read this, not the handover. [`Handover.md`](Handover.md) is the archive of work records.

Last updated 2026-10-15. **Nothing is implemented. Design.md is now a design, §1–§10, not a
template.** 2026-10-15: the build reads the SDK from GitHub Packages (§3, item 1). The
2026-10-09 session before it changed documents only (Handover §2c).

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
- **Reports for a logged-out session** go through Artio's `SessionWriter` (Design §6.3).
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
| Branch | `master`, no commits (the user commits) |
| Licence | AGPL-3.0-or-later (`LICENSE.md`); the SDK it links is Apache-2.0 |
| Modules | 3 — `fix-session`, `order-entry`, `market-data`; packages `nl.lamia.most.exchange.fixadapter.*` |
| Tests | 4, dependency alignment and SDK wiring only. Last run 2026-10-15, passing against the SDK from GitHub Packages |
| Depends on | most-exchange `client` + `sbe` 0.1.0-SNAPSHOT (`20261004.105958-1`) from GitHub Packages (`pmvrolijk/most-exchange`), Artio 0.182 |
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
   - the `OrdStatusUnknown` tag number and the exact 2964 code set (§8.1, §8.10). Small; settled
     with the dictionary.
   - **Unverified assumptions the design rests on**, each for a spike (§8.13):
     - Aeron Archive's `RecordingPos` means fsynced at `fileSyncLevel=1`;
     - Artio re-indexes after a crash;
     - `SessionWriter` and a live `Session` agree on MsgSeqNum;
     - one engine routes sessions to two libraries.

     If (a) or (c) fails, §6.2–§6.3 need a new mechanism, not a tweak.

## 3. To do next

In order. The SDK changes and the spikes come before code, because they decide whether
`order-entry`'s persistence design stands. `fix-session` does not depend on them, so it may run in
parallel with item 2.

1. ~~**Switch the build to GitHub Packages**~~ **Done 2026-10-15.** `mostSdk.url` points at
   `maven.pkg.github.com/pmvrolijk/most-exchange`; `githubUser` + `githubPackagesToken` (classic,
   `read:packages`) in `~/.gradle/gradle.properties`, `GITHUB_ACTOR`/`GITHUB_TOKEN` in CI. Verified:
   `--refresh-dependencies` fetched the metadata from GitHub, 4 tests pass. The GitLab CA step is gone.
2. **Three changes to most-exchange's `client`**, made there by its own procedure and published.
   Nothing is changed there yet:
   - `onReport` may abort, with a controlled poll (Design §6.2, §8.9);
   - `restoreInFlight(participantId, clOrdId)` before start (§3.4, §8.9);
   - an allocation-free `PriceCodec` from and to `DecimalFloat` (§8.11).
3. **Artio spikes** (Design §8.13), against 0.182 with a real initiator: `RecordingPos` after the
   fsync, the replay index after a crash, `SessionWriter`'s MsgSeqNum, sessions routed to two
   libraries by role. They decide whether §6.2–§6.4 stand as written.
4. **`fix-session`:** an Artio FIX 4.4 acceptor that accepts a Logon only from a configured session
   (CompIDs plus 553/554), with the engine's archive and logs on durable paths. Then the
   dictionary's code generation. Tested spec-first against a real Artio initiator.
5. **`order-entry`:** 35=D/35=F through `OrderEntrySession`, stamped with the session's
   participant, and 35=8 back. The fates are mapped per CLAUDE.md non-negotiable 1, tested
   spec-first. Then this repo's **e2e**, against most-exchange's real processes. That is the first
   test of `OrderEntrySession` through a real failover (most-exchange Status §3, item 13(a)).
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


