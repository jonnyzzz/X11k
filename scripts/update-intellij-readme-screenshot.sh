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
READY_TIMEOUT_SECONDS="${READY_TIMEOUT_SECONDS:-180}"
CAPTURE_TIMEOUT_SECONDS="${CAPTURE_TIMEOUT_SECONDS:-120}"
BUILD_TIMEOUT_SECONDS="${BUILD_TIMEOUT_SECONDS:-900}"
ALLOW_NPX_PLAYWRIGHT="${ALLOW_NPX_PLAYWRIGHT:-0}"

if (( DISPLAY_NUMBER < 0 )); then
  echo "PORT must be 6000 or greater so DISPLAY can be derived; got PORT=$PORT" >&2
  exit 2
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

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  run_timed "$BUILD_TIMEOUT_SECONDS" "$ROOT/gradlew" --no-daemon installDist dockerBuildX11Client
fi

docker rm -f "$IDEA_CONTAINER" >/dev/null 2>&1 || true

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
  -v "$ROOT:/workspace/jonnyzzz-x" \
  "$IMAGE" \
  sh -lc "DISPLAY=host.docker.internal:$DISPLAY_NUMBER IDEA_PROJECT=/workspace/jonnyzzz-x IDEA_TRUST_PROJECT=true run-intellij >/tmp/idea-run-readme.log 2>&1" \
  >"$RUN_DIR/container.id"

ready=0
deadline=$((SECONDS + READY_TIMEOUT_SECONDS))
while (( SECONDS < deadline )); do
  text="$(curl -fsS "http://127.0.0.1:$PORT/text.txt" 2>/dev/null || true)"
  svg="$(curl -fsS "http://127.0.0.1:$PORT/screen.svg" 2>/dev/null || true)"
  if [[ "$text" == *"IntelliJ"* && "$svg" == *"framebuffer-image"* ]]; then
    ready=1
    break
  fi
  sleep 2
done

if [[ "$ready" != "1" ]]; then
  echo "IntelliJ page did not become screenshot-ready within ${READY_TIMEOUT_SECONDS}s." >&2
  echo "Server log: $SERVER_LOG" >&2
  docker logs "$IDEA_CONTAINER" >&2 || true
  exit 1
fi

CAPTURE_JS="$RUN_DIR/capture-page.js"
cat >"$CAPTURE_JS" <<'NODE'
const { chromium } = require("playwright");

const url = process.env.SCREENSHOT_URL;
const out = process.env.SCREENSHOT_OUT;

(async () => {
  const browser = await chromium.launch({ headless: true });
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
  SCREENSHOT_URL="http://127.0.0.1:$PORT/" \
  SCREENSHOT_OUT="$OUT" \
  run_timed "$CAPTURE_TIMEOUT_SECONDS" node "$CAPTURE_JS"
elif [[ "$ALLOW_NPX_PLAYWRIGHT" == "1" ]]; then
  run_timed 600 npx --yes playwright install chromium
  SCREENSHOT_URL="http://127.0.0.1:$PORT/" \
  SCREENSHOT_OUT="$OUT" \
  run_timed "$CAPTURE_TIMEOUT_SECONDS" npx --yes --package=playwright node "$CAPTURE_JS"
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
