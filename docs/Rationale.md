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
  (R§5). **Not so, found 2026-10-26** (R§12, Design §8.20): the library handles a 35=D before
  the archive has fsynced it, so a host crash can lose both. The rest of this argument stands;
  this bullet holds only for a 35=D the archive had reached;
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

## 9. Order entry's wiring: one driver, the block after a collision, and what Artio hands over

Decided 2026-10-25 (slice 2b), each the recommendation; Design §2, §3.2, §3.3, §3.5.

**One media driver, the adapter's embedded one.** The alternative was the SDK on a separately run
driver, the host's, which keeps Artio's IPC and archive apart from network traffic. But the adapter
is one process either way: a driver fault in it already takes the FIX side down, and a second
driver is one more process to deploy, configure and supervise. IPC uses no sender or receiver
thread, so the archive's traffic and the gateways' UDP do not compete for them. A host-wide driver
shared with other applications is the case this leaves out.

**A shard's gateways: the directory's, unless the node file lists them.** The directory names one
endpoint per shard, but a co-located shard has a gateway per node, and the adapter must hold them
all to follow the leader (most-exchange Adapters.md §1). Listing them only when needed keeps the
common case free of configuration. An endpoint that moves in a later directory is logged and not
followed: switching a live `OrderEntrySession` to new links is the SDK's business, and it has no
call for it. A restart takes the new endpoint.

**The block after an id collision: accepted, then logged out, lifted by any reset.** The Logon is
authenticated on the engine's framer thread, and the store belongs to the library's. Refusing at
the Logon would need the block shared across threads, and Artio's reject happens before a 141=Y
could raise the `sequenceIndex`, so only an operator could ever lift it. Logging the session out
from the library, on acquiring it, keeps the block on one thread and lets the Logout carry the
reason. Any reset lifts it, the client's own 141=Y included, because what made the collision
possible, a `sequenceIndex` that did not rise, is then gone: every id derived after it is new. The
stricter rule, operator only, was weighed and not taken.

**What Artio hands the library** (found 2026-10-25). Every message its session layer has processed
reaches the `SessionHandler`, session messages included, and messages it has dealt with itself
are flagged `OnMessageInfo.isValid()` false: a MsgSeqNum gap it answers with a resend request, a
SendingTime it rejects, a message after a Logout. A backend that took them all would answer a
Heartbeat with a 35=j, and forward a 35=D Artio had already rejected. The test that shows it sends a
35=D with an hour-old SendingTime: Artio answers 35=3 (SessionRejectReason 10), and nothing reaches
the gateway.

**Artio's encoders wrap, they do not copy** (found 2026-10-25). A generated encoder's setter for an
`AsciiSequenceView`, a `DirectBuffer` or a `byte[]` points the field at the caller's bytes. A later
`CharSequence` setter on the same field copies into whatever the field points at, which may be the
inbound message's buffer, Artio's own. `reset()` points every field back at the encoder's buffer.
So every reply resets its encoder first, and the inbound buffer is never written.

## 10. Reports: a lost row is deferred, lost fills are said, and state follows the 35=8

Decided 2026-10-25 (slice 3), each the recommendation; Design §5.3, §8.17.

**A report for a row the store lost is consumed, for now.** The row can be missing only after a
host crash took RocksDB's unfsynced tail (§6.1, R§4); the 35=D that made it is in Artio's fsynced
archive. Holding the report with `ABORT` until recovery exists would lose nothing for that client
and stall the gateway's report stream for every other participant, until an operator stepped in.
Building the recovery now means reading Artio's archive, which no spike has explored yet. So
recovery is its own slice, after delivery, and until it exists a host crash can cost one client
the reports of the orders whose rows it lost. Counted, so it is seen.

**Fills the client was not told are said, not invented.** After a truncated resend, a mass status
can state a CumQty above the one the client last saw. The fills between happened at prices the
exchange no longer has for the adapter. A 35=8 ExecType F with the order's limit price would keep a
fill-driven client's position right, at the cost of a price that may be wrong and that the client
cannot tell from a real one. Restating the old status as unknown would throw away the one thing
the exchange did state exactly. So the client gets the new CumQty and LeavesQty in an ExecType I,
with AvgPx over the fills known and a Text that says what is missing, and the prices are reconciled
from trade records, as for an ambiguous order (R§6).

**A report changes the store only once its 35=8 was taken.** Applying first and sending second
would leave a refused 35=8 with its report already counted, and the redelivered report would
then be either applied twice or not sent. Sending first makes the redelivery the same report as
the first try. A report the row has already seen, a resend after a restart, is sent again with the
same ExecID, so a 35=8 that was taken but not archived before a crash still reaches the client,
and the client drops the duplicate. Anything that follows from a report but needs its own send (a
parked cancel, a fate's answer) is owed on its row and worked off in the duty cycle, so one refusal
never undoes a report the SDK has already counted.

## 11. Delivery: the archive gates rows as well as the sequence, and every handover is checked

Slice 4, 2026-10-25 (Design §6.1–§6.3, §8.18–§8.19). One decision with the user, the rest found.

**Answers with no `reportSeq` write their rows behind the archive** (decided, the recommendation).
§4's argument covers sequenced reports: if a 35=8 is lost before the archive has it, the stored
`reportSeq` has not passed it and the exchange resends it. A fate, a restatement, a parked cancel's
answer and a gateway's own reject have no `reportSeq`, so nothing resends them. Clearing the row's
owed mark as soon as Artio took the 35=8 left a window of milliseconds in which a host crash could
keep the mark cleared (RocksDB's WAL reaching the disk on the OS's schedule) and lose the 35=8 (the
archive not yet fsynced). The client would never be told, and the store would say it was. Writing
the row only once the archive has the answer turns that into an answer given twice. The cost is a
second store write per such answer, off the send path, and the same tracker the sequence uses.
Sequenced reports keep writing their rows at once, because the stored `reportSeq` must never pass
a report whose row is not stored (most-exchange Adapters.md §5).

**The sequence is per shard.** Each shard is its own engine, numbering each participant's reports
from 1 (`MatchingEngineService`), and each has its own `OrderEntrySession`. The store had one value
per participant, harmless while it was never written. Persisted, one shard would have resumed from
another's number: a resend from too far on, or too far back. So the row records its shard too,
since retention compares a row's last `reportSeq` with its own shard's sequence and the directory
is not there at load to say which shard a security is on.

**Every session is held from startup.** Holding a session only once a report needs it would make
the first report after a restart wait a `requestSession` round trip, holding its shard's report
stream for every participant, as any `ABORT` does. Requesting all of them before the acceptor binds
costs nothing, and makes the request safe without a replay: no client can have logged on yet.

**After a disconnect, the request replays.** Read in Artio's source while building the request: a
session the engine owns and that is logged on is handed over online, with the replay the request
names. The claim of §2 replays from the Logon + 1 for exactly this reason (spike §8.13(d)). A
client that reconnects before the library's request arrives would otherwise have its first
messages skipped, with no gap. Replaying from the last message received + 1 covers both outcomes:
offline it replays nothing, and Artio still hands the session over.

**Two checks on the handover, found by a flaky test.** A test that sent a report just after the
client's Logout lost it in 2 runs of 5: consumed, archived, and its MsgSeqNum reused by the next
Logon. Two things combine. The library still held the session after the Logout, so the report was
written on it, numbered by the library. And the engine, handing the session back offline, read its
last MsgSeqNum from its sequence index, which it brings up to date only with its own stream, not the
library's. So a session that has stopped being active is not written to, and an offline session
handed back below the library's own last MsgSeqNum is released and asked for again. Either alone
would have fixed that test. The second guards the library's own Logout reply too, which the index
also missed once in six runs, and which nothing on the adapter's side can hold back. They cannot
see one case: a client that logs on again within the indexer's lag, before the library's request,
gets its Logon numbered by the engine from the lagging index (§8.19). That is counted, since it
cannot be undone from the library.

**A library reconnect freezes the sequence, as §6.2 already said.** Positions are compared only with
the counter of the connection they were written on, and the old connection's counter is gone. The
alternative, comparing them with the new connection's counter when it extends the same recording,
rests on Aeron's recording extension keeping one position space, which no spike checked. Frozen
until a restart, the error is a longer resend and more rows retained, never a report consumed
unarchived.

## 12. Recovery of lost rows: replayed by the startup request, rebuilt in flight

Decided 2026-10-26, each the recommendation; Design §6.1, §8.17, §8.20. From Artio 0.182's source,
then verified the same day by `RecoveryReplaySpikeTest` (f) and (g).

**Why the startup `requestSession`, not `FixArchiveScanner`.** The adapter already requests every
session offline before the bind (§6.3), and Artio's engine replays archived inbound messages to an
offline session from any (`sequenceIndex`, MsgSeqNum) it is asked for. The messages arrive through
the handler that already decodes them, and only those past the store's prefix are read. The scanner
needs its own Aeron and archive clients and reads a recording from its start. It would also see the
adapter's own answers, which the replay does not; the next paragraph is why that is not needed.
Recovering lazily, per report naming an unknown `clOrdId`, needs the scanner anyway, and never finds
a request that was sent and never answered: it would get no `restoreInFlight`, so no fate, which is an
unknown status the client is never told.

**Why rebuilt in flight, and a second reject accepted.** What the inbound path decides from the
message and the store, it decides again the same way. What it decided from the directory or the
gateway cannot be decided again: the directory is not there at startup, and what a gateway said is
not in the inbound archive. Treating every such request as sent errs only one way. The fence settles
a request the exchange never sequenced as a reject, and a sequenced one comes back with its reports,
because the durable `reportSeq` is older than any row that is lost (RocksDB recovers a prefix, R§4).
A request first rejected here then gets a second reject with the same ClOrdID, which a client can
read as a duplicate. The other way, reading the adapter's answers back from the outbound archive,
avoids that at the cost of the scanner and a matcher from replies to requests. Never resent, because
the exchange does not check `clOrdId` uniqueness: a resent recovered order would be a second order.

**A gap found in R§4, confirmed by spike (g).** R§4 says every row RocksDB can lose is rebuilt from the
35=D in Artio's archive. That holds only if the archive has the 35=D before the order goes out. The
library reads the engine's inbound stream directly, and nothing in Artio waits for the archive's
fsync first. So a host crash after the send can lose the 35=D from the archive as well as the row.
The client's next Logon finds a gap, its PossDup resend finds no row, and the order is sent a second
time under the same `clOrdId`. Every candidate fix costs something: gating the send on the archive's
position adds an fsync to every order; holding a PossDup with no row until its shard's resend has
surfaced every sequenced `clOrdId` ties the client to the exchange's availability. So it was
confirmed by a spike first, and is designed after. The spike found it is not a corner: at
`fileSyncLevel=1`, every one of 500 35=Ds paced one at a time was handled before the archive's
recorded position passed it.

**The fix: send only once archived** (decided 2026-10-26, the recommendation; Design §6.1, §8.20).
The adapter already waits for the archive before it consumes a report (R§4, R§11); this is the same
rule for the other direction. A request reaches the exchange only once its 35=D or 35=F is fsynced,
so R§4's first bullet holds again: a row a host crash takes is either rebuilt from the archive
(§8.17) or belongs to a request that never left, which the client's resend may send. The send moves
off the message callback into the duty cycle. Held in the callback with `ABORT`, each order would
stall the library's whole inbound stream for one fsync, every session's heartbeats with it, and
orders would go out one fsync apart. From the duty cycle they go out in arrival order, in batches,
as far as the recorded position has reached. That also changes non-negotiable 2: a back-pressured
send is retried from the row, not by Artio redelivering the message.

The rejected options each cost more where it matters. An fsync of RocksDB per order blocks the
library thread, every session behind it. The exchange checking `clOrdId` would put the guarantee
where it arguably belongs, but adds state to the matching path and changes the contract (most-exchange
Adapters.md §2). Fencing a PossDup with no row needs `restoreInFlight` after the SDK has started, and
rejects a lawful resend of a request that never went out. What sending once archived costs is the
archive's fsync on every order's path: not measured yet, and no figure is given without
`perf-claim`.

**As built (2026-10-27, slice 4c): where the replay starts, and what it may not decide again.**
The replay starts at the participant's high-water + 1, and that is complete only because rows are
inserted in `clOrdId` order. Artio hands the library no message ahead of a gap (`isValid()` false;
the handler drops it, and the client resends it in order), and RocksDB recovers a prefix (R§4). So
every row a crash takes lies above every row it leaves. Retention breaks that, because it removes
rows from below; so the floor records the highest one it dropped, in the same batch as the drop.
A request rejected here raises the high-water too. Without that, the requests a client sent last,
rejected for a reason that needed the directory ("exchange unavailable" before it came), would be
replayed at every clean restart. They would be rebuilt, fenced, and rejected a second time. The
second reject was accepted for a host crash, not as the cost of every restart. The write is on
the reject path only.

Recovery decides again only what cannot have changed since the message was handled. That is the
message itself, and the store's state that only moves forward: a ClOrdID held, an order terminal,
an order's acknowledged OrderID. The store after a crash is a prefix of an earlier time, so a
write the crash lost can have acknowledged an order, cleared its ambiguity, or answered a cancel
that was pending. A check that reads any of those is skipped, so recovery errs towards *sent*,
which the fence turns into a reject if it was wrong. It never errs towards a request that is not
held, which would leave the exchange's reports for it with no row.

The row of a request rebuilt before the directory came is stored *unresolved*. At the next start
the replay begins no later than the lowest such row, so a stop before the directory comes loses
nothing. Holding the security in the row instead would change the row's format for a state that
lasts only until the first directory.

## 13. After an unclean stop, the outgoing MsgSeqNum moves on before the bind

Found 2026-10-28 by the first end-to-end run (Design §8.21), and confirmed by spike (h).

Artio's framer writes a message to the socket while the archive records the same stream on its
own. Nothing orders the two, so a process can die after a 35=8 reached the client and before the
archive had it. The report is safe: its `reportSeq` was not consumed (R§11), so the exchange
delivers it again and the adapter writes it again under a new number. The *number* is not safe.
The restarted engine counts from the archive, and its first message, the Logon, reuses one the
client has. FIX gives the client one answer to that, a Logout, "MsgSeqNum too low", and the session
cannot be resumed without an operator.

The adapter cannot tell how far the archive trailed the socket. So after a stop it cannot vouch
for, it moves every session's number on by 10 000. The client then sees a gap, not a reuse, and
FIX's ordinary resend closes it: the adapter has nothing in the range, so one gap fill covers it.
The margin costs only numbers. The spike lost 1–3 messages at 500 a second; the archive can trail
by more under a slow disk, which is why the margin is large and not measured.

- **The skip is made on the offline session the library holds before the bind**, so the library,
  not the engine, numbers the client's next Logon (h2). ~~A Heartbeat is then written and waited for
  in the archive, so the skip survives a second crash before the client is back. Without it,
  Artio's replayer complained of an empty range (h2a).~~
- **The skip is 10 000 Heartbeats, never a setter** (2026-10-31, superseding the setter and one
  Heartbeat; Design §8.29). Artio's resend lookup (`ReplayQuery.jumpPosition`) assumes its replay
  index has one record per outbound MsgSeqNum, and jumps ahead by the difference. A number skipped
  by `lastSentMsgSeqNum` is a hole the jump overshoots, so every later ResendRequest found nothing
  and was answered with a gap fill: a 35=8 written offline after a crash never reached its client,
  for as long as the hole stayed in the index's ring. The spike (h2) checked only the gap fill at the
  first Logon, which a jump of nine records happened to land on. Writing each number as a message
  keeps the index whole and is archived before the bind, so the skip still survives a second crash.
  It costs ~0.7 MB of archive per session per crash. Patching Artio was rejected because the adapter
  runs Artio's pinned release (R§2); a new `sequenceIndex` because Artio would then gap-fill every
  archived report from before the crash (R§14).
- **"Clean" is marked after Artio's engine has closed,** because only a clean close waits for the
  archive to record what was sent. It is cleared at start with a synced write: a start that loses
  the clear to a host crash would take the next crash for a clean stop. A missing mark errs towards
  a skip, which costs a client one gap fill; a mark that is wrong would cost it its session.
- **Every start was rejected** as the trigger: it would cost every client a gap fill at every
  restart, to save a flag.

Not covered, and stated so: the client has the 35=8 that died with the process, and receives the
same report again under a new number. The ExecID is the same (§5.3); PossResend is not set, because
the adapter cannot tell which ones the client has.

## 14. A gap Artio leaves open is asked for by the library, never rewound

Found 2026-10-28 by spikes (h3) and (h4), and decided 2026-10-29 after spike (i) (Design §8.21).

When a client's message arrives above the number Artio expects, Artio asks for a resend and counts
the received number up to that message at once (`Session.requestResend`). From then on, Artio's
count no longer shows that anything is missing. If the connection ends before the resend is
answered, the next Logon expects the number after the Logout and asks for nothing (h3). If the
client answers only part of the range, every later message is marked invalid and dropped until it
reconnects, and then (h3) forgets them too (h4). Either way, a 35=D the client sent is never sent
on, rejected or answered.

The first answer was to rewind the received number to the gap's start, then log out, so that
Artio's own Logon handling asks again. Artio's only public rewind starts a new `sequenceIndex`.
Spike (i) showed the rewind did bring the client's messages back, across a restart as well. But
Artio's replayer answers a ResendRequest only from the session's current `sequenceIndex`
(`Replayer.java:226`), so every message the adapter sent before the rewind is gap-filled over. A
35=8 the client had not received at the disconnect would then be gone for good: its `reportSeq` was
consumed once the archive had it (R§11), and the exchange will not send it again. That trades one
loss for another.

The library's own ResendRequest has no such cost. In a stall, the first message resent is the end
of Artio's own request range, which clears `awaitingResend`. The rest are PossDup messages below the
expected number, which Artio passes on as valid. After a disconnect, the request goes out straight
after the next Logon. The session stays logged on at the same `sequenceIndex`, so neither the
derived `clOrdId` (R§5) nor the collision block (§3.3) is disturbed. The store's ClOrdID check makes
asking twice safe.

- **The gap is the library's to know,** because Artio's count has already moved past it. So the
  library tracks the last message it received in order, and stores a gap that is still open at a
  disconnect in `meta`, so it survives a restart.
- **This rests on Artio 0.182's behaviour,** read in its source and pinned by `RewindSpikeTest`.
  An Artio bump (non-negotiable 6) reruns the spike.
- **A client that never fills the gap** is asked a bounded number of times, then logged out with a
  Text naming the range, so the loss is at least stated.
- **The fallback, a Logout alone, was rejected:** it leaves the order to a client that reads the
  Text.

The replay skip was found while building this (2026-10-29), and the defect predates it. Artio's
archive holds every inbound message, including ones the library dropped as invalid. The startup
replay of R§12 hands them back as valid, so recovery took a dropped 35=D for a request that might
have reached the exchange. It rebuilt the 35=D in flight, the fence would reject it "not sequenced",
and the client's resend counted as a duplicate. The client was told its order was refused, though
it had never been sent. The stored gap is the library's record of what it dropped, so the replay
skips a non-PossDup message numbered within it. The cost is stated in Design §2: the gap is one
range, and after a host crash it can make the replay skip a message that did arrive.

## 15. The startup requests go one at a time, and a replayed message is checked against its session

Found 2026-10-30: the e2e's one unexplained run of 8 (Design §8.17, §8.21).

After the SIGKILL, the startup replay of R§12 raised an id collision and blocked CLIENT1. Afterwards
the store and the archive agreed one for one, so neither had lost or invented anything. The
collision came from routing, not from data. Artio 0.182 hands every offline session over with the
same connection id, `NO_CONNECTION_ID` (`Framer.HandoverOfflineFixSession`). The catch-up replay
rewrites each replayed message to that id (`CatchupReplayer`). The library routes an inbound
message by connection id, to the subscriber stored under it last
(`LibraryPoller.connectionIdToSession`). The adapter asked for every session at once. The second
handover landed while the first was still replaying from the archive, so CLIENT2's 35=Ds reached
CLIENT1's handler. They were rebuilt as participant 1001's rows. Both clients are at
`sequenceIndex` 0 with similar MsgSeqNums, so CLIENT1's own 35=D at the same number then met a row
holding another ClOrdID, which is the definition of a collision (§3.3).

It shows only when two clients both have 35=Ds above their high-water at the crash, which a SIGKILL
under load rarely leaves. The quiet case is worse than the collision. A misrouted 35=D numbered above
the other participant's high-water is rebuilt in flight for the wrong participant, and the fence
rejects it to the wrong client. Its own client's row is never rebuilt, so the exchange's report for
it is dropped as a report for a lost row (R§10), and that client never hears of its order.

- **One request at a time** is the fix. The next participant's request goes out only once the one
  before has completed with its session held. A request's reply comes after its replay on the
  engine's inbound stream (spike (f), R§12), so once it has come, every message replayed for that
  session has gone to its own handler. The cost is one handover's latency per order-entry session at
  startup, before the bind, within the existing 10 s startup limit.
- **The handler checks a replayed message's SenderCompID against its session's client,** and a
  mismatch refuses the start. Only the ordering above prevents the misrouting, and it rests on Artio's
  reply following its replay. If that changes in a later Artio, the check refuses the start, which
  is better than a rebuilt row for the wrong participant or a dropped row for the right one. The
  check parses the header, so it runs on the startup replay only, where allocating is allowed.
- **Rejected: routing by the message's CompIDs** to the right participant. That would build on
  messages Artio delivered with the wrong `Session`, and would hide the misrouting instead of
  preventing it.
- **Only the startup replay is affected.** A request after a disconnect replays from the last
  received + 1, which is nothing for an offline session (R§11). A client back first is handed
  over online, with a connection id of its own. Other events the library routes by connection id
  (slow status, throttle) mean nothing to an offline session.
- **Pinned by an end-to-end test,** `rows two sessions lost are each rebuilt from that session's own
  archived messages, for its own participant`, which failed every time before the fix. An Artio bump
  (non-negotiable 6) runs it with the rest of the build. It belongs with (h3) and (h4) on the list
  to report to Artio.

## 16. Market data has its own engine

Decided 2026-10-31 by the user, on the measurement of Design §8.22 (Measurements A3, A4).

One Artio engine served both backends, and its archive, fsynced for order entry (R§4, R§11, R§12),
recorded every 35=W and 35=X too. A 35=D waits for the archive before it is sent, and a `reportSeq`
waits for it before it is consumed, so market data's volume sat in order entry's path. Measured with
four feed sessions at ~4,000 35=X/s: +2 ms p50 and +5.5 ms p99 at 1,000 orders/s. Artio archives an
engine's messages all or none (`logInboundMessages`, `logOutboundMessages`), so the only way out of
order entry's archive is another engine.

- **Its own port.** A session logs on at its role's port and is refused at the other as an unknown
  session, because each engine authenticates only its role's sessions. The user's deployment has a
  participant run a dedicated market-data session beside its order-entry one, so the second port
  costs nothing in practice; one session for both roles was scrapped with it (Design §8.26).
- **fileSyncLevel 0, and emptied at start.** A market-data session starts at MsgSeqNum 1 at every
  Logon and is never resent anything from before it (Design §4), so nothing in its archive is read
  after a restart. Fsyncing it buys nothing; keeping it across restarts only grows it.
- **Not taken: no archive at all** (`logOutboundMessages(false)`). A client's ResendRequest within a
  session would then get a gap fill instead of the 35=X it missed, a change to what a client sees
  (Design §8.28).
- **What it did not fix:** at saturation market data still takes ~8% of order entry's capacity on the
  laptop (A4). That share is CPU or the disk, not the archive.

