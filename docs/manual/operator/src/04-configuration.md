# 4. Configuration in full

Every key the adapter reads, by the part of the adapter that reads it. Section 7.3 lists them
alphabetically. Defaults are the code's; *required* means the adapter refuses to start without it.

## 4.1 How the adapter is configured

```sh
adapter /etc/most-fix/7/adapter.properties
```

- **The node file** is the one argument, a Java properties file of `fix.*` keys.
- **Any `fix.*` JVM system property overrides the file's key**, so one file can serve several runs:
  `ADAPTER_OPTS=-Dfix.idleStrategy=backoff`. With no argument, the system properties alone are the
  configuration.
- **The sessions file** is named by `fix.sessionsFile` (4.7).
- **Everything is checked before anything opens:** a missing or malformed key, a sessions file that
  does not parse, a calendar with no trading day. The adapter prints `adapter: refused to start:`
  and the reason, and exits 1 (6.2).
- **Nothing is re-read while running.** A change takes a restart.

## 4.2 Order entry's engine

| Key | Default | Meaning |
| --- | --- | --- |
| `fix.port` | *required* | The order-entry sessions' TCP port |
| `fix.bindAddress` | `0.0.0.0` | The address it listens on |
| `fix.sessionsFile` | *required* | The sessions file (4.7), for both engines |
| `fix.logDir` | *required* | Artio's logs: its session file, sequence and replay indexes, monitoring counters and error log. **Durable**, never deleted at start |
| `fix.archiveDir` | *required* | Aeron Archive's recordings of every message in and out. **Durable**, never deleted at start |
| `fix.aeronDir` | Aeron's default directory + `-most-fix` | The embedded media driver's directory, on `/dev/shm`. Deleted and recreated at start |
| `fix.fileSyncLevel` | `1` | The archive's fsync: `0` none, `1` data, `2` data and metadata. The catalog is synced at the same level. **Below 1 the delivery guarantee does not hold**; a test rig may use 0 |

::: warning `fix.logDir` and `fix.archiveDir` belong together
Artio's indexes in `fix.logDir` describe the recordings in `fix.archiveDir`. Back them up, restore
them and move them together, with `fix.storeDir`, or not at all (6.7).
:::

## 4.3 Order entry's exchange links

| Key | Default | Meaning |
| --- | --- | --- |
| `fix.storeDir` | *required* | The order store, RocksDB. **Durable**, never deleted at start |
| `fix.directoryChannel` | *required* | The subscriber channel of the exchange's directory broadcast. Market data's security list reads it too |
| `fix.directoryStreamId` | *required* | Its stream id |
| `fix.shard.<id>.gateway.<n>.orderEntryChannel` | — | Optional, per shard: a gateway's order entry channel. `n` counts from 0, in the order the SDK tries them (3.6) |
| `fix.shard.<id>.gateway.<n>.orderEntryStreamId` | — | Its stream id |
| `fix.shard.<id>.gateway.<n>.executionReportChannel` | — | The gateway's execution report channel, subscriber form |
| `fix.shard.<id>.gateway.<n>.executionReportStreamId` | — | Its stream id |

The four `fix.shard.*` keys come as a set per gateway, numbered from 0 without holes; any other
key under `fix.shard.` refuses the start. Listed, a shard's gateways replace the one endpoint the
directory names for it.

## 4.4 The trading calendar

The adapter's own calendar gives a **day order** (TimeInForce 0, or absent) its expiry date. It is
used for nothing else: a GTD date is the client's and the engine checks it.

| Key | Default | Meaning |
| --- | --- | --- |
| `fix.calendar.zone` | *required* | An IANA zone, such as `Europe/Amsterdam` |
| `fix.calendar.rollTime` | *required* | `HH:mm` local to the zone: from this time on, the date is the next trading day |
| `fix.calendar.weekdays` | `MON,TUE,WED,THU,FRI` | The trading weekdays, by their three-letter names |
| `fix.calendar.holidaysFile` | none | One ISO date per line (`2026-12-25`), then optional words; `#` starts a comment |

**The rule:** at local date *d* and time *t*, the trading date is the first trading day on or after
*d* if *t* is before the roll, and the first trading day after *d* otherwise. So a Saturday morning
names Monday.

**Set it to agree with the exchange.** Put the roll inside the exchange's closed window, between
the close and the next pre-open, when orders are refused anyway. If the two disagree:

- **adapter behind** (D−1 against the engine's D): day orders are rejected `ORDER_EXPIRED`. Visible;
- **adapter ahead** (D+1 against D): a day order is accepted as good till D+1 and **survives the
  close**. Silent. It happens when the exchange runs a session off its calendar: late, on a weekend,
  on a holiday. The start line names the adapter's trading date; compare it with the exchange's.

::: todo Trading date override
`POST /trading-date`, to follow an off-calendar session until the next roll, is designed (Design
§9.1) and not built. Today: change the calendar keys and restart.
:::

## 4.5 Market data

Read only when the sessions file has a market-data session.

| Key | Default | Meaning |
| --- | --- | --- |
| `fix.md.port` | *required* | The market-data sessions' TCP port. Must not be `fix.port` |
| `fix.md.bindAddress` | `fix.bindAddress` | The address it listens on |
| `fix.md.logDir` | *required* | Market data's engine's Artio logs. **Emptied at every start**; must not be `fix.logDir` |
| `fix.md.archiveDir` | *required* | Its archive. **Emptied at every start**; must not be `fix.archiveDir` |
| `fix.md.aeronDir` | `fix.aeronDir` + `-md` | Its media driver's directory; must not be `fix.aeronDir` |
| `fix.md.fileSyncLevel` | `0` | Its archive's fsync. Nothing in it is ever replayed |
| `fix.md.l1.channel`, `fix.md.l1.streamId` | *required* | The L1 feed (last trades), subscriber form |
| `fix.md.l2.channel`, `fix.md.l2.streamId` | *required* | The L2 feed (depth updates) |
| `fix.md.snapshot.channel`, `fix.md.snapshot.streamId` | *required* | The L2 recovery feed |

Market data's archive grows within a run, about 200 bytes per 35=W and 35=X sent, and is emptied
only at the next start (Design §8.28). Size its disk for the longest run.

## 4.6 Threads and the trace ring

| Key | Default | Meaning |
| --- | --- | --- |
| `fix.idleStrategy` | `busyspin` | Both library threads: `busyspin`, `yielding`, `backoff` or `sleeping` (1 ms) |
| `fix.trace.file` | none: off | Turns the trace ring on, written to this file (5.8) |
| `fix.trace.ringBytes` | `16777216` (16 MiB) | The ring's size: a power of two, at least 512 |

## 4.7 The sessions file

One block of `session.<name>.*` keys per FIX session. The name is a label only.

```properties
session.C7.senderCompId=MOST
session.C7.targetCompId=CLIENT7
session.C7.username=client7
session.C7.passwordSha256=f799b9772b264e618d6311778b7934d7b9db35f687c9fea1ce6eb2fd858fd6b0
session.C7.role=order-entry
session.C7.participantId=7

session.M7.senderCompId=MOST
session.M7.targetCompId=FEED7
session.M7.username=feed7
session.M7.passwordSha256=8ae74e446931569a018a969ab170df419592b1099c5e9476381eeaf8e78b1ea9
session.M7.role=market-data
```

| Key | Meaning |
| --- | --- |
| `senderCompId` | The adapter's CompID on this session: the client's TargetCompID (56) |
| `targetCompId` | The client's CompID: its SenderCompID (49) |
| `username` | The Logon's Username (553) |
| `passwordSha256` | The SHA-256 of the Logon's Password (554), 64 hex digits |
| `role` | `order-entry` or `market-data` |
| `participantId` | The exchange participant, a number. Required for `order-entry`, refused for `market-data` |

The adapter refuses to start on a missing or malformed key, two sessions with the same CompIDs, or
two order-entry sessions with the same participant.

**A Logon is accepted** when one configured session matches all of it: its 49 is the session's
`targetCompId`, its 56 the `senderCompId`, its 553 the `username`, and the SHA-256 of its 554 the
`passwordSha256`, compared in constant time. One session's credentials do not open another. A
session logs on at its role's port only; at the other port it is refused like any unknown one.

::: warning Generated secrets only
The digest is a plain SHA-256, safe for a generated, high-entropy secret, not for a password a
person chose. Issue each client a secret, store its digest, and reissue a lost one: it cannot be
recovered.
:::

```sh
openssl rand -base64 24                         # a secret for the client
printf '%s' "$SECRET" | sha256sum | cut -d' ' -f1   # its passwordSha256 (shasum -a 256 on a Mac)
```

## 4.8 JVM flags

The start script, `bin/adapter`, carries what the adapter needs:

| Flag | Why |
| --- | --- |
| `--add-opens java.base/jdk.internal.misc=ALL-UNNAMED` | Agrona's unsafe buffers |
| `--add-opens java.base/sun.nio.ch=ALL-UNNAMED` | Artio's network layer |
| `-Dfix.codecs.reject_unknown_field=true` | Artio's codecs refuse a tag outside the dictionary. **The acceptor refuses to start without it**, and also if `-Dfix.codecs.no_validation` or `-Dfix.codecs.disable_reject_unknown_enum_value` is set: the adapter's message validation rests on all three |

`ADAPTER_OPTS` and `JAVA_OPTS` add to them: the heap, Aeron's threading
(`-Daeron.threading.mode=SHARED`, `-Daeron.archive.threading.mode=SHARED` for a small host), and
any `-Dfix.*` override.

## 4.9 What the exchange must agree to

The adapter connects to a gateway, and the gateway enforces which participants it may act for, in
the exchange's participant registry (most-exchange Operator's Manual, 4.3). **Every
`participantId` in the sessions file must be listed for that gateway**, or its orders are rejected
`UNAUTHORIZED_PARTICIPANT` by the exchange. In the development stack, participant 7 is one of
`gw-0`'s.

The feeds' channels and stream ids are those of the exchange's market data process (`md.*`), and
the directory's those of its discovery process.

## 4.10 A complete node file

The development stack's, `deploy/config/adapter.properties`, without its comments:

```properties
fix.bindAddress=0.0.0.0
fix.port=9876
fix.sessionsFile=/config/sessions.properties
fix.logDir=/data/logs
fix.archiveDir=/data/archive
fix.fileSyncLevel=1
fix.storeDir=/data/store
fix.aeronDir=/dev/shm/fix-aeron

fix.directoryChannel=aeron:udp?endpoint=0.0.0.0:0|control=shard0:40000|control-mode=dynamic
fix.directoryStreamId=100

fix.md.port=9877
fix.md.logDir=/data-md/logs
fix.md.archiveDir=/data-md/archive
fix.md.aeronDir=/dev/shm/fix-aeron-md
fix.md.l1.channel=aeron:udp?endpoint=0.0.0.0:0|control=shard0:40001|control-mode=dynamic
fix.md.l1.streamId=1
fix.md.l2.channel=aeron:udp?endpoint=0.0.0.0:0|control=shard0:40002|control-mode=dynamic
fix.md.l2.streamId=2
fix.md.snapshot.channel=aeron:udp?endpoint=0.0.0.0:0|control=shard0:40004|control-mode=dynamic
fix.md.snapshot.streamId=4

fix.calendar.zone=UTC
fix.calendar.rollTime=23:59
fix.calendar.weekdays=MON,TUE,WED,THU,FRI,SAT,SUN

fix.idleStrategy=backoff
```
