#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if ! command -v perl >/dev/null 2>&1; then
  echo "run-agent process-group cleanup skipped: perl/POSIX::setsid unavailable"
  exit 0
fi

mkdir -p "$ROOT/build/tmp"
TEST_DIR="$(mktemp -d "$ROOT/build/tmp/run-agent-process-group.XXXXXX")"
CHILD_PID_FILES=()
LEADER_PIDS=()

cleanup() {
  local pid_file pid
  set +u
  for pid_file in "${CHILD_PID_FILES[@]}"; do
    [[ -s "$pid_file" ]] || continue
    pid="$(cat "$pid_file")"
    kill -KILL "$pid" 2>/dev/null || true
  done
  for pid in "${LEADER_PIDS[@]}"; do
    kill -KILL -- "-$pid" 2>/dev/null || true
    kill -KILL "$pid" 2>/dev/null || true
  done
  rm -rf -- "$TEST_DIR"
}
trap cleanup EXIT

mkdir -p "$TEST_DIR/bin" "$TEST_DIR/runs"
cat > "$TEST_DIR/bin/gemini" <<'EOF'
#!/bin/sh
(
  trap '' TERM
  while :; do
    sleep 1
  done
) &
printf '%s\n' "$!" > "$RUN_AGENT_FAKE_CHILD_PID_FILE"
if [ "${RUN_AGENT_FAKE_MODE:-timeout}" = "normal" ]; then
  exit 0
fi
trap 'exit 0' TERM
wait
EOF
chmod +x "$TEST_DIR/bin/gemini"
printf '%s\n' 'Exercise process-group timeout cleanup.' > "$TEST_DIR/prompt.md"

assert_worker_removed() {
  local pid_file="$1"
  local label="$2"
  local child_pid
  if [[ ! -s "$pid_file" ]]; then
    echo "$label did not record its worker PID" >&2
    exit 1
  fi
  child_pid="$(cat "$pid_file")"
  for _ in $(seq 1 30); do
    if ! kill -0 "$child_pid" 2>/dev/null; then
      echo "$label cleanup passed"
      return
    fi
    sleep 0.1
  done
  echo "$label worker $child_pid escaped process-group cleanup" >&2
  exit 1
}

await_process_exit() {
  local pid="$1"
  local label="$2"
  local attempts="${3:-100}"
  local state
  for _ in $(seq 1 "$attempts"); do
    state="$(ps -p "$pid" -o stat= 2>/dev/null || true)"
    if [[ -z "$state" || "$state" == Z* ]]; then
      return
    fi
    sleep 0.1
  done
  echo "$label process $pid did not exit within $((attempts / 10)) seconds" >&2
  return 1
}

run_agent_case() {
  local mode="$1"
  local expected_status="$2"
  local no_output_timeout="$3"
  local pid_file="$TEST_DIR/$mode-child.pid"
  local status
  CHILD_PID_FILES+=("$pid_file")
  set +e
  PATH="$TEST_DIR/bin:$PATH" \
  RUNS_DIR="$TEST_DIR/runs" \
  RUN_AGENT_FAKE_CHILD_PID_FILE="$pid_file" \
  RUN_AGENT_FAKE_MODE="$mode" \
  RUN_AGENT_PREFLIGHT_WATCH=0 \
  RUN_AGENT_RELIABILITY_PREAMBLE=0 \
  RUN_AGENT_TIMEOUT_SECONDS=30 \
  RUN_AGENT_NO_OUTPUT_DIAGNOSTICS_SECONDS=0 \
  RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS="$no_output_timeout" \
  RUN_AGENT_HEARTBEAT_SECONDS=0 \
  RUN_AGENT_HEARTBEAT_STATUS_SECONDS=0 \
  RUN_AGENT_DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS=1 \
  RUN_AGENT_THREAD_DUMP_MAX_JVMS=0 \
  RUN_AGENT_POLL_SECONDS=1 \
    "$ROOT/run-agent.sh" gemini "$ROOT" "$TEST_DIR/prompt.md"
  status=$?
  set -e
  if [[ "$status" -ne "$expected_status" ]]; then
    echo "Expected $mode run-agent exit $expected_status, got $status" >&2
    exit 1
  fi
  assert_worker_removed "$pid_file" "$mode launcher"
}

run_signal_case() {
  local pid_file="$TEST_DIR/signal-child.pid"
  local status runner_pid
  CHILD_PID_FILES+=("$pid_file")
  PATH="$TEST_DIR/bin:$PATH" \
  RUNS_DIR="$TEST_DIR/runs" \
  RUN_AGENT_FAKE_CHILD_PID_FILE="$pid_file" \
  RUN_AGENT_FAKE_MODE=timeout \
  RUN_AGENT_PREFLIGHT_WATCH=0 \
  RUN_AGENT_RELIABILITY_PREAMBLE=0 \
  RUN_AGENT_TIMEOUT_SECONDS=30 \
  RUN_AGENT_NO_OUTPUT_DIAGNOSTICS_SECONDS=0 \
  RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS=0 \
  RUN_AGENT_HEARTBEAT_SECONDS=0 \
  RUN_AGENT_HEARTBEAT_STATUS_SECONDS=0 \
  RUN_AGENT_DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS=1 \
  RUN_AGENT_THREAD_DUMP_MAX_JVMS=0 \
  RUN_AGENT_POLL_SECONDS=1 \
    "$ROOT/run-agent.sh" gemini "$ROOT" "$TEST_DIR/prompt.md" >"$TEST_DIR/signal-run.log" 2>&1 &
  runner_pid=$!
  for _ in $(seq 1 50); do
    [[ -s "$pid_file" ]] && break
    sleep 0.1
  done
  kill -TERM "$runner_pid"
  await_process_exit "$runner_pid" "signalled run-agent" 300
  set +e
  wait "$runner_pid"
  status=$?
  set -e
  if [[ "$status" -ne 143 ]]; then
    echo "Expected signalled run-agent exit 143, got $status" >&2
    exit 1
  fi
  assert_worker_removed "$pid_file" "signal"
}

run_stale_watch_case() {
  local run_dir="$TEST_DIR/watch-runs/run_stale"
  local pid_file="$TEST_DIR/watch-child.pid"
  local leader_pid
  CHILD_PID_FILES+=("$pid_file")
  mkdir -p "$run_dir"
  RUN_AGENT_FAKE_CHILD_PID_FILE="$pid_file" RUN_AGENT_FAKE_MODE=timeout \
    perl -MPOSIX -e 'defined(POSIX::setsid()) or die "setsid failed: $!\n"; exec @ARGV or die "exec failed: $!\n"' -- \
      "$TEST_DIR/bin/gemini" >"$run_dir/agent-stdout.txt" 2>"$run_dir/agent-stderr.txt" &
  leader_pid=$!
  LEADER_PIDS+=("$leader_pid")
  printf 'PID=%s\nPROCESS_GROUP=1\n' "$leader_pid" >"$run_dir/run-info.txt"
  printf '%s\n' "$leader_pid" >"$run_dir/pid.txt"
  for _ in $(seq 1 50); do
    [[ -s "$pid_file" ]] && break
    sleep 0.1
  done
  RUNS_DIR="$TEST_DIR/watch-runs" \
  RUN_AGENT_WATCH_ONCE=1 \
  RUN_AGENT_WATCH_LIMIT=5 \
  RUN_AGENT_STALE_SECONDS=0 \
  RUN_AGENT_DIAGNOSE_STALE=1 \
  RUN_AGENT_TERMINATE_STALE=1 \
  RUN_AGENT_RESTART_STALE=0 \
  RUN_AGENT_DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS=1 \
  RUN_AGENT_THREAD_DUMP_MAX_JVMS=0 \
    "$ROOT/watch-agents.sh" >"$TEST_DIR/watch.log" 2>&1
  await_process_exit "$leader_pid" "stale watcher leader"
  wait "$leader_pid" 2>/dev/null || true
  assert_worker_removed "$pid_file" "stale watcher"
}

run_agent_case timeout 124 2
run_agent_case normal 0 0
run_signal_case
run_stale_watch_case
echo "run-agent process-group cleanup suite passed"
