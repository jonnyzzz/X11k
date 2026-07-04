#!/bin/bash
# Supervised entry point for repo agent work. It runs a one-shot stale-agent
# recovery pulse before every action and bounds run-agent.sh with an outer timeout.
set -euo pipefail

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"

RUN_AGENT_TIMEOUT_SECONDS="${RUN_AGENT_TIMEOUT_SECONDS:-900}"
RUN_AGENT_OUTER_TIMEOUT_SECONDS="${RUN_AGENT_OUTER_TIMEOUT_SECONDS:-$((RUN_AGENT_TIMEOUT_SECONDS + 90))}"
RUN_AGENT_NO_OUTPUT_DIAGNOSTICS_SECONDS="${RUN_AGENT_NO_OUTPUT_DIAGNOSTICS_SECONDS:-180}"
RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS="${RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS:-300}"
RUN_AGENT_WATCH_LIMIT="${RUN_AGENT_WATCH_LIMIT:-40}"
RUN_AGENT_STALE_SECONDS="${RUN_AGENT_STALE_SECONDS:-900}"
RUN_AGENT_PREFLIGHT_RECOVER_STALE="${RUN_AGENT_PREFLIGHT_RECOVER_STALE:-1}"
RUN_AGENT_RECOVER_TIMEOUT_SECONDS="${RUN_AGENT_RECOVER_TIMEOUT_SECONDS:-180}"
RUN_AGENT_PREFLIGHT_WATCH_TIMEOUT_SECONDS="${RUN_AGENT_PREFLIGHT_WATCH_TIMEOUT_SECONDS:-$RUN_AGENT_RECOVER_TIMEOUT_SECONDS}"
RUN_AGENT_RECOVERY_MAX_PASSES="${RUN_AGENT_RECOVERY_MAX_PASSES:-2}"
RUN_AGENT_ABORT_AFTER_RECOVERY="${RUN_AGENT_ABORT_AFTER_RECOVERY:-0}"

timeout_bin() {
  for candidate in /opt/homebrew/bin/timeout gtimeout timeout; do
    if command -v "$candidate" >/dev/null 2>&1; then
      command -v "$candidate"
      return
    fi
  done
}

descendant_pids() {
  local frontier="$1"
  local children pid
  while [ -n "$frontier" ]; do
    children=""
    for pid in $frontier; do
      children="$children $(pgrep -P "$pid" 2>/dev/null || true)"
    done
    # shellcheck disable=SC2086
    set -- $children
    [ "$#" -gt 0 ] || break
    printf '%s\n' "$@"
    frontier="$*"
  done
}

terminate_bounded_child() {
  local pid="$1"
  local pids extra_pids
  pids="$(
    {
      printf '%s\n' "$pid"
      descendant_pids "$pid"
    } | awk 'NF && !seen[$0]++ { print }'
  )"
  # shellcheck disable=SC2086
  kill -TERM $pids 2>/dev/null || true
  sleep 1
  extra_pids="$(
    for child in $pids; do
      descendant_pids "$child"
    done | awk 'NF { print }'
  )"
  pids="$(
    {
      printf '%s\n' $pids
      printf '%s\n' $extra_pids
    } | awk 'NF && !seen[$0]++ { print }'
  )"
  # shellcheck disable=SC2086
  kill -KILL $pids 2>/dev/null || true
}

run_bounded() {
  local seconds="$1"
  shift
  local bin pid start now elapsed status
  bin="$(timeout_bin || true)"
  if [ "$seconds" = "0" ]; then
    "$@"
  elif [ -n "$bin" ]; then
    "$bin" "$seconds" "$@"
  else
    "$@" &
    pid="$!"
    start="$(date +%s)"
    while kill -0 "$pid" 2>/dev/null; do
      sleep 1
      now="$(date +%s)"
      elapsed=$((now - start))
      if [ "$elapsed" -ge "$seconds" ]; then
        terminate_bounded_child "$pid"
        wait "$pid" 2>/dev/null || true
        return 124
      fi
    done
    wait "$pid" || status=$?
    return "${status:-0}"
  fi
}

recover_stale_agents_once() {
  local fail_on_recovery="$1"
  run_bounded "$RUN_AGENT_RECOVER_TIMEOUT_SECONDS" env \
    RUN_AGENT_WATCH_ONCE=1 \
    RUN_AGENT_WATCH_LIMIT="$RUN_AGENT_WATCH_LIMIT" \
    RUN_AGENT_STALE_SECONDS="$RUN_AGENT_STALE_SECONDS" \
    RUN_AGENT_DIAGNOSE_STALE=1 \
    RUN_AGENT_TERMINATE_STALE=1 \
    RUN_AGENT_RESTART_STALE=1 \
    RUN_AGENT_FAIL_ON_RECOVERY="$fail_on_recovery" \
    "$BASE_DIR/watch-agents.sh"
}

recover_stale_agents() {
  if [ "$RUN_AGENT_ABORT_AFTER_RECOVERY" = "1" ]; then
    recover_stale_agents_once 1
    return
  fi

  local pass status
  for ((pass = 1; pass <= RUN_AGENT_RECOVERY_MAX_PASSES; pass++)); do
    set +e
    recover_stale_agents_once 1
    status="$?"
    set -e
    if [ "$status" -eq 0 ]; then
      return 0
    fi
    if [ "$status" -ne 124 ]; then
      return "$status"
    fi
    if [ "$pass" -ge "$RUN_AGENT_RECOVERY_MAX_PASSES" ]; then
      echo "ralph-loop.sh: stale-agent recovery did not settle after ${RUN_AGENT_RECOVERY_MAX_PASSES} bounded passes." >&2
      return "$status"
    fi
    echo "ralph-loop.sh: stale-agent recovery ran; verifying clean state before launching the next agent." >&2
    sleep 2
  done
}

run_agent() {
  local agent="$1"
  local prompt="$2"
  local status
  if recover_stale_agents; then
    status=0
  else
    status="$?"
    echo "ralph-loop.sh: stale-agent recovery failed or did not settle; inspect runs/agent-watch.log before retrying." >&2
    return "$status"
  fi
  export RUN_AGENT_TIMEOUT_SECONDS
  export RUN_AGENT_NO_OUTPUT_DIAGNOSTICS_SECONDS
  export RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS
  export RUN_AGENT_PREFLIGHT_RECOVER_STALE
  export RUN_AGENT_PREFLIGHT_WATCH_TIMEOUT_SECONDS
  export RUN_AGENT_PREFLIGHT_ABORT_AFTER_RECOVERY="$RUN_AGENT_ABORT_AFTER_RECOVERY"
  run_bounded "$RUN_AGENT_OUTER_TIMEOUT_SECONDS" \
    "$BASE_DIR/run-agent.sh" "$agent" "$BASE_DIR" "$prompt"
}

usage() {
  cat <<USAGE
Usage:
  ./ralph-loop.sh recover
  ./ralph-loop.sh monitor
  ./ralph-loop.sh agent <agent> <prompt-file>
  ./ralph-loop.sh research [agent]
  ./ralph-loop.sh implementation [agent]
  ./ralph-loop.sh review [agent]
  ./ralph-loop.sh test [agent]

Defaults:
  RUN_AGENT_TIMEOUT_SECONDS=$RUN_AGENT_TIMEOUT_SECONDS
  RUN_AGENT_OUTER_TIMEOUT_SECONDS=$RUN_AGENT_OUTER_TIMEOUT_SECONDS
  RUN_AGENT_NO_OUTPUT_DIAGNOSTICS_SECONDS=$RUN_AGENT_NO_OUTPUT_DIAGNOSTICS_SECONDS
  RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS=$RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS
  RUN_AGENT_STALE_SECONDS=$RUN_AGENT_STALE_SECONDS
  RUN_AGENT_RECOVER_TIMEOUT_SECONDS=$RUN_AGENT_RECOVER_TIMEOUT_SECONDS
  RUN_AGENT_PREFLIGHT_WATCH_TIMEOUT_SECONDS=$RUN_AGENT_PREFLIGHT_WATCH_TIMEOUT_SECONDS
  RUN_AGENT_RECOVERY_MAX_PASSES=$RUN_AGENT_RECOVERY_MAX_PASSES
  RUN_AGENT_ABORT_AFTER_RECOVERY=$RUN_AGENT_ABORT_AFTER_RECOVERY
  RUN_AGENT_RESTART_MAX_ATTEMPTS=${RUN_AGENT_RESTART_MAX_ATTEMPTS:-1}
  RUN_AGENT_RESTART_ROTATE_AGENT=${RUN_AGENT_RESTART_ROTATE_AGENT:-1}
USAGE
}

cmd="${1:-}"
case "$cmd" in
  recover)
    recover_stale_agents
    ;;
  monitor)
    exec "$BASE_DIR/monitor-agents.sh"
    ;;
  agent)
    [ "$#" -eq 3 ] || { usage >&2; exit 2; }
    run_agent "$2" "$3"
    ;;
  research)
    run_agent "${2:-any}" "$BASE_DIR/THE_PROMPT_v5_research.md"
    ;;
  implementation)
    run_agent "${2:-any}" "$BASE_DIR/THE_PROMPT_v5_implementation.md"
    ;;
  review)
    run_agent "${2:-any}" "$BASE_DIR/THE_PROMPT_v5_review.md"
    ;;
  test)
    run_agent "${2:-any}" "$BASE_DIR/THE_PROMPT_v5_test.md"
    ;;
  -h|--help|help|"")
    usage
    ;;
  *)
    echo "Unknown command: $cmd" >&2
    usage >&2
    exit 2
    ;;
esac
