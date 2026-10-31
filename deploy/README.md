# The adapter in Docker

Participant 7's FIX adapter in a container, beside most-exchange's dev stack
(`../most-exchange/deploy`). One adapter per participant (Design.md §8.27), with its order-entry
session on 9876 and a market-data session on 9877, each on its own engine (§4).

```sh
./gradlew :adapter:installDist :tools:installDist      # the image copies the host build
(cd ../most-exchange/deploy && docker compose up -d)   # the exchange first: its networks
docker compose -f deploy/docker-compose.yml up -d --build

deploy/smoke.sh                                        # a sell, a cross from participant 8, market data
deploy/fix send --symbol AAPL --side sell --price 100.00 --qty 10 --clordid S1
deploy/fix md --symbol AAPL --follow 10
```

| | |
| --- | --- |
| Order entry | `localhost:9876`, CLIENT7 → MOST, `client7` / `client7-secret`, participant 7 |
| Market data | `localhost:9877`, FEED7 → MOST, `feed7` / `feed7-secret`, 141=Y at every Logon |
| Teardown | `docker compose -f deploy/docker-compose.yml down`. `-v` discards the archive and the store: see "Stopping and crashing" first, and delete `build/deploy/clients` with them |

## How it joins the exchange

A compose project of its own, `most-fix`, that joins most-exchange's two networks as external ones:
`most-aeron`, the exchange's inside, and `most-edge`, the only one with published ports. The
adapter is the one container that bridges them, which is the place most-exchange's compose file
leaves for it. Nothing in most-exchange changes.

It brings its own media drivers, embedded (Design.md §3.5): one for order entry's engine and the
SDK, one for market data's. They share no Aeron directory with any other container. Every endpoint
it talks to is the shard's, so every one is on `shard0`, the alias of the exchange's `cluster-host`,
whose driver binds them all (most-exchange's deploy/README.md, "One media driver is one network
identity"). It subscribes by dynamic MDC (`endpoint=0.0.0.0:0|control=shard0:<port>`), because a
Docker bridge does not route multicast.

| | |
| --- | --- |
| `shard0:40000`, stream 100 | The directory: the shard, its gateway's endpoints, the securities |
| `shard0:20001`, stream 20 | Order entry, to the gateway (from the directory) |
| `shard0:20002`, stream 21 | Execution reports, from the gateway (from the directory) |
| `shard0:40001`, `:40002`, `:40004`, streams 1, 2, 4 | L1, L2 and the L2 recovery feed |

Participant 7 is one of `gw-0`'s, in most-exchange's `config/shard-0-participants.properties`.

## State

| Volume | Holds |
| --- | --- |
| `fix-7-data` → `/data` | Order entry's fsynced archive, Artio's logs and sequence indexes, the order store. A plain named volume, never tmpfs: every 35=D and `reportSeq` waits on the archive (CLAUDE.md 7, 11) |
| `fix-7-md` → `/data-md` | Market data's engine, emptied at every start (CLAUDE.md 14) |
| `/dev/shm`, 1 GB | The two embedded drivers. Docker's default 64 MB is too small, and an Aeron driver that cannot map a buffer dies of SIGBUS |

## Stopping and crashing

- **`docker compose stop` or `restart`** sends SIGTERM. The JVM's shutdown hook closes the engine and
  marks the clean stop (Design.md §8.21); the grace period is 30 s. Verified: `adapter: stopped`,
  and the next start moves no MsgSeqNum on.
- **A crash** (verified with a SIGKILL of the JVM in the container): the archive refuses its own
  mark file for ~10 s (Design.md §6.4) and the adapter exits. `restart: on-failure:10` and Docker's
  back-off outlast that: it came back after 7 refusals in 21 s, moved MsgSeqNum on 10,000, and the
  client logged on across the jump.
  - **Found by it, and fixed:** the jump used to leave a hole in Artio's replay index, after which
    a 35=8 sent while the client was logged out was never resent (Design.md §8.29). The jump is
    now written as Heartbeats; after a crash, `deploy/smoke.sh` passes again.
- **Never wipe the adapter's volume alone** (`down -v` here) while the exchange still holds
  participant 7's orders. The wipe takes the order store and Artio's `sequenceIndex` with it, so
  the new session's `clOrdId`s, `(sequenceIndex << 32) | MsgSeqNum`, repeat ones the exchange
  already has (Design.md §3.2, §8.15), and the guard against that (CLAUDE.md 8) went with the
  store. Its reports then reach a store that does not know them. Cancel participant 7's orders
  first (`most cancel`), or reset the exchange with it (`down -v` in most-exchange's `deploy/`).

## What is dev-only

- **The secrets** in `config/sessions.properties` are committed (their SHA-256 only), as
  most-exchange's are.
- **The calendar** is UTC and every day, because the exchange's seed opens the shard with the
  control container's date, on any day (Design.md §3.6).
- **Shared driver threads and a backing-off idle strategy**: the dev stack runs the whole exchange on
  one machine. Production busy-spins (Design.md §7).
- **The fsync is the VM's.** No figure taken in Docker Desktop is the adapter's (`perf-claim`).
- `most send --qty` is the exchange's raw `int64`, 8 implied decimals: 4 shares is `400000000`. The
  adapter's FIX quantities are decimals (CLAUDE.md 3).

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| `network most-aeron declared as external, but could not be found` | most-exchange's stack is not up. Start it first |
| Restarting, `active mark file detected` | A crash's archive mark file, live for ~10 s. Docker retries; it starts by itself |
| Healthy, but no `shard 0 ready` in `docker logs most-fix-fix-7-1` | No directory: discovery is not up, or the channel's control address is not `shard0:40000` |
| `report for clOrdId N, which the store does not hold; consumed` | The exchange holds an order of participant 7's that this store never had: the adapter's volume was wiped alone. See "Stopping and crashing" |
| The client is refused, `MsgSeqNum too low` | Its state in `build/deploy/clients` outlived a `down -v`. Delete it, or `--reset` |
