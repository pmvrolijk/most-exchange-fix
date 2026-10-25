# Rationale

Why each rule in `CLAUDE.md` exists. Cited from there as → R§n.

## 1. The exchange-facing logic lives in the SDK, not here

Decided with the user on 2026-10-03; the full reasoning is most-exchange `docs/Rationale.md` §17.

The hard part of an adapter is not FIX. It is:
- switching gateways;
- the `reportSeq` ledger;
- the resend fence;
- the mass status that closes a truncation.

That logic is `OrderEntrySession` in most-exchange's `client`. There it is tested against a model of
the exchange (ten mutations each caught) and used by the exchange's own CLI. A copy here would be
tested by nothing that changes when the wire does. When the adapter needs more, the change goes into
`client`, which the composite build (§3) makes as cheap as a change here.

## 2. Artio is pinned to the exchange's Aeron

Each Artio release is built on one Aeron release. Here is how they line up:

| Artio | Aeron |
| --- | --- |
| 0.182 | 1.53.0 |
| 0.183 | 1.53.1 |
| 0.184 | 1.53.2 |
| 0.185 | 1.53.3 |

The exchange runs 1.53.0. Gradle resolves the highest version requested, so Artio 0.185 would
quietly move the SDK's `aeron-client` onto 1.53.3 too. The adapter would then run an Aeron the
exchange has never run against, and nothing would say so.

`DependencyAlignmentTest` reads the Aeron on the classpath and fails on anything but the exchange's
version. It was checked by bumping Artio to 0.185, which failed it.

Note that the adapter runs Aeron's **modular** jars (`aeron-client`, `aeron-archive`,
`aeron-driver`), not the exchange's `aeron-all`. On the modular jars, most-exchange saw a CLI's first
offer back-pressured where `aeron-all` was not (most-exchange Status open issue 18, unexplained).
`OrderEntrySession` retries back-pressure by design, which is why that is safe here. The adapter's
own code must do the same (CLAUDE.md non-negotiable 2).

## 3. The SDK comes from the registry; the sibling build is opt-in

Decided with the user on 2026-10-04. This reverses the first setup, where `../most-exchange` was
always part of the build whenever it was checked out (Handover §2a).

**The registry by default.** The adapter builds against what most-exchange has *published*, as any
other adapter would. A build then means the same thing on every machine and in CI, and an SDK change
reaches the adapter only once it has been released. An `exclusiveContent` filter keeps the SDK's
group to that one repository and everything else away from it.

**GitHub.** The most- projects are expected to move to public GitHub. The registry is one
property (`mostSdk.url`), and so are the credentials, so the move touches settings and pipelines,
not code. GitHub Packages' Maven registry wants a token even to read a public package; Maven Central
does not. That is a choice to make at the move.

**Superseded in part, 2026-10-09: GitHub, not GitLab.** The user decided that the adapter pulls the
SDK from most-exchange's **public GitHub repository**, through GitHub Packages
(`https://maven.pkg.github.com/pmvrolijk/most-exchange`, most-exchange Adapters.md §0). What stays
the same: the registry is still the default, and the sibling build is still opt-in. What changes:
- `mostSdk.url`;
- the credentials: a GitHub username and a `read:packages` token, because GitHub Packages wants one
  even to read a public package;
- the CA trust step, which only the private GitLab needed.

The build was switched on 2026-10-15. Locally the token is `githubPackagesToken` in
`~/.gradle/gradle.properties`, not `GITHUB_TOKEN` in the shell, which `gh` would take over its own login.

## 4. A report is consumed only behind the fsynced archive

Decided with the user on 2026-10-09 (Design.md §6.2–§6.4).

The guarantee the user asked for is that **no execution report is lost**. The SDK's `SessionStore`
holds the one number that decides what the exchange is asked to resend after a restart: the first
missing `reportSeq`. Everything below it will never be asked for again. Look at each place a report
can be when the adapter dies:

| Where the report was | What it costs if the store had passed it |
| --- | --- |
| Received, its 35=8 not yet written | Lost. The exchange will not resend it, and Artio never had it |
| 35=8 written, not yet in the archive | Lost, if it had not reached the socket. If it had, the client has it, but Artio's next MsgSeqNum after the restart reuses its number |
| 35=8 in the archive, page cache only | Lost on a host crash, with the same reuse |
| 35=8 in the archive, fsynced | Safe. FIX's resend delivers it |

So the stored value may pass a report only once its 35=8 is fsynced in Artio's archive. Saving it on
receipt, which the SDK does by default, loses a report to a crash in any of the first three rows.

**Why the lag lives in the adapter, not the SDK.** The SDK knows which reports *arrived*. Only the
adapter knows where their 35=8s went and whether Artio has archived them. Treating `save()` as a
ceiling and storing the archived prefix below it needs nothing from the SDK.

**Why back-pressure still needs the SDK.** `onReport` used to return nothing. A 35=8 that Artio
refused would then belong to a report the ledger had already counted, so the adapter would need its
own outbound queue: a copy, a bound, and a policy for when it fills. Letting `onReport` abort leaves
the fragment in Aeron instead. That is non-negotiable 2 in the other direction. The head-of-line
blocking it causes across participants is already there, because one library has one outbound
publication.

**Why RocksDB need not be fsynced when the archive is.** Every RocksDB write is one of these:
- a row the 35=D can rebuild, and Artio's archive holds the 35=D, found by the derived `clOrdId`
  (R§5);
- the durable `reportSeq`, which, if lost, goes *back*, and so means a resend and a duplicate the
  deterministic ExecID lets the client drop;
- the `sequenceIndex` guard, which Artio's own state restates.

A host crash therefore costs time at restart, not correctness. An fsync per put would cost latency
on every order.

**This holds only because RocksDB recovers a prefix.** Under point-in-time WAL recovery, a crash
leaves the writes up to some point and none after it. The durable `reportSeq` is written after the
order-row updates of the reports it passes. So if it survived, they did too. Under a recovery mode
that tolerates holes, a surviving `reportSeq` could cover a lost terminal update. The order would
then look open, be missing from the next mass status, and be wrongly declared ambiguous, although
its 35=8 had reached the client.

**Why send to the offline session.** The alternatives keep the report in the exchange's ring (do
not consume it, ask again at logon) or in the adapter's memory. Both are bounded by the ring's
retention if the adapter crashes meanwhile. Past it, the resend comes back `TRUNCATED`, the mass
status recovers open orders only, and fills are lost until the exchange has a drop copy. Sending to
the offline session puts the 35=8 in the same fsynced archive as every other.

**Why through Artio's `Session`, not a `SessionWriter`** (2026-10-15). MsgSeqNum belongs to the FIX
session and Artio keeps it (Design §2). An offline `Session` is numbered by Artio from its persisted
state, also after a restart. A `SessionWriter` takes the number from its caller and writes no
header, which would make the adapter a second keeper of the sequence. The spikes showed the writer
works, and what it costs: a hand-written header with a real-clock SendingTime, and a sequence the
adapter would have to persist itself.

**Why reports wait for `requestSession` after a disconnect** (2026-10-15, spike). With engine-owned
acceptance, a disconnect gives the session back to the engine. The library closes its `Session` and
keeps it only in a cache, yet `trySend` on it still succeeds: the 35=8 is numbered and archived.
When the client logs on again, Artio keeps the library's numbering only for a session the library
holds. For a cached one it numbers the Logon from the engine's index, which lags the library's
stream. The Logon and the live reports after it then take MsgSeqNums the offline reports already
had. The client never asks for those reports, because it sees no gap. Under load that happened in
most handovers, to reports that were archived, so §6.2 would have consumed them: the loss this
section exists to rule out. Holding them costs one `requestSession` round trip per disconnect,
about 15 ms in the spike, through the same ABORT as back-pressure.

## 5. The exchange's `clOrdId` is derived from Artio's sequence

Decided with the user on 2026-10-09 (Design.md §3.2).

The exchange needs an `int64` `clOrdId`, unique per participant for the trading day. A FIX ClOrdID
is a string. An allocated counter would be state to persist before each send, and a counter lost in
a crash repeats.

`(sequenceIndex << 32) | MsgSeqNum` of the inbound 35=D or 35=F is already unique per session, so per
participant (one session, one participant), and Artio already persists it. It also makes the
crash cases converge:
- a PossDup resend of a 35=D carries the same MsgSeqNum, so it derives the same `clOrdId`;
- a report for an order whose store row was lost points back at its 35=D in Artio's archive.

It breaks only if Artio's state is wiped while the exchange still holds orders from the old ids.
That is why the store remembers the highest `sequenceIndex` and the adapter refuses to start below
it.

## 6. An ambiguous order is restated unchanged, with a flag

*Two routes to it* (added 2026-10-09 in review). The SDK settles unanswered requests. An order whose
first report had arrived and which then finished in a truncated window is invisible to the SDK, so
the adapter declares it, by its absence from a completed mass status (Design.md §3.4). A restart
loses the SDK's in-flight set, which is why `restoreInFlight` hands it back. Without it, those
requests would all have to be ambiguous, including the ones a complete resend proves were never
sequenced. Settling them is exchange-facing logic, so it belongs in `client` (R§1).

Decided with the user on 2026-10-09 (Design.md §3.4).

What is proven about an `AMBIGUOUS` order is that it is **not open**. What is unknown is whether it
**filled**. The harm to avoid is a double execution, and that comes from the client re-entering the
quantity. So the question for each FIX representation is what a client that ignores our flag will
do:

| Representation | What it claims | What a generic client may do |
| --- | --- | --- |
| Reject (39=8) or Cancel (39=4) | Did not fill | Re-enter everything. **Double execution** if it had filled |
| Done for Day (39=3), LeavesQty 0 | Not fully filled, no more today. On GTC/GTD: working again tomorrow | Re-enter the remainder, or wait for tomorrow. Both are wrong if it filled |
| Last status restated, with the flag | Nothing new | Keep showing it as working. A cancel then gets a 35=9 saying "status unknown" |
| Silence | Nothing | Keep showing it as working, and never be told unless it asks |

The restatement is the only one that claims nothing it cannot prove *and* tells the client. Its
worst misreading, "still working", is conservative: it leads to a cancel or a phone call, never to a
second order. FIX 4.4 has no OrdStatus that means "unknown", so the flag is a user-defined tag.

## 7. The trading date is the adapter's own calendar

Decided with the user on 2026-10-09 (Design.md §3.6), against the recommendation.

The engine expires an order when `0 < expireDate < tradingDate`, so a day order needs the engine's
trading date at entry, and the adapter does not receive it. Three options were weighed:
- **The engine resolves a "day" sentinel at entry**, as it already resolves `smpId = 0`. No two
  components would ever need to agree on a date. It needs a schema, engine and SDK change.
  *Recommended, not chosen.*
- **The exchange publishes the date** (the directory, say). It is still stale for one cycle at the
  roll.
- **The adapter's own calendar.** *Chosen:* no exchange change, and it ships first.

What it costs, and why it is acceptable:
- **Its reach is kept to day orders.** GTD dates go to the engine unchecked, and retention does not
  depend on the calendar.
- **The roll falls in the closed window**, where orders are refused `MARKET_CLOSED`, so on
  schedule the two dates cannot disagree while it matters.
- **Off schedule they can.** An adapter *behind* the engine gets `ORDER_EXPIRED` rejects, which is
  loud. An adapter *ahead* of it silently turns day orders into two-day orders. That is what
  `POST /trading-date` is for, and why the operator has to use it for any session off the calendar.

**Revisit** if off-calendar sessions turn out to be common, or when most-exchange is changed for
another reason near `NewOrderSingle`. The sentinel then becomes the cheaper path.

## 8. The dictionary is FIX 4.4's own, cut to what the adapter handles; validation is the backend's

Decided with the user on 2026-10-20 (Design.md §5.4).

**Where FIX 4.4 comes from.** Artio ships only a small session dictionary, so the adapter needs a
base. Three were weighed:
- **The FIX Trading Community's FIX 4.4 repository, cut to the message types the adapter handles,
  each with its complete structure.** *Chosen.* Apache-2.0, the standard's own publisher, and
  machine-readable, so the cut is a script rather than a transcription. Complete structures keep
  Design §5.1's promise that a standard field the adapter does not read is accepted and ignored.
- **QuickFIX/J's `FIX44.xml`.** Complete and widely used, but its licence carries an acknowledgment
  clause and name restrictions, the shape of Apache 1.1, which the FSF holds GPL-incompatible. Not
  worth the question in an AGPL project, and several thousand generated classes besides.
- **Only the fields the adapter reads, unknown fields let through.** Smallest, but every other
  tag, a misspelt custom tag included, would be dropped silently, and §5.1's accepted-and-ignored
  standard fields would be indistinguishable from garbage.

**2964=3 is a session reject, not an order reject** (against the recommendation). FIX Latest's
CancelAggressivePassive has no exchange equivalent. Keeping it out of the dictionary lets the
decoder's own validation refuse it, the same way as any other value outside a code set; the cost is
that the client gets a 35=3 rather than a 35=8 on its ClOrdID.

**Why validation is the backend's.** The design first assumed Artio refuses an invalid application
message before the backend sees it. Artio 0.182 does not: `SessionParser` checks only the MsgType
and header of a message that is not a session message, and hands the body to the library as is.
A backend that trusted Artio would forward to the exchange a 35=D with any tag or value in it. So
every application message is decoded and `validate()`d by the backend, and a failure is answered
with a 35=3 from the decoder's `rejectReason` and `invalidTagId`.

**Why the acceptor refuses to start without the switches.** Unknown tags are refused only with
`-Dfix.codecs.reject_unknown_field=true`, which Artio reads once per JVM, and which is off by
default. A launch script that forgot it would silently accept tags Design §5.4 says are refused.
A refusal to start makes the omission loud. Its test runs in a JVM of its own
(`:fix-session:testAcceptorGuard`), since the switch cannot be turned off inside one.
