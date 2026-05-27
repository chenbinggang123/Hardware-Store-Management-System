param(
    [string]$ConfigPath = "scripts/rds/rds.config.example.json",
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

. (Join-Path $PSScriptRoot "scripts\backup\common.ps1")

$config = Read-BackupConfig -ConfigPath $ConfigPath
$dbHostValue = Select-SettingValue -ExplicitValue $DbHost -ConfigValue (Get-ConfigValue $config "connection.host")
$dbPortValue = [int](Select-SettingValue -ExplicitValue $DbPort -ConfigValue (Get-ConfigValue $config "connection.port") -DefaultValue 3306)
$dbNameValue = Select-SettingValue -ExplicitValue $DbName -ConfigValue (Get-ConfigValue $config "connection.database") -DefaultValue "hardware_store"
$dbUsernameValue = Select-SettingValue -ExplicitValue $DbUsername -ConfigValue (Get-ConfigValue $config "connection.username")
$dbPasswordValue = Select-SettingValue -ExplicitValue $DbPassword -ConfigValue (Get-ConfigValue $config "connection.password") -DefaultValue ""
$serverPortValue = [int](Select-SettingValue -ExplicitValue $ServerPort -ConfigValue (Get-ConfigValue $config "application.serverPort") -DefaultValue 8084)

if (-not (Test-TextValue $dbHostValue)) {
    throw "DbHost is required."
}
if (-not (Test-TextValue $dbUsernameValue)) {
    throw "DbUsername is required."
}

$env:APP_PROFILE = "mysql"
$env:DB_HOST = $dbHostValue
$env:DB_PORT = "$dbPortValue"
$env:DB_NAME = $dbNameValue
$env:DB_USERNAME = $dbUsernameValue
$env:DB_PASSWORD = $dbPasswordValue

Write-Host ("Running application against RDS: {0}:{1}/{2}" -f $dbHostValue, $dbPortValue, $dbNameValue)

$runScript = Join-Path $PSScriptRoot "run-dev.ps1"
if ($CompileOnly) {
    & $runScript -CompileOnly -Port $serverPortValue
} else {
    & $runScript -Port $serverPortValue
}

if ($LASTEXITCODE -ne 0) {
    throw "run-dev.ps1 failed with exit code $LASTEXITCODE"
}
