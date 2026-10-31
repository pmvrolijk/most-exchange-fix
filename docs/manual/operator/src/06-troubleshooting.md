# 6. Troubleshooting and maintenance

## 6.1 First moves

1. **Is it running, and did it start?** The log's first lines (2.4): `adapter: started`, then
   `shard N through …` and `shard N ready` for each shard. A start that failed says
   `adapter: refused to start:` and why (6.2).
2. **Is the exchange there?** No `shard N through` means no directory; no `shard N ready` means the
   gateway's opening mass status has not finished. Both are on the exchange's side of the link.
3. **Is it one client or all?** One client: its Logon, its sequence numbers, its messages (6.3,
   6.4; the Rules of Engagement's troubleshooting chapter is the client's half). All clients: the
   adapter or the exchange.
4. **What did the wire carry?** The archive has every FIX message in and out (6.8).

## 6.2 Startup failures

Every one of these happens before the ports bind, so no client sees a half-started adapter.

| `adapter: refused to start:` … | Cause and fix |
| --- | --- |
| `missing required configuration key: <key>` | Add the key (section 4) |
| `<key> must be a port number`, `… must be 0, 1 or 2`, `… must be an Aeron stream id, a number` | A malformed value |
| `fix.md.port must not be fix.port: market data has its own engine` | Give market data its own port |
| `fix.md.logDir must not be order entry's directory: market data's is emptied at every start` (also `archiveDir`, `aeronDir`) | Market data's directories are wiped at start: they must be their own |
| `<file>: sessions [A, B] share CompIDs …` / `… share participantId …` | Two sessions with one identity, or two order-entry sessions for one participant (4.7) |
| `<file>: session.<n>.passwordSha256 must be 64 hex digits …` | Store the digest, not the secret |
| `fix.calendar.zone: not a time zone`, `fix.calendar.rollTime must be HH:mm`, `fix.calendar.weekdays: <x> is not one of …` | The calendar (4.4) |
| `Artio accepts unknown tags: start the JVM with -Dfix.codecs.reject_unknown_field=true` | The JVM was started without the start script's flags (4.8) |
| `active mark file detected: …/archive-mark.dat` | Within ~10 s of a crash. Wait and start again; a supervisor retries (3.8) |
| `session <s>: Artio does not know it, yet the order store holds state for participant <p>. Artio's state was lost, …` | `fix.logDir`/`fix.archiveDir` were wiped or replaced while `fix.storeDir` was kept. Restore them together, or see 6.7 |
| `participant <p>: Artio's sequenceIndex <i> is below the stored <j>. …` | Artio's state is older than the store's: an old backup restored. Restore all three directories from one moment |
| `order entry: … refusing to start rather than rebuild it for the wrong participant` | The archive replayed a message whose SenderCompID is not its session's client. The archive and the sessions file disagree: CompIDs changed under an existing session |
| `order entry: the offline sessions were not handed over, their archives replayed, within 10 s` | The startup replay did not finish; usually a starved host. Look for other errors above it |

## 6.3 Logon problems

| Symptom | Cause |
| --- | --- |
| `Logout … Text=Logon refused`, at MsgSeqNum 1 | No configured session matches the Logon's 49, 56, 553 and the SHA-256 of 554 all at once, or the session is at the other role's port. The Logout never says which: check each, starting with the port |
| Logged out, MsgSeqNum lower than expected | The client logged on below what the adapter expects. The client's sequence store and the adapter's disagree: the client's was reset, or the adapter's state is older than the client's. The client may log on with 141=Y, giving up what it had not received |
| Logged out at once, `session sequence reset required` | The session is blocked by an id collision (5.2). A Logon with 141=Y lifts it |
| Logged out, `messages <a> to <b> not received; resend them` | The client did not answer the adapter's ResendRequests (5.2). It is asked again after its next Logon |
| The connection is refused | The adapter is not listening: not started, still in the ~10 s after a crash, or `fix.bindAddress` is not reachable from the client |

## 6.4 Order flow problems

| Symptom | Cause |
| --- | --- |
| Every order rejected `exchange unavailable` | No complete directory yet, a shard's opening mass status not finished, or no gateway. Check 6.1 step 2 |
| Rejected `participant not served` | The exchange's gateway session does not hold the participant |
| Rejected by the exchange, `UNAUTHORIZED_PARTICIPANT` | The participant is not granted to the adapter's gateway in the exchange's registry (4.9) |
| Rejected `ORDER_EXPIRED` on every day order | The adapter's calendar is behind the exchange's trading date (4.4) |
| A day order survived the close | The adapter's calendar is ahead: the exchange ran a session off its calendar (4.4) |
| Rejected `not sequenced (gateway failover)` | A gateway switch; the order never reached the exchange. Safe to resend with a new ClOrdID |
| An order restated with OrdStatusUnknown=Y | Its fate is unknown (5.5) |
| `report for clOrdId <id>, which the store does not hold; consumed` | The exchange reported an order the store lacks. After a host crash, for a request the archive did not have either; or the adapter's state was wiped while the exchange kept the participant's orders (6.7) |
| A fill the client never saw | The report is in the archive if it was delivered (6.8); a client that logs on with 141=Y gives up every report it had not received, and recovers with 35=AF |

## 6.5 Aeron and Docker problems

| Symptom | Cause |
| --- | --- |
| A JVM fatal error (SIGBUS or SIGSEGV) in an Agrona or Aeron frame, `AtomicCounter`, `UnsafeBuffer` | A memory-mapped Aeron file could not be backed: `/dev/shm` (or a tmpfs volume) full. Size it (3.3) |
| The same in **several containers at once** | Docker Desktop's VM ran out of memory: tmpfs and `/dev/shm` are the VM's RAM. Seen when a large Gradle build ran in another container beside both stacks. Give the VM more memory, or run heavy jobs outside it |
| `Restarting` over and over | Read `docker logs most-fix-fix-7-1`: a configuration error repeats on every start |
| Healthy, but no `shard 0 ready` | No directory: most-exchange's discovery is not up, or the channel's control address is not `shard0:40000` |
| `channel error - Cannot assign requested address` | A subscriber channel names an address the adapter's driver does not own. Subscriptions use `endpoint=0.0.0.0:0` with the publisher's control address |

## 6.6 After a crash

1. **Let it restart.** ~10 s of `active mark file detected`, then a normal start with
   `the last stop was not clean; MsgSeqNum moved on …`.
2. **Find out why it crashed**: the JVM's `hs_err_pid*.log`, the kernel log for the OOM killer,
   Artio's error log (6.8).
3. **Clients** see one gap fill at their next Logon and carry on (5.6). Reports owed while the
   adapter was down are delivered: the store resumes each participant from its durable report
   sequence.

## 6.7 Maintenance procedures

### Adding or removing a session

Edit the sessions file and restart. A new order-entry session needs its participant granted on the
exchange (4.9). Removing a session whose participant still has open orders leaves them open at the
exchange; cancel them first.

### Rotating a client's secret

Generate a new secret (4.7), replace the session's `passwordSha256`, restart. The session's sequence
numbers and orders are untouched; the client logs on with the new secret.

### Backing up and restoring

The adapter's state is three directories that must stay consistent with each other: `fix.logDir`,
`fix.archiveDir` and `fix.storeDir`. Copy them together, with the adapter stopped, and restore them
together. A store newer than Artio's state, or older, refuses the start (6.2), which is the guard
doing its job: derived order ids would repeat.

### Starting a participant afresh

::: warning Never wipe the adapter's state alone
While the exchange still holds the participant's orders, a fresh adapter starts at `sequenceIndex`
0 again: its new orders' exchange ids repeat ones the exchange already has, and reports for the old
orders reach a store that does not know them. Cancel the participant's orders at the exchange first
(most-exchange's `most cancel`, or the exchange's operator), then stop the adapter and remove all
three directories. In Docker: `docker compose -f deploy/docker-compose.yml down -v`, and delete the
client's own sequence store.
:::

### Resetting a session's sequence numbers

Today, only the client can: a Logon with ResetSeqNumFlag (141=Y). The client gives up every report
it had not received and asks for a mass status (35=AF).

::: todo Operator reset
`POST /sessions/{id}/reset` and the end-of-day `POST /reset` are designed (Design §9.1), not built.
:::

### Upgrading the adapter

Stop it cleanly, replace the install, start it. The store's rows are versioned, and a row of an
unknown version refuses the start rather than being misread. Artio and Aeron move only together with
most-exchange's Aeron.

## 6.8 Reading the logs

- **The adapter's own log** is stderr: one line per event, most naming the Design section they
  follow from.
- **Artio's error log and counters** are in `fix.logDir` (and `fix.md.logDir`), not the system's
  temp directory.
- **The archive** holds every FIX message each way, and Artio's `FixArchivePrinter` prints it from
  the running adapter's driver. In the Docker stack:

```sh
docker exec most-fix-fix-7-1 sh -c 'java \
  --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  -cp "/opt/fix/adapter/lib/*" uk.co.real_logic.artio.engine.logger.FixArchivePrinter \
  --aeron-dir-name=/dev/shm/fix-aeron --aeron-channel=aeron:ipc \
  --fix-dictionary=nl.lamia.most.exchange.fixadapter.codecs.FixDictionaryImpl \
  --log-file-dir=/data/logs --delimiter="|" --query-stream-id=1'
```

`--query-stream-id=1` prints what clients sent, `2` what the adapter sent. Each line ends with
Artio's verdict on it, `(OK)` or, for a refused Logon, `(AUTH_REJECT)`. Passwords are masked. One
line from the development stack, the adapter's report of a cancel:

```
8=FIX.4.4|9=198|35=8|49=MOST|56=CLIENT7|34=20068|52=20261031-18:55:15.778|37=20029|11=RC1793472897|41=RB1793472897|17=28|150=4|39=4|55=AAPL|48=1|22=8|54=1|38=25|44=99.5|59=0|151=0|14=0|6=0|60=20261031-18:55:15.779|10=125|
```

11 is the cancel's ClOrdID, 41 the order's, ExecType (150) 4 and OrdStatus (39) 4, cancelled.

## 6.9 Consolidated list of what is not implemented

| Designed, not built | Where |
| --- | --- |
| Control API: `/status`, `/start`, `/stop`, session and end-of-day resets, trading date override | Design §9.1 |
| `/metrics`: counters, gauges, internal latencies | Design §9.2 |
| Session schedule: logon windows, logout at the close, reset at end of day | Design §8.2 |
| Several order-entry sessions for one participant | Design §8.27 |
| Client fields carried to the exchange (Account and others) | Design §5.5 |
| An order-entry throttle | Design §8.25 |
| TLS on the acceptor | Not planned: terminated in front |
| Market by order (L3) market data | Waits on the exchange |
