[CmdletBinding()]
param(
    [string]$ConfigPath = "scripts/rds/rds.config.example.json",
    [string]$DbHost,
    [Nullable[int]]$DbPort,
    [string]$Database,
    [string]$Username,
    [string]$Password,
    [string]$MySqlPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\backup\common.ps1")

$config = Read-BackupConfig -ConfigPath $ConfigPath
$hostValue = Select-SettingValue -ExplicitValue $DbHost -ConfigValue (Get-ConfigValue $config "connection.host")
$portValue = [int](Select-SettingValue -ExplicitValue $DbPort -ConfigValue (Get-ConfigValue $config "connection.port") -DefaultValue 3306)
$databaseValue = Select-SettingValue -ExplicitValue $Database -ConfigValue (Get-ConfigValue $config "connection.database")
$usernameValue = Select-SettingValue -ExplicitValue $Username -ConfigValue (Get-ConfigValue $config "connection.username")
$passwordValue = Select-SettingValue -ExplicitValue $Password -ConfigValue (Get-ConfigValue $config "connection.password") -DefaultValue ""
$mysqlExecutable = Resolve-MySqlCliPath -PreferredPath $MySqlPath

if (-not (Test-TextValue $hostValue)) {
    throw "DbHost is required."
}
if (-not (Test-TextValue $usernameValue)) {
    throw "Username is required."
}
if (-not (Test-TextValue $mysqlExecutable)) {
    throw "mysql client was not found. Set MySqlPath or install mysql."
}

$arguments = @(
    "--host=$hostValue",
    "--port=$portValue",
    "--user=$usernameValue",
    "--default-character-set=utf8mb4"
)
if (Test-TextValue $passwordValue) {
    $arguments += "--password=$passwordValue"
}
if (Test-TextValue $databaseValue) {
    $arguments += "--database=$databaseValue"
}
$arguments += @("-N", "-B", "-e", "SELECT VERSION() AS version, DATABASE() AS current_db, NOW() AS server_time;")

$output = & $mysqlExecutable @arguments 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "RDS connection test failed.`n$output"
}

[PSCustomObject]@{
    success   = $true
    host      = $hostValue
    port      = $portValue
    database  = $databaseValue
    username  = $usernameValue
    mysqlPath = $mysqlExecutable
    result    = ($output | Out-String).Trim()
}
