#!/usr/bin/env sh
set -eu

: "${DISPLAY:=host.docker.internal:0}"
: "${VSCODE_CACHE_DIR:=}"
: "${VSCODE_HOME:=/opt/vscode}"
: "${VSCODE_USER_DATA:=/tmp/vscode-user-data}"
: "${VSCODE_EXTENSIONS:=/tmp/vscode-extensions}"
: "${VSCODE_LOG:=/tmp/vscode-log}"
: "${VSCODE_PROJECT:=}"
: "${VSCODE_OPEN_FILE:=}"
: "${VSCODE_DISABLE_GPU:=true}"
: "${VSCODE_PREPARE_ONLY:=false}"

if [ -z "$VSCODE_PROJECT" ]; then
  if [ -d /workspace/jonnyzzz-x ]; then
    VSCODE_PROJECT=/workspace/jonnyzzz-x
  else
    VSCODE_PROJECT=/tmp/vscode-demo-project
  fi
fi

case "$(uname -m)" in
  aarch64|arm64)
    VSCODE_PLATFORM=linux-arm64
    ;;
  armv7l|armhf)
    VSCODE_PLATFORM=linux-armhf
    ;;
  *)
    VSCODE_PLATFORM=linux-x64
    ;;
esac

if [ -z "${VSCODE_URL:-}" ]; then
  VSCODE_URL="https://update.code.visualstudio.com/latest/${VSCODE_PLATFORM}/stable"
fi

vscode_archive=/tmp/vscode.tar.gz
if [ -n "$VSCODE_CACHE_DIR" ]; then
  mkdir -p "$VSCODE_CACHE_DIR"
  archive_name=$(basename "${VSCODE_URL%%\?*}")
  archive_name=$(printf '%s' "$archive_name" | tr -c 'A-Za-z0-9._-' '_')
  if [ -z "$archive_name" ]; then
    archive_name=archive
  fi
  case "$archive_name" in
    *.tar.gz|*.tgz) ;;
    *) archive_name="$archive_name.tar.gz" ;;
  esac
  url_checksum=$(printf '%s' "$VSCODE_URL" | cksum | awk '{print $1}')
  vscode_archive="$VSCODE_CACHE_DIR/vscode-${VSCODE_PLATFORM}-${url_checksum}-${archive_name}"
fi

mkdir -p "$VSCODE_HOME" "$VSCODE_USER_DATA/User" "$VSCODE_EXTENSIONS" "$VSCODE_LOG" "$VSCODE_PROJECT"

if [ ! -x "$VSCODE_HOME/code" ]; then
  if [ -n "$VSCODE_CACHE_DIR" ]; then
    exec 9>"$vscode_archive.lock"
    if ! flock -w 300 9; then
      echo "[run-vscode] timed out waiting for cache lock: $vscode_archive.lock" >&2
      exit 1
    fi
  fi
  if [ -s "$vscode_archive" ] && ! tar -tzf "$vscode_archive" >/dev/null 2>&1; then
    echo "[run-vscode] discarding invalid cached archive: $vscode_archive" >&2
    rm -f "$vscode_archive"
  fi
  if [ ! -s "$vscode_archive" ]; then
    echo "[run-vscode] downloading VSCode archive: $VSCODE_URL -> $vscode_archive" >&2
    tmp_archive=$(mktemp "$vscode_archive.tmp.XXXXXX")
    trap 'rm -f "$tmp_archive"' EXIT
    trap 'rm -f "$tmp_archive"; exit 1' HUP INT TERM
    if ! curl -fL --retry 5 --retry-delay 2 --retry-all-errors --connect-timeout 30 --speed-limit 1024 --speed-time 30 "$VSCODE_URL" -o "$tmp_archive"; then
      rm -f "$tmp_archive"
      exit 1
    fi
    if ! tar -tzf "$tmp_archive" >/dev/null 2>&1; then
      echo "[run-vscode] downloaded archive is invalid: $tmp_archive" >&2
      rm -f "$tmp_archive"
      exit 1
    fi
    mv "$tmp_archive" "$vscode_archive"
    trap - EXIT HUP INT TERM
  else
    echo "[run-vscode] using cached VSCode archive: $vscode_archive" >&2
  fi
  if [ -n "$VSCODE_CACHE_DIR" ]; then
    flock -u 9
    exec 9>&-
  fi
  echo "[run-vscode] extracting VSCode archive into $VSCODE_HOME" >&2
  tar -xzf "$vscode_archive" -C "$VSCODE_HOME" --strip-components=1
  echo "[run-vscode] VSCode extraction complete" >&2
else
  echo "[run-vscode] using existing VSCODE_HOME: $VSCODE_HOME" >&2
fi

if [ "$VSCODE_PREPARE_ONLY" = "true" ]; then
  test -x "$VSCODE_HOME/code"
  exit 0
fi

if [ ! -f "$VSCODE_PROJECT/README.md" ]; then
  cat > "$VSCODE_PROJECT/README.md" <<EOF
# X Server VSCode Smoke

Running VSCode inside jonnyzzz/x X server.
EOF
fi

if [ -z "$VSCODE_OPEN_FILE" ]; then
  VSCODE_OPEN_FILE="$VSCODE_PROJECT/README.md"
fi

cat > "$VSCODE_USER_DATA/User/settings.json" <<'EOF'
{
  "extensions.autoCheckUpdates": false,
  "extensions.autoUpdate": false,
  "editor.cursorBlinking": "solid",
  "editor.cursorSmoothCaretAnimation": "off",
  "editor.scrollbar.horizontal": "visible",
  "editor.scrollbar.vertical": "visible",
  "security.workspace.trust.enabled": false,
  "telemetry.telemetryLevel": "off",
  "update.mode": "none",
  "window.restoreWindows": "none",
  "window.titleBarStyle": "native",
  "workbench.colorTheme": "Default Light Modern",
  "workbench.startupEditor": "none"
}
EOF

if [ -z "${XDG_RUNTIME_DIR:-}" ]; then
  export XDG_RUNTIME_DIR=/tmp/runtime-root
  mkdir -p "$XDG_RUNTIME_DIR"
  chmod 700 "$XDG_RUNTIME_DIR"
fi

export DISPLAY
export ELECTRON_ENABLE_LOGGING=1
export ELECTRON_ENABLE_STACK_DUMPING=1

gpu_flags=
if [ "$VSCODE_DISABLE_GPU" = "true" ]; then
  gpu_flags="--disable-gpu --disable-software-rasterizer"
fi

set -- "$VSCODE_PROJECT"
if [ -n "$VSCODE_OPEN_FILE" ]; then
  set -- "$@" "$VSCODE_OPEN_FILE"
fi

# shellcheck disable=SC2086
exec "$VSCODE_HOME/code" \
  --no-sandbox \
  --disable-dev-shm-usage \
  $gpu_flags \
  --disable-extensions \
  --disable-updates \
  --disable-workspace-trust \
  --skip-release-notes \
  --skip-welcome \
  --user-data-dir "$VSCODE_USER_DATA" \
  --extensions-dir "$VSCODE_EXTENSIONS" \
  --logsPath "$VSCODE_LOG" \
  --new-window \
  "$@"
