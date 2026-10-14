#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [ ! -f "./hioneos-flasher" ]; then
    echo "[*] Compiling HioneOS Web Flasher (Go)..."
    go build -o hioneos-flasher .
fi

echo "[*] Launching HioneOS Web Flasher on http://localhost:3939..."
exec ./hioneos-flasher "$@"
