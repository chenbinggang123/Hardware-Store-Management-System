[CmdletBinding()]
param(
    [string]$ConfigPath = "scripts/backup/backup.config.example.json",
    [string]$DbHost,
    [Nullable[int]]$DbPort,
    [string]$DbName,
    [string]$DbUser,
    [string]$DbPassword,
    [string]$OutputRoot,
    [string]$BinlogRoot,
    [string]$FilePattern,
    [string]$MySqlDumpPath,
    [string]$Mode,
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
$dbPort = [int](Select-SettingValue -ExplicitValue $DbPort -ConfigValue (Get-ConfigValue $config "db.port") -DefaultValue 3306)
$dbName = Select-SettingValue -ExplicitValue $DbName -ConfigValue (Get-ConfigValue $config "db.name")
$dbUser = Select-SettingValue -ExplicitValue $DbUser -ConfigValue (Get-ConfigValue $config "db.user")
$dbPassword = Select-SettingValue -ExplicitValue $DbPassword -ConfigValue (Get-ConfigValue $config "db.password") -DefaultValue ""
$outputRoot = Select-SettingValue -ExplicitValue $OutputRoot -ConfigValue (Get-ConfigValue $config "paths.outputRoot") -DefaultValue "target/backups"
$binlogRoot = Select-SettingValue -ExplicitValue $BinlogRoot -ConfigValue (Get-ConfigValue $config "paths.binlogRoot") -DefaultValue ""
$filePattern = Select-SettingValue -ExplicitValue $FilePattern -ConfigValue (Get-ConfigValue $config "incremental.filePattern") -DefaultValue "mysql-bin.*"
$mode = Select-SettingValue -ExplicitValue $Mode -ConfigValue (Get-ConfigValue $config "incremental.mode") -DefaultValue "snapshot"
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

$directories = New-BackupDirectoryMap -OutputRoot $outputRoot
$timestamp = Get-Timestamp
$logFile = Join-Path $directories.Logs ("incremental_{0}.log" -f (Get-DateStamp))
$manifestPath = Join-Path $directories.Manifest ("inc_{0}.manifest.json" -f $timestamp)
$artifactPath = $null
$manifest = $null

Write-BackupLog -Message ("Starting incremental backup in mode: {0}" -f $mode) -LogFile $logFile

switch ($mode.ToLowerInvariant()) {
    "snapshot" {
        if (-not (Test-TextValue $dbName)) {
            throw "DbName is required for snapshot mode."
        }
        if (-not (Test-TextValue $dbUser)) {
            throw "DbUser is required for snapshot mode."
        }
        if (-not (Test-TextValue $resolvedDumpPath)) {
            throw "mysqldump was not found. Set MySqlDumpPath or configure tools.mysqldumpPath."
        }

        $sqlFile = Join-Path $directories.Incremental ("inc_{0}.sql" -f $timestamp)
        $artifactPath = Join-Path $directories.Incremental ("inc_{0}.sql.zip" -f $timestamp)
        $dumpArguments = @(
            "--host=$dbHost",
            "--port=$dbPort",
            "--user=$dbUser",
            "--single-transaction",
            "--default-character-set=utf8mb4",
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
            throw "mysqldump incremental snapshot failed with exit code $LASTEXITCODE"
        }

        Compress-Archive -Path $sqlFile -DestinationPath $artifactPath -Force
        Remove-Item -Path $sqlFile -Force

        $manifest = New-ManifestBase -BackupType "incremental-snapshot" -DbName $dbName -SourceHost $dbHost -ToolVersion (Get-ToolVersion -ExecutablePath $resolvedDumpPath)
    }
    "binlog" {
        if (-not (Test-TextValue $binlogRoot)) {
            throw "BinlogRoot is required for binlog mode."
        }

        $resolvedBinlogRoot = Resolve-BackupPath -PathValue $binlogRoot
        if (-not (Test-Path $resolvedBinlogRoot)) {
            throw "BinlogRoot not found: $resolvedBinlogRoot"
        }

        $stateFile = Join-Path $directories.Manifest "incremental-state.json"
        $state = Read-StateFile -Path $stateFile
        $lastFileName = if ($null -ne $state) { [string](Get-ConfigValue $state "lastFileName" "") } else { "" }

        $candidateFiles = Get-ChildItem -Path $resolvedBinlogRoot -File -Filter $filePattern |
            Where-Object { $_.Name -notlike "*.index" } |
            Sort-Object Name

        if (Test-TextValue $lastFileName) {
            $candidateFiles = $candidateFiles | Where-Object { $_.Name -gt $lastFileName }
        }

        if (@($candidateFiles).Count -eq 0) {
            Write-BackupLog -Message "No new binlog files were found. Incremental backup skipped." -LogFile $logFile
            [PSCustomObject]@{
                success = $true
                skipped = $true
                mode    = "binlog"
            }
            return
        }

        $stagingDirectory = Ensure-Directory (Join-Path $directories.Incremental ("inc_{0}" -f $timestamp))
        foreach ($candidateFile in $candidateFiles) {
            Copy-Item -Path $candidateFile.FullName -Destination (Join-Path $stagingDirectory $candidateFile.Name) -Force
        }

        $artifactPath = Join-Path $directories.Incremental ("inc_{0}.zip" -f $timestamp)
        Compress-Archive -Path (Join-Path $stagingDirectory "*") -DestinationPath $artifactPath -Force
        Remove-Item -Path $stagingDirectory -Recurse -Force

        $manifest = New-ManifestBase -BackupType "incremental-binlog" -DbName (Select-SettingValue -ExplicitValue $DbName -ConfigValue (Get-ConfigValue $config "db.name") -DefaultValue "unknown") -SourceHost $dbHost -ToolVersion "binlog-copy"
        $manifest.filePattern = $filePattern
        $manifest.lastFileName = ($candidateFiles | Select-Object -Last 1 -ExpandProperty Name)

        Write-StateFile -Path $stateFile -State ([ordered]@{
            lastFileName = $manifest.lastFileName
            updatedAt    = (Get-Date).ToString("s")
        })
    }
    default {
        throw "Unsupported incremental mode: $mode"
    }
}

$artifactInfo = Get-Item $artifactPath
$manifest.fileName = $artifactInfo.Name
$manifest.fileSize = $artifactInfo.Length
$manifest.sha256 = Get-FileSha256 -Path $artifactPath
$manifest.localPath = $artifactInfo.FullName
$manifest.relativePath = Get-RelativePathForManifest -BasePath $directories.Root -TargetPath $artifactInfo.FullName

if (-not $skipUpload) {
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
        throw "Incremental upload step returned an unsuccessful result."
    }

    $manifest.uploadStatus = "SUCCESS"
    $manifest.cloudType = $uploadResult.cloudType
    $manifest.cloudPath = $uploadResult.targetPath
    $manifest.cloudProvider = $uploadResult.provider
} else {
    $manifest.uploadStatus = "SKIPPED"
    $manifest.cloudType = $cloudType
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

Write-BackupLog -Message ("Incremental backup completed: {0}" -f $artifactPath) -LogFile $logFile
[PSCustomObject]@{
    success      = $true
    backupType   = $manifest.backupType
    artifactPath = $artifactPath
    manifestPath = $manifestPath
    mode         = $mode
}
