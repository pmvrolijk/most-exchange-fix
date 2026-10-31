#!/usr/bin/env bash
# A smoke run of the containerised adapter against most-exchange's dev stack, both already up
# (deploy/README.md). Not the e2e: that is e2e/run-e2e.sh, against the installs, with crashes.
#
# What it shows: CLIENT7's 35=D rests; participant 8 crosses it through most-exchange's own CLI;
# CLIENT7, logged out meanwhile, is told of the fill at its next Logon; FEED7 gets the 35=y and a
# 35=W for AAPL. Run it as often as you like: the ids are new each time.
#
# Needs ./gradlew :tools:installDist here. MOST_EXCHANGE defaults to ../most-exchange.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ME="$(cd "${MOST_EXCHANGE:-$ROOT/../most-exchange}" && pwd)"
FIX="$ROOT/deploy/fix"
OUT="$ROOT/build/deploy/smoke"
mkdir -p "$OUT"

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "ok: $*"; }
expect() { grep -qE "$2" "$1" || { cat "$1"; fail "$3"; }; }

[ "$(docker inspect -f '{{.State.Health.Status}}' most-fix-fix-7-1 2>/dev/null)" = healthy ] \
  || fail "the adapter container most-fix-fix-7-1 is not up and healthy (deploy/README.md)"

ID=$(date +%s)
SELL="S$ID"

"$FIX" send --symbol AAPL --side sell --price 100.00 --qty 10 --clordid "$SELL" > "$OUT/sell.out" 2>&1
expect "$OUT/sell.out" "ClOrdID=$SELL .*ExecType=NEW OrdStatus=NEW" "no NEW for $SELL"
pass "CLIENT7's sell $SELL rests"

# most's --qty is the exchange's raw int64, 8 implied decimals: 4 shares is 400000000.
(cd "$ME/deploy" && ./most send --symbol AAPL --side buy --price 100.00 --qty 400000000 \
  --clordid "$ID" --participant 8 --follow 2) > "$OUT/cross.out" 2>&1
expect "$OUT/cross.out" "TRADE .*clOrdId=$ID .*last 400000000" "participant 8's buy did not trade"
pass "participant 8 crossed it through most-exchange's CLI"

"$FIX" listen --follow 3 > "$OUT/listen.out" 2>&1
expect "$OUT/listen.out" "ClOrdID=$SELL .*ExecType=TRADE .*LastQty=4 LastPx=100" \
  "CLIENT7 was not told of its fill while logged out (Design.md §6.3)"
pass "CLIENT7 was told of the fill at its next Logon"

"$FIX" securities --follow 2 > "$OUT/securities.out" 2>&1
expect "$OUT/securities.out" "SecurityList .*AAPL/1" "no 35=y with AAPL"
"$FIX" md --symbol AAPL --follow 2 > "$OUT/md.out" 2>&1
expect "$OUT/md.out" "MarketDataSnapshot .*Symbol=AAPL" "no 35=W for AAPL"
pass "FEED7 has the security list and AAPL's book"

# Leave the book as it was found.
"$FIX" cancel --clordid "C$ID" --orig "$SELL" --symbol AAPL --side sell --qty 10 > "$OUT/cancel.out" 2>&1
expect "$OUT/cancel.out" "ClOrdID=C$ID .*ExecType=CANCELED" "the rest of $SELL was not cancelled"
pass "the rest of $SELL cancelled"
