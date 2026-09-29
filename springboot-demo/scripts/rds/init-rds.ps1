[CmdletBinding()]
param(
    [string]$ConfigPath = "scripts/rds/rds.config.json"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$pythonScript = Join-Path $scriptDir "rds_init.py"
$configResolved = $ConfigPath

if (-not [System.IO.Path]::IsPathRooted($ConfigPath)) {
    $configResolved = Join-Path $scriptDir "..\.." $ConfigPath
    $configResolved = (Resolve-Path $configResolved).Path
}

Write-Host "Initializing RDS database..."
Write-Host ""

python $pythonScript $configResolved

if ($LASTEXITCODE -ne 0) {
    throw "RDS initialization failed."
}
