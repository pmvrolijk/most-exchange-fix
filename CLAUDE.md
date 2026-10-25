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
entry gateways and market data feeds. Kotlin, packages `nl.lamia.most.exchange.fixadapter.*`. Three
modules: `fix-session` (the acceptor, logon and routing by role), and `order-entry` and
`market-data`, still scaffolds.

## Commands

```sh
./gradlew build                              # the SDK from most-exchange's GitHub Packages
./gradlew build -PmostExchange.local=true    # the SDK built from ../most-exchange instead
./gradlew :fix-session:test                  # one module
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
2. **Back-pressure is not loss.** `SendResult.BACK_PRESSURED` maps to Artio's `Action.ABORT`, so the
   FIX message is redelivered and not consumed. Never drop it, and never spin on it on the library
   thread.
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

## Provenance

## Skills

`.claude/skills/` holds the procedures this project runs on: `session-open`, `session-close`,
`spec-first-test`, `decision-fork`, `verify-for-real`, `perf-claim`. They are adapted from
most-exchange's.

**Commits are the user's call.** Do not run `git commit`, `git add` or `git push` unless asked in
that turn — propose the message instead.
