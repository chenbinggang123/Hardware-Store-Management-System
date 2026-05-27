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
$databaseValue = Select-SettingValue -ExplicitValue $Database -ConfigValue (Get-ConfigValue $config "connection.database") -DefaultValue "hardware_store"
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

$initScript = Join-Path (Get-BackupProjectRoot) "init-db.ps1"
if (-not (Test-Path $initScript)) {
    throw "init-db.ps1 not found: $initScript"
}

& $initScript `
    -DbHost $hostValue `
    -Port $portValue `
    -Database $databaseValue `
    -Username $usernameValue `
    -Password $passwordValue `
    -MySqlPath $mysqlExecutable

if ($LASTEXITCODE -ne 0) {
    throw "init-db.ps1 failed with exit code $LASTEXITCODE"
}

[PSCustomObject]@{
    success  = $true
    host     = $hostValue
    port     = $portValue
    database = $databaseValue
    username = $usernameValue
}
