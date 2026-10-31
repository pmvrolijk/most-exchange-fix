# 2. The FIX session

## 2.1 Connecting

Each session is one TCP connection to its own port: order entry at one, market data at another.
**A session logs on only at its own port**; at the other it is refused like an unknown session.
Connect, send a Logon, and wait for the venue's Logon before sending anything else.

If the connection drops, reconnect and log on again, with your sequence numbers as they were (2.3).
Nothing sent to you while you were away is lost (2.6).

## 2.2 Logon (35=A)

| Tag | Name | Req | Value |
| --- | --- | --- | --- |
| 49 | SenderCompID | Y | Your CompID, as the venue configured it |
| 56 | TargetCompID | Y | The venue's CompID |
| 34 | MsgSeqNum | Y | Your next outgoing number (2.3) |
| 98 | EncryptMethod | Y | `0` |
| 108 | HeartBtInt | Y | Seconds. The venue uses the same interval |
| 141 | ResetSeqNumFlag | N | `Y` to reset both sides to 1 (2.7). Order entry: only when you mean to. Market data: always, or log on at 1 |
| 553 | Username | Y | As issued |
| 554 | Password | Y | As issued |

A Logon is accepted only if SenderCompID, TargetCompID, Username and Password all match one session
the venue configured. The venue answers with its own Logon:

```
8=FIX.4.4|9=093|35=A|49=CLIENT7|56=MOST|34=53|52=20261031-18:55:03.218|98=0|108=10|141=N|553=client7|554=***|10=145|
8=FIX.4.4|9=76|35=A|49=MOST|56=CLIENT7|34=20052|52=20261031-18:55:03.232|98=0|108=10|141=N|10=032|
```

**A refused Logon** gets a Logout with Text `Logon refused`, at MsgSeqNum 1, and the connection is
closed. It does not say what was wrong: a CompID, the username, the password, or the port are all
the same refusal. Check each.

```
8=FIX.4.4|9=093|35=A|49=CLIENT7|56=MOST|34=1|52=20261031-18:55:20.729|98=0|108=10|141=N|553=client7|554=***|10=252|
```

answered by `35=5 … 58=Logon refused`. Your session's state is untouched by a refused Logon.

## 2.3 Sequence numbers

**Order-entry sessions are persistent.** MsgSeqNum runs on across Logouts, disconnections and the
venue's restarts, both ways, and resets only when you log on with ResetSeqNumFlag (141=Y). Keep
your sequence store; your first Logon on a new session is at 1.

- **Your Logon numbered above what the venue expects:** accepted. The venue then asks you to resend
  the gap (2.5).
- **Your Logon numbered below:** refused, with a Logout. Your sequence store and the venue's
  disagree; do not guess. Contact the venue, or reset (2.7).
- **The venue's Logon numbered above what you expect:** messages were sent to you while you were
  away. Ask for them with a ResendRequest (2.6).

::: note No end-of-day reset
The venue does not reset sequence numbers at the end of the day or log sessions out at the close.
Sequence numbers run on until you reset them.
:::

**Market-data sessions are transient**: both sides start at 1 at every Logon, and nothing is ever
resent. Log on with 141=Y (or at 1) every time, and subscribe again (section 5).

## 2.4 Heartbeats and test requests

- Heartbeat (35=0) at the HeartBtInt you chose, both ways, when nothing else is sent.
- A TestRequest (35=1) is answered with a Heartbeat carrying its TestReqID (112).
- **Keep your clock synchronised.** SendingTime (52) more than two minutes from the venue's clock
  is a session reject, SessionRejectReason 10 (SendingTime accuracy problem).

## 2.5 Resend requests and gap fills

Both sides follow FIX 4.4's recovery:

- **When the venue asks you** (35=2), resend from BeginSeqNo to your last message sent; an EndSeqNo
  of 0 means that. Application messages go again with PossDupFlag (43) = Y and OrigSendingTime
  (122); session messages are gap-filled (35=4, GapFillFlag 123 = Y).
- **While your own ResendRequest is outstanding,** queue any message from the venue numbered above
  the range you asked for, and process it after the gap is filled. FIX requires it; an engine that
  instead counts it as received stalls its own recovery until it reconnects.
- **The venue asks for what you owe.** If messages from you are missing and its own recovery has
  not closed the gap within a second, the venue sends its own ResendRequest, and again each further
  second without progress. **After three without progress it logs you out**, with Text
  `messages <from> to <to> not received; resend them`, and asks again straight after your next
  Logon.

Resent orders are safe: a PossDup 35=D or 35=F the venue already has is dropped, never executed
twice (4.8).

## 2.6 Messages sent while you were logged out

**Reports do not wait for you.** An execution report for an order of yours, a fill while you are
logged out say, is sent to your session at once, numbered, and recorded by the venue. At your next
Logon your engine sees the venue's MsgSeqNum above what it expects and asks for the gap; the reports
come back with PossDupFlag = Y. As the venue's test client prints one:

```
ExecutionReport ClOrdID=S1793473103 OrderID=20030 ExecID=30 ExecType=TRADE OrdStatus=PARTIALLY_FILLED Side=SELL Symbol=AAPL OrderQty=10 Price=100 LastQty=4 LastPx=100 CumQty=4 LeavesQty=6 AvgPx=100 PossDup
```

**Process a PossDup report** by its ExecID: if you have it, drop it; if not, it is new to you.
Nothing in this path depends on how long you were away.

## 2.7 Resetting (141=Y)

A Logon with ResetSeqNumFlag = Y resets both sides' numbers to 1. **It also gives up every message
you had not yet received**, reports included: the venue cannot resend across a reset.

After a reset on an order-entry session, **send an OrderMassStatusRequest (35=AF)** (3.7) and
rebuild your orders from its answers. Each open order is stated with its current OrdStatus, CumQty
and LeavesQty; fills you missed are reflected in CumQty, not reported one by one. An order that
finished while you were not receiving (filled, cancelled, expired) is not open, and is not in the
answer; ask for it by ClOrdID with an OrderStatusRequest (35=H) if you need its final state.

Reset only when you must: a lost sequence store, or a session blocked by the venue (7.2).

## 2.8 After the venue restarts

- **A planned restart** is invisible except as a disconnection: log on again with your numbers.
- **After a crash** the venue's outgoing MsgSeqNum jumps ahead by 10,000. You see a gap at the
  Logon, ask for it, and receive **one gap fill** covering the jump; carry on. The jump exists so
  that a message you received just before the crash is never numbered twice.
- **A report you had already received before a crash may come again**, under a new MsgSeqNum and
  without PossDupFlag, because the venue cannot tell which ones reached you. **Its ExecID is the
  same:** deduplicate execution reports by ExecID, always.
- **No order is lost.** Every order that reached the venue before the crash is answered after the
  restart: it reached the exchange and is reported, or it did not and is rejected, or its fate is
  unknown and it is said so (4.7).

## 2.9 Logout (35=5)

Either side may log out; the other answers with a Logout. Text (58), when the venue sends it, says
why: see section 6.3. Your orders stay in the market after a Logout or a disconnection: there is no
cancel on disconnect. Reports for them are sent to your session while you are away (2.6).

## 2.10 Session-level reject (35=3)

A message that is malformed for FIX is answered with a Reject and **not processed**: an order
rejected this way is not an order, and its ClOrdID is not used.

| Tag | Name | Value |
| --- | --- | --- |
| 45 | RefSeqNum | The rejected message's MsgSeqNum |
| 372 | RefMsgType | Its MsgType |
| 371 | RefTagID | The offending tag, when there is one |
| 373 | SessionRejectReason | Why: section 6.4 |

What draws one: a required tag missing, a value of the wrong type, a value outside the tag's
allowed values (2964=3, say), a tag not defined for the message type, and a tag the venue's
dictionary does not know at all. The venue's dictionary is FIX 4.4's, for the messages it handles,
plus the tags of 3.11: **send only tags FIX 4.4 defines for the message, or those**.
