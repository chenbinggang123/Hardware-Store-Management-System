[CmdletBinding()]
param(
    [string]$OutputRoot = "target/backups",
    [int]$KeepFullDays = 7,
    [int]$KeepIncrementalDays = 7,
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "common.ps1")

function Remove-ExpiredItems {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [datetime]$CutoffTime,
        [Parameter(Mandatory = $true)]
        [string]$Label,
        [Parameter(Mandatory = $true)]
        [string]$LogFile,
        [switch]$DryRunMode
    )

    if (-not (Test-Path $Path)) {
        return @()
    }

    $items = Get-ChildItem -Path $Path -Force
    $expired = $items | Where-Object { $_.LastWriteTime -lt $CutoffTime }

    foreach ($item in $expired) {
        if ($DryRunMode) {
            Write-BackupLog -Message ("[DryRun] Would remove {0}: {1}" -f $Label, $item.FullName) -LogFile $LogFile
        } else {
            Write-BackupLog -Message ("Removing {0}: {1}" -f $Label, $item.FullName) -LogFile $LogFile
            Remove-Item -LiteralPath $item.FullName -Force -Recurse
        }
    }

    return $expired
}

$directories = New-BackupDirectoryMap -OutputRoot $OutputRoot
$logFile = Join-Path $directories.Logs ("cleanup_{0}.log" -f (Get-DateStamp))
$fullCutoff = (Get-Date).AddDays(-1 * $KeepFullDays)
$incrementalCutoff = (Get-Date).AddDays(-1 * $KeepIncrementalDays)

Write-BackupLog -Message ("Starting backup cleanup under {0}" -f $directories.Root) -LogFile $logFile

$removedFull = Remove-ExpiredItems -Path $directories.Full -CutoffTime $fullCutoff -Label "full backup" -LogFile $logFile -DryRunMode:$DryRun
$removedIncremental = Remove-ExpiredItems -Path $directories.Incremental -CutoffTime $incrementalCutoff -Label "incremental backup" -LogFile $logFile -DryRunMode:$DryRun
$removedRestore = Remove-ExpiredItems -Path $directories.Restore -CutoffTime $incrementalCutoff -Label "restore workspace" -LogFile $logFile -DryRunMode:$DryRun
$removedManifest = Remove-ExpiredItems -Path $directories.Manifest -CutoffTime $incrementalCutoff -Label "manifest file" -LogFile $logFile -DryRunMode:$DryRun

Write-BackupLog -Message "Backup cleanup completed." -LogFile $logFile
[PSCustomObject]@{
    success                  = $true
    outputRoot               = $directories.Root
    removedFullCount         = @($removedFull).Count
    removedIncrementalCount  = @($removedIncremental).Count
    removedRestoreCount      = @($removedRestore).Count
    removedManifestCount     = @($removedManifest).Count
    dryRun                   = [bool]$DryRun
}
