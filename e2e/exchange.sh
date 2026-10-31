#!/usr/bin/env bash
# The exchange and the adapter as the e2e scripts run them, sourced by run-e2e.sh and run-perf.sh so
# the two cannot drift: one cluster member, one independent gateway, market data and discovery from
# ../most-exchange's installs, the adapter on its own media driver over UDP (Design.md §3.5).
#
# The sourcing script sets ROOT, ME, RUN, LOGS, AERON_DIR and FIX_PORT, and SECURITIES (default AAPL)
# and MAX_ORDERS if it needs others; then calls start_exchange and start_adapter. Participants 7 and 8
# are the adapter's sessions; 20 and 21 are for `most load` straight to the gateway (run-perf.sh).
# FEED1 is the e2e's market-data session; FEED2-4 are more, for run-perf.sh's market-data leg. They
# log on at market data's own port, FIX_MD_PORT (default FIX_PORT + 1; Design.md §4).
# FIX_FILE_SYNC_LEVEL (default 1) and FIX_IDLE_STRATEGY (default backoff) are the adapter's, for a
# measured diff only: below 1 the adapter's persistence argument does not hold (CLAUDE.md 7, 11).

MOST="$ME/tools/build/install/most/bin/most"
ENGINE="$ME/engine/build/install/engine/bin/engine"
GATEWAY="$ME/gateway/build/install/gateway/bin/gateway"
DISCOVERY="$ME/discovery/build/install/discovery/bin/discovery"
MARKETDATA="$ME/market-data/build/install/market-data/bin/market-data"
ADAPTER="$ROOT/adapter/build/install/adapter/bin/adapter"
FIX_MD_PORT="${FIX_MD_PORT:-$((FIX_PORT + 1))}"
FIX="$ROOT/tools/build/install/fix/bin/fix"
for bin in "$MOST" "$ENGINE" "$GATEWAY" "$MARKETDATA" "$DISCOVERY" "$ADAPTER" "$FIX"; do
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
# SECURITIES: the symbols on shard 0, AAPL first (the e2e's); every one 0.01 ticks from 0 to 327.67,
# reference 100.00. Any but AAPL gets a made-up ISIN, XX and nine digits, with its check digit: the
# exchange checks it (most-exchange SecuritySpec.isValidIsin: letters as 10-35, then Luhn).
SECURITIES="${SECURITIES:-AAPL}"
isin() { # isin <XX123456789>: the 11 characters and their check digit
  printf '%s' "$1" | awk '{
    d = ""; for (i = 1; i <= length($0); i++) { c = substr($0, i, 1)
      d = d (c ~ /[0-9]/ ? c : index("ABCDEFGHIJKLMNOPQRSTUVWXYZ", c) + 9) }
    sum = 0; dbl = 1
    for (i = length(d); i >= 1; i--) { v = substr(d, i, 1) + 0; if (dbl) { v *= 2; if (v > 9) v -= 9 }; sum += v; dbl = !dbl }
    printf "%s%d", $0, (10 - sum % 10) % 10 }'
}
{
  echo "shard.id=0"
  ids=""; i=0; for s in $SECURITIES; do i=$((i + 1)); ids="$ids${ids:+,}$i"; done
  echo "shard.securities=$ids"
  i=0
  for s in $SECURITIES; do
    i=$((i + 1))
    if [ "$s" = AAPL ]; then isin=US0378331005; name="Apple Inc."; else isin=$(isin "$(printf 'XX%09d' "$i")"); name="$s"; fi
    printf '\nsecurity.%d.symbol=%s\nsecurity.%d.isin=%s\nsecurity.%d.name=%s\nsecurity.%d.currency=USD\n' "$i" "$s" "$i" "$isin" "$i" "$name" "$i"
    printf 'security.%d.priceFloor=0\nsecurity.%d.tickSize=1000000\nsecurity.%d.levelCount=32768\nsecurity.%d.maxOrders=%d\n' "$i" "$i" "$i" "$i" "${MAX_ORDERS:-100000}"
  done
} > "$RUN/securities.properties"

GATEWAY_SECRET="fix-e2e-secret"
printf '%s\n' "$GATEWAY_SECRET" > "$RUN/gateway.secret"
cat > "$RUN/participants.properties" <<EOF
shard.id=0
registry.gateways=gw-0
gateway.gw-0.secret=$(sha256 "$GATEWAY_SECRET")
gateway.gw-0.participants=1,7,8,20,21
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

# The feeds cross from the exchange's driver to the adapter's on dynamic multi-destination channels,
# as the directory does: the market-data process publishes, the adapter subscribes (Design.md §4).
MD_PUB() { echo "aeron:udp?control=localhost:$1|control-mode=dynamic"; }
MD_SUB() { echo "aeron:udp?endpoint=localhost:0|control=localhost:$1|control-mode=dynamic"; }

cat > "$RUN/market-data.properties" <<EOF
md.securitiesFile=$RUN/securities.properties
md.aeronDir=$AERON_DIR
md.bookEvent.channel=aeron:ipc
md.bookEvent.streamId=12
md.l1.channel=$(MD_PUB 40201)
md.l1.streamId=31
md.l2.channel=$(MD_PUB 40202)
md.l2.streamId=32
md.l3.channel=aeron:ipc
md.l3.streamId=33
md.snapshot.channel=$(MD_PUB 40204)
md.snapshot.streamId=34
md.snapshot.cycleMs=500
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
session.M1.senderCompId=MOST
session.M1.targetCompId=FEED1
session.M1.username=feed1
session.M1.passwordSha256=$(sha256 "feed1-secret")
session.M1.role=market-data
session.M2.senderCompId=MOST
session.M2.targetCompId=FEED2
session.M2.username=feed2
session.M2.passwordSha256=$(sha256 "feed2-secret")
session.M2.role=market-data
session.M3.senderCompId=MOST
session.M3.targetCompId=FEED3
session.M3.username=feed3
session.M3.passwordSha256=$(sha256 "feed3-secret")
session.M3.role=market-data
session.M4.senderCompId=MOST
session.M4.targetCompId=FEED4
session.M4.username=feed4
session.M4.passwordSha256=$(sha256 "feed4-secret")
session.M4.role=market-data
EOF

cat > "$RUN/adapter.properties" <<EOF
fix.bindAddress=localhost
fix.port=$FIX_PORT
fix.logDir=$RUN/adapter/logs
fix.archiveDir=$RUN/adapter/archive
fix.aeronDir=$RUN/adapter/aeron
fix.fileSyncLevel=${FIX_FILE_SYNC_LEVEL:-1}
fix.sessionsFile=$RUN/sessions.properties
fix.storeDir=$RUN/adapter/store
fix.directoryChannel=$DIR_SUB
fix.directoryStreamId=100
fix.md.port=$FIX_MD_PORT
fix.md.logDir=$RUN/adapter/md-logs
fix.md.archiveDir=$RUN/adapter/md-archive
fix.md.aeronDir=$RUN/adapter/md-aeron
fix.md.l1.channel=$(MD_SUB 40201)
fix.md.l1.streamId=31
fix.md.l2.channel=$(MD_SUB 40202)
fix.md.l2.streamId=32
fix.md.snapshot.channel=$(MD_SUB 40204)
fix.md.snapshot.streamId=34
fix.calendar.zone=UTC
fix.calendar.rollTime=23:59
fix.calendar.weekdays=MON,TUE,WED,THU,FRI,SAT,SUN
fix.idleStrategy=${FIX_IDLE_STRATEGY:-backoff}
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

# ---------------------------------------------------------------- processes
start_exchange() {
  echo "== starting most-exchange: one member, a gateway, market data, discovery"
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
  $MARKETDATA "$RUN/market-data.properties" > "$LOGS/market-data.log" 2>&1 &
  PIDS+=($!)
  wait_for "$LOGS/market-data.log" "market-data: started" 30 "market data" || fail "market data"
  $DISCOVERY "$RUN/discovery.properties" > "$LOGS/discovery.log" 2>&1 &
  PIDS+=($!)
  wait_for "$LOGS/discovery.log" "discovery: started" 30 "discovery" || fail "discovery"

  for s in $SECURITIES; do
    $MOST define --symbol "$s" --reference 100.00 --static-collar 5000 --dynamic-collar 2000 $CONN \
      >> "$LOGS/define.log" 2>&1 || { cat "$LOGS/define.log"; fail "define $s"; }
  done
  $MOST session --phase continuous --shard 0 --trading-date "$TRADING_DATE" $CONN \
    > "$LOGS/session.log" 2>&1 || { cat "$LOGS/session.log"; fail "session transition"; }
  pass "$SECURITIES defined, shard 0 continuous, trading date $TRADING_DATE"
}
