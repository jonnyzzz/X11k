#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WATCH_TIMEOUT_SECONDS="${SUPERVISED_WATCH_TIMEOUT_SECONDS:-180}"
WATCH_LIMIT="${SUPERVISED_WATCH_LIMIT:-40}"
WATCH_STALE_SECONDS="${SUPERVISED_WATCH_STALE_SECONDS:-900}"
RECOVERY_MAX_PASSES="${SUPERVISED_RECOVERY_MAX_PASSES:-2}"
ABORT_AFTER_RECOVERY="${SUPERVISED_ABORT_AFTER_RECOVERY:-0}"
HEALTH_CHECK_BEFORE_WORK="${SUPERVISED_HEALTH_CHECK_BEFORE_WORK:-1}"
HEALTH_WATCH_LIMIT="${SUPERVISED_HEALTH_WATCH_LIMIT:-20}"
SIGNAL_GRACE_SECONDS="${SUPERVISED_SIGNAL_GRACE_SECONDS:-30}"

usage() {
  cat <<USAGE
Usage:
  scripts/run-supervised.sh health
  scripts/run-supervised.sh recover
  scripts/run-supervised.sh gradle [gradle args...]
  scripts/run-supervised.sh experiment -- <command> [args...]
  scripts/run-supervised.sh agent <agent> <prompt-file>
  scripts/run-supervised.sh review-quorum <prompt-file>
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
  SUPERVISED_HEALTH_CHECK_BEFORE_WORK=$HEALTH_CHECK_BEFORE_WORK
  SUPERVISED_HEALTH_WATCH_LIMIT=$HEALTH_WATCH_LIMIT
  SUPERVISED_SIGNAL_GRACE_SECONDS=$SIGNAL_GRACE_SECONDS
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

descendant_pids() {
  local frontier="$1"
  local children pid
  while [[ -n "$frontier" ]]; do
    children=""
    for pid in $frontier; do
      children="$children $(pgrep -P "$pid" 2>/dev/null || true)"
    done
    # shellcheck disable=SC2086
    set -- $children
    [[ "$#" -gt 0 ]] || break
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
  if [[ "$seconds" == "0" ]]; then
    "$@"
  elif [[ -n "$bin" ]]; then
    "$bin" "$seconds" "$@"
  else
    "$@" &
    pid="$!"
    start="$(date +%s)"
    while kill -0 "$pid" 2>/dev/null; do
      sleep 1
      now="$(date +%s)"
      elapsed=$((now - start))
      if (( elapsed >= seconds )); then
        terminate_bounded_child "$pid"
        wait "$pid" 2>/dev/null || true
        return 124
      fi
    done
    wait "$pid" || status=$?
    return "${status:-0}"
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

shell_health_check() {
  local status=0
  local script
  for script in \
    "$ROOT/run-agent.sh" \
    "$ROOT/watch-agents.sh" \
    "$ROOT/ralph-loop.sh" \
    "$ROOT/scripts/run-supervised.sh" \
    "$ROOT/scripts/run-gradle-bounded.sh" \
    "$ROOT/scripts/run-bounded-experiment.sh" \
    "$ROOT/scripts/run-review-quorum.sh" \
    "$ROOT/scripts/update-intellij-readme-screenshot.sh" \
    "$ROOT/scripts/update-vscode-readme-screenshots.sh"; do
    [[ -f "$script" ]] || continue
    if ! bash -n "$script"; then
      echo "scripts/run-supervised.sh: shell syntax check failed for $script" >&2
      status=1
    fi
  done
  return "$status"
}

health_check() {
  local mode="${1:-full}"
  local status=0

  echo "== supervised shell health =="
  shell_health_check || status="$?"

  if [[ "$mode" != "quick" ]]; then
    echo
    echo "== supervised watcher health =="
    run_bounded "$WATCH_TIMEOUT_SECONDS" env \
      RUN_AGENT_WATCH_ONCE=1 \
      RUN_AGENT_WATCH_LIMIT="$HEALTH_WATCH_LIMIT" \
      RUN_AGENT_STALE_SECONDS="$WATCH_STALE_SECONDS" \
      RUN_AGENT_DIAGNOSE_STALE=0 \
      "$ROOT/watch-agents.sh" || status="$?"

    echo
    echo "== latest diagnostic anchors =="
    print_latest_failure_context agent || true
    print_latest_failure_context gradle || true
    print_latest_failure_context experiment || true

    echo
    echo "== diagnostic tools =="
    for tool in jps jcmd jstack docker; do
      if command -v "$tool" >/dev/null 2>&1; then
        echo "$tool=$(command -v "$tool")"
      else
        echo "$tool=missing"
      fi
    done
  fi

  return "$status"
}

ACTIVE_CHILD_PID=""
ACTIVE_CHILD_KIND=""

forward_child_signal() {
  local signal="$1"
  local exit_code="$2"
  local start now elapsed
  if [[ -n "$ACTIVE_CHILD_PID" ]] && kill -0 "$ACTIVE_CHILD_PID" 2>/dev/null; then
    echo "scripts/run-supervised.sh: received $signal; forwarding to child PID $ACTIVE_CHILD_PID and waiting up to ${SIGNAL_GRACE_SECONDS}s for diagnostics." >&2
    kill "-$signal" "$ACTIVE_CHILD_PID" 2>/dev/null || true
    start="$(date +%s)"
    while kill -0 "$ACTIVE_CHILD_PID" 2>/dev/null; do
      sleep 1
      now="$(date +%s)"
      elapsed=$((now - start))
      if (( elapsed >= SIGNAL_GRACE_SECONDS )); then
        echo "scripts/run-supervised.sh: child PID $ACTIVE_CHILD_PID did not exit after ${SIGNAL_GRACE_SECONDS}s; terminating process tree." >&2
        terminate_bounded_child "$ACTIVE_CHILD_PID"
        break
      fi
    done
    wait "$ACTIVE_CHILD_PID" 2>/dev/null || true
    if [[ -n "$ACTIVE_CHILD_KIND" ]]; then
      print_latest_failure_context "$ACTIVE_CHILD_KIND" >&2 || true
    fi
  fi
  exit "$exit_code"
}

run_supervised_child() {
  local kind="$1"
  shift
  local status
  ACTIVE_CHILD_KIND="$kind"
  "$@" &
  ACTIVE_CHILD_PID="$!"
  trap 'forward_child_signal TERM 143' TERM
  trap 'forward_child_signal INT 130' INT
  trap 'forward_child_signal HUP 129' HUP
  set +e
  wait "$ACTIVE_CHILD_PID"
  status="$?"
  set -e
  trap - TERM INT HUP
  ACTIVE_CHILD_PID=""
  ACTIVE_CHILD_KIND=""
  return "$status"
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
  if [[ "$HEALTH_CHECK_BEFORE_WORK" == "1" ]]; then
    health_check quick
  fi
  set +e
  run_supervised_child "$kind" "$@"
  status="$?"
  set -e
  if [[ "$status" -ne 0 ]]; then
    print_latest_failure_context "$kind" >&2
  fi
  return "$status"
}

cmd="${1:-}"
case "$cmd" in
  health)
    health_check full
    ;;
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
  review-quorum)
    shift
    if [[ "$#" -ne 1 ]]; then
      usage >&2
      exit 2
    fi
    run_with_recovery review "$ROOT/scripts/run-review-quorum.sh" "$1"
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
