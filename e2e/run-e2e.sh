#!/usr/bin/env bash
# End to end: FIX clients -> the adapter -> most-exchange's real processes, and back.
#
# One cluster member, one independent gateway, discovery, all from ../most-exchange's installs. The
# adapter runs its own media driver (Design.md §3.5), so the gateway's client channels and the
# directory are UDP: the requests to the gateway's endpoint, the reports and the directory on
# dynamic multi-destination channels, which both the adapter and most-exchange's `most` subscribe to.
#
# What it shows: a 35=D reaches the book and rests; a cross trades both sides; a report for a
# logged-out client waits for its logon; 35=H, 35=F and 35=AF; a clean restart keeps the store;
# and a SIGKILL of the adapter under load loses no order and leaves none unknown.
#
# Prerequisites:  ./gradlew :adapter:installDist :tools:installDist   (here)
#                 ./gradlew installDist                                (in ../most-exchange)
#
# Knobs: MOST_EXCHANGE (default ../most-exchange), E2E_DIR, FIX_PORT (default 29880),
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

MOST="$ME/tools/build/install/most/bin/most"
ENGINE="$ME/engine/build/install/engine/bin/engine"
GATEWAY="$ME/gateway/build/install/gateway/bin/gateway"
DISCOVERY="$ME/discovery/build/install/discovery/bin/discovery"
ADAPTER="$ROOT/adapter/build/install/adapter/bin/adapter"
FIX="$ROOT/tools/build/install/fix/bin/fix"
for bin in "$MOST" "$ENGINE" "$GATEWAY" "$DISCOVERY" "$ADAPTER" "$FIX"; do
  [ -x "$bin" ] || { echo "missing $bin: see the prerequisites at the top of $0" >&2; exit 2; }
done

if pgrep -f "nl.lamia.most.exchange" > /dev/null; then
  echo "an exchange or adapter process is already running; stop it first:" >&2
  pgrep -fl "nl.lamia.most.exchange" >&2
  exit 2
fi
rm -rf "$RUN"; mkdir -p "$LOGS"

PIDS=()
cleanup() {
  for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null; done
  pkill -f "$RUN/clients/" 2>/dev/null  # a backgrounded fixc's JVM is its subshell's child
  sleep 1
  for pid in "${PIDS[@]:-}"; do kill -9 "$pid" 2>/dev/null; done
  pkill -9 -f "$RUN/clients/" 2>/dev/null
}
trap cleanup EXIT

fail() {
  echo "FAIL: $*" >&2; echo "--- logs in $LOGS ---" >&2
  # E2E_PAUSE_ON_FAIL=SECONDS keeps every process up that long, to look at them.
  if [ -n "${E2E_PAUSE_ON_FAIL:-}" ]; then echo "PAUSED for $E2E_PAUSE_ON_FAIL s" >&2; sleep "$E2E_PAUSE_ON_FAIL"; fi
  exit 1
}
pass() { echo "  ok: $*"; }

wait_for() { # wait_for <file> <pattern> <seconds> <what>
  local file="$1" pattern="$2" secs="$3" what="$4" i=0
  while [ "$i" -lt "$((secs * 4))" ]; do
    [ -f "$file" ] && grep -q "$pattern" "$file" && return 0
    sleep 0.25; i=$((i + 1))
  done
  echo "--- $what did not happen; tail of $file ---" >&2
  tail -30 "$file" >&2 2>/dev/null
  return 1
}

sha256() { # sha256 <text>
  if command -v sha256sum > /dev/null 2>&1; then printf '%s' "$1" | sha256sum | cut -d' ' -f1
  else printf '%s' "$1" | shasum -a 256 | cut -d' ' -f1; fi
}

# ---------------------------------------------------------------- the exchange's configuration
cat > "$RUN/securities.properties" <<EOF
shard.id=0
shard.securities=1

security.1.symbol=AAPL
security.1.isin=US0378331005
security.1.name=Apple Inc.
security.1.currency=USD
security.1.priceFloor=0
security.1.tickSize=1000000
security.1.levelCount=32768
security.1.maxOrders=100000
EOF

GATEWAY_SECRET="fix-e2e-secret"
printf '%s\n' "$GATEWAY_SECRET" > "$RUN/gateway.secret"
cat > "$RUN/participants.properties" <<EOF
shard.id=0
registry.gateways=gw-0
gateway.gw-0.secret=$(sha256 "$GATEWAY_SECRET")
gateway.gw-0.participants=1,7,8
gateway.gw-0.operator=true
EOF

cat > "$RUN/engine.properties" <<EOF
engine.securitiesFile=$RUN/securities.properties
engine.participantRegistry=$RUN/participants.properties
engine.aeronDir=$AERON_DIR
engine.clusterDir=$RUN/cluster-host/cluster
engine.bookEvent.channel=aeron:ipc
engine.bookEvent.streamId=12
EOF

GW_IN="aeron:udp?endpoint=localhost:21000"
GW_OUT="aeron:udp?control=localhost:22000|control-mode=dynamic"
GW_SUB="aeron:udp?endpoint=localhost:0|control=localhost:22000|control-mode=dynamic"
DIR_PUB="aeron:udp?control=localhost:40101|control-mode=dynamic"
DIR_SUB="aeron:udp?endpoint=localhost:0|control=localhost:40101|control-mode=dynamic"

cat > "$RUN/gateway.properties" <<EOF
gateway.securitiesFile=$RUN/securities.properties
gateway.aeronDir=$AERON_DIR
gateway.ingressChannel=aeron:udp
gateway.ingressEndpoints=0=localhost:20110
gateway.egressChannel=aeron:udp?endpoint=localhost:0
gateway.client.inbound.channel=$GW_IN
gateway.client.inbound.streamId=20
gateway.client.outbound.channel=$GW_OUT
gateway.client.outbound.streamId=21
gateway.participantRegistry=$RUN/participants.properties
gateway.gatewayId=gw-0
gateway.credentialTokenFile=$RUN/gateway.secret
EOF

cat > "$RUN/discovery.properties" <<EOF
discovery.shards=0
discovery.shard.0.securitiesFile=$RUN/securities.properties
discovery.shard.0.orderEntryChannel=$GW_IN
discovery.shard.0.orderEntryStreamId=20
discovery.shard.0.executionReportChannel=$GW_SUB
discovery.shard.0.executionReportStreamId=21
discovery.aeronDir=$AERON_DIR
discovery.channel=$DIR_PUB
discovery.streamId=100
discovery.intervalMs=1000
EOF

CONN="--aeron-dir $AERON_DIR --discovery-channel $DIR_SUB --discovery-stream 100"

# ---------------------------------------------------------------- the adapter's configuration
# The calendar in UTC, trading every day, so the run works on any day; the shard is opened with
# the same date (Design.md §3.6). A run across 23:59 UTC would see the two disagree.
TRADING_DATE=$(date -u +%Y%m%d)

cat > "$RUN/sessions.properties" <<EOF
session.C1.senderCompId=MOST
session.C1.targetCompId=CLIENT1
session.C1.username=client1
session.C1.passwordSha256=$(sha256 "client1-secret")
session.C1.role=order-entry
session.C1.participantId=7
session.C2.senderCompId=MOST
session.C2.targetCompId=CLIENT2
session.C2.username=client2
session.C2.passwordSha256=$(sha256 "client2-secret")
session.C2.role=order-entry
session.C2.participantId=8
EOF

cat > "$RUN/adapter.properties" <<EOF
fix.bindAddress=localhost
fix.port=$FIX_PORT
fix.logDir=$RUN/adapter/logs
fix.archiveDir=$RUN/adapter/archive
fix.aeronDir=$RUN/adapter/aeron
fix.fileSyncLevel=1
fix.sessionsFile=$RUN/sessions.properties
fix.storeDir=$RUN/adapter/store
fix.directoryChannel=$DIR_SUB
fix.directoryStreamId=100
fix.calendar.zone=UTC
fix.calendar.rollTime=23:59
fix.calendar.weekdays=MON,TUE,WED,THU,FRI,SAT,SUN
fix.idleStrategy=backoff
EOF
# Shared driver and archive threads: this machine runs the whole exchange too.
export ADAPTER_OPTS="-Daeron.threading.mode=SHARED -Daeron.archive.threading.mode=SHARED"

ADAPTER_PID=""
ADAPTER_RUN=0
# After a SIGKILL, Aeron Archive refuses its own mark file until it ages out (the driver timeout,
# ~10 s): as run-failover.sh does for a cluster member, a supervisor retries.
start_adapter() {
  local tries=0 t0=$SECONDS
  while :; do
    ADAPTER_RUN=$((ADAPTER_RUN + 1))
    ADAPTER_LOG="$LOGS/adapter-$ADAPTER_RUN.log"
    "$ADAPTER" "$RUN/adapter.properties" > "$ADAPTER_LOG" 2>&1 &
    ADAPTER_PID=$!; PIDS+=("$ADAPTER_PID")
    local i=0
    while [ "$i" -lt 240 ]; do
      grep -q "adapter: started" "$ADAPTER_LOG" && break 2
      kill -0 "$ADAPTER_PID" 2>/dev/null || break
      sleep 0.25; i=$((i + 1))
    done
    wait "$ADAPTER_PID" 2>/dev/null
    tries=$((tries + 1))
    if grep -q "active mark file detected" "$ADAPTER_LOG" && [ "$tries" -lt 15 ]; then
      echo "  (the adapter's archive mark file is still live; retrying in 2 s)"
      sleep 2; continue
    fi
    tail -30 "$ADAPTER_LOG" >&2
    fail "the adapter did not start"
  done
  [ "$tries" -gt 0 ] && echo "  adapter started after $tries refusals, $((SECONDS - t0)) s"
  wait_for "$ADAPTER_LOG" "shard 0 ready" 30 "shard 0 ready" || fail "the adapter's SDK session for shard 0 did not become ready"
}

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
echo "== starting most-exchange: one member, a gateway, discovery"
$MOST cluster --fresh --dir "$RUN/cluster-host" --aeron-dir "$AERON_DIR" \
  --participants "$RUN/participants.properties" > "$LOGS/cluster.log" 2>&1 &
PIDS+=($!)
wait_for "$LOGS/cluster.log" "awaiting shutdown signal" 45 "cluster host" || fail "cluster host"
$ENGINE "$RUN/engine.properties" > "$LOGS/engine.log" 2>&1 &
PIDS+=($!)
wait_for "$LOGS/engine.log" "awaiting shutdown signal" 45 "engine" || fail "engine"
$GATEWAY "$RUN/gateway.properties" > "$LOGS/gateway.log" 2>&1 &
PIDS+=($!)
wait_for "$LOGS/gateway.log" "gateway: started" 45 "gateway" || fail "gateway"
$DISCOVERY "$RUN/discovery.properties" > "$LOGS/discovery.log" 2>&1 &
PIDS+=($!)
wait_for "$LOGS/discovery.log" "discovery: started" 30 "discovery" || fail "discovery"

$MOST define --symbol AAPL --reference 100.00 --static-collar 5000 --dynamic-collar 2000 $CONN \
  > "$LOGS/define.log" 2>&1 || { cat "$LOGS/define.log"; fail "define AAPL"; }
$MOST session --phase continuous --shard 0 --trading-date "$TRADING_DATE" $CONN \
  > "$LOGS/session.log" 2>&1 || { cat "$LOGS/session.log"; fail "session transition"; }
pass "AAPL defined, shard 0 continuous, trading date $TRADING_DATE"

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
