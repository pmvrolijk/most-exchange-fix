# 1. Introduction

## 1.1 Scope

This document is for a participant connecting to most-exchange over FIX: the developers who build
the connection and the support staff who run it. It states what the venue's FIX interface accepts,
what it sends, and what it guarantees. It does not repeat the FIX 4.4 specification; where this
document is silent, FIX 4.4 applies.

The FIX interface is an adapter in front of most-exchange's matching engine. It adds no order types
and no risk checks of its own: **an order is accepted only if the exchange can carry it**, and
everything else is refused at the interface, with a reject that says why, before it reaches the
market.

## 1.2 The venue and its sessions

| | |
| --- | --- |
| Protocol | FIX 4.4 (`8=FIX.4.4`), with the custom tags of 3.11 |
| Transport | TCP. TLS, where offered, is the venue operator's and is terminated before the FIX engine |
| Your role | Initiator. The venue is the acceptor |
| Sessions | Two per participant, each on its own port: **order entry** and **market data** |

**An order-entry session acts for one participant**, the trading identity the venue configured for
it. Every order and cancel on it is that participant's; nothing on a message can name another.

**A market-data session** asks for the list of securities and subscribes to their books. It carries
no orders and acts for no participant.

The venue gives you, per session: the host and port, your SenderCompID, the venue's CompID, a
username, and a password. The password is a generated secret: the venue keeps only a digest of it
and can reissue it, never recover it.

## 1.3 Conventions

- **Prices and quantities are decimals, exact to eight places.** The exchange holds them as
  fixed-point integers with eight implied decimals; a value with more than eight decimals is
  refused, never rounded. `100.25`, `100.250`, `10` and `0.00000001` are all valid.
- **Timestamps** are UTC, `YYYYMMDD-HH:MM:SS.sss`, as FIX 4.4 defines UTCTimestamp.
- **Dates** (ExpireDate, 432) are LocalMktDate, `YYYYMMDD`.
- **A security** is named by SecurityID (48) with SecurityIDSource (22) `8`, the exchange's own
  numeric id, or `4`, its ISIN; or by Symbol (55) alone. The security list (section 5) gives all
  three.
- In tables, *Req* is **Y** for required, **N** for optional, **C** for conditional.
- Examples are real messages from the venue's development environment, with `|` for the SOH
  delimiter. Passwords appear as `***`, as the venue logs them.

## 1.4 Identifiers

| Identifier | Tag | Whose | Rules |
| --- | --- | --- | --- |
| ClOrdID | 11 | Yours | 1 to 20 characters of `A-Z a-z 0-9 - _ + ; : . /` (4.8). Unique per session for the trading day, and for as long as the order is open. Used on every order and cancel, and carried to the exchange as sent |
| OrigClOrdID | 41 | Yours | The ClOrdID of the order a cancel refers to |
| OrderID | 37 | The exchange's | Assigned when the order is acknowledged; `NONE` before that, and on a reject |
| ExecID | 17 | The exchange's | Unique per session. A report from the exchange carries the exchange's report sequence number; a message the interface answers itself carries `A<index>.<MsgSeqNum>` (3.8) |

The exchange's ExecID never changes for a report: **a report delivered twice carries the same
ExecID**, and you can drop the second (2.8).

## 1.5 What is not supported

- **Order types:** market, stop and stop-limit orders; IOC and FOK; anything but a limit order with
  time in force Day, GTC or GTD (section 4).
- **Replace:** OrderCancelReplaceRequest (35=G) is answered with a cancel reject (3.5).
- **Client fields the exchange does not carry**, Account (1) among them: accepted, ignored, not
  echoed.
- **Market by order:** market data is by price level (L1, L2) only.
- **Drop copy** and **trade capture:** not offered.

## 1.6 How to read this document

Section 2 is the FIX session: connecting, logging on, sequence numbers, and how nothing is lost
across a disconnection. Section 3 is every order-entry message and tag, in and out. Section 4 is
what an order can be and the states it goes through. Section 5 is market data. Section 6 collects
every reject code and its text. Section 7 is troubleshooting. Section 8 indexes tags, messages and
terms.
