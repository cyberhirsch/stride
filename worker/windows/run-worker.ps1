# Runs the Stride alignment worker natively on Windows (no Docker needed).
# Settings come from %USERPROFILE%\.stride\worker.env (KEY=VALUE lines):
#   STRIDE_URL, STRIDE_EMAIL, STRIDE_PASSWORD, optional STRIDE_THREADS.
# Started at logon by the "Stride Worker" scheduled task (install.ps1).

$ErrorActionPreference = "Stop"
$workerDir = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $env:USERPROFILE ".stride\worker.env"
$venv = Join-Path $env:LOCALAPPDATA "Stride\worker-venv"
$log = Join-Path $env:LOCALAPPDATA "Stride\worker.log"

foreach ($line in Get-Content $envFile) {
    if ($line -match '^\s*([A-Z_]+)\s*=\s*(.*)\s*$') { Set-Item "env:$($Matches[1])" $Matches[2] }
}
if (-not $env:STRIDE_PASSWORD) { throw "Fill in $envFile first" }
if (-not $env:STRIDE_THREADS) { $env:STRIDE_THREADS = "24" }   # leave headroom on 32 threads
$env:STRIDE_WORKDIR = Join-Path $env:LOCALAPPDATA "Stride\work"
$env:PYTHONPATH = $workerDir
$env:PYTHONUNBUFFERED = "1"

while ($true) {
    $p = Start-Process -FilePath (Join-Path $venv "Scripts\python.exe") -ArgumentList "-m", "stride_worker.main" `
        -WorkingDirectory $workerDir -NoNewWindow -PassThru `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    # keep the desktop responsive while COLMAP uses most cores
    $p.PriorityClass = [System.Diagnostics.ProcessPriorityClass]::BelowNormal
    $p.WaitForExit()
    Start-Sleep -Seconds 60   # crashed or lost the server: retry
}
