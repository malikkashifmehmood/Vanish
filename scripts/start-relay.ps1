# Starts the local Vanish relay on port 8000 (Windows PowerShell).
$ErrorActionPreference = "Stop"
$relay = Join-Path (Split-Path -Parent $PSScriptRoot) "relay"
Set-Location $relay

$py = Get-Command python -ErrorAction SilentlyContinue
if (-not $py) { $py = Get-Command py -ErrorAction SilentlyContinue }
if (-not $py) { Write-Host "ERROR: Python 3 not found. Install it from python.org and tick 'Add to PATH'." -ForegroundColor Red; exit 1 }

if (-not (Test-Path ".venv")) { & $py.Source -m venv .venv }
$venvPy = Join-Path $relay ".venv\Scripts\python.exe"
& $venvPy -m pip install -q -r requirements.txt

Write-Host ""
Write-Host "Relay address for the EMULATOR:      ws://10.0.2.2:8000/ws"
Write-Host "Relay address for a PHONE (same Wi-Fi), use one of:"
Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" } |
    ForEach-Object { Write-Host ("    ws://" + $_.IPAddress + ":8000/ws") }
Write-Host "(Allow Python through the Windows firewall if asked.)  Ctrl+C stops the relay."
Write-Host ""

& $venvPy -m uvicorn main:app --host 0.0.0.0 --port 8000 --no-access-log
