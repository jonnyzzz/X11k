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
NATIVE_CRASH_SCAN_FIND_TIMEOUT_SECONDS="${GRADLE_NATIVE_CRASH_SCAN_FIND_TIMEOUT_SECONDS:-5}"
NATIVE_CRASH_SCAN_GREP_TIMEOUT_SECONDS="${GRADLE_NATIVE_CRASH_SCAN_GREP_TIMEOUT_SECONDS:-2}"
NATIVE_CRASH_SCAN_MAX_FILES="${GRADLE_NATIVE_CRASH_SCAN_MAX_FILES:-200}"
NATIVE_CRASH_SCAN_MAX_BYTES="${GRADLE_NATIVE_CRASH_SCAN_MAX_BYTES:-1048576}"

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
  GRADLE_NATIVE_CRASH_SCAN_FIND_TIMEOUT_SECONDS=$NATIVE_CRASH_SCAN_FIND_TIMEOUT_SECONDS
  GRADLE_NATIVE_CRASH_SCAN_GREP_TIMEOUT_SECONDS=$NATIVE_CRASH_SCAN_GREP_TIMEOUT_SECONDS
  GRADLE_NATIVE_CRASH_SCAN_MAX_FILES=$NATIVE_CRASH_SCAN_MAX_FILES
  GRADLE_NATIVE_CRASH_SCAN_MAX_BYTES=$NATIVE_CRASH_SCAN_MAX_BYTES

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
        terminate_bounded_tree "$pid"
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

native_crash_candidate_files() {
  printf '%s\n' "$STDOUT_FILE" "$STDERR_FILE"
  run_bounded "$NATIVE_CRASH_SCAN_FIND_TIMEOUT_SECONDS" find \
    "$ROOT/build/test-results/test" "$ROOT/build/tmp" \
    -type f \
    -newer "$RUN_INFO_FILE" \
    \( -name 'TEST-*.xml' \
      -o -name 'hs_err_pid*.log' \
      -o -name '*.log' \
      -o -name '*.txt' \
      -o -name '*.out' \
      -o -name '*.err' \
      -o -name '*.xml' \) \
    -size -"${NATIVE_CRASH_SCAN_MAX_BYTES}c" \
    -print 2>/dev/null || true
  run_bounded "$NATIVE_CRASH_SCAN_FIND_TIMEOUT_SECONDS" find "$ROOT" \
    -maxdepth 3 -type f -newer "$RUN_INFO_FILE" -name 'hs_err_pid*.log' -print 2>/dev/null || true
}

scan_native_crash_anchors() {
  local pattern file size matches status=0
  pattern='SIG[A-Z]+|Problematic frame|java_error|hs_err|XextFindDisplay|fatal error has been detected'
  native_crash_candidate_files \
    | awk 'NF && !seen[$0]++ { print }' \
    | head -"$NATIVE_CRASH_SCAN_MAX_FILES" \
    | while read -r file; do
      [[ -f "$file" ]] || continue
      size="$(wc -c < "$file" 2>/dev/null || echo 0)"
      [[ "$size" =~ ^[0-9]+$ ]] || size=0
      if (( size > NATIVE_CRASH_SCAN_MAX_BYTES )) && [[ "$(basename "$file")" != hs_err_pid*.log ]]; then
        echo "-- skipped $file (${size} bytes > ${NATIVE_CRASH_SCAN_MAX_BYTES}) --"
        continue
      fi
      matches="$(run_bounded "$NATIVE_CRASH_SCAN_GREP_TIMEOUT_SECONDS" grep -nI -E "$pattern" "$file" 2>/dev/null)" || status=$?
      if [[ "$status" -eq 124 ]]; then
        echo "-- grep timed out after ${NATIVE_CRASH_SCAN_GREP_TIMEOUT_SECONDS}s: $file --"
      elif [[ -n "$matches" ]]; then
        printf '%s\n' "$matches" | sed "s#^#$file:#"
      fi
      status=0
    done
}

dump_failure_artifacts() {
  local reason="$1"
  local safe_reason diag_file native_scan_file
  safe_reason="$(printf '%s' "$reason" | tr -cd '[:alnum:]_-')"
  diag_file="$THIS_RUN_DIR/diagnostics-${safe_reason}-$(date -u +%Y%m%d-%H%M%S).txt"
  {
    echo "REASON=$reason"
    echo "RUN_ID=$RUN_ID"
    echo "ROOT=$ROOT"
    echo "UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "CMD=$ROOT/gradlew ${GRADLE_ARGS[*]}"
    echo
    echo "== run info =="
    cat "$RUN_INFO_FILE" 2>/dev/null || true
    echo
    echo "== failed test result anchors =="
    find "$ROOT/build/test-results/test" -maxdepth 1 -type f -name 'TEST-*.xml' -print 2>/dev/null | sort | while read -r file; do
      if grep -q '<failure\|<error' "$file" 2>/dev/null; then
        echo "-- $file --"
        grep -n -E '<testcase name=|<failure|<error|SIG[A-Z]+|Problematic frame|java_error|XextFindDisplay|AssertionFailedError' "$file" 2>/dev/null | head -160 || true
      fi
    done
    echo
    echo "== native crash anchors =="
    native_scan_file="$THIS_RUN_DIR/native-crash-scan.txt"
    echo "Scanning text artifacts only: max_files=${NATIVE_CRASH_SCAN_MAX_FILES}, max_bytes=${NATIVE_CRASH_SCAN_MAX_BYTES}, find_timeout=${NATIVE_CRASH_SCAN_FIND_TIMEOUT_SECONDS}s, grep_timeout=${NATIVE_CRASH_SCAN_GREP_TIMEOUT_SECONDS}s"
    scan_native_crash_anchors >"$native_scan_file" 2>/dev/null || true
    head -240 "$native_scan_file" 2>/dev/null || true
    echo
    echo "== intellij smoke artifacts =="
    if [[ -d "$ROOT/build/tmp/intellij-community-smoke" ]]; then
      find "$ROOT/build/tmp/intellij-community-smoke" -maxdepth 1 -type f \
        \( -name '*.log' -o -name '*.txt' \) -print 2>/dev/null | sort | while read -r file; do
        case "$(basename "$file")" in
          *idea.log|*run.log|*xawt-trace.log|*glx-xdpyinfo.log|*xprop-root.log|*visual-region-metrics.txt)
            echo "-- $file --"
            tail -80 "$file" 2>/dev/null || true
            ;;
        esac
      done
    fi
    echo
    echo "== stdout tail =="
    tail -160 "$STDOUT_FILE" 2>/dev/null || true
    echo
    echo "== stderr tail =="
    tail -160 "$STDERR_FILE" 2>/dev/null || true
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
      if [[ -n "${RUN_INFO_FILE:-}" ]]; then
        if [[ -n "$owner" ]] && kill -0 "$owner" 2>/dev/null; then
          diagnose_gradle "gradle-lock-wait-timeout-${LOCK_WAIT_TIMEOUT_SECONDS}s-owner-${owner}" "$owner" "${GRADLE_ARGS[@]}"
        fi
        {
          echo "TIMEOUT_REASON=gradle-lock-wait-timeout-${LOCK_WAIT_TIMEOUT_SECONDS}s"
          echo "LOCK_OWNER_PID=${owner:-unknown}"
          echo "EXIT_CODE=124"
        } >> "$RUN_INFO_FILE" 2>/dev/null || true
      fi
      exit 124
    fi
    echo "Waiting for Gradle lock held by PID ${owner:-unknown}: $LOCK_DIR (${waited}s)" >&2
    sleep 10
  done
  echo "$$" >"$LOCK_DIR/pid"
  trap cleanup_run_state EXIT
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

acquire_lock

cleanup_stale_testcontainers

"$ROOT/gradlew" "${GRADLE_ARGS[@]}" > >(tee "$STDOUT_FILE") 2> >(tee "$STDERR_FILE" >&2) &
GRADLE_PID="$!"
echo "PID=$GRADLE_PID" >> "$RUN_INFO_FILE"
echo "$GRADLE_PID" > "$PID_FILE"

handle_signal() {
  local signal="$1"
  local exit_code="$2"
  if [[ -n "${GRADLE_PID:-}" ]] && kill -0 "$GRADLE_PID" 2>/dev/null; then
    echo "Gradle wrapper received ${signal}; collecting JVM diagnostics before termination." >&2
    diagnose_gradle "gradle-signal-${signal}" "$GRADLE_PID" "${GRADLE_ARGS[@]}"
    terminate_tree "$GRADLE_PID"
    wait "$GRADLE_PID" 2>/dev/null || true
  fi
  {
    echo "TIMEOUT_REASON=gradle-signal-${signal}"
    echo "EXIT_CODE=$exit_code"
  } >> "$RUN_INFO_FILE" 2>/dev/null || true
  cleanup_run_state
  exit "$exit_code"
}

trap 'handle_signal TERM 143' TERM
trap 'handle_signal INT 130' INT
trap 'handle_signal HUP 129' HUP

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
if [[ "$exit_code" -ne 0 ]]; then
  dump_failure_artifacts "gradle-exit-${exit_code}"
fi
exit "$exit_code"
