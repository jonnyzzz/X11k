#!/bin/bash
# Periodically report agent status using PID files under runs/
set -euo pipefail
BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
RUNS_DIR="${RUNS_DIR:-$BASE_DIR/runs}"
LOG="$RUNS_DIR/agent-watch.log"
RUN_AGENT_WATCH_LIMIT="${RUN_AGENT_WATCH_LIMIT:-80}"
RUN_AGENT_STALE_SECONDS="${RUN_AGENT_STALE_SECONDS:-900}"

now_seconds() {
  date +%s
}

file_mtime() {
  local file="$1"
  if [ ! -e "$file" ]; then
    echo 0
    return
  fi
  stat -f %m "$file" 2>/dev/null || stat -c %Y "$file" 2>/dev/null || echo 0
}

latest_output_age() {
  local run_dir="$1"
  local stdout_mtime stderr_mtime latest now
  stdout_mtime="$(file_mtime "$run_dir/agent-stdout.txt")"
  stderr_mtime="$(file_mtime "$run_dir/agent-stderr.txt")"
  latest="$stdout_mtime"
  [ "$stderr_mtime" -gt "$latest" ] && latest="$stderr_mtime"
  if [ "$latest" -le 0 ]; then
    echo "unknown"
    return
  fi
  now="$(now_seconds)"
  echo "$((now - latest))s"
}

while true; do
  ts=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
  echo "[$ts] status check (latest $RUN_AGENT_WATCH_LIMIT runs)" | tee -a "$LOG"
  found=0
  while IFS= read -r run_dir; do
    [ -n "$run_dir" ] || continue
    found=1
    pid_file="$run_dir/pid.txt"
    if [ -f "$pid_file" ]; then
      pid=$(cat "$pid_file" || true)
      if [ -z "$pid" ]; then
        echo "  $run_dir: PID file empty" | tee -a "$LOG"
        continue
      fi
      if ps -p "$pid" >/dev/null 2>&1; then
        output_age="$(latest_output_age "$run_dir")"
        stale_note=""
        if [[ "$output_age" =~ ^[0-9]+s$ ]]; then
          age_number="${output_age%s}"
          if [ "$age_number" -ge "$RUN_AGENT_STALE_SECONDS" ]; then
            stale_note=" STALE_OUTPUT"
          fi
        fi
        echo "  $run_dir: PID $pid running output_age=$output_age$stale_note" | tee -a "$LOG"
      else
        echo "  $run_dir: PID $pid finished" | tee -a "$LOG"
      fi
      continue
    fi
    if rg -q "EXIT_CODE=" "$run_dir/run-info.txt" 2>/dev/null; then
      echo "  $run_dir: finished (exit recorded)" | tee -a "$LOG"
    else
      echo "  $run_dir: unknown (no pid/exit)" | tee -a "$LOG"
    fi
  done < <(ls -td "$RUNS_DIR"/run_* 2>/dev/null | head -n "$RUN_AGENT_WATCH_LIMIT")

  if [ $found -eq 0 ]; then
    echo "  no runs found" | tee -a "$LOG"
  fi

  sleep 60
 done
