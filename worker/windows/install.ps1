# One-time setup of the Stride worker on this Windows PC:
# a Python venv with the worker's requirements, a settings file template,
# and a per-user scheduled task that starts the worker at logon.
# Uninstall: Unregister-ScheduledTask -TaskName "Stride Worker"

$ErrorActionPreference = "Stop"
$workerDir = Split-Path -Parent $PSScriptRoot
$base = Join-Path $env:LOCALAPPDATA "Stride"
$venv = Join-Path $base "worker-venv"
New-Item -ItemType Directory -Force $base | Out-Null

if (-not (Test-Path (Join-Path $venv "Scripts\python.exe"))) { py -3.13 -m venv $venv }
& (Join-Path $venv "Scripts\python.exe") -m pip install --quiet --upgrade pip
& (Join-Path $venv "Scripts\python.exe") -m pip install --quiet -r (Join-Path $workerDir "requirements.txt")

$cfgDir = Join-Path $env:USERPROFILE ".stride"
$cfg = Join-Path $cfgDir "worker.env"
if (-not (Test-Path $cfg)) {
    New-Item -ItemType Directory -Force $cfgDir | Out-Null
    @"
STRIDE_URL=http://192.168.178.66:8091
STRIDE_EMAIL=
STRIDE_PASSWORD=
STRIDE_THREADS=24
"@ | Set-Content -Encoding ascii $cfg
}

$action = New-ScheduledTaskAction -Execute "powershell.exe" `
    -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$PSScriptRoot\run-worker.ps1`""
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 5)
Register-ScheduledTask -TaskName "Stride Worker" -Action $action -Trigger $trigger -Settings $settings `
    -Description "Stride alignment worker (G:\Code\Stride\worker)" -Force | Out-Null

Write-Host "Installed. Fill in $cfg, then start it now with: Start-ScheduledTask -TaskName 'Stride Worker'"
Write-Host "Log: $base\worker.log"
