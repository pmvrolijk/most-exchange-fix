# most-exchange-fix

[![CI](https://github.com/pmvrolijk/most-exchange-fix/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/pmvrolijk/most-exchange-fix/actions/workflows/ci.yml?query=branch%3Amaster)

The FIX adapter for [most-exchange](https://github.com/pmvrolijk/most-exchange). It is an Artio FIX
acceptor in front of the exchange's order entry gateways and market data feeds. It is built on the
exchange's adapter SDK (`nl.lamia.most.exchange:client`, Apache-2.0).

## Documentation

| Document | What it is |
| --- | --- |
| [`docs/Status.md`](docs/Status.md) | Where things stand, what is open, what to do next: the shortest way in |
| [`docs/Design.md`](docs/Design.md) | The specification, authoritative; §8 is the open list |
| [`docs/Rationale.md`](docs/Rationale.md) | Why each rule in [`CLAUDE.md`](CLAUDE.md) exists |
| [`docs/Handover.md`](docs/Handover.md) | How each change happened, the archive behind Status |
| [`docs/Measurements.md`](docs/Measurements.md) | Latency measurements, with how each was taken |
| [`docs/OperatorManual.pdf`](docs/OperatorManual.pdf) | The Operator's Manual: installing, configuring and running the adapter, every configuration key, troubleshooting |
| [`docs/RulesOfEngagement.pdf`](docs/RulesOfEngagement.pdf) | The Rules of Engagement, for a participant's FIX developers: the session, every message and tag, order types, reject codes |
| [`docs/manual/`](docs/manual/README.md) | The manuals' sources and how to build them |
| [`deploy/README.md`](deploy/README.md) | The Docker setup beside most-exchange's development stack |
| [Adapters.md](https://github.com/pmvrolijk/most-exchange/blob/master/docs/Adapters.md) | most-exchange's contract for adapters, which this repository implements |

## Modules

| Module | Role |
| --- | --- |
| `fix-session` | The Artio engine and library, the acceptor, logon and session persistence, shared by both backends |
| `order-entry` | FIX orders and cancels to SBE, and execution reports back, through `OrderEntrySession` |
| `market-data` | L1/L2 books from `DepthFeedAssembler` and the directory, out as FIX market data |
| `adapter` | The process: configuration, both engines, startup and crash recovery |
| `tools` | `bin/fix`: the e2e's FIX client, and `perf`/`report`, the latency tooling (Design §10) |

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

**The `docs/` folder is licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)**, not
the AGPL, as most-exchange's: the specification, the rules and their rationale, the Operator's Manual
and the Rules of Engagement may be shared and adapted with attribution. The full text is
[`docs/LICENSE`](docs/LICENSE).

    Copyright (C) 2026  P.M.Vrolijk

    The contents of docs/ are licensed under the Creative Commons
    Attribution 4.0 International License. To view a copy of this
    license, visit https://creativecommons.org/licenses/by/4.0/
