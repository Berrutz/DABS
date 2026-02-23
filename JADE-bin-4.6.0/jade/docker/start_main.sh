#!/usr/bin/env bash
set -euo pipefail
set -x

# === Project paths (configurable via env) ===
BASE_DIR="${BASE_DIR:-/app/web-ui}"          # contains: agents/ utils/ lib/
LIB_DIR="${LIB_DIR:-${BASE_DIR}/lib}"        # jade.jar (+ any other .jar)
OUT_DIR="${OUT_DIR:-/app/out}"               # output folder for .class files

# === Network/JADE ===
PUBLIC_HOST="${PUBLIC_HOST:-jade-main}"      # hostname/IP advertised for RMI
PORT="${PORT:-1099}"                         # JADE IMTP/RMI port
PLATFORM_NAME="${PLATFORM_NAME:-CoordinatorPlatform}"

# === Java classpath ===
JPL_JAR="${JPL_JAR:-/usr/lib/swi-prolog/lib/jpl.jar}"
JADE_CP="${LIB_DIR}/*:${JPL_JAR}:."

# === Native Prolog libraries (JPL) ===
# Typical Ubuntu path for SWI/JPL; also add the JVM server if present
LD_BASE="/usr/lib/swi-prolog/lib/x86_64-linux"
LD_JVM="${JAVA_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}/jre/lib/amd64/server"
LD_VAL="$LD_BASE"
[ -d "$LD_JVM" ] && LD_VAL="${LD_VAL}:$LD_JVM"
export LD_LIBRARY_PATH="${LD_VAL}:${LD_LIBRARY_PATH:-}"

echo "[INFO] BASE_DIR     $BASE_DIR"
echo "[INFO] LIB_DIR      $LIB_DIR"
echo "[INFO] OUT_DIR      $OUT_DIR"
echo "[INFO] JPL_JAR      $JPL_JAR"
echo "[INFO] JADE_CP      $JADE_CP"
echo "[INFO] PUBLIC_HOST  $PUBLIC_HOST"
echo "[INFO] PORT         $PORT"
echo "[INFO] PLATFORM     $PLATFORM_NAME"

# === Cleanup ===
echo "[INFO] Cleaning .class files"
mkdir -p "$OUT_DIR"
# source cleanup (if present)
find "${BASE_DIR}/agents" -name "*.class" -delete 2>/dev/null || true
find "${BASE_DIR}/utils"  -name "*.class" -delete 2>/dev/null || true
# output cleanup
find "${OUT_DIR}" -name "*.class" -delete 2>/dev/null || true

# === Compilation (UTF-8) ===
echo "[INFO] Compiling Java sources"
# collect sources from agents/ and utils/
SRC_LIST="/tmp/sources.list"
: > "$SRC_LIST"
[ -d "${BASE_DIR}/agents" ] && find "${BASE_DIR}/agents" -type f -name '*.java' >> "$SRC_LIST"
[ -d "${BASE_DIR}/utils"  ] && find "${BASE_DIR}/utils"  -type f -name '*.java' >> "$SRC_LIST"

if [ -s "$SRC_LIST" ]; then
  javac -encoding UTF-8 -cp "$JADE_CP" -d "$OUT_DIR" @"$SRC_LIST"
else
  echo "[WARN] No Java sources found in ${BASE_DIR}/{agents,utils}."
fi

# === Start Main monitor UI (port 4100) ===
if [ "${MAIN_MONITOR_AUTOSTART:-1}" = "1" ]; then
  echo "[INFO] Starting server-main.js (port ${MONITOR_PORT:-4100})"
  (
    cd /app/web-ui \
    && MONITOR_PORT="${MONITOR_PORT:-4100}" \
       node server-main.js
  ) &
  echo "[INFO] Main monitor running in background"
fi

# Resolve a local bind IP for JADE internals.
# PUBLIC_HOST may be a VPN-reachable IP not present on local interfaces.
if [ -z "${BIND_IP:-}" ]; then
  BIND_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
  [ -z "$BIND_IP" ] && BIND_IP="$(hostname -i 2>/dev/null | awk '{print $1}')"
  [ -z "$BIND_IP" ] && BIND_IP="127.0.0.1"
fi

# Best-effort diagnostic when PUBLIC_HOST is an IP.
if echo "$PUBLIC_HOST" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$'; then
  if ip addr show 2>/dev/null | grep -q "$PUBLIC_HOST"; then
    echo "[INFO] PUBLIC_HOST is local: $PUBLIC_HOST"
  else
    echo "[INFO] PUBLIC_HOST ($PUBLIC_HOST) is not local. Using BIND_IP ($BIND_IP) for -local-host."
  fi
fi

# === Start JADE platform ONLY (AMS/DF), no agents ===
echo "[INFO] Starting JADE Main (AMS/DF only), no agents"
echo "[INFO] BIND_IP      $BIND_IP"
echo "[INFO] PUBLIC_HOST  $PUBLIC_HOST"

exec java \
  -Dfile.encoding=UTF-8 \
  -Djava.library.path="$LD_LIBRARY_PATH" \
  -cp "$OUT_DIR:$JADE_CP" \
  -Djava.rmi.server.hostname="$PUBLIC_HOST" \
  jade.Boot \
    -name "$PLATFORM_NAME" \
    -host 0.0.0.0 \
    -port "$PORT" \
    -local-host "$PUBLIC_HOST" \
    -local-port "$PORT" \
    -agents monitor:utils.MonitorAgent

