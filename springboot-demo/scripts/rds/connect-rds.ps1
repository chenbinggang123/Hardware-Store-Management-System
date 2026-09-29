[CmdletBinding()]
param(
    [string]$ConfigPath = "scripts/rds/rds.config.json"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$pythonScript = Join-Path $scriptDir "rds_connect.py"
$configResolved = $ConfigPath

if (-not [System.IO.Path]::IsPathRooted($ConfigPath)) {
    $configResolved = Join-Path $scriptDir "..\.." $ConfigPath
    $configResolved = (Resolve-Path $configResolved).Path
}

Write-Host "Testing RDS connection..."
Write-Host ""

python $pythonScript $configResolved

if ($LASTEXITCODE -ne 0) {
    throw "RDS connection test failed."
}
