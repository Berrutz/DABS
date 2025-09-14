#!/usr/bin/env bash
set -euo pipefail

ROLE="${ROLE:-}"
case "$ROLE" in
  main)
    exec ./docker/start_main.sh "$@"
    ;;
  agent)
    exec ./docker/start_agent.sh "$@"
    ;;
  *)
    echo "❌ ROLE not set or invalid. Use ROLE=main or ROLE=agent (via docker compose profiles)."
    exit 1
    ;;
esac
