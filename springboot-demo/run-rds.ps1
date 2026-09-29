param(
    [string]$ConfigPath = "scripts/rds/rds.config.json",
    [string]$DbHost,
    [Nullable[int]]$DbPort,
    [string]$DbName,
    [string]$DbUsername,
    [string]$DbPassword,
    [Nullable[int]]$ServerPort,
    [switch]$CompileOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$configResolved = $ConfigPath

if (-not [System.IO.Path]::IsPathRooted($ConfigPath)) {
    $configResolved = Join-Path $scriptDir $ConfigPath
    $configResolved = (Resolve-Path $configResolved).Path
}

$config = Get-Content -Raw $configResolved | ConvertFrom-Json

$dbHostValue = if ($DbHost) { $DbHost } else { $config.connection.host }
$dbPortValue = if ($DbPort) { $DbPort } else { $config.connection.port }
$dbNameValue = if ($DbName) { $DbName } else { $config.connection.database }
$dbUsernameValue = if ($DbUsername) { $DbUsername } else { $config.connection.username }
$dbPasswordValue = if ($DbPassword) { $DbPassword } else { $config.connection.password }
$serverPortValue = if ($ServerPort) { $ServerPort } else {
    if ($config.application.serverPort) { $config.application.serverPort } else { 8084 }
}

$env:APP_PROFILE = "mysql"
$env:DB_HOST = $dbHostValue
$env:DB_PORT = "$dbPortValue"
$env:DB_NAME = $dbNameValue
$env:DB_USERNAME = $dbUsernameValue
$env:DB_PASSWORD = $dbPasswordValue

Write-Host "Running application against RDS: ${dbHostValue}:${dbPortValue}/${dbNameValue}"
Write-Host "Server port: $serverPortValue"
Write-Host ""

$runScript = Join-Path $scriptDir "run-dev.ps1"

if ($CompileOnly) {
    & $runScript -CompileOnly -Port $serverPortValue
} else {
    & $runScript -Port $serverPortValue
}

if ($LASTEXITCODE -ne 0) {
    throw "run-dev.ps1 failed with exit code $LASTEXITCODE"
}
