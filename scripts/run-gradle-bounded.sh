#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="${GRADLE_RUN_DIR:-$ROOT/runs/gradle-bounded}"
LOCK_DIR="${GRADLE_LOCK_DIR:-$ROOT/runs/gradle-bounded.lock}"
TIMEOUT_SECONDS="${GRADLE_TIMEOUT_SECONDS:-1800}"
NO_OUTPUT_DIAGNOSTICS_SECONDS="${GRADLE_NO_OUTPUT_DIAGNOSTICS_SECONDS:-300}"
NO_OUTPUT_TIMEOUT_SECONDS="${GRADLE_NO_OUTPUT_TIMEOUT_SECONDS:-900}"
HEARTBEAT_SECONDS="${GRADLE_HEARTBEAT_SECONDS:-30}"
LOCK_WAIT_TIMEOUT_SECONDS="${GRADLE_LOCK_WAIT_TIMEOUT_SECONDS:-3600}"
POLL_SECONDS="${GRADLE_POLL_SECONDS:-5}"
DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS="${GRADLE_DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS:-20}"
THREAD_DUMP_TIMEOUT_SECONDS="${GRADLE_THREAD_DUMP_TIMEOUT_SECONDS:-8}"
THREAD_DUMP_MAX_JVMS="${GRADLE_THREAD_DUMP_MAX_JVMS:-8}"
DOCKER_DIAGNOSTICS="${GRADLE_DOCKER_DIAGNOSTICS:-1}"
STALE_TESTCONTAINERS_CLEANUP="${GRADLE_STALE_TESTCONTAINERS_CLEANUP:-1}"
STALE_TESTCONTAINERS_SECONDS="${GRADLE_STALE_TESTCONTAINERS_SECONDS:-3600}"

usage() {
  cat <<USAGE
Usage:
  scripts/run-gradle-bounded.sh [gradle args...]

Runs ./gradlew with repository-safe defaults:
  --no-daemon --max-workers=1 -Dkotlin.incremental=false

Environment:
  GRADLE_TIMEOUT_SECONDS=$TIMEOUT_SECONDS
  GRADLE_NO_OUTPUT_DIAGNOSTICS_SECONDS=$NO_OUTPUT_DIAGNOSTICS_SECONDS
  GRADLE_NO_OUTPUT_TIMEOUT_SECONDS=$NO_OUTPUT_TIMEOUT_SECONDS
  GRADLE_LOCK_WAIT_TIMEOUT_SECONDS=$LOCK_WAIT_TIMEOUT_SECONDS
  GRADLE_RUN_DIR=$RUN_DIR
  GRADLE_LOCK_DIR=$LOCK_DIR
  GRADLE_DOCKER_DIAGNOSTICS=$DOCKER_DIAGNOSTICS
  GRADLE_STALE_TESTCONTAINERS_CLEANUP=$STALE_TESTCONTAINERS_CLEANUP

On wall-clock or output-idle timeout the script writes jps, jcmd/jstack thread
dumps, Docker/Testcontainers diagnostics, and output tails before terminating the
Gradle process tree. Use -- to pass Gradle arguments that start with a dash.
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

iso_to_seconds() {
  local value="${1%%.*}"
  if date -u -j -f "%Y-%m-%dT%H:%M:%S" "$value" +%s >/dev/null 2>&1; then
    date -u -j -f "%Y-%m-%dT%H:%M:%S" "$value" +%s
  else
    date -u -d "$value" +%s 2>/dev/null || echo 0
  fi
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

output_size() {
  local stdout_size stderr_size
  stdout_size="$(wc -c < "$STDOUT_FILE" 2>/dev/null || echo 0)"
  stderr_size="$(wc -c < "$STDERR_FILE" 2>/dev/null || echo 0)"
  echo $((stdout_size + stderr_size))
}

diagnose_docker_state() {
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

cleanup_stale_testcontainers() {
  [[ "$STALE_TESTCONTAINERS_CLEANUP" == "1" ]] || return 0
  command -v docker >/dev/null 2>&1 || return 0
  local stopped running id started started_seconds now age stale_ids
  stopped="$(docker ps -aq --filter "label=org.testcontainers=true" \
    --filter "status=exited" --filter "status=created" --filter "status=dead" 2>/dev/null || true)"
  if [[ -n "$stopped" ]]; then
    echo "Removing stopped Testcontainers: $(echo "$stopped" | tr '\n' ' ')" >&2
    # shellcheck disable=SC2086
    run_bounded "$DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS" docker rm -f $stopped >/dev/null 2>&1 || true
  fi
  running="$(docker ps -q --filter "label=org.testcontainers=true" 2>/dev/null || true)"
  [[ -n "$running" ]] || return 0
  now="$(date +%s)"
  stale_ids=""
  for id in $running; do
    started="$(docker inspect --format '{{.State.StartedAt}}' "$id" 2>/dev/null || true)"
    [[ -n "$started" ]] || continue
    started_seconds="$(iso_to_seconds "$started")"
    [[ "$started_seconds" =~ ^[0-9]+$ ]] || continue
    (( started_seconds > 0 )) || continue
    age=$((now - started_seconds))
    if (( age >= STALE_TESTCONTAINERS_SECONDS )); then
      stale_ids="${stale_ids:+$stale_ids }$id"
    fi
  done
  if [[ -n "$stale_ids" ]]; then
    echo "Removing stale running Testcontainers older than ${STALE_TESTCONTAINERS_SECONDS}s: $stale_ids" >&2
    # shellcheck disable=SC2086
    run_bounded "$DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS" docker rm -f $stale_ids >/dev/null 2>&1 || true
  fi
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

cleanup_run_state() {
  if [[ -n "${PID_FILE:-}" ]]; then
    rm -f "$PID_FILE"
  fi
  rm -rf "$LOCK_DIR"
}

diagnose_gradle() {
  local reason="$1"
  shift
  local gradle_pid="$1"
  shift
  local safe_reason diag_file command_name java_pids
  safe_reason="$(printf '%s' "$reason" | tr -cd '[:alnum:]_-')"
  diag_file="$THIS_RUN_DIR/diagnostics-${safe_reason}-$(date -u +%Y%m%d-%H%M%S).txt"
  {
    echo "REASON=$reason"
    echo "RUN_ID=$RUN_ID"
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
    diagnose_docker_state
    echo
    echo "== stdout tail =="
    tail -120 "$STDOUT_FILE" 2>/dev/null || true
    echo
    echo "== stderr tail =="
    tail -120 "$STDERR_FILE" 2>/dev/null || true
  } >"$diag_file" 2>&1 || true
  echo "GRADLE_DIAGNOSTICS=$diag_file" >&2
  { echo "DIAGNOSTICS=$diag_file" >> "$RUN_INFO_FILE"; } || true
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
  trap cleanup_run_state EXIT
}

acquire_lock

cleanup_stale_testcontainers

RUN_ID="run_$(date -u +%Y%m%d-%H%M%S)-$$"
THIS_RUN_DIR="$RUN_DIR/$RUN_ID"
mkdir -p "$THIS_RUN_DIR"
rm -f "$RUN_DIR/latest" 2>/dev/null || true
(cd "$RUN_DIR" && ln -s "$RUN_ID" latest) 2>/dev/null || true
STDOUT_FILE="$THIS_RUN_DIR/stdout.txt"
STDERR_FILE="$THIS_RUN_DIR/stderr.txt"
RUN_INFO_FILE="$THIS_RUN_DIR/run-info.txt"
PID_FILE="$THIS_RUN_DIR/pid.txt"

GRADLE_ARGS=(--no-daemon --max-workers=1 -Dkotlin.incremental=false "$@")
echo "Running: $ROOT/gradlew ${GRADLE_ARGS[*]}" >&2
{
  echo "RUN_ID=$RUN_ID"
  echo "RUN_DIR=$THIS_RUN_DIR"
  echo "ROOT=$ROOT"
  echo "CMD=$ROOT/gradlew ${GRADLE_ARGS[*]}"
  echo "TIMEOUT_SECONDS=$TIMEOUT_SECONDS"
  echo "NO_OUTPUT_DIAGNOSTICS_SECONDS=$NO_OUTPUT_DIAGNOSTICS_SECONDS"
  echo "NO_OUTPUT_TIMEOUT_SECONDS=$NO_OUTPUT_TIMEOUT_SECONDS"
  echo "STDOUT=$STDOUT_FILE"
  echo "STDERR=$STDERR_FILE"
  echo "LATEST=$RUN_DIR/latest"
} > "$RUN_INFO_FILE"

"$ROOT/gradlew" "${GRADLE_ARGS[@]}" > >(tee "$STDOUT_FILE") 2> >(tee "$STDERR_FILE" >&2) &
GRADLE_PID="$!"
echo "PID=$GRADLE_PID" >> "$RUN_INFO_FILE"
echo "$GRADLE_PID" > "$PID_FILE"

trap 'terminate_tree "$GRADLE_PID"; cleanup_run_state; exit 143' TERM
trap 'terminate_tree "$GRADLE_PID"; cleanup_run_state; exit 130' INT

start_seconds="$(date +%s)"
last_heartbeat_seconds=0
last_output_size=0
last_output_seconds="$start_seconds"
no_output_diagnostics_fired=false
exit_code=0
while kill -0 "$GRADLE_PID" 2>/dev/null; do
  sleep "$POLL_SECONDS"
  now_seconds="$(date +%s)"
  elapsed_seconds=$((now_seconds - start_seconds))
  current_output_size="$(output_size)"
  if [[ "$current_output_size" -ne "$last_output_size" ]]; then
    last_output_size="$current_output_size"
    last_output_seconds="$now_seconds"
    no_output_diagnostics_fired=false
  fi
  output_idle_seconds=$((now_seconds - last_output_seconds))
  if [[ "$HEARTBEAT_SECONDS" -gt 0 ]] && (( now_seconds - last_heartbeat_seconds >= HEARTBEAT_SECONDS )); then
    {
      echo "UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      echo "PID=$GRADLE_PID"
      echo "ELAPSED_SECONDS=$elapsed_seconds"
      echo "OUTPUT_BYTES=$current_output_size"
      echo "OUTPUT_IDLE_SECONDS=$output_idle_seconds"
      ps -p "$GRADLE_PID" -o pid=,ppid=,stat=,etime=,command= 2>/dev/null || true
    } > "$THIS_RUN_DIR/heartbeat.txt" 2>/dev/null || true
    last_heartbeat_seconds="$now_seconds"
  fi
  if [[ "$no_output_diagnostics_fired" == false ]] && \
     [[ "$NO_OUTPUT_DIAGNOSTICS_SECONDS" -gt 0 ]] && \
     (( output_idle_seconds >= NO_OUTPUT_DIAGNOSTICS_SECONDS )); then
    no_output_diagnostics_fired=true
    echo "Gradle command produced no new output for ${NO_OUTPUT_DIAGNOSTICS_SECONDS}s; collecting diagnostics and continuing." >&2
    diagnose_gradle "gradle-no-output-${NO_OUTPUT_DIAGNOSTICS_SECONDS}s" "$GRADLE_PID" "${GRADLE_ARGS[@]}"
  fi
  if [[ "$NO_OUTPUT_TIMEOUT_SECONDS" -gt 0 ]] && (( output_idle_seconds >= NO_OUTPUT_TIMEOUT_SECONDS )); then
    echo "Gradle command produced no new output for ${NO_OUTPUT_TIMEOUT_SECONDS}s; collecting diagnostics before termination." >&2
    diagnose_gradle "gradle-no-output-timeout-${NO_OUTPUT_TIMEOUT_SECONDS}s" "$GRADLE_PID" "${GRADLE_ARGS[@]}"
    terminate_tree "$GRADLE_PID"
    wait "$GRADLE_PID" 2>/dev/null || true
    {
      echo "TIMEOUT_REASON=gradle-no-output-timeout-${NO_OUTPUT_TIMEOUT_SECONDS}s"
      echo "EXIT_CODE=124"
    } >> "$RUN_INFO_FILE"
    exit 124
  fi
  if (( TIMEOUT_SECONDS > 0 && elapsed_seconds >= TIMEOUT_SECONDS )); then
    echo "Gradle command exceeded ${TIMEOUT_SECONDS}s; collecting JVM diagnostics before termination." >&2
    diagnose_gradle "gradle-timeout-${TIMEOUT_SECONDS}s" "$GRADLE_PID" "${GRADLE_ARGS[@]}"
    terminate_tree "$GRADLE_PID"
    wait "$GRADLE_PID" 2>/dev/null || true
    {
      echo "TIMEOUT_REASON=gradle-timeout-${TIMEOUT_SECONDS}s"
      echo "EXIT_CODE=124"
    } >> "$RUN_INFO_FILE"
    exit 124
  fi
done

wait "$GRADLE_PID" || exit_code=$?
echo "EXIT_CODE=$exit_code" >> "$RUN_INFO_FILE"
exit "$exit_code"
