# 7. Troubleshooting

## 7.1 Before you call the venue

The venue records every FIX message on your session, both ways. To find yours quickly, give:

- the session: your SenderCompID, and whether order entry or market data;
- the **MsgSeqNums** involved, yours and the venue's, and the times in UTC;
- for an order: its **ClOrdID**, and the OrderID and ExecIDs you received;
- the raw messages from your engine's log, if you have them.

## 7.2 Cannot log on

| What you see | Likely cause | What to do |
| --- | --- | --- |
| Connection refused | Wrong host or port; the venue is not accepting connections (a restart takes some seconds) | Check the address; retry after a few seconds |
| Logout `Logon refused`, at MsgSeqNum 1 | One of SenderCompID, TargetCompID, Username, Password does not match, **or you connected to the other session's port** | Check all four and the port. The venue does not say which |
| Logout naming a MsgSeqNum too low | Your Logon is numbered below what the venue expects: your sequence store was reset or restored | Do not guess a number. Contact the venue, or reset with 141=Y and recover with 35=AF (2.7) |
| Market data: Logon refused or out of sequence after a reconnect | Market-data sessions start at 1 every time | Log on with 141=Y, or at MsgSeqNum 1 |

## 7.3 Logged out during the session

| Text | What to do |
| --- | --- |
| `messages <from> to <to> not received; resend them` | Your engine did not answer the venue's ResendRequests. After your next Logon, resend that range, PossDup = Y. Check that your engine answers a ResendRequest with EndSeqNo 0 up to its last message sent (2.5) |
| No Text, after a period of silence | Heartbeats missed: your engine stopped sending, or the network did. Check HeartBtInt and that your engine answers TestRequests |
| A session reject before it, SessionRejectReason 10 | Your clock is more than two minutes off |

## 7.4 Missing or duplicate reports

- **A fill you did not receive.** If it happened while you were logged out, your engine should have
  seen the venue's MsgSeqNum jump at the next Logon and asked for the gap (2.6). If it did not ask,
  or skipped the gap, the report is still in the venue's record: ask for the order's status (35=H)
  or a mass status (35=AF), and contact the venue with the MsgSeqNums.
- **You reset with 141=Y.** A reset gives up every report not yet received; CumQty on the status
  answers shows the fills, not one by one (2.7).
- **A report twice.** After a resend, or after a venue crash, a report can arrive again; its ExecID
  is the same. Deduplicate by ExecID (2.8).
- **CumQty above the sum of the fills you have.** Fills could not be reported one by one; the Text
  on the report says so, and CumQty and LeavesQty are right (3.8).
- **An order with OrdStatusUnknown = Y.** Its fate is unknown; do not treat it as cancelled or
  rejected (4.7). Contact the venue to reconcile.
- **A rejected order you are sure was valid,** `exchange unavailable` or
  `not sequenced (gateway failover)`: the market was not reachable for a moment. The order did not
  trade; send it again under a new ClOrdID.

## 7.5 Orders and cancels refused

Read the Text: it is fixed and section 6 lists every one. The most common:

| Text | Usually |
| --- | --- |
| `time in force 0, 1 or 6 only` | IOC or FOK sent: not supported (4.1) |
| `limit orders only` | OrdType other than 2 |
| `unknown security` | Symbol misspelt, or 22 missing with 48. Take the identifiers from the security list (5.2) |
| `price not exact at 8 decimals`, `incorrect quantity` | A float formatted with more than eight decimals: format decimals exactly |
| `duplicate ClOrdID` | ClOrdIDs reused within the day; use unique ones |
| `ClOrdID must be 1-20 of A-Z a-z 0-9 - _ + ; : . /` | A ClOrdID too long, a UUID, or with another character: shorten it, or map your ids to ones that fit (4.8) |
| `MARKET_CLOSED` | The security is not trading: outside hours, or halted |
| `PRICE_OUT_OF_BOUNDS` | The price is outside the collar around the reference price |
| `ORDER_EXPIRED` | A GTD ExpireDate in the past |
| `too late to cancel` | The order was already filled, cancelled or expired |
| `already pending cancel` | Wait for the first cancel's answer |

## 7.6 Market data

| What you see | Likely cause |
| --- | --- |
| No 35=W after a 35=V | The venue is not yet sure of the book: the snapshot comes once it is (5.7). A 35=Y instead says why it was refused |
| A fresh 35=W in the middle of updates | A resynchronisation, or you fell behind (5.7). Replace your book with it |
| 35=Y, MDReqRejReason 1 | That MDReqID is already subscribed on the session; unsubscribe it or use another |
| 35=y with SecurityRequestResult 4 | The venue is starting up; ask again shortly |
| After a reconnect, nothing arrives | Subscriptions end with the session: subscribe again |

## 7.7 Testing your connection

Before going live, in the venue's test environment:

1. Log on, log out, log on again, with sequence numbers carried over; then with a wrong password,
   and see `Logon refused`.
2. Enter, fill and cancel an order; check ExecType, OrdStatus, CumQty, LeavesQty and AvgPx on each
   report.
3. Log out with an order resting, have it filled, log on: the fill must arrive as a PossDup report
   your engine accepts.
4. Send an IOC, a market order and an unknown symbol: each must be rejected with its Text.
5. Send a 35=H and a 35=AF and reconcile your view against the answers.
6. Resend an order with PossDup = Y and check it is not executed twice.
7. Subscribe to a book at both depths, apply snapshots and updates, and compare with the venue's
   view; unsubscribe.
8. Reset with 141=Y and recover your orders from a 35=AF.
