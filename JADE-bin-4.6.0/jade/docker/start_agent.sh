#!/usr/bin/env bash
set -euo pipefail
set -x

# === Project paths (configurable via env) ===
BASE_DIR="${BASE_DIR:-/app/web-ui}"          # contains: agents/ utils/ lib/
LIB_DIR="${LIB_DIR:-${BASE_DIR}/lib}"        # jade.jar (+ any other .jar)
OUT_DIR="${OUT_DIR:-/app/out}"               # output folder for .class files

# === Network/JADE ===
MAIN_HOST="${MAIN_HOST:-jade-main}"          # hostname/IP of the JADE Main
PORT="${PORT:-1099}"                         # JADE Main IMTP/RMI port
PUBLIC_HOST="${PUBLIC_HOST:-${COMPOSE_SERVICE:-jade-agent}}"     # hostname/IP advertised for callbacks
LOCAL_HOST="${LOCAL_HOST:-}"                 # optional override for JADE local-host binding
LOCAL_PORT="${LOCAL_PORT:-}"                 # optional override for JADE local-port binding
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

# Knowledge base path (used by LLMService)
export KB_PATH="${KB_PATH:-/app/web-ui/kb/knowledge.pl}"

echo "[INFO] BASE_DIR     $BASE_DIR"
echo "[INFO] LIB_DIR      $LIB_DIR"
echo "[INFO] OUT_DIR      $OUT_DIR"
echo "[INFO] JPL_JAR      $JPL_JAR"
echo "[INFO] JADE_CP      $JADE_CP"
echo "[INFO] MAIN_HOST    $MAIN_HOST"
echo "[INFO] PORT         $PORT"
echo "[INFO] PUBLIC_HOST  $PUBLIC_HOST"
echo "[INFO] PLATFORM     $PLATFORM_NAME"
[ -n "$LOCAL_HOST" ] && echo "[INFO] LOCAL_HOST   $LOCAL_HOST"
[ -n "$LOCAL_PORT" ] && echo "[INFO] LOCAL_PORT   $LOCAL_PORT"

# === Cleanup ===
echo "[🧹 Cleaning .class files]"
mkdir -p "$OUT_DIR"
find "${BASE_DIR}/agents" -name "*.class" -delete 2>/dev/null || true
find "${BASE_DIR}/utils"  -name "*.class" -delete 2>/dev/null || true
find "$OUT_DIR" -name "*.class" -delete 2>/dev/null || true

# === Compilation (UTF-8) ===
echo "[🧱 Compiling Java sources]"
SRC_LIST="/tmp/sources.list"
: > "$SRC_LIST"
[ -d "${BASE_DIR}/agents" ] && find "${BASE_DIR}/agents" -type f -name '*.java' >> "$SRC_LIST"
[ -d "${BASE_DIR}/utils"  ] && find "${BASE_DIR}/utils"  -type f -name '*.java' >> "$SRC_LIST"

if [ -s "$SRC_LIST" ]; then
  javac -encoding UTF-8 -cp "$JADE_CP" -d "$OUT_DIR" @"$SRC_LIST"
else
  echo "⚠️  No Java sources found in ${BASE_DIR}/{agents,utils} (okay if you already have precompiled classes)."
fi

# === Start JADE container and specified agents ===
AGENTS="${1:-${AGENTS:-}}"

# Make agent names unique (user -> user-<suffix>) to avoid DF/AMS conflicts
AGENT_UNIQUE="${AGENT_UNIQUE:-1}"
AGENT_SUFFIX="${AGENT_SUFFIX:-}"
if [ -n "$AGENTS" ] && [ "$AGENT_UNIQUE" = "1" ]; then
  # generate a short, likely-unique suffix
  if [ -z "$AGENT_SUFFIX" ]; then
    HN="$(hostname 2>/dev/null || echo host)"
    HN="$(echo "$HN" | tr -dc 'A-Za-z0-9' | tail -c 6)"
    RND="$RANDOM"
    AGENT_SUFFIX="${HN}-${RND}"
  fi
  NEW_AGENTS=""
  IFS=';' read -r -a ENTRIES <<< "$AGENTS"
  for entry in "${ENTRIES[@]}"; do
    [ -z "$entry" ] && continue
    name="${entry%%:*}"
    rest="${entry#*:}"
    if [ -n "$name" ] && [ -n "$rest" ]; then
      entry="${name}-${AGENT_SUFFIX}:${rest}"
    fi
    if [ -z "$NEW_AGENTS" ]; then NEW_AGENTS="$entry"; else NEW_AGENTS="${NEW_AGENTS};${entry}"; fi
  done
  AGENTS="$NEW_AGENTS"
fi

if echo "$AGENTS" | grep -qiE ':agents\.UserAgent'; then
   if [ "${UI_AUTOSTART:-1}" = "1" ]; then
     echo '[🕸  Web UI] Starting server.js (npm start) on port 4000…'
    (
       cd /app/web-ui \
       && npm start
     ) &
    echo '[🕸  Web UI] running in background'
   fi
fi

echo "[🚀 Starting agents '$AGENTS' connected to $MAIN_HOST:$PORT]"

JAVA_CMD=(
  java
  -Dfile.encoding=UTF-8
  -Djava.library.path="$LD_LIBRARY_PATH"
  -Djava.rmi.server.hostname="$PUBLIC_HOST"
  -cp "$OUT_DIR:$JADE_CP"
  jade.Boot
  -container
)

if [ -n "$LOCAL_HOST" ]; then
  JAVA_CMD+=(-local-host "$LOCAL_HOST")
fi
if [ -n "$LOCAL_PORT" ]; then
  JAVA_CMD+=(-local-port "$LOCAL_PORT")
fi

JAVA_CMD+=(
  -host "$MAIN_HOST"
  -port "$PORT"
)

if [ -n "$AGENTS" ]; then
  JAVA_CMD+=("$AGENTS")
fi

exec "${JAVA_CMD[@]}"
