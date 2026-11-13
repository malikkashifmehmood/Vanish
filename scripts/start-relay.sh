#!/usr/bin/env bash
# Starts the local Vanish relay on port 8000 (macOS / Linux).
set -e
cd "$(dirname "$0")/../relay"
command -v python3 >/dev/null || { echo "ERROR: python3 not found"; exit 1; }
[ -d .venv ] || python3 -m venv .venv
.venv/bin/python -m pip install -q -r requirements.txt
echo
echo "Relay address for the EMULATOR:  ws://10.0.2.2:8000/ws"
echo "Relay address for a PHONE: ws://<this computer's Wi-Fi IP>:8000/ws"
echo
exec .venv/bin/python -m uvicorn main:app --host 0.0.0.0 --port 8000 --no-access-log
