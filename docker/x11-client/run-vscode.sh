#!/usr/bin/env sh
set -eu

: "${DISPLAY:=host.docker.internal:0}"
: "${VSCODE_HOME:=/opt/vscode}"
: "${VSCODE_USER_DATA:=/tmp/vscode-user-data}"
: "${VSCODE_EXTENSIONS:=/tmp/vscode-extensions}"
: "${VSCODE_LOG:=/tmp/vscode-log}"
: "${VSCODE_PROJECT:=}"
: "${VSCODE_DISABLE_GPU:=true}"

if [ -z "$VSCODE_PROJECT" ]; then
  if [ -d /workspace/jonnyzzz-x ]; then
    VSCODE_PROJECT=/workspace/jonnyzzz-x
  else
    VSCODE_PROJECT=/tmp/vscode-demo-project
  fi
fi

if [ -z "${VSCODE_URL:-}" ]; then
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
  VSCODE_URL="https://update.code.visualstudio.com/latest/${VSCODE_PLATFORM}/stable"
fi

mkdir -p "$VSCODE_HOME" "$VSCODE_USER_DATA/User" "$VSCODE_EXTENSIONS" "$VSCODE_LOG" "$VSCODE_PROJECT"

if [ ! -x "$VSCODE_HOME/code" ]; then
  curl -L "$VSCODE_URL" -o /tmp/vscode.tar.gz
  tar -xzf /tmp/vscode.tar.gz -C "$VSCODE_HOME" --strip-components=1
fi

if [ ! -f "$VSCODE_PROJECT/README.md" ]; then
  cat > "$VSCODE_PROJECT/README.md" <<EOF
# X Server VSCode Smoke

Running VSCode inside jonnyzzz/x X server.
EOF
fi

cat > "$VSCODE_USER_DATA/User/settings.json" <<'EOF'
{
  "extensions.autoCheckUpdates": false,
  "extensions.autoUpdate": false,
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
  "$VSCODE_PROJECT"
