param(
    [string]$DbHost = "127.0.0.1",
    [int]$Port = 3306,
    [string]$Database = "hardware_store",
    [string]$Username = "root",
    [string]$Password = "root",
    [string]$ConfigPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$pythonScript = Join-Path $scriptDir "scripts\rds\rds_init.py"

if ($ConfigPath -and (Test-Path $ConfigPath)) {
    Write-Host "Initializing database from config: $ConfigPath"
    python $pythonScript $ConfigPath
} elseif ($DbHost -and $Username) {
    Write-Host "Initializing database: ${DbHost}:${Port}/${Database}"
    python $pythonScript --host $DbHost --port $Port --database $Database --username $Username --password $Password
} else {
    throw "Either ConfigPath or (DbHost + Username) must be provided."
}

if ($LASTEXITCODE -ne 0) {
    throw "Database initialization failed."
}
