# most-exchange-fix

The FIX adapter for [most-exchange](../most-exchange). It is an Artio FIX acceptor in front of the
exchange's order entry gateways and market data feeds. It is built on the exchange's adapter SDK
(`nl.lamia.most.exchange:client`, Apache-2.0).

**[`docs/Status.md`](docs/Status.md)** is where things stand.
**[`docs/Design.md`](docs/Design.md)** is the specification.
The exchange-side contract is `most-exchange/docs/Adapters.md`.

| Module | Role |
| --- | --- |
| `fix-session` | The Artio engine and library, the acceptor, logon and session persistence, shared by both backends |
| `order-entry` | FIX orders and cancels to SBE, and execution reports back, through `OrderEntrySession` |
| `market-data` | L1/L2 books from `DepthFeedAssembler` and the directory, out as FIX market data |

```sh
./gradlew build                              # SDK from most-exchange's GitHub Packages
./gradlew build -PmostExchange.local=true    # SDK built from ../most-exchange
```

## License and copyright

    Copyright (C) 2026  P.M.Vrolijk

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation, either version 3 of the
    License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.

The SDK it links, `nl.lamia.most.exchange:client` and `:sbe`, is Apache-2.0.
