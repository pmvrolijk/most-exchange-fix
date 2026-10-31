# CLAUDE.md

Guidance for Claude Code working in this repository. **Rules only.** Each one is the residue of a
defect or a decision; the reasoning lives in [`docs/Rationale.md`](docs/Rationale.md), cited as → R§n.
Do not change a rule without reading its section there.

| | |
| --- | --- |
| **Where things stand, what to do next** | [`docs/Status.md`](docs/Status.md) — the session entry point |
| **Normative behaviour** | [`docs/Design.md`](docs/Design.md) — authoritative. §8 is the open list |
| **Why a rule exists** | [`docs/Rationale.md`](docs/Rationale.md) |
| **How a change happened** | [`docs/Handover.md`](docs/Handover.md) — archive, §2a– |
| **What an adapter must do** | `../most-exchange/docs/Adapters.md` — the exchange's contract, which this repo implements |

`docs/Design.md` is the specification. Read it before implementing anything, and update it in the
same commit when the design changes.

This is the **FIX adapter** for most-exchange, AGPL-3.0-or-later like the exchange: an Artio acceptor in front of the exchange's order
entry gateways and market data feeds. Kotlin, packages `nl.lamia.most.exchange.fixadapter.*`. Modules:
`fix-session` (the acceptor, logon and routing by role), the backends `order-entry` and
`market-data` (35=x/V in, 35=y/W/X out, from the SDK's depth listener), `adapter` (the process) and `tools` (`bin/fix`: the e2e's client, and `perf`/`report`, the latency tooling of Design §10).

## Commands

```sh
./gradlew build                              # the SDK from most-exchange's GitHub Packages
./gradlew build -PmostExchange.local=true    # the SDK built from ../most-exchange instead
./gradlew :fix-session:test                  # one module
./e2e/run-e2e.sh                             # against ../most-exchange's installs; see its header
./e2e/run-perf.sh                            # the latency baseline, beside `most load`; perf-claim first
```

- **The SDK comes from the registry by default.** That needs `githubUser` and a `read:packages`
  token as `githubPackagesToken` in `~/.gradle/gradle.properties`, never in this repo or the shell. Use `-PmostExchange.local=true` only while
  changing the SDK, and publish it before relying on the change here. → R§3

## The non-negotiables

1. **An order whose fate is unknown is never reported as rejected.** `OrderFate.AMBIGUOUS` is an
   unknown status to the client. A `GATEWAY_UNAVAILABLE` is a gateway's, not the market's, and the
   SDK never delivers it as a report. Only `NEVER_SEQUENCED` may become a reject or a resend.
   An ambiguous order is restated with its last status and OrdStatusUnknown=Y, never as rejected,
   cancelled, expired or Done for Day. (most-exchange Adapters.md §3–§4, Design.md §3.4) → R§6
2. **Back-pressure is not loss.** A `SendResult.BACK_PRESSURED` request stays on its row, to send,
   and the duty cycle tries it again; a FIX message is consumed only once its row is stored. A reply
   Artio refuses aborts its inbound message (`Action.ABORT`), which is redelivered. Never drop
   either, and never spin on it on the library thread. (Design.md §3.3, §6.1) → R§12
3. **Prices and quantities are fixed-point `int64` with 8 implied decimals** on the exchange side.
   FIX decimals are converted with the SDK's `PriceCodec`, never through `Double`.
4. **Exchange-facing logic lives in most-exchange's `client`**, never here. That covers connecting,
   gateway switching, the `reportSeq` ledger, the resend fence, the mass status and the encoders.
   If the adapter needs more of it, change `client` in `../most-exchange` (it is in this build) and
   use it. Never copy it. → R§1
5. **The participant comes from the FIX session, never from the message.** One session acts for one
   configured `participantId`. A Logon that does not match a configured session's CompIDs and
   credentials is refused. (Design.md §2)
6. **Artio is pinned to the release built on the exchange's Aeron.** Today that is Artio 0.182 for
   Aeron 1.53.0. Bump Artio only together with most-exchange's `aeron`. `DependencyAlignmentTest`
   fails if Gradle resolves any other Aeron. → R§2
7. **A `reportSeq` is consumed only behind the fsynced archive.** The durable first-missing
   `reportSeq`, per participant and shard, advances only past reports whose 35=8 (online or offline
   `Session.trySend`) is at or below the archive's recorded position, at `fileSyncLevel=1`. Never
   store what the SDK's `save()` hands over as is: it is a ceiling. A 35=8 or 35=9 with no
   `reportSeq` (a fate, a restatement, a gateway's own reject) writes its row only once the archive
   has it. A back-pressured 35=8 aborts the report, never queues it. RocksDB stays in point-in-time
   WAL recovery: its unfsynced WAL is safe only because a crash leaves a prefix.
   (Design.md §6.1–§6.2) → R§4, R§11
   - **Write only on a session the library holds and may use.** Not once a logged-on session stops
     being active, and not after a disconnect until `requestSession` has it back: on the `Session`
     the disconnect took, a 35=8 can reuse a MsgSeqNum and never reach the client. Request it back
     replaying from its last received MsgSeqNum + 1, never `NO_MESSAGE_REPLAY`: a client back first
     is handed over online with that replay. An offline session handed back below the library's
     last MsgSeqNum is released and asked for again. (Design.md §6.3) → R§11
8. **The exchange `clOrdId` is `(sequenceIndex << 32) | MsgSeqNum`** of the inbound 35=D/F, never an
   allocated counter. Refuse to start if Artio's `sequenceIndex` is below the stored one. → R§5
9. **Artio does not validate an application message; the backend does.** Decode every inbound
   message and call `validate()` before using it. A failure is a 35=3 from the decoder's
   `rejectReason` and `invalidTagId`, never forwarded. The dictionary is
   `fix-session/src/main/dictionary/fix44-most.xml`, generated by `extract_fix44.py` there: change
   the script, never the XML. (Design.md §5.4) → R§8
10. **Only a valid application message reaches order entry's inbound path.** Artio hands the library
    session messages too, and messages it has already answered (`OnMessageInfo.isValid()` false).
    Both stop at the handler. And **reset an Artio encoder before every use**: its view, buffer and
    `byte[]` setters wrap the caller's bytes, and a later copying setter writes into them.
    (Design.md §3.3) → R§9
11. **A request goes to the exchange only once the archive has its 35=D or 35=F.** The send is the
    duty cycle's, from the row, once the `RecordingPos` of the engine's inbound stream has passed the
    message's position. Never from the message callback: the library sees a 35=D before the archive
    has fsynced it, and the exchange does not check `clOrdId`, so a host crash would turn the client's
    PossDup resend into a second order. And **a request replayed from the archive at startup is never
    sent, nor answered**: it may be at the exchange already. It is rebuilt in flight for the fence
    to settle. (Design.md §6.1, §8.17, §8.20) → R§12
    - **The startup requests go one at a time.** Request the next offline session only once the
      one before has completed with its session held: Artio gives every offline session the same
      connection id, and replays routed by it reach the session handed over last. A replayed
      message whose SenderCompID is not its session's client refuses the start; never rebuild it,
      and never route it by its CompIDs. (Design.md §8.17) → R§15

12. **After an unclean stop, every order-entry session's MsgSeqNum moves on 10 000 before the
    acceptor binds**, on the offline session the library holds, then a Heartbeat is archived. A 35=8
    can reach the client and not the archive, and a reused number ends the client's session. The
    clean stop is marked only after Artio's engine has closed (`OrderStore.markStoppedCleanly`),
    and cleared at start with a synced write. (Design.md §8.21) → R§13

13. **A gap Artio leaves open is asked for by the library, never rewound.** Never lower a session's
    received number (`tryUpdateLastReceivedSequenceNumber`, `lastReceivedMsgSeqNum`): it starts a
    new `sequenceIndex`, and Artio's replayer then gap-fills over every message sent before it,
    so a 35=8 the client missed is lost. When Artio's resend stalls, or a connection ends with its
    gap open, the library sends its own ResendRequest for the range: at once, or straight after
    the next Logon from the gap stored in `meta`. The startup replay skips a non-PossDup message
    numbered within that stored gap: Artio archives what it marked invalid and replays it as valid,
    and rebuilt, it would be rejected "not sequenced". (Design.md §2, §8.21) → R§14

14. **Market data runs on its own engine, never on order entry's.** The adapter's market-data
    sessions belong to a second acceptor, on its own port, with an archive at fileSyncLevel 0 that is
    emptied at every start; order entry's acceptor holds the order-entry sessions only. Order
    entry's fsynced archive gates every 35=D and `reportSeq` (7, 11), so market data on it slows the
    order path. (Design.md §4) → R§16

## Provenance

## Skills

`.claude/skills/` holds the procedures this project runs on: `session-open`, `session-close`,
`spec-first-test`, `decision-fork`, `verify-for-real`, `perf-claim`. They are adapted from
most-exchange's.

**Commits are the user's call.** Do not run `git commit`, `git add` or `git push` unless asked in
that turn — propose the message instead.
