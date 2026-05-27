[CmdletBinding()]
param(
    [string]$ConfigPath = "scripts/backup/backup.config.example.json",
    [Parameter(Mandatory = $true)]
    [string]$BackupFile,
    [string]$ManifestFile,
    [string]$RestoreDbHost,
    [Nullable[int]]$RestoreDbPort,
    [string]$RestoreDbName,
    [string]$RestoreDbUser,
    [string]$RestoreDbPassword,
    [string]$MySqlPath,
    [switch]$DropIfExists
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "common.ps1")

function Invoke-MySqlQuery {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ExecutablePath,
        [Parameter(Mandatory = $true)]
        [string]$Host,
        [Parameter(Mandatory = $true)]
        [int]$Port,
        [Parameter(Mandatory = $true)]
        [string]$User,
        [AllowNull()]
        [string]$Password,
        [AllowNull()]
        [string]$Database,
        [Parameter(Mandatory = $true)]
        [string]$Query
    )

    $arguments = @(
        "--host=$Host",
        "--port=$Port",
        "--user=$User",
        "--default-character-set=utf8mb4",
        "-N",
        "-B",
        "-e",
        $Query
    )
    if (Test-TextValue $Password) {
        $arguments += "--password=$Password"
    }
    if (Test-TextValue $Database) {
        $arguments += "--database=$Database"
    }

    $output = & $ExecutablePath @arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "mysql query failed: $Query`n$output"
    }

    return ($output | Out-String).Trim()
}

$config = Read-BackupConfig -ConfigPath $ConfigPath
$backupFilePath = Resolve-BackupPath -PathValue $BackupFile -BasePath (Get-Location).Path
if (-not (Test-Path $backupFilePath)) {
    throw "BackupFile not found: $backupFilePath"
}

$resolvedManifestFile = $null
if (Test-TextValue $ManifestFile) {
    $resolvedManifestFile = Resolve-BackupPath -PathValue $ManifestFile -BasePath (Get-Location).Path
} else {
    $backupLeaf = Split-Path -Leaf $backupFilePath
    $baseNames = @($backupLeaf)
    if ($backupLeaf.ToLowerInvariant().EndsWith(".sql.zip")) {
        $baseNames += $backupLeaf.Substring(0, $backupLeaf.Length - ".sql.zip".Length)
    }
    $baseNames += [System.IO.Path]::GetFileNameWithoutExtension($backupLeaf)

    $candidateNames = @()
    foreach ($baseName in ($baseNames | Select-Object -Unique)) {
        $candidateNames += ("{0}.manifest.json" -f $baseName)
    }

    $searchDirectories = @(
        (Split-Path -Parent $backupFilePath),
        (Join-Path (Split-Path -Parent (Split-Path -Parent $backupFilePath)) "manifest")
    ) | Select-Object -Unique

    foreach ($searchDirectory in $searchDirectories) {
        if (-not (Test-Path $searchDirectory)) {
            continue
        }
        foreach ($candidateName in $candidateNames) {
            $candidatePath = Join-Path $searchDirectory $candidateName
            if (Test-Path $candidatePath) {
                $resolvedManifestFile = $candidatePath
                break
            }
        }
        if (Test-TextValue $resolvedManifestFile) {
            break
        }
    }
}

$restoreHost = Select-SettingValue -ExplicitValue $RestoreDbHost -ConfigValue (Get-ConfigValue $config "restore.host") -DefaultValue "127.0.0.1"
$restorePort = [int](Select-SettingValue -ExplicitValue $RestoreDbPort -ConfigValue (Get-ConfigValue $config "restore.port") -DefaultValue 3306)
$restoreDbName = Select-SettingValue -ExplicitValue $RestoreDbName -ConfigValue (Get-ConfigValue $config "restore.database") -DefaultValue "hardware_store_restore"
$restoreUser = Select-SettingValue -ExplicitValue $RestoreDbUser -ConfigValue (Get-ConfigValue $config "restore.user")
$restorePassword = Select-SettingValue -ExplicitValue $RestoreDbPassword -ConfigValue (Get-ConfigValue $config "restore.password") -DefaultValue ""
$mysqlExecutable = Resolve-MySqlCliPath -PreferredPath (Select-SettingValue -ExplicitValue $MySqlPath -ConfigValue (Get-ConfigValue $config "tools.mysqlPath") -DefaultValue "")

if (-not (Test-TextValue $restoreUser)) {
    throw "RestoreDbUser is required."
}
if (-not (Test-TextValue $mysqlExecutable)) {
    throw "mysql client was not found. Set MySqlPath or configure tools.mysqlPath."
}

$directories = New-BackupDirectoryMap -OutputRoot (Select-SettingValue -ExplicitValue (Get-ConfigValue $config "paths.outputRoot") -ConfigValue $null -DefaultValue "target/backups")
$logFile = Join-Path $directories.Logs ("restore_{0}.log" -f (Get-DateStamp))
$restoreSessionRoot = Ensure-Directory (Join-Path $directories.Restore (Get-Timestamp))
$workingBackupFile = $backupFilePath

Write-BackupLog -Message ("Starting restore from backup: {0}" -f $backupFilePath) -LogFile $logFile

$manifest = $null
if (Test-TextValue $resolvedManifestFile) {
    if (-not (Test-Path $resolvedManifestFile)) {
        throw "ManifestFile not found: $resolvedManifestFile"
    }
    $manifest = Read-JsonFile -Path $resolvedManifestFile
    $expectedHash = [string](Get-ConfigValue $manifest "sha256")
    if (Test-TextValue $expectedHash) {
        $actualHash = Get-FileSha256 -Path $backupFilePath
        if ($actualHash -ne $expectedHash.ToLowerInvariant()) {
            throw "Backup file hash verification failed."
        }
    }
}

if ($backupFilePath.ToLowerInvariant().EndsWith(".zip")) {
    Write-BackupLog -Message "Extracting compressed backup file." -LogFile $logFile
    Expand-Archive -Path $backupFilePath -DestinationPath $restoreSessionRoot -Force
    $sqlCandidates = Get-ChildItem -Path $restoreSessionRoot -Filter *.sql -Recurse
    if (@($sqlCandidates).Count -eq 0) {
        throw "No SQL file was found after extracting the backup archive."
    }
    $workingBackupFile = $sqlCandidates | Select-Object -First 1 -ExpandProperty FullName
}

if (-not (Test-Path $workingBackupFile)) {
    throw "Working SQL file not found: $workingBackupFile"
}

if ($DropIfExists) {
    Write-BackupLog -Message ("Dropping existing restore database if present: {0}" -f $restoreDbName) -LogFile $logFile
    Invoke-MySqlQuery -ExecutablePath $mysqlExecutable -Host $restoreHost -Port $restorePort -User $restoreUser -Password $restorePassword -Database "" -Query ("DROP DATABASE IF EXISTS `{0}`;" -f $restoreDbName) | Out-Null
}

Write-BackupLog -Message ("Creating restore database: {0}" -f $restoreDbName) -LogFile $logFile
Invoke-MySqlQuery -ExecutablePath $mysqlExecutable -Host $restoreHost -Port $restorePort -User $restoreUser -Password $restorePassword -Database "" -Query ("CREATE DATABASE IF NOT EXISTS `{0}` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;" -f $restoreDbName) | Out-Null

Write-BackupLog -Message "Importing SQL into restore database." -LogFile $logFile
$importArguments = @(
    "--host=$restoreHost",
    "--port=$restorePort",
    "--user=$restoreUser",
    "--default-character-set=utf8mb4",
    "--database=$restoreDbName"
)
if (Test-TextValue $restorePassword) {
    $importArguments += "--password=$restorePassword"
}
$sqlText = Get-Content -Raw $workingBackupFile
$sqlText | & $mysqlExecutable @importArguments
if ($LASTEXITCODE -ne 0) {
    throw "mysql import failed with exit code $LASTEXITCODE"
}

$tablesToCheck = @(
    "product",
    "customer",
    "supplier",
    "purchase_order",
    "sales_order",
    "inventory",
    "inventory_log",
    "operation_log"
)

$tableCounts = [ordered]@{}
foreach ($tableName in $tablesToCheck) {
    $count = Invoke-MySqlQuery -ExecutablePath $mysqlExecutable -Host $restoreHost -Port $restorePort -User $restoreUser -Password $restorePassword -Database $restoreDbName -Query ("SELECT COUNT(*) FROM `{0}`;" -f $tableName)
    $tableCounts[$tableName] = [int]$count
}

$reportPath = Join-Path $restoreSessionRoot ("restore_{0}.report.json" -f (Get-Timestamp))
$report = [ordered]@{
    success          = $true
    generatedAt      = (Get-Date).ToString("s")
    backupFile       = (Resolve-Path $backupFilePath).Path
    manifestFile     = $resolvedManifestFile
    sqlFile          = $workingBackupFile
    restoreHost      = $restoreHost
    restorePort      = $restorePort
    restoreDatabase  = $restoreDbName
    mysqlVersion     = Get-ToolVersion -ExecutablePath $mysqlExecutable
    tableCounts      = $tableCounts
    restoreWorkspace = $restoreSessionRoot
}
Save-JsonFile -Path $reportPath -Data $report

Write-BackupLog -Message ("Restore validation report generated: {0}" -f $reportPath) -LogFile $logFile
[PSCustomObject]@{
    success         = $true
    reportPath      = $reportPath
    restoreDatabase = $restoreDbName
    restoreHost     = $restoreHost
}
