#!/usr/bin/env bash
# Downloads the on-device nudity detection model (NudeNet 320n, ONNX) from PyPI
# and places it in the app assets. Runs in CI and can be run locally.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/nudenet_320n.onnx"
if [ -f "$DEST" ]; then echo "model already present"; exit 0; fi
TMP="$(mktemp -d)"
python3 -m pip download "nudenet==3.4.2" --no-deps -d "$TMP" -q
unzip -q -o "$TMP"/nudenet-*.whl "nudenet/320n.onnx" -d "$TMP"
mkdir -p "$(dirname "$DEST")"
cp "$TMP/nudenet/320n.onnx" "$DEST"
rm -rf "$TMP"
echo "model saved to $DEST"
