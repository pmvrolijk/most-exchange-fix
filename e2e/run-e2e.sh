#!/usr/bin/env bash
# End to end: FIX clients -> the adapter -> most-exchange's real processes, and back.
#
# One cluster member, one independent gateway, market data, discovery, all from ../most-exchange's installs. The
# adapter runs its own media driver (Design.md §3.5), so the gateway's client channels and the
# directory are UDP: the requests to the gateway's endpoint, the reports and the directory on
# dynamic multi-destination channels, which both the adapter and most-exchange's `most` subscribe to.
#
# What it shows: a 35=D reaches the book and rests; a cross trades both sides; a report for a
# logged-out client waits for its logon; market data, 35=x and 35=V, from most-exchange's own
# market-data process; 35=H, 35=F and 35=AF; a clean restart keeps the store; and a SIGKILL of the
# adapter under load loses no order and leaves none unknown.
#
# Prerequisites:  ./gradlew :adapter:installDist :tools:installDist   (here)
#                 ./gradlew installDist                                (in ../most-exchange)
#
# Knobs: MOST_EXCHANGE (default ../most-exchange), E2E_DIR, FIX_PORT (default 29880), FIX_MD_PORT (FIX_PORT + 1),
#        LOAD_COUNT (default 400), LOAD_RATE (default 100/s per client),
#        E2E_PAUSE_ON_FAIL=SECONDS to keep the processes up after a failure, to inspect them.
#
# Single machine, fileSyncLevel 1 on whatever disk this is: nothing here is a latency figure.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ME="$(cd "${MOST_EXCHANGE:-$ROOT/../most-exchange}" && pwd)"
RUN="${E2E_DIR:-$ROOT/build/e2e}"
LOGS="$RUN/logs"
AERON_DIR="$RUN/exchange-aeron"
FIX_PORT="${FIX_PORT:-29880}"
LOAD_COUNT="${LOAD_COUNT:-400}"
LOAD_RATE="${LOAD_RATE:-100}"


# shellcheck source=exchange.sh
source "$ROOT/e2e/exchange.sh"

# fixc <client 1|2> <command> [options]: the FIX client, logged on as CLIENTn, its state kept across runs.
fixc() {
  local n="$1"; shift
  local command="$1"; shift
  "$FIX" "$command" "$@" --port "$FIX_PORT" --sender "CLIENT$n" --user "client$n" --password "client$n-secret" \
    --state "$RUN/clients/client$n"
}

# expect <file> <pattern> <what>
expect() { grep -qE "$2" "$1" || { cat "$1"; fail "$3"; }; }

# ---------------------------------------------------------------- processes
start_exchange

echo
echo "== 1. the adapter starts, finds the shard in the directory, and its SDK session is ready"
start_adapter
grep "order entry: shard 0 through" "$ADAPTER_LOG" | head -1
pass "adapter up on port $FIX_PORT"

echo
echo "== 2. a 35=D rests"
fixc 1 send --symbol AAPL --side sell --price 100.00 --qty 10 --clordid S1 > "$RUN/s1.out" 2>&1
cat "$RUN/s1.out"
expect "$RUN/s1.out" "ClOrdID=S1 .*ExecType=NEW OrdStatus=NEW" "no NEW for S1"
pass "CLIENT1's sell is acknowledged NEW"

echo
echo "== 3. a cross trades, and the logged-out maker is told at its next logon"
fixc 2 send --symbol AAPL --side buy --price 100.00 --qty 4 --clordid B1 > "$RUN/b1.out" 2>&1
cat "$RUN/b1.out"
expect "$RUN/b1.out" "ClOrdID=B1 .*ExecType=TRADE OrdStatus=FILLED.*LastQty=4 LastPx=100" "B1 did not trade"
fixc 1 listen --follow 3 > "$RUN/s1-listen.out" 2>&1
cat "$RUN/s1-listen.out"
expect "$RUN/s1-listen.out" "ClOrdID=S1 .*ExecType=TRADE OrdStatus=PARTIALLY_FILLED.*CumQty=4 LeavesQty=6" \
  "CLIENT1 was not told of its fill while logged out (Design.md §6.3)"
pass "both sides of the trade reported; the maker's while it was logged out"

# fixmd <command> [options]: the FIX client as the market-data session FEED1, at market data's own
# port (141=Y at every Logon, Design.md §4).
fixmd() {
  local command="$1"; shift
  "$FIX" "$command" "$@" --port "$FIX_MD_PORT" --sender FEED1 --user feed1 --password feed1-secret --state "$RUN/clients/feed1"
}

echo
echo "== 3b. market data: the security list, the book as a 35=W, a new bid as a 35=X"
fixmd securities > "$RUN/sl.out" 2>&1
cat "$RUN/sl.out"
expect "$RUN/sl.out" "SecurityList .*SecurityRequestResult=0 .*Securities=\[AAPL/1/USD/tick=0.01\]" "the 35=y"
fixmd md --symbol AAPL --id MD-1 --follow 12 > "$RUN/md.out" 2>&1 &
MD_CLIENT=$!; PIDS+=("$MD_CLIENT")
wait_for "$RUN/md.out" "MarketDataSnapshot" 20 "the 35=W" || fail "no 35=W for AAPL"
fixc 2 send --symbol AAPL --side buy --price 99.00 --qty 2 --clordid B2 > "$RUN/b2.out" 2>&1
expect "$RUN/b2.out" "ClOrdID=B2 .*ExecType=NEW" "B2 did not rest"
wait_for "$RUN/md.out" "MarketDataIncremental .*NEW BID AAPL 99x2" 20 "the 35=X for B2" || fail "no 35=X for the new bid"
grep -E "MarketDataSnapshot|MarketDataIncremental" "$RUN/md.out" | head -3
expect "$RUN/md.out" "MarketDataSnapshot MDReqID=MD-1 Symbol=AAPL Entries=\[OFFER 100x6\(1\)" \
  "the 35=W: S1's rest, 6 at 100.00, after B1's 4"
# The market-data process repeats its snapshot every 500 ms; a quiet book is not sent again (Design.md §4).
[ "$(grep -c MarketDataSnapshot "$RUN/md.out")" -eq 1 ] || { cat "$RUN/md.out"; fail "the 35=W was sent more than once"; }
kill "$MD_CLIENT" 2>/dev/null; wait "$MD_CLIENT" 2>/dev/null
pass "SecurityList, then the book from most-exchange's feeds, and a change to it"

echo
echo "== 4. 35=H states the order from the store"
fixc 1 status --clordid S1 --symbol AAPL --side sell --id Q1 > "$RUN/h.out" 2>&1
cat "$RUN/h.out"
expect "$RUN/h.out" "ClOrdID=S1 .*ExecType=ORDER_STATUS OrdStatus=PARTIALLY_FILLED.*CumQty=4 LeavesQty=6.*OrdStatusReqID=Q1" "35=H"
fixc 1 status --clordid NOPE --symbol AAPL --side sell > "$RUN/h2.out" 2>&1
expect "$RUN/h2.out" "ClOrdID=NOPE .*ExecType=ORDER_STATUS OrdStatus=REJECTED.*OrdRejReason=5" "35=H for an unknown ClOrdID"
pass "known and unknown ClOrdIDs"

echo
echo "== 5. 35=F cancels the rest; 35=AF then finds nothing open"
fixc 1 cancel --clordid C1 --orig S1 --symbol AAPL --side sell --qty 10 > "$RUN/c1.out" 2>&1
cat "$RUN/c1.out"
expect "$RUN/c1.out" "ClOrdID=C1 OrigClOrdID=S1 .*ExecType=CANCELED OrdStatus=CANCELED" "the cancel"
fixc 1 mass-status --id M1 > "$RUN/af.out" 2>&1
cat "$RUN/af.out"
expect "$RUN/af.out" "BusinessMessageReject .*Text=no open orders" "35=AF with nothing open"
pass "cancelled; nothing open"

echo
echo "== 6. a refused 35=D: an unknown symbol"
fixc 2 send --symbol ZZZZ --side buy --price 1.00 --qty 1 --clordid Z1 > "$RUN/z1.out" 2>&1
cat "$RUN/z1.out"
expect "$RUN/z1.out" "ClOrdID=Z1 .*ExecType=REJECTED .*Text=unknown security" "unknown symbol"
pass "rejected unknown security"

echo
echo "== 7. a clean restart keeps the store"
kill "$ADAPTER_PID"; wait "$ADAPTER_PID" 2>/dev/null
grep -q "adapter: stopped" "$ADAPTER_LOG" || fail "the adapter did not stop cleanly"
start_adapter
fixc 1 status --clordid S1 --symbol AAPL --side sell > "$RUN/h3.out" 2>&1
cat "$RUN/h3.out"
expect "$RUN/h3.out" "ClOrdID=S1 .*OrdStatus=CANCELED.*CumQty=4" "S1 after the restart"
fixc 1 send --symbol AAPL --side sell --price 101.00 --qty 1 --clordid S2 > "$RUN/s2.out" 2>&1
cat "$RUN/s2.out"
expect "$RUN/s2.out" "ClOrdID=S2 .*ExecType=NEW" "a new order after the restart"
pass "S1 is still CANCELED, CumQty 4, after a restart, and a new order rests"

echo
echo "== 8. SIGKILL the adapter under load from both clients; nothing lost, nothing left unknown"
LOAD=(--symbol AAPL --count "$LOAD_COUNT" --rate "$LOAD_RATE" --price-min 99.90 --price-max 100.10
      --cancel-pct 10 --drain 30 --reconnect 90 ${LOAD_VERBOSE:+--verbose})
fixc 1 load "${LOAD[@]}" --prefix K1- > "$RUN/load1.out" 2>&1 &
L1=$!; PIDS+=("$L1")
fixc 2 load "${LOAD[@]}" --prefix K2- > "$RUN/load2.out" 2>&1 &
L2=$!; PIDS+=("$L2")
wait_for "$RUN/load1.out" "load: 2s sent" 30 "the load under way" || fail "the load did not start"
kill -9 "$ADAPTER_PID"; wait "$ADAPTER_PID" 2>/dev/null
echo "  adapter killed"
start_adapter
grep -E "rebuilt from the archive|restored in flight|resolved against" "$ADAPTER_LOG" || true
wait "$L1"; wait "$L2"
for n in 1 2; do
  echo "  -- client $n"; grep -E "^load: (sent|orders|last|rejected|trades|unanswered|unknown|the session|logged on again)" "$RUN/load$n.out"
done
for n in 1 2; do
  expect "$RUN/load$n.out" "unanswered 0, unknown 0" "client $n: an order unanswered or unknown after the crash"
  expect "$RUN/load$n.out" "reconnects [1-9]" "client $n never saw the adapter go"
  expect "$RUN/load$n.out" "35=3 0," "client $n got a session reject"
  grep -q "UNANSWERED" "$RUN/load$n.out" && { cat "$RUN/load$n.out"; fail "client $n: a cancel unanswered"; }
done
pass "every order and cancel answered after a SIGKILL, none left unknown"

echo
echo "PASS: FIX through the adapter to most-exchange and back, across a restart and a crash"
