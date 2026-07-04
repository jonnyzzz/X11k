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
IDEA_PROJECT_CONTAINER="${IDEA_PROJECT_CONTAINER:-/workspace/jonnyzzz-x}"
READY_TIMEOUT_SECONDS="${READY_TIMEOUT_SECONDS:-180}"
READY_SETTLE_SECONDS="${READY_SETTLE_SECONDS:-8}"
CAPTURE_TIMEOUT_SECONDS="${CAPTURE_TIMEOUT_SECONDS:-120}"
BUILD_TIMEOUT_SECONDS="${BUILD_TIMEOUT_SECONDS:-900}"
ALLOW_NPX_PLAYWRIGHT="${ALLOW_NPX_PLAYWRIGHT:-0}"

if (( DISPLAY_NUMBER < 0 )); then
  echo "PORT must be 6000 or greater so DISPLAY can be derived; got PORT=$PORT" >&2
  exit 2
fi

intellij_screenshot_ready() {
  local text="$1"
  local svg="$2"
  [[ "$text" == *"Mapped windows:"* &&
    "$text" == *"Content window"* &&
    "$svg" == *"framebuffer-image"* &&
    "$text" != *"Download SDK"* &&
    "$text" != *"Download JDK"* ]]
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
  assert_readiness "ready content framebuffer" 1 "$ready_text" "$ready_svg"
  assert_readiness "missing mapped windows" 0 "Content window" "$ready_svg"
  assert_readiness "missing content window" 0 "Mapped windows: 3" "$ready_svg"
  assert_readiness "missing framebuffer" 0 "$ready_text" '<svg></svg>'
  assert_readiness "download sdk modal" 0 $'Mapped windows: 4\nContent window\nDownload SDK?' "$ready_svg"
  assert_readiness "download jdk modal" 0 $'Mapped windows: 4\nContent window\nDownload JDK' "$ready_svg"
  assert_project_export_guard "default runs export" 1 "$ROOT/runs/intellij-readme-screenshot/project"
  assert_project_export_guard "relative runs export" 1 "runs/intellij-readme-screenshot/project"
  assert_project_export_guard "build tmp export" 1 "$ROOT/build/tmp/intellij-readme-screenshot/project"
  assert_project_export_guard "repo root rejected" 0 "$ROOT"
  assert_project_export_guard "parent traversal rejected" 0 "$ROOT/runs/../../outside-project"
  assert_project_export_guard "outside tmp rejected" 0 "/tmp/intellij-readme-screenshot-project"
}

if [[ "${INTELLIJ_README_SCREENSHOT_SELF_TEST:-0}" == "1" ]]; then
  run_readiness_self_test
  exit 0
fi

TIMEOUT_BIN="${TIMEOUT_BIN:-}"
if [[ -z "$TIMEOUT_BIN" ]]; then
  for candidate in /opt/homebrew/bin/timeout gtimeout timeout; do
    if command -v "$candidate" >/dev/null 2>&1; then
      TIMEOUT_BIN="$(command -v "$candidate")"
      break
    fi
  done
fi
if [[ -z "$TIMEOUT_BIN" ]]; then
  echo "No timeout command found. Install coreutils or set TIMEOUT_BIN." >&2
  exit 2
fi

mkdir -p "$RUN_DIR" "$(dirname "$OUT")"
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

run_timed() {
  local seconds="$1"
  shift
  "$TIMEOUT_BIN" "$seconds" "$@"
}

prepare_project_export() {
  PROJECT_EXPORT_DIR="$(guarded_project_export_dir "$PROJECT_EXPORT_DIR")"
  rm -rf -- "$PROJECT_EXPORT_DIR"
  mkdir -p "$PROJECT_EXPORT_DIR"
  while IFS= read -r -d '' path; do
    mkdir -p "$PROJECT_EXPORT_DIR/$(dirname "$path")"
    cp -pP "$ROOT/$path" "$PROJECT_EXPORT_DIR/$path"
  done < <(git -C "$ROOT" ls-files -z)
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
    docker exec "$IDEA_CONTAINER" sh -lc 'tail -200 /tmp/idea-run-readme.log 2>/dev/null || true' 2>/dev/null || true
    echo
    echo "---- IntelliJ idea.log ----"
    docker exec "$IDEA_CONTAINER" sh -lc 'tail -240 /tmp/idea-log/idea.log 2>/dev/null || true' 2>/dev/null || true
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

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  GRADLE_TIMEOUT_SECONDS="$BUILD_TIMEOUT_SECONDS" "$ROOT/scripts/run-supervised.sh" gradle installDist dockerBuildX11Client
fi

docker rm -f "$IDEA_CONTAINER" >/dev/null 2>&1 || true
prepare_project_export

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

docker run -d --name "$IDEA_CONTAINER" \
  -v "$PROJECT_EXPORT_DIR:$IDEA_PROJECT_CONTAINER" \
  "$IMAGE" \
  sh -lc "DISPLAY=host.docker.internal:$DISPLAY_NUMBER IDEA_PROJECT=$IDEA_PROJECT_CONTAINER IDEA_TRUST_PROJECT=true run-intellij >/tmp/idea-run-readme.log 2>&1" \
  >"$RUN_DIR/container.id"

ready=0
deadline=$((SECONDS + READY_TIMEOUT_SECONDS))
while (( SECONDS < deadline )); do
  text="$(curl -fsS "http://127.0.0.1:$PORT/text.txt" 2>/dev/null || true)"
  svg="$(curl -fsS "http://127.0.0.1:$PORT/screen.svg" 2>/dev/null || true)"
  if intellij_screenshot_ready "$text" "$svg"; then
    ready=1
    break
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
