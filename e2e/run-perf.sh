#!/usr/bin/env bash
# The latency baseline (Status §3 item 6, Design.md §10): `fix perf` through the adapter to
# most-exchange's real processes, and `fix report` on each run.
#
# The e2e's topology (e2e/exchange.sh): one cluster member, one gateway, market data and discovery,
# UDP over localhost, the adapter at fileSyncLevel 1 with a back-off idle strategy and shared driver
# and archive threads, everything on this one machine. Market data runs; nothing subscribes to it.
#
# A discard run first (a cold exchange and adapter wear a cost that is not a property of the rate,
# `perf-claim`), then one run per rate in PERF_RATES. Every run has its own security, so no run's
# resting orders sit ahead of the next one's in the book, and discards its own first
# PERF_WARMUP_SECONDS (a new client JVM each time).
#
# Writes $PERF_DIR (default build/perf): runs/<name>.log and runs/<name>-report/report.html, and
# measurements.md, a docs/Measurements.md row per measured run, its id and "idle?" for you to fill.
#
# Prerequisites:  ./gradlew :adapter:installDist :tools:installDist   (here)
#                 ./gradlew installDist                                (in ../most-exchange)
#
# Then, with PERF_DIRECT=1 (the default), the same rates through most-exchange's own `most load`,
# straight to the gateway with no FIX: the same machine, topology and day, so the difference between
# the two is the adapter's (and the FIX client's). Each on a security of its own again.
#
# Knobs: PERF_RATES (default "1000 5000 10000" per second), PERF_SECONDS (20 per run),
#        PERF_WARMUP_SECONDS (2), PERF_MIX (passive=60,cross=20,cancel=20), PERF_DISCARD_SECONDS (10),
#        PERF_DIR, FIX_PORT (default 29881), MOST_EXCHANGE (default ../most-exchange).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ME="$(cd "${MOST_EXCHANGE:-$ROOT/../most-exchange}" && pwd)"
RUN="${PERF_DIR:-$ROOT/build/perf}"
LOGS="$RUN/logs"
AERON_DIR="$RUN/exchange-aeron"
FIX_PORT="${FIX_PORT:-29881}"
read -r -a RATES <<< "${PERF_RATES:-1000 5000 10000}"
SECONDS_PER_RUN="${PERF_SECONDS:-20}"
WARMUP_SECONDS="${PERF_WARMUP_SECONDS:-2}"
DISCARD_SECONDS="${PERF_DISCARD_SECONDS:-10}"
MIX="${PERF_MIX:-passive=60,cross=20,cancel=20}"

DIRECT="${PERF_DIRECT:-1}"
SECURITIES="PDISCARD"
for r in "${RATES[@]}"; do SECURITIES="$SECURITIES P$r"; done
[ "$DIRECT" = 1 ] && for r in "${RATES[@]}"; do SECURITIES="$SECURITIES D$r"; done
# Room for the longest run's book, whatever its mix: one order per message.
MAX_ORDERS=100000
for r in "${RATES[@]}"; do [ $((r * SECONDS_PER_RUN)) -gt "$MAX_ORDERS" ] && MAX_ORDERS=$((r * SECONDS_PER_RUN)); done

# shellcheck source=exchange.sh
source "$ROOT/e2e/exchange.sh"

EXCHANGE_DESC="e2e topology, one machine: one cluster member, one gateway, UDP over localhost; adapter fileSyncLevel=${FIX_FILE_SYNC_LEVEL:-1}, idle ${FIX_IDLE_STRATEGY:-backoff}, SHARED driver and archive; market data running, no subscriber"
mkdir -p "$RUN/runs"

# perf_run <name> <symbol> <rate> <seconds> <warmup seconds>
perf_run() {
  local name="$1" symbol="$2" rate="$3" seconds="$4" warmup="$5"
  local count=$((rate * seconds)) out="$LOGS/perf-$name.out"
  echo "== $name: $count messages at $rate/s on $symbol, the first $((rate * warmup)) discarded"
  # CLIENT1 makes, CLIENT2 takes: one participant cannot trade with itself (Design.md §10.1).
  "$FIX" perf --port "$FIX_PORT" --sender CLIENT1 --user client1 --password client1-secret --state "$RUN/clients/perf" \
    --taker-sender CLIENT2 --taker-user client2 --taker-password client2-secret \
    --symbol "$symbol" --mid 100.00 --tick 0.01 --levels 5 --qty 10 \
    --rate "$rate" --count "$count" --warmup $((rate * warmup)) --mix "$MIX" \
    --drain 30 --log "$RUN/runs/$name.log" --exchange "$EXCHANGE_DESC" > "$out" 2>&1
  local code=$?
  grep -E "^perf: (sent|unanswered|the send thread|the session)" "$out" | sed 's/^/  /'
  [ "$code" -eq 0 ] || echo "  WARNING: fix perf exited $code (a message not sent, or the log full): see $out"
  "$FIX" report --log "$RUN/runs/$name.log" --out "$RUN/runs/$name-report" > "$LOGS/report-$name.out" 2>&1 \
    || { cat "$LOGS/report-$name.out"; fail "the report on $name"; }
  grep -E "^(offered|WARNING)" "$LOGS/report-$name.out" | sed 's/^/  /'
  grep -E "^(new ack|fill|resting fill|cancel ack) +(service|response)" "$LOGS/report-$name.out" | sed 's/^/  /'
  grep -E "^all " "$LOGS/report-$name.out" | sed 's/^/  pacing: /'
}

start_exchange
start_adapter
echo

perf_run discard PDISCARD 1000 "$DISCARD_SECONDS" 1
echo
: > "$RUN/measurements.md"
for r in "${RATES[@]}"; do
  perf_run "rate-$r" "P$r" "$r" "$SECONDS_PER_RUN" "$WARMUP_SECONDS"
  grep -A1 "^Measurements row" "$LOGS/report-rate-$r.out" | tail -1 >> "$RUN/measurements.md"
  echo
done

if [ "$DIRECT" = 1 ]; then
  for r in "${RATES[@]}"; do
    echo "== direct-$r: most load, $((r * SECONDS_PER_RUN)) orders at $r/s on D$r, participants 20 and 21, no FIX"
    $MOST load --symbol "D$r" --price-min 99.90 --price-max 100.10 --qty-min 1 --qty-max 10 \
      --count $((r * SECONDS_PER_RUN)) --delay-us $((1000000 / r)) --participant 20 --participants 2 $CONN \
      > "$LOGS/direct-$r.out" 2>&1 || echo "  WARNING: most load exited $?: see $LOGS/direct-$r.out"
    grep -E "^load:|offers|reports  |unanswered|pacing|ack  (response|service)" "$LOGS/direct-$r.out" | sed 's/^/  /'
    echo
  done
fi

if grep -qiE "exception|error" "$ADAPTER_LOG"; then
  echo "WARNING: the adapter's log has errors:"; grep -iE "exception|error" "$ADAPTER_LOG" | head -5
fi
echo "== reports"
for f in "$RUN"/runs/*-report/report.html; do echo "  $f"; done
echo
echo "== docs/Measurements.md rows (fill in the id and idle?)"
cat "$RUN/measurements.md"
