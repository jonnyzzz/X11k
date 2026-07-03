#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="${GRADLE_RUN_DIR:-$ROOT/runs/gradle-bounded}"
LOCK_DIR="${GRADLE_LOCK_DIR:-$ROOT/runs/gradle-bounded.lock}"
TIMEOUT_SECONDS="${GRADLE_TIMEOUT_SECONDS:-1800}"
LOCK_WAIT_TIMEOUT_SECONDS="${GRADLE_LOCK_WAIT_TIMEOUT_SECONDS:-3600}"
POLL_SECONDS="${GRADLE_POLL_SECONDS:-5}"
DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS="${GRADLE_DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS:-20}"
THREAD_DUMP_TIMEOUT_SECONDS="${GRADLE_THREAD_DUMP_TIMEOUT_SECONDS:-8}"
THREAD_DUMP_MAX_JVMS="${GRADLE_THREAD_DUMP_MAX_JVMS:-8}"

usage() {
  cat <<USAGE
Usage:
  scripts/run-gradle-bounded.sh [gradle args...]

Runs ./gradlew with repository-safe defaults:
  --no-daemon --max-workers=1 -Dkotlin.incremental=false

Environment:
  GRADLE_TIMEOUT_SECONDS=$TIMEOUT_SECONDS
  GRADLE_LOCK_WAIT_TIMEOUT_SECONDS=$LOCK_WAIT_TIMEOUT_SECONDS
  GRADLE_RUN_DIR=$RUN_DIR
  GRADLE_LOCK_DIR=$LOCK_DIR

On timeout the script writes jps plus jcmd/jstack thread dumps before
terminating the Gradle process tree. Use -- to pass Gradle arguments that start
with a dash.
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" || "${1:-}" == "help" ]]; then
  usage
  exit 0
fi

if [[ "${1:-}" == "--" ]]; then
  shift
fi

mkdir -p "$RUN_DIR"

timeout_bin() {
  for candidate in /opt/homebrew/bin/timeout gtimeout timeout; do
    if command -v "$candidate" >/dev/null 2>&1; then
      command -v "$candidate"
      return
    fi
  done
}

TIMEOUT_BIN="$(timeout_bin || true)"

run_bounded() {
  local seconds="$1"
  shift
  local pid start now elapsed status
  if [[ "$seconds" == "0" ]]; then
    "$@"
    return
  fi
  if [[ -n "$TIMEOUT_BIN" ]]; then
    "$TIMEOUT_BIN" "$seconds" "$@"
  else
    "$@" &
    pid="$!"
    start="$(date +%s)"
    while kill -0 "$pid" 2>/dev/null; do
      sleep 1
      now="$(date +%s)"
      elapsed=$((now - start))
      if (( elapsed >= seconds )); then
        kill -TERM "$pid" 2>/dev/null || true
        sleep 1
        kill -KILL "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
        return 124
      fi
    done
    wait "$pid" || status=$?
    return "${status:-0}"
  fi
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

terminate_tree() {
  local root_pid="$1"
  local pids extra_pids
  pids="$(
    {
      printf '%s\n' "$root_pid"
      descendant_pids "$root_pid"
    } | awk 'NF && !seen[$0]++ { print }'
  )"
  # shellcheck disable=SC2086
  kill -TERM $pids 2>/dev/null || true
  sleep 5
  extra_pids="$(
    for pid in $pids; do
      descendant_pids "$pid"
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

diagnose_gradle_timeout() {
  local gradle_pid="$1"
  shift
  local diag_file="$RUN_DIR/gradle-timeout-$(date -u +%Y%m%d-%H%M%S).txt"
  {
    echo "REASON=gradle-timeout-${TIMEOUT_SECONDS}s"
    echo "ROOT=$ROOT"
    echo "PID=$gradle_pid"
    echo "UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "CMD=$ROOT/gradlew --no-daemon --max-workers=1 -Dkotlin.incremental=false $*"
    echo
    echo "== gradle process =="
    ps -p "$gradle_pid" -o pid=,ppid=,stat=,etime=,command= 2>/dev/null || true
    echo
    echo "== descendants =="
    for child in $(descendant_pids "$gradle_pid"); do
      ps -p "$child" -o pid=,ppid=,stat=,etime=,command= 2>/dev/null || true
    done
    echo
    echo "== jps =="
    if command -v jps >/dev/null 2>&1; then
      run_bounded "$DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS" jps -lm 2>/dev/null || true
    else
      echo "jps not found"
    fi
    echo
    echo "== java thread dumps =="
    java_pids=""
    command_name="$(ps -p "$gradle_pid" -o comm= 2>/dev/null || true)"
    case "$command_name" in
      *java*) java_pids="$gradle_pid" ;;
    esac
    for child in $(descendant_pids "$gradle_pid"); do
      command_name="$(ps -p "$child" -o comm= 2>/dev/null || true)"
      case "$command_name" in
        *java*) java_pids="${java_pids:+$java_pids }$child" ;;
      esac
    done
    if [[ -z "$java_pids" ]] && command -v jps >/dev/null 2>&1; then
      java_pids="$(run_bounded "$DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS" jps -q 2>/dev/null | head -"$THREAD_DUMP_MAX_JVMS" || true)"
    elif [[ -n "$java_pids" ]]; then
      java_pids="$(printf '%s\n' $java_pids | awk 'NF && !seen[$0]++ { print }' | head -"$THREAD_DUMP_MAX_JVMS" || true)"
    fi
    for java_pid in $java_pids; do
      [[ -n "$java_pid" ]] || continue
      echo "-- pid $java_pid --"
      if command -v jcmd >/dev/null 2>&1; then
        run_bounded "$THREAD_DUMP_TIMEOUT_SECONDS" jcmd "$java_pid" Thread.print 2>&1 || true
      elif command -v jstack >/dev/null 2>&1; then
        run_bounded "$THREAD_DUMP_TIMEOUT_SECONDS" jstack "$java_pid" 2>&1 || true
      else
        echo "jcmd/jstack not found"
      fi
    done
  } >"$diag_file" 2>&1 || true
  echo "GRADLE_DIAGNOSTICS=$diag_file" >&2
}

acquire_lock() {
  local start now waited owner
  start="$(date +%s)"
  while ! mkdir "$LOCK_DIR" 2>/dev/null; do
    now="$(date +%s)"
    waited=$((now - start))
    owner="$(cat "$LOCK_DIR/pid" 2>/dev/null || true)"
    if [[ -n "$owner" ]] && ! kill -0 "$owner" 2>/dev/null; then
      if rm -f "$LOCK_DIR/pid" 2>/dev/null && rmdir "$LOCK_DIR" 2>/dev/null; then
        echo "Removed stale Gradle lock held by dead PID $owner: $LOCK_DIR" >&2
      fi
      continue
    fi
    if (( waited >= LOCK_WAIT_TIMEOUT_SECONDS )); then
      echo "Timed out waiting ${LOCK_WAIT_TIMEOUT_SECONDS}s for Gradle lock: $LOCK_DIR" >&2
      exit 124
    fi
    echo "Waiting for Gradle lock held by PID ${owner:-unknown}: $LOCK_DIR (${waited}s)" >&2
    sleep 10
  done
  echo "$$" >"$LOCK_DIR/pid"
  trap 'rm -rf "$LOCK_DIR"' EXIT
}

acquire_lock

GRADLE_ARGS=(--no-daemon --max-workers=1 -Dkotlin.incremental=false "$@")
echo "Running: $ROOT/gradlew ${GRADLE_ARGS[*]}" >&2
"$ROOT/gradlew" "${GRADLE_ARGS[@]}" &
GRADLE_PID="$!"

trap 'terminate_tree "$GRADLE_PID"; rm -rf "$LOCK_DIR"; exit 143' TERM
trap 'terminate_tree "$GRADLE_PID"; rm -rf "$LOCK_DIR"; exit 130' INT

start_seconds="$(date +%s)"
exit_code=0
while kill -0 "$GRADLE_PID" 2>/dev/null; do
  sleep "$POLL_SECONDS"
  now_seconds="$(date +%s)"
  elapsed_seconds=$((now_seconds - start_seconds))
  if (( TIMEOUT_SECONDS > 0 && elapsed_seconds >= TIMEOUT_SECONDS )); then
    echo "Gradle command exceeded ${TIMEOUT_SECONDS}s; collecting JVM diagnostics before termination." >&2
    diagnose_gradle_timeout "$GRADLE_PID" "${GRADLE_ARGS[@]}"
    terminate_tree "$GRADLE_PID"
    wait "$GRADLE_PID" 2>/dev/null || true
    exit 124
  fi
done

wait "$GRADLE_PID" || exit_code=$?
exit "$exit_code"
