[CmdletBinding()]
param(
    [string]$ConfigPath = "scripts/backup/backup.config.example.json",
    [string]$DbHost,
    [Nullable[int]]$DbPort,
    [string]$DbName,
    [string]$DbUser,
    [string]$DbPassword,
    [string]$OutputRoot,
    [string]$MySqlDumpPath,
    [Nullable[bool]]$Compress,
    [Nullable[int]]$KeepLocalDays,
    [string]$CloudType,
    [string]$CloudBucket,
    [string]$CloudPath,
    [string]$AccessKey,
    [string]$SecretKey,
    [string]$Region,
    [string]$Endpoint,
    [string]$UploaderPath,
    [Nullable[bool]]$SkipUpload
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "common.ps1")

$config = Read-BackupConfig -ConfigPath $ConfigPath

$dbHost = Select-SettingValue -ExplicitValue $DbHost -ConfigValue (Get-ConfigValue $config "db.host") -DefaultValue "127.0.0.1"
$dbPortValue = Select-SettingValue -ExplicitValue $DbPort -ConfigValue (Get-ConfigValue $config "db.port") -DefaultValue 3306
$dbPort = [int]$dbPortValue
$dbName = Select-SettingValue -ExplicitValue $DbName -ConfigValue (Get-ConfigValue $config "db.name")
$dbUser = Select-SettingValue -ExplicitValue $DbUser -ConfigValue (Get-ConfigValue $config "db.user")
$dbPassword = Select-SettingValue -ExplicitValue $DbPassword -ConfigValue (Get-ConfigValue $config "db.password") -DefaultValue ""
$outputRoot = Select-SettingValue -ExplicitValue $OutputRoot -ConfigValue (Get-ConfigValue $config "paths.outputRoot") -DefaultValue "target/backups"
$compressOutput = [bool](Select-SettingValue -ExplicitValue $Compress -ConfigValue $null -DefaultValue $true)
$keepLocalDaysValue = Select-SettingValue -ExplicitValue $KeepLocalDays -ConfigValue (Get-ConfigValue $config "retention.keepFullDays") -DefaultValue 7
$keepLocalDays = [int]$keepLocalDaysValue
$cloudType = Select-SettingValue -ExplicitValue $CloudType -ConfigValue (Get-ConfigValue $config "cloud.type") -DefaultValue "filesystem"
$cloudBucket = Select-SettingValue -ExplicitValue $CloudBucket -ConfigValue (Get-ConfigValue $config "cloud.bucket") -DefaultValue ""
$cloudPath = Select-SettingValue -ExplicitValue $CloudPath -ConfigValue (Get-ConfigValue $config "cloud.path") -DefaultValue ""
$accessKey = Select-SettingValue -ExplicitValue $AccessKey -ConfigValue (Get-ConfigValue $config "cloud.accessKey") -DefaultValue ""
$secretKey = Select-SettingValue -ExplicitValue $SecretKey -ConfigValue (Get-ConfigValue $config "cloud.secretKey") -DefaultValue ""
$region = Select-SettingValue -ExplicitValue $Region -ConfigValue (Get-ConfigValue $config "cloud.region") -DefaultValue ""
$endpoint = Select-SettingValue -ExplicitValue $Endpoint -ConfigValue (Get-ConfigValue $config "cloud.endpoint") -DefaultValue ""
$resolvedDumpPath = Resolve-MySqlDumpPath -PreferredPath (Select-SettingValue -ExplicitValue $MySqlDumpPath -ConfigValue (Get-ConfigValue $config "tools.mysqldumpPath") -DefaultValue "")
$uploaderPath = Resolve-UploaderPath -CloudType $cloudType -ExplicitPath $UploaderPath -Config $config
$skipUpload = [bool](Select-SettingValue -ExplicitValue $SkipUpload -ConfigValue $null -DefaultValue $false)

if (-not (Test-TextValue $dbName)) {
    throw "DbName is required."
}
if (-not (Test-TextValue $dbUser)) {
    throw "DbUser is required."
}
if (-not (Test-TextValue $resolvedDumpPath)) {
    throw "mysqldump was not found. Set MySqlDumpPath or configure tools.mysqldumpPath."
}

$directories = New-BackupDirectoryMap -OutputRoot $outputRoot
$logFile = Join-Path $directories.Logs ("full_{0}.log" -f (Get-DateStamp))
$timestamp = Get-Timestamp
$sqlFile = Join-Path $directories.Full ("full_{0}.sql" -f $timestamp)
$archiveFile = Join-Path $directories.Full ("full_{0}.sql.zip" -f $timestamp)
$manifestPath = Join-Path $directories.Manifest ("full_{0}.manifest.json" -f $timestamp)

Write-BackupLog -Message ("Starting full backup for database {0}" -f $dbName) -LogFile $logFile
Write-BackupLog -Message ("Using mysqldump: {0}" -f $resolvedDumpPath) -LogFile $logFile

$dumpArguments = @(
    "--host=$dbHost",
    "--port=$dbPort",
    "--user=$dbUser",
    "--single-transaction",
    "--routines",
    "--triggers",
    "--events",
    "--default-character-set=utf8mb4",
    "--hex-blob",
    "--skip-comments",
    "--set-gtid-purged=OFF",
    "--column-statistics=0",
    "--result-file=$sqlFile"
)
if (Test-TextValue $dbPassword) {
    $dumpArguments += "--password=$dbPassword"
}
$dumpArguments += $dbName

& $resolvedDumpPath @dumpArguments
if ($LASTEXITCODE -ne 0) {
    throw "mysqldump failed with exit code $LASTEXITCODE"
}

if (-not (Test-Path $sqlFile)) {
    throw "Backup SQL file was not generated: $sqlFile"
}

$artifactPath = $sqlFile
if ($compressOutput) {
    Compress-Archive -Path $sqlFile -DestinationPath $archiveFile -Force
    if (-not (Test-Path $archiveFile)) {
        throw "Compressed backup file was not generated: $archiveFile"
    }
    Remove-Item -Path $sqlFile -Force
    $artifactPath = $archiveFile
}

$artifactInfo = Get-Item $artifactPath
$artifactHash = Get-FileSha256 -Path $artifactPath
$manifest = New-ManifestBase -BackupType "full" -DbName $dbName -SourceHost $dbHost -ToolVersion (Get-ToolVersion -ExecutablePath $resolvedDumpPath)
$manifest.fileName = $artifactInfo.Name
$manifest.fileSize = $artifactInfo.Length
$manifest.sha256 = $artifactHash
$manifest.localPath = $artifactInfo.FullName
$manifest.relativePath = Get-RelativePathForManifest -BasePath $directories.Root -TargetPath $artifactInfo.FullName
$manifest.compressed = $compressOutput
$manifest.logFile = $logFile
$manifest.retentionDays = $keepLocalDays

if (-not $skipUpload) {
    Write-BackupLog -Message ("Uploading backup with provider {0}" -f $cloudType) -LogFile $logFile
    $uploadResult = & (Join-Path $PSScriptRoot "upload-cloud.ps1") `
        -SourcePath $artifactPath `
        -CloudType $cloudType `
        -CloudBucket $cloudBucket `
        -CloudPath $cloudPath `
        -AccessKey $accessKey `
        -SecretKey $secretKey `
        -Region $region `
        -Endpoint $endpoint `
        -UploaderPath $uploaderPath `
        -LogFile $logFile

    if (-not $uploadResult.success) {
        throw "Upload step returned an unsuccessful result."
    }

    $manifest.uploadStatus = "SUCCESS"
    $manifest.cloudType = $uploadResult.cloudType
    $manifest.cloudPath = $uploadResult.targetPath
    $manifest.cloudProvider = $uploadResult.provider
} else {
    $manifest.uploadStatus = "SKIPPED"
    $manifest.cloudType = $cloudType
    $manifest.cloudPath = $null
    $manifest.cloudProvider = $null
}

$manifest.status = "SUCCESS"
Write-ManifestFile -Path $manifestPath -Manifest $manifest | Out-Null

if (-not $skipUpload) {
    & (Join-Path $PSScriptRoot "upload-cloud.ps1") `
        -SourcePath $manifestPath `
        -CloudType $cloudType `
        -CloudBucket $cloudBucket `
        -CloudPath $cloudPath `
        -AccessKey $accessKey `
        -SecretKey $secretKey `
        -Region $region `
        -Endpoint $endpoint `
        -UploaderPath $uploaderPath `
        -LogFile $logFile | Out-Null
}

if ($keepLocalDays -gt 0) {
    & (Join-Path $PSScriptRoot "cleanup-backups.ps1") `
        -OutputRoot $directories.Root `
        -KeepFullDays $keepLocalDays `
        -KeepIncrementalDays $keepLocalDays `
        -DryRun:$false | Out-Null
}

Write-BackupLog -Message ("Full backup completed: {0}" -f $artifactPath) -LogFile $logFile
[PSCustomObject]@{
    success      = $true
    backupType   = "full"
    artifactPath = $artifactPath
    manifestPath = $manifestPath
    cloudType    = $cloudType
    uploadStatus = $manifest.uploadStatus
}
