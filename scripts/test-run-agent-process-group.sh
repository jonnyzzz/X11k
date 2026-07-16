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
if [ -n "${RUN_AGENT_FAKE_REPARENTED_CHILD_PID_FILE:-}" ]; then
  perl -e '
    my $file = shift;
    defined(my $pid = fork) or die "fork: $!";
    if ($pid) {
      open my $fh, ">", $file or die "open $file: $!";
      print {$fh} "$pid\n";
      close $fh;
      exit 0;
    }
    $SIG{TERM} = "IGNORE";
    sleep 1 while 1;
  ' "$RUN_AGENT_FAKE_REPARENTED_CHILD_PID_FILE"
fi
if [ "${RUN_AGENT_FAKE_MODE:-timeout}" = "normal" ]; then
  exit 0
fi
on_term() {
  if [ -n "${RUN_AGENT_FAKE_LATE_CHILD_PID_FILE:-}" ]; then
    (
      trap '' TERM
      while :; do
        sleep 1
      done
    ) &
    printf '%s\n' "$!" > "$RUN_AGENT_FAKE_LATE_CHILD_PID_FILE"
  fi
  exit 0
}
trap on_term TERM
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
  local attempt_token="process-group-$mode"
  CHILD_PID_FILES+=("$pid_file")
  set +e
  PATH="$TEST_DIR/bin:$PATH" \
  RUNS_DIR="$TEST_DIR/runs" \
  RUN_AGENT_FAKE_CHILD_PID_FILE="$pid_file" \
  RUN_AGENT_FAKE_MODE="$mode" \
  RUN_AGENT_ATTEMPT_TOKEN="$attempt_token" \
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
  if ! rg -q "^ATTEMPT_TOKEN=$attempt_token$" "$TEST_DIR/runs"/run_*/run-info.txt; then
    echo "$mode run did not retain its attempt token" >&2
    exit 1
  fi
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
  local late_pid_file="$TEST_DIR/watch-late-child.pid"
  local reparented_pid_file="$TEST_DIR/watch-reparented-child.pid"
  local leader_pid pid_start
  CHILD_PID_FILES+=("$pid_file")
  CHILD_PID_FILES+=("$late_pid_file")
  CHILD_PID_FILES+=("$reparented_pid_file")
  mkdir -p "$run_dir"
  RUN_AGENT_FAKE_CHILD_PID_FILE="$pid_file" RUN_AGENT_FAKE_LATE_CHILD_PID_FILE="$late_pid_file" RUN_AGENT_FAKE_REPARENTED_CHILD_PID_FILE="$reparented_pid_file" RUN_AGENT_FAKE_MODE=timeout \
    perl -MPOSIX -e 'defined(POSIX::setsid()) or die "setsid failed: $!\n"; exec @ARGV or die "exec failed: $!\n"' -- \
      "$TEST_DIR/bin/gemini" >"$run_dir/agent-stdout.txt" 2>"$run_dir/agent-stderr.txt" &
  leader_pid=$!
  LEADER_PIDS+=("$leader_pid")
  pid_start="$(LC_ALL=C ps -p "$leader_pid" -o lstart= | awk '{$1=$1; print}')"
  printf 'PID=%s\nPID_START=%s\nAGENT=gemini\nPROCESS_GROUP=1\n' "$leader_pid" "$pid_start" >"$run_dir/run-info.txt"
  printf '%s\n' "$leader_pid" >"$run_dir/pid.txt"
  for _ in $(seq 1 50); do
    [[ -s "$pid_file" && -s "$reparented_pid_file" ]] && break
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
  assert_worker_removed "$late_pid_file" "stale watcher late child"
  assert_worker_removed "$reparented_pid_file" "stale watcher pre-reparented child"
}

assert_process_alive() {
  local pid="$1"
  local label="$2"
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "$label process $pid was terminated" >&2
    exit 1
  fi
}

run_reused_pid_watch_case() {
  local watch_runs="$TEST_DIR/reused-pid-watch-runs"
  local abandoned_dir="$watch_runs/run_abandoned"
  local completed_dir="$watch_runs/run_completed"
  local legacy_dir="$watch_runs/run_legacy"
  local wrong_start_dir="$watch_runs/run_wrong_start"
  local unmatched_dir="$watch_runs/run_unmatched"
  local unrelated_pid legacy_pid wrong_start_pid
  local legacy_child_file="$TEST_DIR/legacy-child.pid"
  local wrong_start_child_file="$TEST_DIR/wrong-start-child.pid"
  mkdir -p "$abandoned_dir" "$completed_dir" "$legacy_dir" "$wrong_start_dir" "$unmatched_dir"
  sleep 300 &
  unrelated_pid=$!
  LEADER_PIDS+=("$unrelated_pid")

  RUN_AGENT_FAKE_CHILD_PID_FILE="$legacy_child_file" RUN_AGENT_FAKE_MODE=timeout \
    perl -MPOSIX -e 'defined(POSIX::setsid()) or die "setsid failed: $!\n"; exec @ARGV or die "exec failed: $!\n"' -- \
      "$TEST_DIR/bin/gemini" >"$legacy_dir/agent-stdout.txt" 2>"$legacy_dir/agent-stderr.txt" &
  legacy_pid=$!
  LEADER_PIDS+=("$legacy_pid")
  CHILD_PID_FILES+=("$legacy_child_file")

  RUN_AGENT_FAKE_CHILD_PID_FILE="$wrong_start_child_file" RUN_AGENT_FAKE_MODE=timeout \
    perl -MPOSIX -e 'defined(POSIX::setsid()) or die "setsid failed: $!\n"; exec @ARGV or die "exec failed: $!\n"' -- \
      "$TEST_DIR/bin/gemini" >"$wrong_start_dir/agent-stdout.txt" 2>"$wrong_start_dir/agent-stderr.txt" &
  wrong_start_pid=$!
  LEADER_PIDS+=("$wrong_start_pid")
  CHILD_PID_FILES+=("$wrong_start_child_file")
  for _ in $(seq 1 50); do
    [[ -s "$legacy_child_file" && -s "$wrong_start_child_file" ]] && break
    sleep 0.1
  done

  printf 'PID=%s\nAGENT=gemini\nPROCESS_GROUP=0\nWATCH_ABANDONED_UTC=2026-07-16T00:00:00Z\n' \
    "$unrelated_pid" >"$abandoned_dir/run-info.txt"
  printf 'PID=%s\nAGENT=gemini\nPROCESS_GROUP=0\nEXIT_CODE=0\n' \
    "$unrelated_pid" >"$completed_dir/run-info.txt"
  printf '%s\n' "$unrelated_pid" >"$completed_dir/pid.txt"
  printf 'PID=%s\nAGENT=gemini\nPROCESS_GROUP=1\n' \
    "$legacy_pid" >"$legacy_dir/run-info.txt"
  printf '%s\n' "$legacy_pid" >"$legacy_dir/pid.txt"
  printf 'PID=%s\nPID_START=Mon Jan 1 00:00:00 2001\nAGENT=gemini\nPROCESS_GROUP=1\n' \
    "$wrong_start_pid" >"$wrong_start_dir/run-info.txt"
  printf '%s\n' "$wrong_start_pid" >"$wrong_start_dir/pid.txt"
  printf 'PID=%s\nAGENT=gemini\nPROCESS_GROUP=0\n' \
    "$unrelated_pid" >"$unmatched_dir/run-info.txt"

  RUNS_DIR="$watch_runs" \
  RUN_AGENT_WATCH_ONCE=1 \
  RUN_AGENT_WATCH_LIMIT=8 \
  RUN_AGENT_STALE_SECONDS=0 \
  RUN_AGENT_ABANDONED_SECONDS=0 \
  RUN_AGENT_DIAGNOSE_STALE=1 \
  RUN_AGENT_TERMINATE_STALE=1 \
  RUN_AGENT_RESTART_STALE=1 \
  RUN_AGENT_DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS=1 \
  RUN_AGENT_THREAD_DUMP_MAX_JVMS=0 \
    "$ROOT/watch-agents.sh" >"$TEST_DIR/reused-pid-watch.log" 2>&1

  assert_process_alive "$unrelated_pid" "reused PID watcher"
  assert_process_alive "$legacy_pid" "legacy same-agent PID watcher"
  assert_process_alive "$wrong_start_pid" "start-signature watcher"
  [[ ! -e "$abandoned_dir/pid.txt" ]] || {
    echo "abandoned run restored a reused PID" >&2
    exit 1
  }
  [[ ! -e "$unmatched_dir/pid.txt" ]] || {
    echo "identity-mismatched run restored a reused PID" >&2
    exit 1
  }
  [[ -e "$completed_dir/pid.txt" ]] || {
    echo "completed run was mutated despite terminal state" >&2
    exit 1
  }
  [[ ! -e "$wrong_start_dir/pid.txt" ]] || {
    echo "start-signature-mismatched run retained a recoverable PID" >&2
    exit 1
  }
  [[ ! -e "$legacy_dir/pid.txt" ]] || {
    echo "legacy same-agent run retained a recoverable PID without a birth signature" >&2
    exit 1
  }
  grep -q 'run_abandoned: abandoned (terminal record)' "$TEST_DIR/reused-pid-watch.log"
  grep -q 'run_completed: finished (terminal record)' "$TEST_DIR/reused-pid-watch.log"
  grep -q 'run_legacy: PID .* identity mismatch .* refusing recovery' "$TEST_DIR/reused-pid-watch.log"
  grep -q 'run_wrong_start: PID .* identity mismatch .* refusing recovery' "$TEST_DIR/reused-pid-watch.log"
  grep -q 'run_unmatched: PID .* identity mismatch .* refusing recovery' "$TEST_DIR/reused-pid-watch.log"
  grep -q 'WATCH_ABANDONED_REASON=pid-.*-identity-mismatch' "$unmatched_dir/run-info.txt"
  ! grep -q 'WATCH_RESTART_UTC=' "$abandoned_dir/run-info.txt"
  ! grep -q 'WATCH_RESTART_UTC=' "$unmatched_dir/run-info.txt"
  grep -q 'WATCH_SUMMARY stale=0 diagnostics=0 terminated=0 restarted=0 restart_skipped=0' "$TEST_DIR/reused-pid-watch.log"
  echo "reused PID watcher protection passed"
}

run_agent_case timeout 124 2
run_agent_case normal 0 0
run_signal_case
run_stale_watch_case
run_reused_pid_watch_case
echo "run-agent process-group cleanup suite passed"
