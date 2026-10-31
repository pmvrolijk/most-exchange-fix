# 5. Daily operations, order flow and monitoring

## 5.1 The trading day

The exchange's session phases, from pre-open to the close, are the exchange's (most-exchange
Operator's Manual, 5.1). The adapter takes part in one way: **it stamps each day order with the
trading date its own calendar gives** (4.4). At the calendar's roll the date moves on; day orders
from then on carry the next trading day.

- **Day orders** expire at the exchange at the end of their date; the client gets a 35=8
  ExecType C, *expired*, as the engine reports it.
- **GTC and GTD orders** stay open across days, at the exchange and in the adapter's store.
- **The store is pruned at start.** A finished request entered before the current trading date, with
  no report owed and no resend that could bring one, is dropped as the adapter starts; open and
  ambiguous orders are kept. Nothing is pruned while running, so a long run's store only grows;
  a restart prunes it.

::: todo Session schedule and end-of-day reset
The adapter does not log sessions out at the close, nor reset their sequence numbers at the end of
the day (Design §8.2, §9.1). Sessions may stay logged on across days; sequence numbers run on until
a client logs on with 141=Y.
:::

## 5.2 Sessions

### Logon

A Logon is accepted when it matches a configured session in full (4.7); the adapter answers with its
own Logon, HeartBtInt echoed. Anything else gets a Logout, Text `Logon refused`, at MsgSeqNum 1,
and the connection is closed. **The Logout never says what was wrong**: CompIDs, username and
password are all the same refusal. What the client sees:

```console
$ fix listen --port 9876 --sender CLIENT7 --user client7 --password wrong …
Logout MsgSeqNum=1 Text=Logon refused
```

and the adapter's archive shows the attempt, password masked by Artio:

```
8=FIX.4.4|9=093|35=A|49=CLIENT7|56=MOST|34=1|52=20261031-18:55:20.729|98=0|108=10|141=N|553=client7|554=***|10=252| (AUTH_REJECT)
```

### Sequence numbers

**Order-entry sessions are persistent.** MsgSeqNum is Artio's, across Logouts and adapter restarts.
A Logon numbered above what the adapter expects is accepted and the adapter asks for a resend; one
below is refused. Numbers reset only when the client logs on with ResetSeqNumFlag (141=Y): the
client then gives up every message it had not yet received, and is expected to ask for a mass
status (35=AF) to recover its orders.

**Market-data sessions are transient**: MsgSeqNum 1 both ways at every Logon, and nothing is ever
resent. The client logs on with 141=Y, or at 1.

### Gaps the adapter asks for

If messages from a client go missing (a gap Artio's own resend has not closed within a second, or a
connection that ended with one open), the adapter sends its own ResendRequest, and again each second
without progress. After three without progress it logs the session out. The log lines, as
formats:

```
order entry: participant <p>: messages <from> to <to> missing; ResendRequest <n> (Design.md §2)
order entry: participant <p>: messages <from> to <to> not received; resend them; logged out after 3 ResendRequests (Design.md §2)
```

The gap is kept across a disconnect and a restart, and asked for again straight after the next
Logon.

### An id collision blocks a session

The exchange's `clOrdId` is derived from the session's sequence (1.5). If Artio's sequence went back
without its `sequenceIndex` rising (its state lost or restored from an old copy), a new request
would derive an id an older one holds. The adapter rejects it, logs the session out with Text
`session sequence reset required`, and **refuses its Logons until the sequence is reset**. Today
the reset is the client's: a Logon with 141=Y lifts the block. The log lines, as formats:

```
participant <p>: id collision, session blocked until its sequence is reset (Design.md §3.3): <detail>
participant <p>: back at sequenceIndex <i> after a reset; block lifted (Design.md §3.3)
```

## 5.3 The order path

1. **Artio receives the 35=D** and records it in the archive. The library thread decodes and
   validates it; a malformed message is a 35=3, an order the exchange cannot carry a 35=8 reject
   (the Rules of Engagement list each).
2. **The order store gets a row**, *to send*, and the message is consumed.
3. **The archive gate:** the row is sent to the exchange only once the archive has recorded its 35=D,
   fsynced. Then a host crash cannot leave the exchange holding an order whose 35=D the adapter has
   forgotten, which a client's PossDup resend would turn into a second order.
4. **`newOrder` to the gateway**, through the SDK. A back-pressured send stays on its row and is
   tried again in the next duty cycle.
5. **Reports** come back on the gateway's execution report stream, become 35=8 (or 35=9 for a
   cancel refused), and update the row.

Refused at the adapter, never sent: anything malformed, market and stop orders, IOC and FOK, a
replace (35=G), and every order and cancel while the exchange is unavailable: before the directory,
before a shard's opening mass status, with no gateway. Those get Text `exchange unavailable`, and
the client may retry with a new ClOrdID.

A cancel sent before its order's first report is **parked** on its row and sent when the report
brings the exchange's order id; if that report is terminal, the cancel is answered *too late*.

## 5.4 Delivery

**A report is consumed from the exchange only once its 35=8 is fsynced in the archive.** The store
keeps, per participant and shard, the first report sequence number not yet durably delivered; it
advances only past reports whose 35=8 the archive has recorded. After any restart the adapter asks
the exchange to resend from there.

- **A report for a logged-out client** is written to its session offline, numbered by Artio and
  archived. At the next Logon the client sees the gap and asks for a resend; the report comes back
  PossDup=Y (2.6). Delivery does not depend on how long the exchange keeps its reports.
- **A report delivered twice** across a restart carries the same ExecID, the exchange's report
  sequence number, so the client can drop the second.
- **A 35=8 Artio refuses** (back-pressure) is not consumed: the report waits on the exchange's
  stream until Artio takes it.

## 5.5 An order whose fate is unknown

When a gateway fails over, the exchange's resend fence settles every request in flight. A request
that never reached the exchange is rejected `not sequenced (gateway failover)`, and the client may
send it again with a new ClOrdID. One the fence cannot settle, because the resend was truncated, is
**ambiguous**: not open at the exchange, but whether it filled is unknown.

The adapter **never reports an ambiguous order as rejected, cancelled, expired or done**: that would
invite the client to re-enter a quantity that may have traded. It restates the order as the client
last knew it, ExecType I, with OrdStatusUnknown (5001) = Y and Text
`STATUS UNKNOWN: not open at the exchange; fills since CumQty=… unrecoverable until reconciled`. A
cancel for it is answered locally with the same flag. A later report for it clears the flag. The
log line, as a format:

```
participant <p>: order <clOrdId> open in the store, not stated by the mass status: ambiguous (Design.md §3.4)
```

Reconciling an ambiguous order's fills is manual until the exchange has a drop copy: the exchange's
operator can see the trades.

## 5.6 Restarts and crashes

### A clean stop

SIGTERM (Ctrl-C, `docker compose stop`, `systemctl stop`) runs the shutdown hook: the library
threads stop, the engines close (Artio waits for the archive to record everything it sent), and
only then is the clean stop marked in the store. `adapter: stopped` is the last line.

### At start

Before the ports bind, the adapter:

1. loads the store, pruning what retention allows (5.1);
2. refuses to start if Artio's state looks wiped while the store holds the participant's state,
   or Artio's `sequenceIndex` is below the stored one (6.2);
3. rebuilds any request the archive has and the store lost (a host crash), from Artio's archive, one
   session at a time;
4. **after an unclean stop**, moves every order-entry session's MsgSeqNum on 10,000, by writing
   that many Heartbeats offline. A 35=8 can have reached the client and not the archive; without the
   jump the next Logon would reuse its number and the client would end the session. The client sees
   one gap fill at its next Logon and carries on;
5. hands the requests in flight back to the SDK, whose resend and mass status settle them.

```
order entry: participant 7: the last stop was not clean; MsgSeqNum moved on from 31 to 10031, 10000 Heartbeats (Design.md §8.21)
```

and, when requests were in flight at the crash (from the e2e's step 8):

```
order entry: shard 0: 2 requests restored in flight
```

### After a crash

- **~10 s of `active mark file detected`**: the archive's mark file is still live. Retry; a
  supervisor does (3.8).
- **Then everything is recovered**: orders, reports owed, offline reports, and the gap fill. The
  e2e's step 8 kills the adapter under load from two clients and finds every order answered and none
  unknown.
- **A report the client had already received** before the crash may come again under a new
  MsgSeqNum, with the same ExecID.

## 5.7 Market data operations

- **A subscription** is a 35=V; the client gets a snapshot (35=W) per instrument, then incremental
  updates (35=X). It lives in memory and ends with the session.
- **A book the adapter does not trust yet** (it has not seen its recovery image, or lost sequence)
  sends nothing: the snapshot waits, updates stop, and a fresh 35=W follows once it is trusted again.
- **A client that cannot keep up** (Artio refuses a 35=X) gets a fresh 35=W once it can, never a
  wrong book; it is not logged out.
- **A market-data restart** of the exchange's own process is seen as a resynchronisation; the
  adapter needs nothing done.
- **Market data's archive grows within a run** (4.5); the next start empties it.

## 5.8 Measuring

The adapter has its own measuring sender and report, built for one rule: report both service time
(from the send) and response time (from the scheduled send), so a stalled sender cannot hide queueing
(Design §10, the `perf-claim` skill).

```sh
./e2e/run-perf.sh        # the baseline beside `most load`, against most-exchange's installs
fix perf --symbol AAPL --mid 100.00 --rate 1000 --count 60000 --log run.log …
fix report --log run.log --out report/      # report.html, .hgrm, CSV
```

**The trace ring** records each order's stages inside the adapter (in, archive gate, sent, report,
out, durable) when `fix.trace.file` is set; `fix report --trace FILE` joins it to the sender's log
by MsgSeqNum and cuts the service time at each stage. Its times are the host's monotonic clock, so
sender and adapter must share the host.

Figures taken in Docker Desktop or on a laptop are not production's (3.1). Every measurement taken
so far is in `docs/Measurements.md`, with its conditions.

## 5.9 Monitoring

Until `/metrics` and `/status` exist, **the log is the monitoring surface**. Lines worth alerting on:

| Line | Means |
| --- | --- |
| `adapter: refused to start:` | A start failed; the reason follows (6.2) |
| `the last stop was not clean` | A crash, recovered; find out why it crashed |
| `the library lost the engine; reports wait until it is back` | Artio's library and engine lost each other; reports are held |
| `id collision, session blocked` | A session needs its sequence reset (5.2) |
| `… ambiguous` | An order's fate is unknown; reconcile it (5.5) |
| `logged out after 3 ResendRequests` | A client is not resending what it owes |
| `report for clOrdId …, which the store does not hold; consumed` | The exchange has an order the store lacks (6.4) |
| `shard N refused participant P's request` | The exchange's registry does not grant the participant (4.9) |
| `order-entry:` or `market-data:` followed by a stack trace | An unexpected error on a library thread |

::: todo Metrics and status
Counters (messages, rejects, fates, back-pressure, gateway switches), gauges (sessions, open orders,
durable lag) and internal latencies are designed for `/metrics` (Design §9.2), not built.
:::
