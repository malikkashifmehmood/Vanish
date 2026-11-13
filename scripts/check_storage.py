"""
Prove that expired messages are really gone from the phone's storage (Stage 4 / live demo).

  python scripts/check_storage.py            list what is stored
  python scripts/check_storage.py "my text"  also search the raw database bytes for that text

Works on the debug build via `adb run-as`. Message text is never printed, only its length.
Run it once while the message is on screen (text FOUND is expected) and once after it expired.
"""
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile

PKG = "com.example.vanish"


def find_adb() -> str:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk and os.name == "nt" and os.environ.get("LOCALAPPDATA"):
        sdk = os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk")
    if sdk:
        candidate = os.path.join(sdk, "platform-tools", "adb.exe" if os.name == "nt" else "adb")
        if os.path.exists(candidate):
            return candidate
    found = shutil.which("adb")
    if not found:
        sys.exit("adb not found. Set ANDROID_HOME or add platform-tools to PATH.")
    return found


def pull(adb: str, name: str) -> bytes:
    r = subprocess.run([adb, "exec-out", "run-as", PKG, "cat", f"databases/{name}"], capture_output=True)
    return r.stdout if r.returncode == 0 else b""


def main() -> None:
    probe = sys.argv[1] if len(sys.argv) > 1 else None
    adb = find_adb()
    files = {n: pull(adb, n) for n in ("vanish.db", "vanish.db-journal", "vanish.db-wal")}
    if not files["vanish.db"]:
        sys.exit("Could not read vanish.db. Is the debug app installed and has it been opened once?")

    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "vanish.db")
        with open(path, "wb") as f:
            f.write(files["vanish.db"])
        con = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        rows = con.execute(
            "SELECT id, sender, length(text), state, expires_at FROM messages ORDER BY sent_at"
        ).fetchall()
        seen = con.execute("SELECT COUNT(*) FROM seen_ids").fetchone()[0]
        con.close()

    print(f"messages rows stored: {len(rows)}   (seen_ids rows, ids only: {seen})")
    for mid, sender, n, state, exp in rows:
        print(f"  {mid[:8]}  from={sender}  text_length={n}  state={state}  expires_at={exp}")

    for name, data in files.items():
        size = len(data)
        line = f"{name}: {size} bytes"
        if probe and size:
            line += "   TEXT FOUND in raw bytes" if probe.encode("utf-8") in data else "   text not found in raw bytes"
        print(line)


if __name__ == "__main__":
    main()
