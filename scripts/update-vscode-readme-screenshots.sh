#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_DIR="${ARTIFACT_DIR:-$ROOT/build/tmp/vscode-smoke}"
OUT_DIR="${OUT_DIR:-$ROOT/docs/images}"
RUN_PARITY="${RUN_PARITY:-1}"
BUILD_IMAGES="${BUILD_IMAGES:-1}"
GRADLE_TIMEOUT_SECONDS="${GRADLE_TIMEOUT_SECONDS:-1800}"

if [[ "$RUN_PARITY" == "1" ]]; then
  if [[ "$BUILD_IMAGES" == "1" ]]; then
    GRADLE_TIMEOUT_SECONDS="$GRADLE_TIMEOUT_SECONDS" \
      "$ROOT/scripts/run-supervised.sh" gradle dockerBuildX11Images --console=plain
  fi
  GRADLE_TIMEOUT_SECONDS="$GRADLE_TIMEOUT_SECONDS" \
    "$ROOT/scripts/run-supervised.sh" \
    gradle \
    test \
    --tests "org.jonnyzzz.xserver.VSCodeSmokeTest.vscode robot and svg roughly match xvfb reference" \
    -Dx.vscodeParity=true \
    --rerun-tasks \
    --console=plain
fi

reference="$ARTIFACT_DIR/vscode-xvfb-reference.png"
kotlin_svg="$ARTIFACT_DIR/vscode-kotlin-svg-composed.png"
metrics="$ARTIFACT_DIR/vscode-visual-metrics.txt"

for file in "$reference" "$kotlin_svg"; do
  if [[ ! -f "$file" ]]; then
    echo "Missing VSCode parity artifact: $file" >&2
    echo "Run this script with RUN_PARITY=1, or run the VSCode parity test first." >&2
    exit 1
  fi
done

mkdir -p "$OUT_DIR"
cp "$reference" "$OUT_DIR/vscode-xvfb-reference.png"
cp "$kotlin_svg" "$OUT_DIR/vscode-kotlin-svg.png"

echo "Updated:"
echo "  $OUT_DIR/vscode-xvfb-reference.png"
echo "  $OUT_DIR/vscode-kotlin-svg.png"

if [[ -f "$metrics" ]]; then
  echo
  sed -n '1,80p' "$metrics"
fi
