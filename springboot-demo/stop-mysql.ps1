param()

$ErrorActionPreference = "Stop"

$pidFile = Join-Path $PSScriptRoot "target\mysql84\mysqld.pid"

if (!(Test-Path $pidFile)) {
    Write-Host "MySQL PID file was not found."
    exit 0
}

$processId = (Get-Content $pidFile | Select-Object -First 1).Trim()

if (-not $processId) {
    Write-Host "MySQL PID file is empty."
    exit 0
}

try {
    Stop-Process -Id ([int]$processId) -Force -ErrorAction Stop
    Write-Host "MySQL process stopped. PID: $processId"
} catch {
    Write-Host "MySQL process was not running. PID: $processId"
}

Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
