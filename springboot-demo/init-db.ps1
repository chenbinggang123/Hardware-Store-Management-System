param(
    [string]$DbHost = "127.0.0.1",
    [int]$Port = 3306,
    [string]$Database = "hardware_store",
    [string]$Username = "root",
    [string]$Password = "root",
    [string]$MySqlPath = ""
)

$ErrorActionPreference = "Stop"

function Resolve-MySqlPath {
    param([string]$PreferredPath)

    if ($PreferredPath -and (Test-Path $PreferredPath)) {
        return $PreferredPath
    }

    $fromCommand = Get-Command mysql -ErrorAction SilentlyContinue
    if ($fromCommand -and (Test-Path $fromCommand.Source)) {
        return $fromCommand.Source
    }

    $candidates = @(
        "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
        "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe",
        "C:\Program Files\MySQL\MySQL Server 9.0\bin\mysql.exe"
    )

    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    throw "mysql.exe was not found. Set -MySqlPath explicitly or add MySQL bin to PATH."
}

$mysql = Resolve-MySqlPath -PreferredPath $MySqlPath
$resourceRoot = Join-Path $PSScriptRoot "src\main\resources"
$schemaPath = Join-Path $resourceRoot "schema.sql"
$dataPath = Join-Path $resourceRoot "data.sql"

if (-not (Test-Path $schemaPath)) {
    throw "Schema file not found: $schemaPath"
}

if (-not (Test-Path $dataPath)) {
    throw "Seed data file not found: $dataPath"
}

$createDatabaseSql = "CREATE DATABASE IF NOT EXISTS $Database CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
$schemaSql = ((Get-Content $schemaPath -Raw) -replace "\r?\n", " ")
$dataSql = ((Get-Content $dataPath -Raw) -replace "\r?\n", " ")

try {
    & $mysql "--host=$DbHost" "--port=$Port" "--user=$Username" "--password=$Password" "--default-character-set=utf8mb4" "-e" $createDatabaseSql
    if ($LASTEXITCODE -ne 0) {
        throw "mysql exited with code $LASTEXITCODE"
    }

    & $mysql "--host=$DbHost" "--port=$Port" "--user=$Username" "--password=$Password" "--default-character-set=utf8mb4" "--database=$Database" "-e" $schemaSql
    if ($LASTEXITCODE -ne 0) {
        throw "mysql exited with code $LASTEXITCODE"
    }

    & $mysql "--host=$DbHost" "--port=$Port" "--user=$Username" "--password=$Password" "--default-character-set=utf8mb4" "--database=$Database" "-e" $dataSql
    if ($LASTEXITCODE -ne 0) {
        throw "mysql exited with code $LASTEXITCODE"
    }

    Write-Host "Database initialization completed."
    Write-Host "Database: $Database"
    Write-Host "Schema: $schemaPath"
    Write-Host "Seed data: $dataPath"
} finally {
}
