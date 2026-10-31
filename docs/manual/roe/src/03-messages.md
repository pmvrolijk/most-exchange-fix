# 3. Order entry messages

## 3.1 Overview

| You send | | The venue answers |
| --- | --- | --- |
| NewOrderSingle | 35=D | ExecutionReport (35=8): acknowledged, or rejected |
| OrderCancelRequest | 35=F | 35=8 ExecType 4, cancelled; or OrderCancelReject (35=9) |
| OrderCancelReplaceRequest | 35=G | 35=9: not supported |
| OrderStatusRequest | 35=H | 35=8 ExecType I |
| OrderMassStatusRequest | 35=AF | One 35=8 ExecType I per open order, or BusinessMessageReject (35=j) |
| Any other application message | | 35=j, BusinessRejectReason 3, unsupported message type |

Unsolicited, the venue sends 35=8 for every fill, cancellation, expiry and change of an order's
status, and 35=9 when a cancel it accepted is refused by the exchange. The session messages, 35=0,
1, 2, 3, 4, 5 and A, are FIX 4.4's (section 2).

## 3.2 Standard header and trailer

| Tag | Name | Req | Notes |
| --- | --- | --- | --- |
| 8 | BeginString | Y | `FIX.4.4` |
| 9 | BodyLength | Y | |
| 35 | MsgType | Y | |
| 49 | SenderCompID | Y | Yours |
| 56 | TargetCompID | Y | The venue's |
| 34 | MsgSeqNum | Y | Section 2.3 |
| 52 | SendingTime | Y | UTC, within two minutes of the venue's clock |
| 43 | PossDupFlag | C | `Y` on a resent message |
| 97 | PossResend | N | |
| 122 | OrigSendingTime | C | Required with 43=Y |
| 10 | CheckSum | Y | |

Other FIX 4.4 header fields are accepted and ignored.

## 3.3 NewOrderSingle (35=D)

| Tag | Name | Req | Values and rules |
| --- | --- | --- | --- |
| 11 | ClOrdID | Y | Unique per session for the trading day and while the order is open (4.8) |
| 55 | Symbol | C | The security, if 48 is absent |
| 48 | SecurityID | C | The security: the exchange's id with 22=8, or its ISIN with 22=4. Takes precedence over 55 |
| 22 | SecurityIDSource | C | Required with 48: `8` (exchange id) or `4` (ISIN) |
| 54 | Side | Y | `1` buy, `2` sell |
| 38 | OrderQty | Y | Greater than 0, exact to eight decimals |
| 40 | OrdType | Y | `2`, limit, only |
| 44 | Price | Y | Exact to eight decimals, and on the security's tick (MinPriceIncrement, 969, in the security list). Price collars are the exchange's to check (6.2) |
| 59 | TimeInForce | N | `0` day (the default), `1` good till cancel, `6` good till date |
| 432 | ExpireDate | C | Required with 59=6. `YYYYMMDD`; a date already past is rejected by the exchange |
| 60 | TransactTime | Y | Required by FIX 4.4; not forwarded |
| 2362 | SelfMatchPreventionID | N | A positive integer, as text. Absent: your participant (4.3) |
| 2964 | SelfMatchPreventionInstruction | N | `1` cancel the aggressor (the default), `2` cancel the resting order |

Other FIX 4.4 fields of 35=D, Account (1) and HandlInst (21) among them, are accepted and ignored.

**Answered** by a 35=8: ExecType 0, *new*, once the exchange has the order; or ExecType 8,
*rejected*, by the venue's interface (section 6.1) or by the exchange (6.2). An order refused for
what it says (6.1, the first table) is answered at once, never reaches the market, and its ClOrdID
is not used up; one refused because the venue cannot trade (`exchange unavailable`, 6.1) has used
its ClOrdID: send it again under a new one.

```
8=FIX.4.4|9=128|35=D|49=CLIENT7|56=MOST|34=54|52=20261031-18:55:03.240|11=RB1793472897|55=AAPL|54=1|60=20261031-18:55:02.865|38=25|40=2|44=99.5|10=171|
8=FIX.4.4|9=183|35=8|49=MOST|56=CLIENT7|34=20053|52=20261031-18:55:03.265|37=20029|11=RB1793472897|17=27|150=0|39=0|55=AAPL|48=1|22=8|54=1|38=25|44=99.5|59=0|151=25|14=0|6=0|60=20261031-18:55:03.266|10=054|
```

Refused at the interface, an IOC and an unknown security:

```
8=FIX.4.4|9=133|35=D|49=CLIENT7|56=MOST|34=57|52=20261031-18:55:05.746|11=RI1793472897|55=AAPL|54=1|60=20261031-18:55:05.376|38=25|40=2|44=99.5|59=3|10=157|
8=FIX.4.4|9=216|35=8|49=MOST|56=CLIENT7|34=20056|52=20261031-18:55:05.750|37=NONE|11=RI1793472897|17=A0.20056|150=8|39=8|103=99|55=AAPL|54=1|38=25|44=99.5|59=3|151=0|14=0|6=0|60=20261031-18:55:05.751|58=time in force 0, 1 or 6 only|10=150|
8=FIX.4.4|9=194|35=8|49=MOST|56=CLIENT7|34=20059|52=20261031-18:55:08.262|37=NONE|11=RZ1793472897|17=A0.20059|150=8|39=8|103=1|55=ZZZZ|54=1|38=1|44=1|151=0|14=0|6=0|60=20261031-18:55:08.263|58=unknown security|10=209|
```

## 3.4 OrderCancelRequest (35=F)

| Tag | Name | Req | Values and rules |
| --- | --- | --- | --- |
| 11 | ClOrdID | Y | The cancel's own id, unique as an order's is |
| 41 | OrigClOrdID | Y | The order's ClOrdID |
| 37 | OrderID | N | If sent, must be the order's |
| 54 | Side | Y | Must be the order's |
| 55, 48, 22 | The security | N | If sent, must be the order's |
| 60 | TransactTime | Y | |

The venue finds the order by OrigClOrdID; anything you send besides must match it, or the cancel is
refused (35=9, CxlRejReason 1). Other FIX 4.4 fields of 35=F, OrderQty (38) among them, are accepted
and not used.

**Answered** by a 35=8 for the order, ExecType 4, *cancelled*, with 11 the cancel's ClOrdID and 41
the order's; or by a 35=9 (3.9).

```
8=FIX.4.4|9=131|35=F|49=CLIENT7|56=MOST|34=69|52=20261031-18:55:15.763|41=RB1793472897|11=RC1793472897|55=AAPL|54=1|60=20261031-18:55:15.388|38=25|10=192|
8=FIX.4.4|9=198|35=8|49=MOST|56=CLIENT7|34=20068|52=20261031-18:55:15.778|37=20029|11=RC1793472897|41=RB1793472897|17=28|150=4|39=4|55=AAPL|48=1|22=8|54=1|38=25|44=99.5|59=0|151=0|14=0|6=0|60=20261031-18:55:15.779|10=125|
```

- **A cancel sent before the order is acknowledged** is held and sent as soon as it is. If the
  order's first report is final (rejected, or filled at once), the cancel is refused *too late*.
- **One cancel at a time:** a cancel for an order with another cancel still pending is refused,
  CxlRejReason 3. Once the first is answered, a new one is accepted.

## 3.5 OrderCancelReplaceRequest (35=G)

Not supported: the exchange has no replace. Answered with a 35=9, CxlRejResponseTo (434) 2,
CxlRejReason (102) 2 and Text `replace not supported`. The order is unchanged. To change an order,
cancel it and enter a new one.

## 3.6 OrderStatusRequest (35=H)

| Tag | Name | Req | Values and rules |
| --- | --- | --- | --- |
| 11 | ClOrdID | Y | The order's ClOrdID: the order is found by this alone |
| 54 | Side | Y | |
| 55, 48, 22 | The security | C | As FIX 4.4 requires; echoed on an unknown order |
| 790 | OrdStatusReqID | N | Echoed on the answer |

**Answered** with a 35=8, ExecType I, stating the order as you were last told it: OrdStatus,
CumQty, LeavesQty, AvgPx. A ClOrdID of a cancel gets its order's status, with 11 the cancel's
and 41 the order's.

```
8=FIX.4.4|9=84|35=H|49=CLIENT7|56=MOST|34=63|52=20261031-18:55:10.763|11=RB1793472897|55=AAPL|54=1|10=079|
8=FIX.4.4|9=189|35=8|49=MOST|56=CLIENT7|34=20062|52=20261031-18:55:10.767|37=20029|11=RB1793472897|17=A0.20062|150=I|39=0|55=AAPL|48=1|22=8|54=1|38=25|44=99.5|59=0|151=25|14=0|6=0|60=20261031-18:55:10.768|10=143|
```

**An unknown ClOrdID** (never sent, or no longer held after its trading day) is answered as FIX 4.4
says: ExecType I, OrdStatus 8, OrdRejReason 5, Text `unknown order`, OrderID `NONE`, and ClOrdID,
Side and the security echoed.

## 3.7 OrderMassStatusRequest (35=AF)

| Tag | Name | Req | Values and rules |
| --- | --- | --- | --- |
| 584 | MassStatusReqID | Y | Echoed on every answer |
| 585 | MassStatusReqType | Y | `7` all your orders, or `1` one security. Any other value is a session reject (6.4) |
| 55, 48, 22 | The security | C | Required with 585=1 |
| 54 | Side | N | Only orders of this side |

**Answered** with one 35=8, ExecType I, per open order, each with MassStatusReqID (584),
TotNumReports (911) and LastRptRequested (912), `Y` on the last. *Open* is every order not in a
final state: pending, new, partially filled, or of unknown status (4.7).

```
8=FIX.4.4|9=222|35=8|49=MOST|56=CLIENT7|34=20065|52=20261031-18:55:13.273|37=20029|11=RB1793472897|584=MS-1793472912902|911=1|912=Y|17=A0.20065|150=I|39=0|55=AAPL|48=1|22=8|54=1|38=25|44=99.5|59=0|151=25|14=0|6=0|60=20261031-18:55:13.274|10=016|
```

Answered instead with a 35=j:

| Case | BusinessRejectReason (380) | Text |
| --- | --- | --- |
| No open order matches | 0 | `no open orders` |
| 585=1 with no security named | 5 | |
| 585=1 naming a security the venue does not list | 2 | `unknown security` |
| The venue not ready to trade | 4 | `exchange unavailable` |

## 3.8 ExecutionReport (35=8)

| Tag | Name | Notes |
| --- | --- | --- |
| 37 | OrderID | The exchange's order id; `NONE` before it is acknowledged |
| 11 | ClOrdID | The order's; on the report of a cancel, the cancel's |
| 41 | OrigClOrdID | On the report of a cancel: the order's ClOrdID |
| 17 | ExecID | See below |
| 150 | ExecType | `0` new, `F` trade, `4` cancelled, `C` expired, `8` rejected, `I` order status |
| 39 | OrdStatus | See the table below |
| 55, 48, 22 | The security | Symbol, the exchange's SecurityID, SecurityIDSource `8` |
| 54 | Side | As sent |
| 38 | OrderQty | As sent |
| 44 | Price | As sent |
| 59, 432 | TimeInForce, ExpireDate | As sent; 59 stated on every order |
| 2362, 2964 | Self-match prevention | Echoed when sent |
| 32, 31 | LastQty, LastPx | On a trade |
| 151 | LeavesQty | Stated by the exchange |
| 14 | CumQty | Stated by the exchange |
| 6 | AvgPx | Over the fills reported to you; 0 before any |
| 60 | TransactTime | The venue's time when it reported |
| 103, 58 | OrdRejReason, Text | On a reject (section 6) |
| 5001 | OrdStatusUnknown | `Y` when the order's status is unknown (4.7); absent otherwise |
| 584, 911, 912 | Mass status | On an answer to 35=AF |
| 43 | PossDupFlag | `Y` on a resend (2.6) |

| ExecType (150) | OrdStatus (39) |
| --- | --- |
| `0` new | `0` new |
| `F` trade | `1` partially filled, or `2` filled when LeavesQty is 0 |
| `4` cancelled | `4` cancelled |
| `C` expired | `C` expired |
| `8` rejected | `8` rejected |
| `I` order status | The order's: `A` pending new, `0`, `1`, `2`, `4`, `C` or `8` |

**ExecID.** A report from the exchange carries the exchange's report sequence number, in decimal;
it never changes for that report, so a duplicate is recognised by it. A message the venue's
interface answers itself (a reject at the interface, a status, an unknown status) carries
`A<index>.<MsgSeqNum>`, the MsgSeqNum of the 35=8 itself; those are never sent twice.

**CumQty and LeavesQty** are the exchange's own figures on every report, never derived; trust them
over a sum of fills. A status that finds CumQty above what you were told means fills were made that
could not be reported one by one: Text says
`fills not reported individually: CumQty <a> to <b>, prices unknown`, and AvgPx stays that of the
fills you were told of.

## 3.9 OrderCancelReject (35=9)

| Tag | Name | Notes |
| --- | --- | --- |
| 37 | OrderID | The order's; `NONE` if unknown or not yet acknowledged |
| 11 | ClOrdID | The cancel's (or the replace's) |
| 41 | OrigClOrdID | As sent |
| 39 | OrdStatus | The order's status as you were last told it; `8` when the order is unknown (CxlRejReason 1) |
| 434 | CxlRejResponseTo | `1` to a 35=F, `2` to a 35=G |
| 102 | CxlRejReason | Section 6.3 |
| 58 | Text | Why, in words |
| 5001 | OrdStatusUnknown | `Y` when the order's status is unknown |

```
8=FIX.4.4|9=131|35=F|49=CLIENT7|56=MOST|34=72|52=20261031-18:55:18.265|41=RB1793472897|11=RD1793472897|55=AAPL|54=1|60=20261031-18:55:17.892|38=25|10=189|
8=FIX.4.4|9=138|35=9|49=MOST|56=CLIENT7|34=20071|52=20261031-18:55:18.269|37=20029|11=RD1793472897|41=RB1793472897|39=4|434=1|102=0|58=too late to cancel|10=152|
```

(A second cancel of an order already cancelled: *too late*, OrdStatus 4.)

## 3.10 BusinessMessageReject (35=j)

| Tag | Name | Notes |
| --- | --- | --- |
| 45 | RefSeqNum | The refused message's MsgSeqNum |
| 372 | RefMsgType | Its MsgType |
| 380 | BusinessRejectReason | `3` unsupported message type; for 35=AF, section 3.7 |
| 58 | Text | Why, in words |

A 35=j you send is never answered.

## 3.11 Custom and non-standard tags

| Tag | Name | Type | On | Source |
| --- | --- | --- | --- | --- |
| 2362 | SelfMatchPreventionID | String: a positive integer | 35=D, echoed on 35=8 | FIX Latest (EP211) |
| 2964 | SelfMatchPreventionInstruction | int: `1` or `2` | 35=D, echoed on 35=8 | FIX Latest (EP280); its value `3` is not accepted |
| 5001 | OrdStatusUnknown | Boolean | 35=8, 35=9 | The venue's own (user-defined range) |
| 969 | MinPriceIncrement | Float | 35=y entries | FIX 5.0; the tick size |
