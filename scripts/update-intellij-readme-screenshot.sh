#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${PORT:-6000}"
DISPLAY_NUMBER=$((PORT - 6000))
SERVER_WIDTH="${SERVER_WIDTH:-3840}"
SERVER_HEIGHT="${SERVER_HEIGHT:-2160}"
SERVER_DPI="${SERVER_DPI:-100}"
IMAGE="${X_INTELLIJ_IMAGE:-jonnyzzz-x/x11-client:latest}"
OUT="${OUT:-$ROOT/docs/images/intellij-demo-renderer.png}"
RUN_DIR="${RUN_DIR:-$ROOT/runs/intellij-readme-screenshot}"
PROJECT_EXPORT_DIR="${PROJECT_EXPORT_DIR:-$RUN_DIR/project}"
IDEA_CACHE_DIR="${IDEA_CACHE_DIR:-$RUN_DIR/idea-cache}"
IDEA_PROJECT_CONTAINER="${IDEA_PROJECT_CONTAINER:-/workspace/jonnyzzz-x}"
IDEA_OPEN_FILE_CONTAINER="${IDEA_OPEN_FILE_CONTAINER:-$IDEA_PROJECT_CONTAINER/README.md}"
READY_TIMEOUT_SECONDS="${READY_TIMEOUT_SECONDS:-600}"
READY_SETTLE_SECONDS="${READY_SETTLE_SECONDS:-8}"
CAPTURE_TIMEOUT_SECONDS="${CAPTURE_TIMEOUT_SECONDS:-120}"
BUILD_TIMEOUT_SECONDS="${BUILD_TIMEOUT_SECONDS:-900}"
ALLOW_NPX_PLAYWRIGHT="${ALLOW_NPX_PLAYWRIGHT:-0}"
IDEA_PROJECT_COLOR_INDEX="${IDEA_PROJECT_COLOR_INDEX:-0}"

if (( DISPLAY_NUMBER < 0 )); then
  echo "PORT must be 6000 or greater so DISPLAY can be derived; got PORT=$PORT" >&2
  exit 2
fi
if [[ ! "$IDEA_PROJECT_COLOR_INDEX" =~ ^[0-8]$ ]]; then
  echo "IDEA_PROJECT_COLOR_INDEX must be an integer from 0 through 8; got $IDEA_PROJECT_COLOR_INDEX" >&2
  exit 2
fi

intellij_screenshot_ready() {
  local text="$1"
  local svg="$2"
  [[ "$text" == *"Mapped windows:"* &&
    "$text" == *"Content window"* &&
    "$svg" == *"framebuffer-image"* &&
    "$text" != *"Download SDK"* &&
    "$text" != *"Download JDK"* &&
    "$text" != *"script launcher"* &&
    "$text" != *"ide.script.launcher.used"* &&
    "$svg" != *"script launcher"* &&
    "$svg" != *"ide.script.launcher.used"* ]]
}

intellij_project_workspace_seed() {
  cat <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="ProjectColorInfo">{
  &quot;associatedIndex&quot;: $IDEA_PROJECT_COLOR_INDEX,
  &quot;fromUser&quot;: false
}</component>
</project>
EOF
}

assert_readiness() {
  local name="$1"
  local expected="$2"
  local text="$3"
  local svg="$4"
  local actual
  if intellij_screenshot_ready "$text" "$svg"; then
    actual=1
  else
    actual=0
  fi
  if [[ "$actual" != "$expected" ]]; then
    echo "readiness self-test failed: $name expected $expected got $actual" >&2
    exit 1
  fi
}

canonical_dir() {
  local dir="$1"
  (cd "$dir" && pwd -P)
}

absolute_path() {
  local path="$1"
  case "$path" in
    /*) printf '%s\n' "$path" ;;
    *) printf '%s\n' "$ROOT/$path" ;;
  esac
}

guarded_project_export_dir() {
  local requested raw parent base runs_root build_tmp_root parent_real candidate
  requested="$1"
  raw="$(absolute_path "$requested")"
  raw="${raw%/}"
  parent="$(dirname "$raw")"
  base="$(basename "$raw")"
  if [[ -z "$base" || "$base" == "." || "$base" == ".." ]]; then
    echo "Unsafe PROJECT_EXPORT_DIR: $requested" >&2
    return 1
  fi
  mkdir -p "$ROOT/runs" "$ROOT/build/tmp"
  runs_root="$(canonical_dir "$ROOT/runs")"
  build_tmp_root="$(canonical_dir "$ROOT/build/tmp")"
  case "$raw" in
    "$ROOT/runs/"*|"$ROOT/build/tmp/"*) ;;
    *)
      echo "PROJECT_EXPORT_DIR must be under $ROOT/runs or $ROOT/build/tmp; got $requested" >&2
      return 1
      ;;
  esac
  mkdir -p "$parent"
  parent_real="$(canonical_dir "$parent")"
  candidate="$parent_real/$base"
  case "$candidate" in
    "$runs_root/"*|"$build_tmp_root/"*) ;;
    *)
      echo "PROJECT_EXPORT_DIR resolves outside guarded run/build roots: $candidate" >&2
      return 1
      ;;
  esac
  if [[ -L "$candidate" ]]; then
    echo "PROJECT_EXPORT_DIR must not be a symlink: $candidate" >&2
    return 1
  fi
  printf '%s\n' "$candidate"
}

assert_project_export_guard() {
  local name="$1"
  local expected="$2"
  local path="$3"
  local actual
  if guarded_project_export_dir "$path" >/dev/null 2>&1; then
    actual=1
  else
    actual=0
  fi
  if [[ "$actual" != "$expected" ]]; then
    echo "project export guard self-test failed: $name expected $expected got $actual" >&2
    exit 1
  fi
}

run_readiness_self_test() {
  local ready_text=$'Screen: 3840 x 2160\nMapped windows: 3\n- 0x200001 label="Content window"'
  local ready_svg='<svg><image class="framebuffer-image backing-pixmap-image"/></svg>'
  local workspace_seed
  assert_readiness "ready content framebuffer" 1 "$ready_text" "$ready_svg"
  assert_readiness "missing mapped windows" 0 "Content window" "$ready_svg"
  assert_readiness "missing content window" 0 "Mapped windows: 3" "$ready_svg"
  assert_readiness "missing framebuffer" 0 "$ready_text" '<svg></svg>'
  assert_readiness "download sdk modal" 0 $'Mapped windows: 4\nContent window\nDownload SDK?' "$ready_svg"
  assert_readiness "download jdk modal" 0 $'Mapped windows: 4\nContent window\nDownload JDK' "$ready_svg"
  assert_readiness "script launcher notification" 0 $'Mapped windows: 4\nContent window\nThe IDE seems to be launched with a script launcher' "$ready_svg"
  assert_readiness "script launcher registry key" 0 $'Mapped windows: 4\nContent window\nide.script.launcher.used' "$ready_svg"
  assert_project_export_guard "default runs export" 1 "$ROOT/runs/intellij-readme-screenshot/project"
  assert_project_export_guard "relative runs export" 1 "runs/intellij-readme-screenshot/project"
  assert_project_export_guard "build tmp export" 1 "$ROOT/build/tmp/intellij-readme-screenshot/project"
  assert_project_export_guard "repo root rejected" 0 "$ROOT"
  assert_project_export_guard "parent traversal rejected" 0 "$ROOT/runs/../../outside-project"
  assert_project_export_guard "outside tmp rejected" 0 "/tmp/intellij-readme-screenshot-project"
  workspace_seed="$(intellij_project_workspace_seed)"
  [[ "$workspace_seed" == *'component name="ProjectColorInfo"'* ]]
  [[ "$workspace_seed" == *"associatedIndex&quot;: $IDEA_PROJECT_COLOR_INDEX"* ]]
}

timeout_bin() {
  if [[ "${INTELLIJ_README_SCREENSHOT_FORCE_LOCAL_TIMEOUT:-0}" == "1" ]]; then
    return 1
  fi
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

terminate_timed_child() {
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

TIMEOUT_BIN="${TIMEOUT_BIN:-}"
if [[ -z "$TIMEOUT_BIN" ]]; then
  TIMEOUT_BIN="$(timeout_bin || true)"
fi

mkdir -p "$RUN_DIR" "$IDEA_CACHE_DIR" "$(dirname "$OUT")"
SERVER_LOG="$RUN_DIR/server.log"
IDEA_CONTAINER="x-readme-idea-$PORT"
SERVER_PID=""

cleanup() {
  docker rm -f "$IDEA_CONTAINER" >/dev/null 2>&1 || true
  if [[ -n "$SERVER_PID" ]]; then
    kill "$SERVER_PID" >/dev/null 2>&1 || true
    wait "$SERVER_PID" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

container_file() {
  local path="$1"
  local tmp
  if [[ "$(docker inspect "$IDEA_CONTAINER" --format '{{.State.Running}}' 2>/dev/null || true)" == "true" ]]; then
    docker exec "$IDEA_CONTAINER" sh -lc "cat '$path' 2>/dev/null || true" 2>/dev/null || true
    return 0
  fi
  tmp="$(mktemp "$RUN_DIR/container-file.XXXXXX")" || return 0
  if docker cp "$IDEA_CONTAINER:$path" "$tmp" 2>/dev/null; then
    cat "$tmp"
  fi
  rm -f "$tmp"
}

run_timed() {
  local seconds="$1"
  shift
  local pid start now elapsed status
  if [[ "$seconds" == "0" ]]; then
    "$@"
  elif [[ -n "$TIMEOUT_BIN" ]]; then
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
        terminate_timed_child "$pid"
        wait "$pid" 2>/dev/null || true
        return 124
      fi
    done
    wait "$pid" || status=$?
    return "${status:-0}"
  fi
}

run_timeout_self_test() {
  local status
  TIMEOUT_BIN=""
  set +e
  run_timed 1 sh -c 'sleep 30'
  status="$?"
  set -e
  if [[ "$status" -ne 124 ]]; then
    echo "timeout fallback self-test failed: expected 124 got $status" >&2
    exit 1
  fi
}

if [[ "${INTELLIJ_README_SCREENSHOT_SELF_TEST:-0}" == "1" ]]; then
  run_readiness_self_test
  exit 0
fi

if [[ "${INTELLIJ_README_SCREENSHOT_TIMEOUT_SELF_TEST:-0}" == "1" ]]; then
  run_timeout_self_test
  exit 0
fi

prepare_project_export() {
  PROJECT_EXPORT_DIR="$(guarded_project_export_dir "$PROJECT_EXPORT_DIR")"
  rm -rf -- "$PROJECT_EXPORT_DIR"
  mkdir -p "$PROJECT_EXPORT_DIR"
  while IFS= read -r -d '' path; do
    mkdir -p "$PROJECT_EXPORT_DIR/$(dirname "$path")"
    cp -pP "$ROOT/$path" "$PROJECT_EXPORT_DIR/$path"
  done < <(git -C "$ROOT" ls-files -z)
  mkdir -p "$PROJECT_EXPORT_DIR/.idea"
  intellij_project_workspace_seed > "$PROJECT_EXPORT_DIR/.idea/workspace.xml"
}

diagnose_readiness_failure() {
  {
    echo "==== IntelliJ README screenshot diagnostics ===="
    echo "Run dir: $RUN_DIR"
    echo "Server log: $SERVER_LOG"
    echo
    echo "---- /text.txt snapshot ----"
    curl -fsS "http://127.0.0.1:$PORT/text.txt" 2>/dev/null | sed -n '1,160p' || true
    echo
    echo "---- /screen.svg markers ----"
    curl -fsS "http://127.0.0.1:$PORT/screen.svg" 2>/dev/null \
      | grep -Eo 'class="[^"]*framebuffer-image[^"]*"|label="[^"]*"' \
      | sed -n '1,120p' || true
    echo
    echo "---- server.log ----"
    sed -n '1,220p' "$SERVER_LOG" 2>/dev/null || true
    echo
    echo "---- Docker container state ----"
    docker ps -a --filter "name=$IDEA_CONTAINER" || true
    docker inspect "$IDEA_CONTAINER" --format '{{json .State}}' 2>/dev/null || true
    echo
    echo "---- docker logs ----"
    docker logs "$IDEA_CONTAINER" 2>&1 | tail -200 || true
    echo
    echo "---- IntelliJ run log ----"
    container_file /tmp/idea-run-readme.log | tail -200 || true
    echo
    echo "---- IntelliJ idea.log ----"
    container_file /tmp/idea-log/idea.log | tail -240 || true
    echo
    echo "---- IntelliJ JVM threads ----"
    docker exec "$IDEA_CONTAINER" sh -lc 'pid=$(pgrep -f "com.intellij.idea.Main" | head -1); if [ -n "$pid" ]; then jcmd "$pid" Thread.print | sed -n "1,260p"; else jps -lm || true; fi' 2>/dev/null || true
    echo
    echo "---- X server JVM threads ----"
    if [[ -n "$SERVER_PID" ]]; then
      run_timed 30 jcmd "$SERVER_PID" Thread.print 2>/dev/null | sed -n '1,260p' || true
    fi
  } >&2
}

dismiss_intellij_readme_notifications() {
  local text content_line local_x local_y root_x root_y
  text="$(curl -fsS "http://127.0.0.1:$PORT/text.txt" 2>/dev/null || true)"
  content_line="$(printf '%s\n' "$text" | grep 'label="Content window"' | head -1 || true)"
  if [[ ! "$content_line" =~ geometry=([-0-9]+),([-0-9]+)[[:space:]]([0-9]+)x([0-9]+) ]]; then
    return 0
  fi

  # Keep README screenshots focused on renderer state, not transient IntelliJ first-run notifications.
  local points=(940 395 1030 725)
  local index
  for ((index = 0; index < ${#points[@]}; index += 2)); do
    local_x="${points[index]}"
    local_y="${points[index + 1]}"
    root_x=$((BASH_REMATCH[1] + local_x))
    root_y=$((BASH_REMATCH[2] + local_y))
    curl -fsS -X POST \
      -d "x=$root_x&y=$root_y&button=left" \
      "http://127.0.0.1:$PORT/input/click" >/dev/null 2>&1 || true
    sleep 1
  done
}

assert_intellij_readme_launcher_state() {
  local run_log idea_log disabled_plugins text svg
  run_log="$(container_file /tmp/idea-run-readme.log)"
  idea_log="$(container_file /tmp/idea-log/idea.log | tail -400)"
  disabled_plugins="$(container_file /tmp/idea-config/disabled_plugins.txt)"
  text="$(curl -fsS "http://127.0.0.1:$PORT/text.txt" 2>/dev/null || true)"
  svg="$(curl -fsS "http://127.0.0.1:$PORT/screen.svg" 2>/dev/null || true)"
  if ! printf '%s\n' "$run_log" | grep -qx '\[run-intellij\] launcher=/opt/idea/bin/idea'; then
    echo "IntelliJ README screenshot must use the native IDEA launcher; rebuild jonnyzzz-x/x11-client:latest if this marker is missing." >&2
    diagnose_readiness_failure
    exit 1
  fi
  if ! printf '%s\n' "$disabled_plugins" | grep -qx 'org.intellij.plugins.markdown'; then
    echo "IntelliJ README screenshot must disable the Markdown/JCEF preview in its isolated config." >&2
    diagnose_readiness_failure
    exit 1
  fi
  if ! printf '%s\n' "$idea_log" | grep -q 'fileOpened README.md'; then
    echo "IntelliJ README screenshot did not open README.md in the isolated editor." >&2
    diagnose_readiness_failure
    exit 1
  fi
  if [[ "$run_log" == *"ide.script.launcher.used"* ||
        "$idea_log" == *"ide.script.launcher.used"* ||
        "$text" == *"script launcher"* ||
        "$svg" == *"script launcher"* ]]; then
    echo "IntelliJ README screenshot detected the script-launcher warning; refusing to refresh $OUT." >&2
    diagnose_readiness_failure
    exit 1
  fi
  if [[ "$text" == *"Embedded Browser is suspended"* ||
        "$svg" == *"Embedded Browser is suspended"* ]]; then
    echo "IntelliJ README screenshot still contains the suspended-browser warning; refusing to refresh $OUT." >&2
    diagnose_readiness_failure
    exit 1
  fi
}

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  GRADLE_TIMEOUT_SECONDS="$BUILD_TIMEOUT_SECONDS" "$ROOT/scripts/run-supervised.sh" gradle installDist dockerBuildX11Client
fi

docker rm -f "$IDEA_CONTAINER" >/dev/null 2>&1 || true
prepare_project_export

echo "Starting X server on port $PORT for IntelliJ README screenshot."
"$ROOT/build/install/x/bin/x" \
  --host 0.0.0.0 \
  --port "$PORT" \
  --width "$SERVER_WIDTH" \
  --height "$SERVER_HEIGHT" \
  --dpi "$SERVER_DPI" \
  >"$SERVER_LOG" 2>&1 &
SERVER_PID="$!"

for _ in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:$PORT/text.txt" >/dev/null 2>&1; then
    break
  fi
  sleep 0.5
done

echo "Launching IntelliJ README screenshot container $IDEA_CONTAINER with native launcher."
docker run -d --name "$IDEA_CONTAINER" \
  -v "$PROJECT_EXPORT_DIR:$IDEA_PROJECT_CONTAINER" \
  -v "$IDEA_CACHE_DIR:/tmp/idea-cache" \
  -e IDEA_CACHE_DIR=/tmp/idea-cache \
  -e IDEA_OPEN_FILE="$IDEA_OPEN_FILE_CONTAINER" \
  "$IMAGE" \
  sh -lc "mkdir -p /tmp/idea-config && printf '%s\\n' org.intellij.plugins.markdown >/tmp/idea-config/disabled_plugins.txt && DISPLAY=host.docker.internal:$DISPLAY_NUMBER IDEA_PROJECT=$IDEA_PROJECT_CONTAINER IDEA_TRUST_PROJECT=true IDEA_LAUNCHER=native run-intellij >/tmp/idea-run-readme.log 2>&1" \
  >"$RUN_DIR/container.id"

ready=0
deadline=$((SECONDS + READY_TIMEOUT_SECONDS))
last_progress=0
while (( SECONDS < deadline )); do
  text="$(curl -fsS "http://127.0.0.1:$PORT/text.txt" 2>/dev/null || true)"
  svg="$(curl -fsS "http://127.0.0.1:$PORT/screen.svg" 2>/dev/null || true)"
  if intellij_screenshot_ready "$text" "$svg"; then
    ready=1
    break
  fi
  container_state="$(docker inspect "$IDEA_CONTAINER" --format '{{.State.Running}} {{.State.ExitCode}}' 2>/dev/null || true)"
  if [[ "$container_state" != true\ * ]]; then
    echo "IntelliJ README screenshot container exited before readiness: ${container_state:-missing}" >&2
    diagnose_readiness_failure
    exit 1
  fi
  if (( SECONDS - last_progress >= 15 )); then
    mapped="$(printf '%s\n' "$text" | awk -F': ' '/^Mapped windows:/ { print $2; exit }')"
    run_tail="$(docker exec "$IDEA_CONTAINER" sh -lc 'tr "\r" "\n" < /tmp/idea-run-readme.log 2>/dev/null | sed "/^[[:space:]]*$/d" | tail -1 | cut -c1-160 || true' 2>/dev/null || true)"
    echo "Waiting for IntelliJ screenshot readiness: elapsed=$((READY_TIMEOUT_SECONDS - (deadline - SECONDS)))s mapped=${mapped:-unknown} lastLog=${run_tail:-none}"
    last_progress="$SECONDS"
  fi
  sleep 2
done

if [[ "$ready" != "1" ]]; then
  echo "IntelliJ page did not become screenshot-ready within ${READY_TIMEOUT_SECONDS}s." >&2
  echo "Server log: $SERVER_LOG" >&2
  diagnose_readiness_failure
  exit 1
fi

if (( READY_SETTLE_SECONDS > 0 )); then
  sleep "$READY_SETTLE_SECONDS"
fi
dismiss_intellij_readme_notifications
assert_intellij_readme_launcher_state

CAPTURE_JS="$RUN_DIR/capture-page.js"
cat >"$CAPTURE_JS" <<'NODE'
const { chromium } = require("playwright");

const url = process.env.SCREENSHOT_URL;
const out = process.env.SCREENSHOT_OUT;

(async () => {
  const port = new URL(url).port;
  const browser = await chromium.launch({
    headless: true,
    args: port ? [`--explicitly-allowed-ports=${port}`] : [],
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 1600, height: 1000 },
      deviceScaleFactor: 1,
    });
    await page.goto(url, { waitUntil: "networkidle", timeout: 60000 });
    await page.waitForSelector(".framebuffer-image", { timeout: 60000 });
    await page.screenshot({ path: out, fullPage: true });
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error(error && error.stack ? error.stack : String(error));
  process.exit(1);
});
NODE

if node -e 'require.resolve("playwright")' >/dev/null 2>&1; then
  PLAYWRIGHT_NODE_PATH="$(node -e 'const path = require("path"); const pkg = require.resolve("playwright/package.json"); process.stdout.write(path.dirname(path.dirname(pkg)));')"
  run_timed "$CAPTURE_TIMEOUT_SECONDS" env \
    NODE_PATH="$PLAYWRIGHT_NODE_PATH" \
    SCREENSHOT_URL="http://127.0.0.1:$PORT/" \
    SCREENSHOT_OUT="$OUT" \
    node "$CAPTURE_JS"
elif [[ "$ALLOW_NPX_PLAYWRIGHT" == "1" ]]; then
  PLAYWRIGHT_RUN_DIR="$RUN_DIR/playwright-node"
  mkdir -p "$PLAYWRIGHT_RUN_DIR"
  run_timed 600 npm --prefix "$PLAYWRIGHT_RUN_DIR" install --no-audit --no-fund playwright
  run_timed 600 node "$PLAYWRIGHT_RUN_DIR/node_modules/playwright/cli.js" install chromium
  run_timed "$CAPTURE_TIMEOUT_SECONDS" env \
    NODE_PATH="$PLAYWRIGHT_RUN_DIR/node_modules" \
    SCREENSHOT_URL="http://127.0.0.1:$PORT/" \
    SCREENSHOT_OUT="$OUT" \
    node "$CAPTURE_JS"
else
  cat >&2 <<EOF
Playwright is required to refresh the full-page README screenshot.

Install it in a temporary npx environment by rerunning:

  ALLOW_NPX_PLAYWRIGHT=1 $0

Or make a project/user playwright package available to Node.
EOF
  exit 2
fi

echo "Updated $OUT"
