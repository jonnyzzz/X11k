#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNS_DIR="${RUNS_DIR:-$ROOT/runs}"
QUORUM_COUNT="${REVIEW_QUORUM_COUNT:-3}"
MAX_ATTEMPTS="${REVIEW_QUORUM_MAX_ATTEMPTS:-$((QUORUM_COUNT * 3))}"
AGENTS_CSV="${REVIEW_QUORUM_AGENTS:-codex,codex,codex}"
MIN_OUTPUT_BYTES="${REVIEW_QUORUM_MIN_OUTPUT_BYTES:-120}"
RUN_AGENT_TIMEOUT_SECONDS="${REVIEW_RUN_AGENT_TIMEOUT_SECONDS:-600}"
RUN_AGENT_NO_OUTPUT_DIAGNOSTICS_SECONDS="${REVIEW_RUN_AGENT_NO_OUTPUT_DIAGNOSTICS_SECONDS:-120}"
RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS="${REVIEW_RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS:-240}"

usage() {
  cat <<USAGE
Usage:
  scripts/run-review-quorum.sh <prompt-file>
  scripts/run-review-quorum.sh --classify <stdout-file>

Runs bounded review agents until REVIEW_QUORUM_COUNT usable PASS verdicts are
collected, a FAIL verdict is found, or REVIEW_QUORUM_MAX_ATTEMPTS is exhausted.

Environment:
  REVIEW_QUORUM_COUNT=$QUORUM_COUNT
  REVIEW_QUORUM_MAX_ATTEMPTS=$MAX_ATTEMPTS
  REVIEW_QUORUM_AGENTS=$AGENTS_CSV
  REVIEW_QUORUM_MIN_OUTPUT_BYTES=$MIN_OUTPUT_BYTES
  REVIEW_RUN_AGENT_TIMEOUT_SECONDS=$RUN_AGENT_TIMEOUT_SECONDS
  REVIEW_RUN_AGENT_NO_OUTPUT_DIAGNOSTICS_SECONDS=$RUN_AGENT_NO_OUTPUT_DIAGNOSTICS_SECONDS
  REVIEW_RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS=$RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" || "${1:-}" == "help" ]]; then
  usage
  exit 0
fi

CLASSIFY_FILE=""
if [[ "${1:-}" == "--classify" ]]; then
  if [[ "$#" -ne 2 ]]; then
    usage >&2
    exit 2
  fi
  CLASSIFY_FILE="$2"
elif [[ "$#" -ne 1 ]]; then
  usage >&2
  exit 2
fi

PROMPT_FILE=""
if [[ -z "$CLASSIFY_FILE" ]]; then
  PROMPT_FILE="$1"
  PROMPT_FILE="$(cd "$(dirname "$PROMPT_FILE")" && pwd)/$(basename "$PROMPT_FILE")"
  if [[ ! -f "$PROMPT_FILE" ]]; then
    echo "Review prompt not found: $PROMPT_FILE" >&2
    exit 1
  fi
fi

if ! [[ "$QUORUM_COUNT" =~ ^[0-9]+$ ]] || (( QUORUM_COUNT < 1 )); then
  echo "REVIEW_QUORUM_COUNT must be a positive integer: $QUORUM_COUNT" >&2
  exit 2
fi
if ! [[ "$MAX_ATTEMPTS" =~ ^[0-9]+$ ]] || (( MAX_ATTEMPTS < QUORUM_COUNT )); then
  echo "REVIEW_QUORUM_MAX_ATTEMPTS must be an integer >= REVIEW_QUORUM_COUNT: $MAX_ATTEMPTS" >&2
  exit 2
fi
if ! [[ "$MIN_OUTPUT_BYTES" =~ ^[0-9]+$ ]]; then
  echo "REVIEW_QUORUM_MIN_OUTPUT_BYTES must be a non-negative integer: $MIN_OUTPUT_BYTES" >&2
  exit 2
fi

IFS=',' read -ra AGENTS <<< "$AGENTS_CSV"
VALID_AGENTS=()
for agent in "${AGENTS[@]}"; do
  agent="$(printf '%s' "$agent" | tr -d '[:space:]')"
  [[ -n "$agent" ]] || continue
  if ! [[ "$agent" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
    echo "Invalid review agent name: $agent" >&2
    exit 2
  fi
  VALID_AGENTS+=("$agent")
done
if (( ${#VALID_AGENTS[@]} == 0 )); then
  echo "REVIEW_QUORUM_AGENTS did not contain any valid agents: $AGENTS_CSV" >&2
  exit 2
fi

mkdir -p "$RUNS_DIR"
SUMMARY_FILE="$RUNS_DIR/review-quorum-$(date -u +%Y%m%d-%H%M%S)-$$.txt"

latest_run_dir() {
  local latest="$RUNS_DIR/latest"
  if [[ -e "$latest" ]]; then
    (cd "$latest" && pwd -P) 2>/dev/null || true
  fi
}

run_dir_for_attempt() {
  local attempt_token="$1"
  local run_dir
  while IFS= read -r run_dir; do
    [[ -n "$run_dir" ]] || continue
    if grep -Fqx "ATTEMPT_TOKEN=$attempt_token" "$run_dir/run-info.txt" 2>/dev/null; then
      printf '%s\n' "$run_dir"
      return
    fi
  done < <(ls -td "$RUNS_DIR"/run_* 2>/dev/null | head -n 200)
}

stdout_verdict() {
  local stdout_file="$1"
  if [[ ! -f "$stdout_file" ]]; then
    echo "EMPTY"
    return
  fi
  local bytes
  bytes="$(wc -c < "$stdout_file" 2>/dev/null || echo 0)"
  local verdict
  verdict="$(awk '
    function trim(s) {
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", s)
      return s
    }
    BEGIN {
      result = ""
      sawVerdictLine = 0
    }
    {
      line = $0
      gsub(/[*_`#]/, "", line)
      line = trim(line)
      if (line == "" || sawVerdictLine) next
      sawVerdictLine = 1
      lower = tolower(line)
      sub(/^(overall[[:space:]]+result|result|verdict)[[:space:]]*[:=-][[:space:]]*/, "", lower)
      if (lower ~ /^fail[.]?$/) {
        result = "FAIL"
        exit 0
      }
      if (lower ~ /^pass[.]?$/) {
        result = "PASS"
        exit 0
      }
      result = "NO_VERDICT"
      exit 0
    }
    END {
      if (result == "") {
        print "NO_VERDICT"
      } else {
        print result
      }
    }
  ' "$stdout_file")"
  if [[ "$verdict" == "FAIL" ]]; then
    echo "$verdict"
    return
  fi
  if [[ "$verdict" == "PASS" ]]; then
    echo "$verdict"
    return
  fi
  if (( bytes < MIN_OUTPUT_BYTES )); then
    echo "EMPTY"
  else
    echo "$verdict"
  fi
}

run_matches_attempt() {
  local run_dir="$1"
  local expected_agent="$2"
  local expected_prompt="$3"
  local expected_token="$4"
  local run_info="$run_dir/run-info.txt"
  local copied_prompt="$run_dir/prompt.md"
  if [[ ! -f "$run_info" || ! -f "$copied_prompt" ]]; then
    return 1
  fi
  grep -Fqx "AGENT=$expected_agent" "$run_info" || return 1
  grep -Fqx "CWD=$ROOT" "$run_info" || return 1
  grep -Fqx "ATTEMPT_TOKEN=$expected_token" "$run_info" || return 1
  if grep -qE '^RESTART_(OF|ROOT)=' "$run_info"; then
    return 1
  fi
  local expected_bytes actual_bytes
  expected_bytes="$(wc -c < "$expected_prompt" | tr -d '[:space:]')"
  actual_bytes="$(wc -c < "$copied_prompt" | tr -d '[:space:]')"
  if (( actual_bytes < expected_bytes )); then
    return 1
  fi
  tail -c "$expected_bytes" "$copied_prompt" | cmp -s "$expected_prompt" -
}

if [[ -n "$CLASSIFY_FILE" ]]; then
  stdout_verdict "$CLASSIFY_FILE"
  exit 0
fi

record() {
  printf '%s\n' "$*" | tee -a "$SUMMARY_FILE"
}

record "REVIEW_QUORUM_START_UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
record "PROMPT=$PROMPT_FILE"
record "QUORUM_COUNT=$QUORUM_COUNT"
record "MAX_ATTEMPTS=$MAX_ATTEMPTS"
record "AGENTS=$AGENTS_CSV"
record "SUMMARY=$SUMMARY_FILE"

passes=0
attempt=0

while (( passes < QUORUM_COUNT && attempt < MAX_ATTEMPTS )); do
  attempt=$((attempt + 1))
  agent_index=$(((attempt - 1) % ${#VALID_AGENTS[@]}))
  agent="${VALID_AGENTS[$agent_index]}"
  attempt_token="review-quorum-$$-$attempt-$(date +%s)"
  record "ATTEMPT_${attempt}_AGENT=$agent"
  record "ATTEMPT_${attempt}_TOKEN=$attempt_token"
  previous_run_dir="$(latest_run_dir)"
  record "ATTEMPT_${attempt}_PREVIOUS_RUN_DIR=$previous_run_dir"

  set +e
  env \
    RUN_AGENT_TIMEOUT_SECONDS="$RUN_AGENT_TIMEOUT_SECONDS" \
    RUN_AGENT_NO_OUTPUT_DIAGNOSTICS_SECONDS="$RUN_AGENT_NO_OUTPUT_DIAGNOSTICS_SECONDS" \
    RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS="$RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS" \
    RUN_AGENT_ATTEMPT_TOKEN="$attempt_token" \
    "$ROOT/ralph-loop.sh" agent "$agent" "$PROMPT_FILE"
  status="$?"
  set -e

  run_dir="$(run_dir_for_attempt "$attempt_token")"
  record "ATTEMPT_${attempt}_EXIT_CODE=$status"
  record "ATTEMPT_${attempt}_RUN_DIR=$run_dir"

  if [[ -z "$run_dir" || ! -d "$run_dir" ]]; then
    record "ATTEMPT_${attempt}_RESULT=UNUSABLE_NO_RUN_DIR"
    continue
  fi
  if [[ -n "$previous_run_dir" && "$run_dir" == "$previous_run_dir" ]]; then
    record "ATTEMPT_${attempt}_RESULT=UNUSABLE_NO_NEW_RUN_DIR"
    continue
  fi
  if ! run_matches_attempt "$run_dir" "$agent" "$PROMPT_FILE" "$attempt_token"; then
    record "ATTEMPT_${attempt}_RESULT=UNUSABLE_RUN_PROVENANCE"
    continue
  fi

  verdict_file="$run_dir/agent-stdout.txt"
  if [[ "$agent" == "codex" && -s "$run_dir/agent-final.txt" ]]; then
    verdict_file="$run_dir/agent-final.txt"
  fi
  record "ATTEMPT_${attempt}_VERDICT_FILE=$verdict_file"
  verdict="$(stdout_verdict "$verdict_file")"
  record "ATTEMPT_${attempt}_VERDICT=$verdict"

  case "$verdict" in
    FAIL)
      record "ATTEMPT_${attempt}_RESULT=FAIL"
      record "REVIEW_QUORUM_RESULT=FAIL"
      record "REVIEW_QUORUM_END_UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      echo
      echo "== failing review verdict =="
      sed -n '1,220p' "$verdict_file" || true
      exit 1
      ;;
    PASS)
      if [[ "$status" -ne 0 ]]; then
        record "ATTEMPT_${attempt}_RESULT=RETRY_AGENT_EXIT_$status"
        continue
      fi
      passes=$((passes + 1))
      record "ATTEMPT_${attempt}_RESULT=PASS"
      record "PASS_COUNT=$passes"
      ;;
    *)
      if [[ "$status" -ne 0 ]]; then
        record "ATTEMPT_${attempt}_RESULT=RETRY_AGENT_EXIT_$status"
        continue
      fi
      record "ATTEMPT_${attempt}_RESULT=RETRY_UNUSABLE_$verdict"
      ;;
  esac
done

if (( passes >= QUORUM_COUNT )); then
  record "REVIEW_QUORUM_RESULT=PASS"
  record "REVIEW_QUORUM_END_UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  exit 0
fi

record "REVIEW_QUORUM_RESULT=INCOMPLETE"
record "REVIEW_QUORUM_END_UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "Review quorum incomplete: collected $passes/$QUORUM_COUNT PASS verdicts after $attempt attempts." >&2
echo "Summary: $SUMMARY_FILE" >&2
exit 124
