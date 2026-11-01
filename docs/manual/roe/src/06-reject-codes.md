# 6. Reject and reason codes

Every refusal the venue sends, with its code and its Text. **The Texts are fixed**: you may match on
them.

## 6.1 Orders refused at the venue's interface

A 35=8, ExecType 8, OrdStatus 8, OrderID `NONE`, answered at once; the order never reaches the
market. For those in the first table nothing is recorded, and the ClOrdID may be used again.

| Refused | OrdRejReason (103) | Text (58) |
| --- | --- | --- |
| 11 not 1 to 20 of `A-Z a-z 0-9 - _ + ; : . /` (4.8) | 99 | `ClOrdID must be 1-20 of A-Z a-z 0-9 - _ + ; : . /` |
| Neither 48 nor 55; 48 without 22, or 22 other than 4 or 8; a security the venue does not list | 1 | `unknown security` |
| 38 absent, zero or negative, or not exact at 8 decimals | 13 | `incorrect quantity` |
| 40 other than 2 | 99 | `limit orders only` |
| 44 absent | 99 | `price required` |
| 44 not exact at 8 decimals | 99 | `price not exact at 8 decimals` |
| 54 other than 1 or 2 | 99 | `side 1 or 2 only` |
| 59 other than 0, 1 or 6 | 99 | `time in force 0, 1 or 6 only` |
| 59=6 without 432, or 432 not a `YYYYMMDD` date | 99 | `ExpireDate required for GTD` |
| 2362 not a positive integer | 99 | `SelfMatchPreventionID must be a positive integer` |

Refused after the order was recorded (its ClOrdID is used):

| Refused | OrdRejReason | Text |
| --- | --- | --- |
| ClOrdID already used on the session (4.8) | 6 | `duplicate ClOrdID` |
| The venue cannot reach the exchange, or is not yet ready to trade | 99 | `exchange unavailable` |
| The exchange's gateway does not serve your participant | 99 | `participant not served` |
| Never reached the exchange, at a gateway failover (4.7) | 99 | `not sequenced (gateway failover)` |

## 6.2 Orders rejected by the exchange

A 35=8, ExecType 8, with **the exchange's reason as Text**:

| Text | OrdRejReason | Why |
| --- | --- | --- |
| `UNKNOWN_SECURITY` | 1 | The security is not traded on the exchange's shard |
| `MARKET_CLOSED` | 2 | The security is not in a phase that accepts orders; a volatility halt looks like this |
| `PRICE_OUT_OF_BOUNDS` | 99 | Outside the static price collar |
| `PRICE_OUT_OF_LADDER` | 99 | Outside the book's price range |
| `BOOK_CAPACITY` | 99 | The book cannot take more orders |
| `ORDER_EXPIRED` | 99 | ExpireDate before the current trading date |
| `UNAUTHORIZED_PARTICIPANT` | 99 | Your participant may not trade through the venue's gateway; contact the venue |

## 6.3 Cancels refused (35=9)

| Refused | CxlRejReason (102) | Text (58) |
| --- | --- | --- |
| The order is already filled, cancelled, expired or rejected | 0 | `too late to cancel` |
| The cancel's own 11 not 1 to 20 of `A-Z a-z 0-9 - _ + ; : . /` (4.8) | 99 | `ClOrdID must be 1-20 of A-Z a-z 0-9 - _ + ; : . /` |
| No order with that OrigClOrdID, 41 names a cancel, or 41 does not fit (4.8) | 1 | `unknown order` |
| OrderID, Side or the security sent and not the order's | 1 | `does not match the order` |
| A replace (35=G), CxlRejResponseTo 2 | 2 | `replace not supported` |
| Another cancel of the order is pending | 3 | `already pending cancel` |
| The exchange refused it: the order is not resting | 1 | `UNKNOWN_ORDER` (or 0, `too late to cancel`, when the venue already knows the order is finished) |
| The exchange refused it: the market is closed | 2 | `MARKET_CLOSED` |
| The venue cannot reach the exchange | 99 | `exchange unavailable` |
| Never reached the exchange, at a failover | 99 | `not sequenced` |
| Its fate unknown, at a failover; OrdStatusUnknown = Y | 99 | `cancel status unknown` |
| The order's status is unknown (4.7); OrdStatusUnknown = Y | 99 | `STATUS UNKNOWN: …` |

## 6.4 Session-level rejects (35=3)

The message is not processed (2.10). SessionRejectReason (373), FIX 4.4's codes; those you will
meet:

| 373 | Means |
| --- | --- |
| `0` | Invalid tag number: a tag the venue's dictionary does not define |
| `1` | Required tag missing |
| `2` | Tag not defined for this message type |
| `5` | Value incorrect (out of range) for this tag: 2964=3, 585 other than 1 or 7 |
| `6` | Incorrect data format for value |
| `10` | SendingTime accuracy problem: your clock (2.4) |

RefTagID (371) names the tag when there is one.

## 6.5 Business-level rejects (35=j)

| 380 | Text (58) | When |
| --- | --- | --- |
| `0` | `no open orders` | A 35=AF that matches no open order |
| `2` | `unknown security` | A 35=AF naming a security the venue does not list |
| `3` | `unsupported message type` | A message type the venue does not handle |
| `4` | `exchange unavailable` | A 35=AF before the venue is ready to trade |
| `5` | `security required for MassStatusReqType 1` | A 35=AF of type 1 with no security |

## 6.6 Logouts

| Text (58) | Why | What to do |
| --- | --- | --- |
| `Logon refused` | Your Logon matched no session: CompIDs, username, password, or the port (2.2) | Check each; the venue does not say which |
| `messages <from> to <to> not received; resend them` | Three ResendRequests went unanswered (2.5) | Resend that range after your next Logon |

## 6.7 Unknown order on a status request

A 35=H for a ClOrdID the venue does not hold: 35=8, ExecType I, OrdStatus 8, OrdRejReason 5,
Text `unknown order`, OrderID `NONE` (3.6).

## 6.8 Market data

MDReqRejReason (281) is in 5.6; SecurityRequestResult (560) in 5.2.
