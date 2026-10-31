# 5. Market data

## 5.1 The market-data session

A separate session, at its own port, with its own CompIDs and credentials. It is **transient**:
both sides start at MsgSeqNum 1 at every Logon, nothing is resent, and subscriptions end with the
session. After a reconnect, log on with ResetSeqNumFlag = Y and subscribe again: you get a fresh
snapshot, never a stale one.

Market data is **by price level**: the best bid and offer (MarketDepth 1), or every level (0), plus
last trades. There is no market by order.

| You send | | The venue answers |
| --- | --- | --- |
| SecurityListRequest | 35=x | SecurityList (35=y), one or more |
| MarketDataRequest | 35=V | MarketDataSnapshotFullRefresh (35=W), then MarketDataIncrementalRefresh (35=X); or MarketDataRequestReject (35=Y) |

## 5.2 The security list (35=x, 35=y)

| Tag | Name | Req | Values |
| --- | --- | --- | --- |
| 320 | SecurityReqID | Y | Yours, echoed |
| 559 | SecurityListRequestType | Y | `4`, all securities. Any other is answered with SecurityRequestResult 1 |

The answer lists every security, in SecurityID order, **at most 100 per 35=y**:

| Tag | Name | Notes |
| --- | --- | --- |
| 320 | SecurityReqID | Yours |
| 322 | SecurityResponseID | Your SecurityReqID |
| 560 | SecurityRequestResult | `0` the list; `1` invalid or unsupported request; `4` temporarily unavailable, before the venue has its directory |
| 393 | TotNoRelatedSym | The number of securities in all the messages |
| 893 | LastFragment | `Y` on the last message |
| 146 | NoRelatedSym | Then, per security: |
| 55 | Symbol | |
| 48, 22 | SecurityID, SecurityIDSource | The exchange's id, `8` |
| 454 | NoSecurityAltID | `1`, then: |
| 455, 456 | SecurityAltID, SecurityAltIDSource | The ISIN, `4` |
| 107 | SecurityDesc | The security's name |
| 15 | Currency | |
| 969 | MinPriceIncrement | The tick size (FIX 5.0's tag) |

```
8=FIX.4.4|9=79|35=x|49=FEED7|56=MOST|34=2|52=20261031-18:55:21.282|320=SL-1793472920856|559=4|10=023|
8=FIX.4.4|9=285|35=y|49=MOST|56=FEED7|34=2|52=20261031-18:55:21.288|320=SL-1793472920856|322=SL-1793472920856|560=0|393=2|893=Y|146=2|55=AAPL|48=1|22=8|454=1|455=US0378331005|456=4|107=Apple Inc.|15=USD|969=0.01|55=MSFT|48=2|22=8|454=1|455=US5949181045|456=4|107=Microsoft Corporation|15=USD|969=0.01|10=029|
```

## 5.3 MarketDataRequest (35=V)

| Tag | Name | Req | Values |
| --- | --- | --- | --- |
| 262 | MDReqID | Y | Your subscription's name; unique among your session's subscriptions |
| 263 | SubscriptionRequestType | Y | `0` snapshot only; `1` snapshot plus updates; `2` unsubscribe |
| 264 | MarketDepth | Y | `1` top of book; `0` every level |
| 265 | MDUpdateType | C | Required with 263=1: `1`, incremental |
| 266 | AggregatedBook | N | `Y` or absent. `N` is refused |
| 267 | NoMDEntryTypes | Y | Then 269 MDEntryType, once per type: `0` bid, `1` offer, `2` trade |
| 146 | NoRelatedSym | Y | Then, per security: 48 with 22 (`8` or `4`), or 55 |

A subscription may name several securities; each gets its own 35=W and its own 35=X. **263=2**
removes the subscription named by MDReqID, unanswered; an MDReqID not subscribed is ignored.
**263=0** sends each security's 35=W and keeps nothing.

Refused, with a 35=Y (5.6): TradingSessionID (336), Scope (546), OpenCloseSettlFlag (286), and
MDImplicitDelete (547) = Y.

```
8=FIX.4.4|9=129|35=V|49=FEED7|56=MOST|34=2|52=20261031-18:55:23.776|262=MD-1793472923401|263=1|264=1|265=1|267=3|269=0|269=1|269=2|146=1|55=AAPL|10=051|
```

## 5.4 The snapshot (35=W)

One per security, first in every subscription:

| Tag | Name | Notes |
| --- | --- | --- |
| 262 | MDReqID | Yours |
| 55, 48, 22 | The security | Symbol, SecurityID, `8` |
| 268 | NoMDEntries | Then the bids, best first; the offers, best first; then the last trade, if you asked for trades and there is one |
| 269 | MDEntryType | `0` bid, `1` offer, `2` trade |
| 270 | MDEntryPx | The level's price |
| 271 | MDEntrySize | The level's total quantity |
| 346 | NumberOfOrders | The orders at the level (not on a trade) |

One level per side at MarketDepth 1, every level at 0. An empty book is a 35=W with no entries; only
the entry types you asked for are sent.

```
8=FIX.4.4|9=134|35=W|49=MOST|56=FEED7|34=2|52=20261031-18:55:23.781|262=MD-1793472923401|55=AAPL|48=1|22=8|268=1|269=0|270=99.98|271=0.00000089|346=1|10=079|
```

(The book had one bid, no offer and no trade yet.)

## 5.5 Updates (35=X)

One 35=X per change, with the security on each entry:

| Tag | Name | Notes |
| --- | --- | --- |
| 262 | MDReqID | Yours |
| 268 | NoMDEntries | Then, per entry: |
| 279 | MDUpdateAction | `0` new, `1` change, `2` delete |
| 269 | MDEntryType | `0` bid, `1` offer, `2` trade |
| 55, 48, 22 | The security | |
| 270 | MDEntryPx | The level's price |
| 271 | MDEntrySize | Its new total quantity; absent on a delete |
| 346 | NumberOfOrders | Its orders; absent on a delete |

**At MarketDepth 0** (every level): `0` a price the book did not have; `1` a new total at a price it
had; `2` the level is gone.

**At MarketDepth 1** (top of book), you hold one entry per side: a new best price is a `2` for the
old one and a `0` for the new; a new total at the same best price is a `1`; an empty side is a `2`.
A change below the best sends nothing.

**A trade** is 269=2, 279=0, with 270 and 271.

## 5.6 MarketDataRequestReject (35=Y)

| Tag | Name | Notes |
| --- | --- | --- |
| 262 | MDReqID | Yours |
| 281 | MDReqRejReason | Below |
| 58 | Text | What was refused |

| 281 | Why |
| --- | --- |
| `0` | A security the venue does not list. The whole request is refused |
| `1` | The MDReqID is already subscribed on this session |
| `5` | MarketDepth other than 0 or 1 |
| `6` | MDUpdateType other than 1, or none, with 263=1 |
| `7` | AggregatedBook = N |
| `8` | An MDEntryType other than 0, 1 or 2 |
| `9` | A TradingSessionID |
| `A` | A Scope |
| `B` | An OpenCloseSettlFlag |
| `C` | MDImplicitDelete = Y |

## 5.7 Resynchronisation and slow consumers

- **A book the venue is not sure of**, when it has lost sequence with the exchange's feed, sends you
  nothing: your snapshot waits, updates stop. Once the book is trusted again, you get a **fresh
  35=W** that replaces what you hold, and updates resume. If the book is exactly as you hold it,
  updates simply resume, with no 35=W.
- **If you cannot keep up**, the venue does not queue updates without limit: it stops sending you
  35=X for that subscription and sends a **fresh 35=W** as soon as it can. Your book is replaced,
  never left wrong; you are not logged out.
- **Apply a 35=W as a replacement**, not as changes, every time one arrives, not only the first.
