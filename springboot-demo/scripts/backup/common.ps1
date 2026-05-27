Set-StrictMode -Version Latest

function Test-TextValue {
    param(
        [AllowNull()]
        [AllowEmptyString()]
        [string]$Value
    )

    return -not [string]::IsNullOrWhiteSpace($Value)
}

function Get-BackupProjectRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}

function Resolve-BackupPath {
    param(
        [AllowNull()]
        [AllowEmptyString()]
        [string]$PathValue,
        [string]$BasePath = (Get-BackupProjectRoot)
    )

    if (-not (Test-TextValue $PathValue)) {
        return $null
    }

    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return $PathValue
    }

    return (Join-Path $BasePath $PathValue)
}

function Ensure-Directory {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path $Path)) {
        New-Item -ItemType Directory -Path $Path -Force | Out-Null
    }

    return (Resolve-Path $Path).Path
}

function Get-Timestamp {
    return (Get-Date).ToString("yyyyMMdd_HHmmss")
}

function Get-DateStamp {
    return (Get-Date).ToString("yyyyMMdd")
}

function Write-BackupLog {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Message,
        [string]$Level = "INFO",
        [AllowNull()]
        [string]$LogFile
    )

    $line = "[{0}] [{1}] {2}" -f (Get-Date).ToString("yyyy-MM-dd HH:mm:ss"), $Level.ToUpperInvariant(), $Message
    Write-Host $line

    if (Test-TextValue $LogFile) {
        $logDir = Split-Path -Parent $LogFile
        Ensure-Directory $logDir | Out-Null
        Add-Content -Path $LogFile -Value $line -Encoding Ascii
    }
}

function Get-FileSha256 {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    return (Get-FileHash -Path $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Save-JsonFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [object]$Data
    )

    $directory = Split-Path -Parent $Path
    if (Test-TextValue $directory) {
        Ensure-Directory $directory | Out-Null
    }

    $json = $Data | ConvertTo-Json -Depth 10
    Set-Content -Path $Path -Value $json -Encoding UTF8
}

function Write-ManifestFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [object]$Manifest
    )

    Save-JsonFile -Path $Path -Data $Manifest
    return (Resolve-Path $Path).Path
}

function Read-JsonFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    return (Get-Content -Raw $Path | ConvertFrom-Json)
}

function Read-BackupConfig {
    param(
        [AllowNull()]
        [AllowEmptyString()]
        [string]$ConfigPath
    )

    if (-not (Test-TextValue $ConfigPath)) {
        return $null
    }

    $resolvedPath = Resolve-BackupPath -PathValue $ConfigPath -BasePath (Get-BackupProjectRoot)
    if (-not (Test-Path $resolvedPath)) {
        throw "Backup config file not found: $resolvedPath"
    }

    return Read-JsonFile -Path $resolvedPath
}

function Get-ConfigValue {
    param(
        [AllowNull()]
        [object]$Config,
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [AllowNull()]
        [object]$Default = $null
    )

    if ($null -eq $Config) {
        return $Default
    }

    $current = $Config
    foreach ($segment in ($Path -split '\.')) {
        if ($null -eq $current) {
            return $Default
        }

        if ($current -is [System.Collections.IDictionary]) {
            if (-not $current.Contains($segment)) {
                return $Default
            }

            $current = $current[$segment]
            continue
        }

        $property = $current.PSObject.Properties[$segment]
        if ($null -eq $property) {
            return $Default
        }

        $current = $property.Value
    }

    if ($null -eq $current) {
        return $Default
    }

    return $current
}

function Select-SettingValue {
    param(
        [AllowNull()]
        [object]$ExplicitValue,
        [AllowNull()]
        [object]$ConfigValue,
        [AllowNull()]
        [object]$DefaultValue = $null
    )

    if ($null -ne $ExplicitValue) {
        if ($ExplicitValue -is [string]) {
            if (Test-TextValue $ExplicitValue) {
                return $ExplicitValue
            }
        } else {
            return $ExplicitValue
        }
    }

    if ($null -ne $ConfigValue) {
        if ($ConfigValue -is [string]) {
            if (Test-TextValue $ConfigValue) {
                return $ConfigValue
            }
        } else {
            return $ConfigValue
        }
    }

    return $DefaultValue
}

function Test-CommandExists {
    param(
        [Parameter(Mandatory = $true)]
        [string]$CommandName
    )

    return $null -ne (Get-Command $CommandName -ErrorAction SilentlyContinue)
}

function Resolve-ExecutablePath {
    param(
        [AllowNull()]
        [AllowEmptyString()]
        [string]$PreferredPath,
        [string[]]$CommandNames = @(),
        [string[]]$CandidatePaths = @()
    )

    if (Test-TextValue $PreferredPath) {
        $resolvedPreferred = Resolve-BackupPath -PathValue $PreferredPath -BasePath (Get-BackupProjectRoot)
        if (Test-Path $resolvedPreferred) {
            return $resolvedPreferred
        }
    }

    foreach ($commandName in $CommandNames) {
        $command = Get-Command $commandName -ErrorAction SilentlyContinue
        if ($null -ne $command -and (Test-Path $command.Source)) {
            return $command.Source
        }
    }

    foreach ($candidatePath in $CandidatePaths) {
        if (Test-Path $candidatePath) {
            return $candidatePath
        }
    }

    return $null
}

function Resolve-MySqlDumpPath {
    param(
        [AllowNull()]
        [AllowEmptyString()]
        [string]$PreferredPath
    )

    return Resolve-ExecutablePath -PreferredPath $PreferredPath -CommandNames @("mysqldump") -CandidatePaths @(
        "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe",
        "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqldump.exe",
        "C:\Program Files\MySQL\MySQL Server 9.0\bin\mysqldump.exe"
    )
}

function Resolve-MySqlCliPath {
    param(
        [AllowNull()]
        [AllowEmptyString()]
        [string]$PreferredPath
    )

    return Resolve-ExecutablePath -PreferredPath $PreferredPath -CommandNames @("mysql") -CandidatePaths @(
        "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
        "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe",
        "C:\Program Files\MySQL\MySQL Server 9.0\bin\mysql.exe"
    )
}

function Resolve-UploaderPath {
    param(
        [AllowNull()]
        [AllowEmptyString()]
        [string]$CloudType,
        [AllowNull()]
        [AllowEmptyString()]
        [string]$ExplicitPath,
        [AllowNull()]
        [object]$Config
    )

    if (Test-TextValue $ExplicitPath) {
        return Resolve-BackupPath -PathValue $ExplicitPath -BasePath (Get-BackupProjectRoot)
    }

    if (-not (Test-TextValue $CloudType)) {
        return ""
    }

    switch ($CloudType.ToLowerInvariant()) {
        "aws-s3" {
            return [string](Select-SettingValue -ExplicitValue $null -ConfigValue (Get-ConfigValue $Config "tools.awsCliPath") -DefaultValue "")
        }
        "aliyun-oss" {
            return [string](Select-SettingValue -ExplicitValue $null -ConfigValue (Get-ConfigValue $Config "tools.ossutilPath") -DefaultValue "")
        }
        "tencent-cos" {
            return [string](Select-SettingValue -ExplicitValue $null -ConfigValue (Get-ConfigValue $Config "tools.coscliPath") -DefaultValue "")
        }
        "huaweicloud-obs" {
            return [string](Select-SettingValue -ExplicitValue $null -ConfigValue (Get-ConfigValue $Config "tools.obsutilPath") -DefaultValue "")
        }
        default {
            return ""
        }
    }
}

function Get-AppVersion {
    $pomPath = Join-Path (Get-BackupProjectRoot) "pom.xml"
    if (-not (Test-Path $pomPath)) {
        return "unknown"
    }

    try {
        [xml]$pom = Get-Content -Raw $pomPath
        if ($null -ne $pom.project.version -and (Test-TextValue $pom.project.version)) {
            return [string]$pom.project.version
        }
    } catch {
    }

    return "unknown"
}

function Get-ToolVersion {
    param(
        [AllowNull()]
        [AllowEmptyString()]
        [string]$ExecutablePath,
        [string[]]$Arguments = @("--version")
    )

    if (-not (Test-TextValue $ExecutablePath) -or -not (Test-Path $ExecutablePath)) {
        return "unknown"
    }

    try {
        $output = & $ExecutablePath @Arguments 2>&1
        if ($LASTEXITCODE -eq 0 -and $output) {
            return ($output | Select-Object -First 1).ToString().Trim()
        }
    } catch {
    }

    return "unknown"
}

function New-BackupDirectoryMap {
    param(
        [AllowNull()]
        [AllowEmptyString()]
        [string]$OutputRoot
    )

    $resolvedRoot = Resolve-BackupPath -PathValue (Select-SettingValue -ExplicitValue $OutputRoot -ConfigValue $null -DefaultValue "target/backups")
    Ensure-Directory $resolvedRoot | Out-Null

    $map = [ordered]@{
        Root        = $resolvedRoot
        Full        = Ensure-Directory (Join-Path $resolvedRoot "full")
        Incremental = Ensure-Directory (Join-Path $resolvedRoot "incremental")
        Restore     = Ensure-Directory (Join-Path $resolvedRoot "restore")
        Logs        = Ensure-Directory (Join-Path $resolvedRoot "logs")
        Manifest    = Ensure-Directory (Join-Path $resolvedRoot "manifest")
    }

    return [PSCustomObject]$map
}

function New-ManifestBase {
    param(
        [Parameter(Mandatory = $true)]
        [string]$BackupType,
        [Parameter(Mandatory = $true)]
        [string]$DbName,
        [Parameter(Mandatory = $true)]
        [string]$SourceHost,
        [Parameter(Mandatory = $true)]
        [string]$ToolVersion
    )

    return [ordered]@{
        backupType   = $BackupType
        generatedAt  = (Get-Date).ToString("s")
        dbName       = $DbName
        sourceHost   = $SourceHost
        appVersion   = Get-AppVersion
        mysqlVersion = $ToolVersion
        status       = "PENDING"
        uploadStatus = "SKIPPED"
    }
}

function Read-StateFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path $Path)) {
        return $null
    }

    return Read-JsonFile -Path $Path
}

function Write-StateFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [object]$State
    )

    Save-JsonFile -Path $Path -Data $State
}

function Get-RelativePathForManifest {
    param(
        [Parameter(Mandatory = $true)]
        [string]$BasePath,
        [Parameter(Mandatory = $true)]
        [string]$TargetPath
    )

    try {
        $baseUri = [System.Uri]((Resolve-Path $BasePath).Path.TrimEnd('\') + '\')
        $targetUri = [System.Uri](Resolve-Path $TargetPath).Path
        return $baseUri.MakeRelativeUri($targetUri).ToString().Replace('/', '\')
    } catch {
        return $TargetPath
    }
}
