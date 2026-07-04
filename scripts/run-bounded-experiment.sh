#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="${EXPERIMENT_RUN_DIR:-$ROOT/runs/bounded-experiments}"
TIMEOUT_SECONDS="${EXPERIMENT_TIMEOUT_SECONDS:-900}"
NO_OUTPUT_DIAGNOSTICS_SECONDS="${EXPERIMENT_NO_OUTPUT_DIAGNOSTICS_SECONDS:-180}"
NO_OUTPUT_TIMEOUT_SECONDS="${EXPERIMENT_NO_OUTPUT_TIMEOUT_SECONDS:-300}"
HEARTBEAT_SECONDS="${EXPERIMENT_HEARTBEAT_SECONDS:-30}"
POLL_SECONDS="${EXPERIMENT_POLL_SECONDS:-5}"
DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS="${EXPERIMENT_DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS:-20}"
THREAD_DUMP_TIMEOUT_SECONDS="${EXPERIMENT_THREAD_DUMP_TIMEOUT_SECONDS:-8}"
THREAD_DUMP_MAX_JVMS="${EXPERIMENT_THREAD_DUMP_MAX_JVMS:-8}"
DOCKER_DIAGNOSTICS="${EXPERIMENT_DOCKER_DIAGNOSTICS:-1}"
MIRROR_OUTPUT="${EXPERIMENT_MIRROR_OUTPUT:-1}"

usage() {
  cat <<USAGE
Usage:
  scripts/run-bounded-experiment.sh -- <command> [args...]

Runs an arbitrary non-Gradle experiment with repository diagnostics.
Use scripts/run-gradle-bounded.sh for Gradle/Maven/build/test commands.

Environment:
  EXPERIMENT_TIMEOUT_SECONDS=$TIMEOUT_SECONDS
  EXPERIMENT_NO_OUTPUT_DIAGNOSTICS_SECONDS=$NO_OUTPUT_DIAGNOSTICS_SECONDS
  EXPERIMENT_NO_OUTPUT_TIMEOUT_SECONDS=$NO_OUTPUT_TIMEOUT_SECONDS
  EXPERIMENT_RUN_DIR=$RUN_DIR
  EXPERIMENT_MIRROR_OUTPUT=$MIRROR_OUTPUT

Each run writes stdout, stderr, run-info.txt, heartbeat.txt, and timeout
diagnostics under EXPERIMENT_RUN_DIR/run_YYYYMMDD-HHMMSS-PID. By default
stdout/stderr are also mirrored to this terminal; set EXPERIMENT_MIRROR_OUTPUT=0
for fully quiet persisted-output runs.
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" || "${1:-}" == "help" ]]; then
  usage
  exit 0
fi

if [[ "${1:-}" == "--" ]]; then
  shift
fi

if [[ "$#" -eq 0 ]]; then
  usage >&2
  exit 2
fi

mkdir -p "$RUN_DIR"

bounded_descendant_pids() {
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

terminate_bounded_tree() {
  local root_pid="$1"
  local pids extra_pids
  pids="$(
    {
      printf '%s\n' "$root_pid"
      bounded_descendant_pids "$root_pid"
    } | awk 'NF && !seen[$0]++ { print }'
  )"
  # shellcheck disable=SC2086
  kill -TERM $pids 2>/dev/null || true
  sleep 1
  extra_pids="$(
    for pid in $pids; do
      bounded_descendant_pids "$pid"
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
  if [[ "$seconds" == "0" ]]; then
    "$@"
  elif [[ -x /opt/homebrew/bin/timeout ]]; then
    /opt/homebrew/bin/timeout "$seconds" "$@"
  elif command -v timeout >/dev/null 2>&1; then
    timeout "$seconds" "$@"
  elif command -v gtimeout >/dev/null 2>&1; then
    gtimeout "$seconds" "$@"
  else
    "$@" &
    local pid="$!" start now elapsed status=0
    start="$(date +%s)"
    while kill -0 "$pid" 2>/dev/null; do
      sleep 1
      now="$(date +%s)"
      elapsed=$((now - start))
      if (( elapsed >= seconds )); then
        terminate_bounded_tree "$pid"
        wait "$pid" 2>/dev/null || true
        return 124
      fi
    done
    wait "$pid" || status=$?
    return "$status"
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

output_size() {
  local stdout_size stderr_size
  stdout_size="$(wc -c < "$STDOUT_FILE" 2>/dev/null || echo 0)"
  stderr_size="$(wc -c < "$STDERR_FILE" 2>/dev/null || echo 0)"
  echo $((stdout_size + stderr_size))
}

docker_relevant_container_ids() {
  command -v docker >/dev/null 2>&1 || return 0
  {
    docker ps -aq --filter "label=org.testcontainers=true" 2>/dev/null || true
    docker ps -aq --filter "ancestor=testcontainers/ryuk" 2>/dev/null || true
    docker ps -aq --filter "ancestor=jonnyzzz-x/x11-client:latest" 2>/dev/null || true
    docker ps -aq --filter "ancestor=jonnyzzz-x/x11-reference:latest" 2>/dev/null || true
  } | awk 'NF && !seen[$0]++ { print }'
}

dump_docker_diagnostics() {
  [[ "$DOCKER_DIAGNOSTICS" == "1" ]] || return 0
  command -v docker >/dev/null 2>&1 || {
    echo "docker not found"
    return 0
  }
  echo
  echo "== docker ps =="
  run_bounded "$DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS" docker ps --format 'table {{.ID}}\t{{.Image}}\t{{.Status}}\t{{.Names}}\t{{.Ports}}' 2>&1 || true
  echo
  echo "== relevant docker containers =="
  local ids
  ids="$(docker_relevant_container_ids || true)"
  if [[ -z "${ids:-}" ]]; then
    echo "none"
    return 0
  fi
  for id in $ids; do
    echo "-- container $id --"
    run_bounded "$DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS" docker inspect \
      --format 'name={{.Name}} image={{.Config.Image}} status={{.State.Status}} started={{.State.StartedAt}} labels={{json .Config.Labels}} cmd={{json .Config.Cmd}}' \
      "$id" 2>&1 || true
    run_bounded "$DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS" docker top "$id" auxww 2>&1 || true
    echo "-- logs $id --"
    run_bounded "$DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS" docker logs --tail 120 "$id" 2>&1 || true
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

dump_diagnostics() {
  local reason="$1"
  local safe_reason diag_file command_name java_pids
  safe_reason="$(printf '%s' "$reason" | tr -cd '[:alnum:]_-')"
  diag_file="$THIS_RUN_DIR/diagnostics-${safe_reason}-$(date -u +%Y%m%d-%H%M%S).txt"
  {
    echo "REASON=$reason"
    echo "RUN_ID=$RUN_ID"
    echo "ROOT=$ROOT"
    echo "PID=$COMMAND_PID"
    echo "UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "CMD=$CMDLINE"
    echo
    echo "== root process =="
    ps -p "$COMMAND_PID" -o pid=,ppid=,stat=,etime=,command= 2>/dev/null || true
    echo
    echo "== descendants =="
    for child in $(descendant_pids "$COMMAND_PID"); do
      ps -p "$child" -o pid=,ppid=,stat=,etime=,command= 2>/dev/null || true
    done
    echo
    echo "== matching processes =="
    ps -axo pid,ppid,stat,etime,comm 2>/dev/null | \
      egrep 'codex|claude|gemini|gradle|java|docker|Xvfb|Xorg|run-agent' | \
      egrep -v 'egrep|diagnostics-' || true
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
    command_name="$(ps -p "$COMMAND_PID" -o comm= 2>/dev/null || true)"
    case "$command_name" in
      *java*) java_pids="$COMMAND_PID" ;;
    esac
    for child in $(descendant_pids "$COMMAND_PID"); do
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
    dump_docker_diagnostics
    echo
    echo "== stdout tail =="
    tail -120 "$STDOUT_FILE" 2>/dev/null || true
    echo
    echo "== stderr tail =="
    tail -120 "$STDERR_FILE" 2>/dev/null || true
  } >"$diag_file" 2>&1 || true
  echo "DIAGNOSTICS=$diag_file"
  { echo "DIAGNOSTICS=$diag_file" >> "$RUN_INFO_FILE"; } || true
}

RUN_ID="run_$(date -u +%Y%m%d-%H%M%S)-$$"
THIS_RUN_DIR="$RUN_DIR/$RUN_ID"
mkdir -p "$THIS_RUN_DIR"
rm -f "$RUN_DIR/latest" 2>/dev/null || true
(cd "$RUN_DIR" && ln -s "$RUN_ID" latest) 2>/dev/null || true

STDOUT_FILE="$THIS_RUN_DIR/stdout.txt"
STDERR_FILE="$THIS_RUN_DIR/stderr.txt"
RUN_INFO_FILE="$THIS_RUN_DIR/run-info.txt"
PID_FILE="$THIS_RUN_DIR/pid.txt"
CMDLINE="$(printf '%q ' "$@")"

{
  echo "RUN_ID=$RUN_ID"
  echo "RUN_DIR=$THIS_RUN_DIR"
  echo "ROOT=$ROOT"
  echo "CMD=$CMDLINE"
  echo "TIMEOUT_SECONDS=$TIMEOUT_SECONDS"
  echo "NO_OUTPUT_DIAGNOSTICS_SECONDS=$NO_OUTPUT_DIAGNOSTICS_SECONDS"
  echo "NO_OUTPUT_TIMEOUT_SECONDS=$NO_OUTPUT_TIMEOUT_SECONDS"
  echo "MIRROR_OUTPUT=$MIRROR_OUTPUT"
  echo "STDOUT=$STDOUT_FILE"
  echo "STDERR=$STDERR_FILE"
  echo "LATEST=$RUN_DIR/latest"
} > "$RUN_INFO_FILE"

if [[ "$MIRROR_OUTPUT" == "1" ]]; then
  (
    cd "$ROOT"
    exec "$@" > >(tee "$STDOUT_FILE") 2> >(tee "$STDERR_FILE" >&2)
  ) &
else
  (
    cd "$ROOT"
    exec "$@" >"$STDOUT_FILE" 2>"$STDERR_FILE"
  ) &
fi
COMMAND_PID="$!"
echo "PID=$COMMAND_PID" >> "$RUN_INFO_FILE"
echo "$COMMAND_PID" > "$PID_FILE"

echo "RUN_DIR=$THIS_RUN_DIR"
echo "PID=$COMMAND_PID"

handle_signal() {
  local signal="$1"
  local exit_code="$2"
  dump_diagnostics "signal-${signal}"
  terminate_tree "$COMMAND_PID"
  wait "$COMMAND_PID" 2>/dev/null || true
  rm -f "$PID_FILE"
  {
    echo "TIMEOUT_REASON=signal-${signal}"
    echo "EXIT_CODE=$exit_code"
  } >> "$RUN_INFO_FILE"
  exit "$exit_code"
}

trap 'handle_signal TERM 143' TERM
trap 'handle_signal INT 130' INT

EXIT_CODE=0
START_SECONDS="$(date +%s)"
LAST_HEARTBEAT_SECONDS=0
LAST_OUTPUT_SIZE=0
LAST_OUTPUT_SECONDS="$START_SECONDS"
NO_OUTPUT_DIAGNOSTICS_FIRED=false
TIMEOUT_FIRED=false
TIMEOUT_REASON=""

while kill -0 "$COMMAND_PID" 2>/dev/null; do
  sleep "$POLL_SECONDS"
  NOW_SECONDS="$(date +%s)"
  ELAPSED_SECONDS=$((NOW_SECONDS - START_SECONDS))
  OUTPUT_SIZE="$(output_size)"
  if [[ "$OUTPUT_SIZE" -ne "$LAST_OUTPUT_SIZE" ]]; then
    LAST_OUTPUT_SIZE="$OUTPUT_SIZE"
    LAST_OUTPUT_SECONDS="$NOW_SECONDS"
    NO_OUTPUT_DIAGNOSTICS_FIRED=false
  fi
  OUTPUT_IDLE_SECONDS=$((NOW_SECONDS - LAST_OUTPUT_SECONDS))
  if [[ "$HEARTBEAT_SECONDS" -gt 0 ]] && (( NOW_SECONDS - LAST_HEARTBEAT_SECONDS >= HEARTBEAT_SECONDS )); then
    {
      echo "UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      echo "PID=$COMMAND_PID"
      echo "ELAPSED_SECONDS=$ELAPSED_SECONDS"
      echo "OUTPUT_BYTES=$OUTPUT_SIZE"
      echo "OUTPUT_IDLE_SECONDS=$OUTPUT_IDLE_SECONDS"
      ps -p "$COMMAND_PID" -o pid=,ppid=,stat=,etime=,command= 2>/dev/null || true
    } > "$THIS_RUN_DIR/heartbeat.txt" 2>/dev/null || true
    LAST_HEARTBEAT_SECONDS="$NOW_SECONDS"
  fi
  if [[ "$NO_OUTPUT_DIAGNOSTICS_FIRED" == false ]] && \
     [[ "$NO_OUTPUT_DIAGNOSTICS_SECONDS" -gt 0 ]] && \
     (( OUTPUT_IDLE_SECONDS >= NO_OUTPUT_DIAGNOSTICS_SECONDS )); then
    NO_OUTPUT_DIAGNOSTICS_FIRED=true
    dump_diagnostics "no-output-${NO_OUTPUT_DIAGNOSTICS_SECONDS}s"
  fi
  if [[ "$NO_OUTPUT_TIMEOUT_SECONDS" -gt 0 ]] && (( OUTPUT_IDLE_SECONDS >= NO_OUTPUT_TIMEOUT_SECONDS )); then
    TIMEOUT_FIRED=true
    TIMEOUT_REASON="no-output-timeout-${NO_OUTPUT_TIMEOUT_SECONDS}s"
    dump_diagnostics "$TIMEOUT_REASON"
    terminate_tree "$COMMAND_PID"
    EXIT_CODE=124
    break
  fi
  if [[ "$TIMEOUT_SECONDS" -gt 0 ]] && (( ELAPSED_SECONDS >= TIMEOUT_SECONDS )); then
    TIMEOUT_FIRED=true
    TIMEOUT_REASON="timeout-${TIMEOUT_SECONDS}s"
    dump_diagnostics "$TIMEOUT_REASON"
    terminate_tree "$COMMAND_PID"
    EXIT_CODE=124
    break
  fi
done

if [[ "$TIMEOUT_FIRED" == false ]]; then
  wait "$COMMAND_PID" || EXIT_CODE=$?
fi

rm -f "$PID_FILE"
if [[ "$TIMEOUT_FIRED" == true ]]; then
  {
    echo "TIMEOUT_REASON=$TIMEOUT_REASON"
    echo "TIMEOUT_SECONDS=$TIMEOUT_SECONDS"
  } >> "$RUN_INFO_FILE"
fi
echo "EXIT_CODE=$EXIT_CODE" >> "$RUN_INFO_FILE"
echo "EXIT_CODE=$EXIT_CODE"
exit "$EXIT_CODE"
