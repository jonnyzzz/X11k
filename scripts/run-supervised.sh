#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WATCH_TIMEOUT_SECONDS="${SUPERVISED_WATCH_TIMEOUT_SECONDS:-180}"
WATCH_LIMIT="${SUPERVISED_WATCH_LIMIT:-40}"
WATCH_STALE_SECONDS="${SUPERVISED_WATCH_STALE_SECONDS:-900}"
RECOVERY_MAX_PASSES="${SUPERVISED_RECOVERY_MAX_PASSES:-2}"
ABORT_AFTER_RECOVERY="${SUPERVISED_ABORT_AFTER_RECOVERY:-0}"

usage() {
  cat <<USAGE
Usage:
  scripts/run-supervised.sh recover
  scripts/run-supervised.sh gradle [gradle args...]
  scripts/run-supervised.sh experiment -- <command> [args...]
  scripts/run-supervised.sh agent <agent> <prompt-file>
  scripts/run-supervised.sh research [agent]
  scripts/run-supervised.sh implementation [agent]
  scripts/run-supervised.sh review [agent]
  scripts/run-supervised.sh test [agent]

This is the default front door for long repository work. It runs a bounded
stale-agent recovery pulse before starting work, dispatches Gradle and ad hoc
commands through the repository bounded wrappers, and prints the latest
run-info/diagnostic paths when a command fails or times out.

Environment:
  SUPERVISED_WATCH_TIMEOUT_SECONDS=$WATCH_TIMEOUT_SECONDS
  SUPERVISED_WATCH_LIMIT=$WATCH_LIMIT
  SUPERVISED_WATCH_STALE_SECONDS=$WATCH_STALE_SECONDS
  SUPERVISED_RECOVERY_MAX_PASSES=$RECOVERY_MAX_PASSES
  SUPERVISED_ABORT_AFTER_RECOVERY=$ABORT_AFTER_RECOVERY
USAGE
}

timeout_bin() {
  for candidate in /opt/homebrew/bin/timeout gtimeout timeout; do
    if command -v "$candidate" >/dev/null 2>&1; then
      command -v "$candidate"
      return
    fi
  done
}

run_bounded() {
  local seconds="$1"
  shift
  local bin
  bin="$(timeout_bin || true)"
  if [[ "$seconds" == "0" ]]; then
    "$@"
  elif [[ -n "$bin" ]]; then
    "$bin" "$seconds" "$@"
  else
    echo "scripts/run-supervised.sh requires timeout or gtimeout for bounded recovery." >&2
    return 125
  fi
}

latest_run_info() {
  local latest="$1/latest"
  if [[ -f "$latest/run-info.txt" ]]; then
    local resolved
    resolved="$(cd "$latest" && pwd -P)"
    echo "$resolved/run-info.txt"
    return 0
  fi
  return 1
}

print_run_diagnostics() {
  local label="$1"
  local run_info="$2"
  echo
  echo "== $label run info =="
  echo "$run_info"
  if [[ -f "$run_info" ]]; then
    grep -E '^(RUN_ID|RUN_DIR|CMD|PID|TIMEOUT_REASON|EXIT_CODE|DIAGNOSTICS|GRADLE_DIAGNOSTICS)=' "$run_info" 2>/dev/null || true
    local diagnostics
    diagnostics="$(grep -E '^(DIAGNOSTICS|GRADLE_DIAGNOSTICS)=' "$run_info" 2>/dev/null | tail -5 || true)"
    if [[ -n "$diagnostics" ]]; then
      echo
      echo "== $label diagnostics =="
      echo "$diagnostics"
    fi
  fi
}

print_latest_failure_context() {
  local kind="$1"
  local run_info=""
  case "$kind" in
    gradle)
      run_info="$(latest_run_info "$ROOT/runs/gradle-bounded" || true)"
      ;;
    experiment)
      run_info="$(latest_run_info "$ROOT/runs/bounded-experiments" || true)"
      ;;
    agent|research|implementation|review|test)
      run_info="$(latest_run_info "$ROOT/runs" || true)"
      ;;
  esac
  if [[ -n "$run_info" ]]; then
    print_run_diagnostics "$kind" "$run_info"
  else
    echo "No latest run-info.txt found for $kind." >&2
  fi
}

recover_stale_agents_once() {
  local fail_on_recovery="$1"
  run_bounded "$WATCH_TIMEOUT_SECONDS" env \
    RUN_AGENT_WATCH_ONCE=1 \
    RUN_AGENT_WATCH_LIMIT="$WATCH_LIMIT" \
    RUN_AGENT_STALE_SECONDS="$WATCH_STALE_SECONDS" \
    RUN_AGENT_DIAGNOSE_STALE=1 \
    RUN_AGENT_TERMINATE_STALE=1 \
    RUN_AGENT_RESTART_STALE=1 \
    RUN_AGENT_FAIL_ON_RECOVERY="$fail_on_recovery" \
    "$ROOT/watch-agents.sh"
}

recover_stale_agents() {
  if [[ "$ABORT_AFTER_RECOVERY" == "1" ]]; then
    recover_stale_agents_once 1
    return
  fi

  local pass status
  for ((pass = 1; pass <= RECOVERY_MAX_PASSES; pass++)); do
    set +e
    recover_stale_agents_once 1
    status="$?"
    set -e
    if [[ "$status" -eq 0 ]]; then
      return 0
    fi
    if [[ "$status" -ne 124 ]]; then
      return "$status"
    fi
    if (( pass >= RECOVERY_MAX_PASSES )); then
      echo "scripts/run-supervised.sh: stale-agent recovery did not settle after ${RECOVERY_MAX_PASSES} bounded passes." >&2
      return "$status"
    fi
    echo "scripts/run-supervised.sh: stale-agent recovery ran; verifying clean state before starting requested work." >&2
    sleep 2
  done
}

run_with_recovery() {
  local kind="$1"
  shift
  local status
  set +e
  recover_stale_agents
  status="$?"
  set -e
  if [[ "$status" -ne 0 ]]; then
    echo "scripts/run-supervised.sh: stale-agent recovery failed or did not settle; inspect runs/agent-watch.log before retrying." >&2
    return "$status"
  fi
  set +e
  "$@"
  status="$?"
  set -e
  if [[ "$status" -ne 0 ]]; then
    print_latest_failure_context "$kind" >&2
  fi
  return "$status"
}

cmd="${1:-}"
case "$cmd" in
  recover)
    recover_stale_agents
    ;;
  gradle)
    shift
    [[ "${1:-}" == "--" ]] && shift
    run_with_recovery gradle "$ROOT/scripts/run-gradle-bounded.sh" "$@"
    ;;
  experiment)
    shift
    [[ "${1:-}" == "--" ]] && shift
    if [[ "$#" -eq 0 ]]; then
      usage >&2
      exit 2
    fi
    run_with_recovery experiment "$ROOT/scripts/run-bounded-experiment.sh" -- "$@"
    ;;
  agent)
    shift
    if [[ "$#" -ne 2 ]]; then
      usage >&2
      exit 2
    fi
    run_with_recovery agent "$ROOT/ralph-loop.sh" agent "$1" "$2"
    ;;
  research|implementation|review|test)
    role="$cmd"
    shift
    if [[ "$#" -gt 1 ]]; then
      usage >&2
      exit 2
    fi
    run_with_recovery "$role" "$ROOT/ralph-loop.sh" "$role" "$@"
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
