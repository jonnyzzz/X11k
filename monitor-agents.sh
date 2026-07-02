#!/bin/bash
# Long-running stale-agent monitor. Delegates detection, diagnostics, process-tree
# termination, and restart to watch-agents.sh so the recovery policy is centralized.
set -euo pipefail

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"

RUN_AGENT_WATCH_INTERVAL_SECONDS="${RUN_AGENT_WATCH_INTERVAL_SECONDS:-600}"
RUN_AGENT_WATCH_LIMIT="${RUN_AGENT_WATCH_LIMIT:-80}"
RUN_AGENT_STALE_SECONDS="${RUN_AGENT_STALE_SECONDS:-900}"

while true; do
  env \
    RUN_AGENT_WATCH_ONCE=1 \
    RUN_AGENT_WATCH_LIMIT="$RUN_AGENT_WATCH_LIMIT" \
    RUN_AGENT_STALE_SECONDS="$RUN_AGENT_STALE_SECONDS" \
    RUN_AGENT_DIAGNOSE_STALE=1 \
    RUN_AGENT_TERMINATE_STALE=1 \
    RUN_AGENT_RESTART_STALE=1 \
    "$BASE_DIR/watch-agents.sh" || true

  sleep "$RUN_AGENT_WATCH_INTERVAL_SECONDS"
done
