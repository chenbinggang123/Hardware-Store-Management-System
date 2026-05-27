[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$SourcePath,
    [string]$CloudType = "filesystem",
    [string]$CloudBucket,
    [string]$CloudPath,
    [string]$AccessKey,
    [string]$SecretKey,
    [string]$Region,
    [string]$Endpoint,
    [string]$UploaderPath,
    [string]$LogFile
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "common.ps1")

function Join-ObjectPath {
    param(
        [AllowNull()]
        [AllowEmptyString()]
        [string[]]$Parts
    )

    $segments = @()
    foreach ($part in $Parts) {
        if (Test-TextValue $part) {
            $trimmed = $part.Trim().Trim('/').Trim('\')
            if (Test-TextValue $trimmed) {
                $segments += $trimmed
            }
        }
    }

    return ($segments -join "/")
}

if (-not (Test-Path $SourcePath)) {
    throw "Upload source file not found: $SourcePath"
}

$fileName = Split-Path -Leaf $SourcePath
$cloudType = if (Test-TextValue $CloudType) { $CloudType.ToLowerInvariant() } else { "filesystem" }
$result = [ordered]@{
    success    = $false
    cloudType  = $cloudType
    sourcePath = (Resolve-Path $SourcePath).Path
    targetPath = $null
    provider   = $null
}

switch ($cloudType) {
    "filesystem" {
        if (-not (Test-TextValue $CloudPath)) {
            throw "CloudPath is required when CloudType is filesystem."
        }

        $targetDirectory = Resolve-BackupPath -PathValue $CloudPath
        if (Test-TextValue $CloudBucket) {
            $targetDirectory = Join-Path $targetDirectory $CloudBucket
        }
        Ensure-Directory $targetDirectory | Out-Null

        $targetPath = Join-Path $targetDirectory $fileName
        Copy-Item -Path $SourcePath -Destination $targetPath -Force

        $result.success = $true
        $result.provider = "filesystem"
        $result.targetPath = $targetPath
    }
    "aws-s3" {
        if (-not (Test-TextValue $CloudBucket)) {
            throw "CloudBucket is required when CloudType is aws-s3."
        }

        $awsCliPath = Resolve-ExecutablePath -PreferredPath $UploaderPath -CommandNames @("aws")
        if (-not (Test-TextValue $awsCliPath)) {
            throw "AWS CLI was not found. Set UploaderPath or install aws."
        }

        $objectKey = Join-ObjectPath -Parts @($CloudPath, $fileName)
        $targetUri = "s3://{0}/{1}" -f $CloudBucket, $objectKey
        $arguments = @("s3", "cp", $SourcePath, $targetUri)
        if (Test-TextValue $Region) {
            $arguments += @("--region", $Region)
        }

        & $awsCliPath @arguments
        if ($LASTEXITCODE -ne 0) {
            throw "AWS CLI upload failed with exit code $LASTEXITCODE"
        }

        $result.success = $true
        $result.provider = "aws-cli"
        $result.targetPath = $targetUri
    }
    "aliyun-oss" {
        if (-not (Test-TextValue $CloudBucket)) {
            throw "CloudBucket is required when CloudType is aliyun-oss."
        }

        $ossUtilPath = Resolve-ExecutablePath -PreferredPath $UploaderPath -CommandNames @("ossutil", "ossutil64")
        if (-not (Test-TextValue $ossUtilPath)) {
            throw "ossutil was not found. Set UploaderPath or install ossutil."
        }

        $objectKey = Join-ObjectPath -Parts @($CloudPath, $fileName)
        $targetUri = "oss://{0}/{1}" -f $CloudBucket, $objectKey
        $arguments = @("cp", $SourcePath, $targetUri)
        if (Test-TextValue $Endpoint) {
            $arguments += @("-e", $Endpoint)
        }
        if (Test-TextValue $AccessKey) {
            $arguments += @("-i", $AccessKey)
        }
        if (Test-TextValue $SecretKey) {
            $arguments += @("-k", $SecretKey)
        }

        & $ossUtilPath @arguments
        if ($LASTEXITCODE -ne 0) {
            throw "ossutil upload failed with exit code $LASTEXITCODE"
        }

        $result.success = $true
        $result.provider = "ossutil"
        $result.targetPath = $targetUri
    }
    "tencent-cos" {
        if (-not (Test-TextValue $CloudBucket)) {
            throw "CloudBucket is required when CloudType is tencent-cos."
        }

        $cosCliPath = Resolve-ExecutablePath -PreferredPath $UploaderPath -CommandNames @("coscli")
        if (-not (Test-TextValue $cosCliPath)) {
            throw "coscli was not found. Set UploaderPath or install coscli."
        }

        $objectKey = Join-ObjectPath -Parts @($CloudPath, $fileName)
        $targetUri = "cos://{0}/{1}" -f $CloudBucket, $objectKey
        $arguments = @("cp", $SourcePath, $targetUri)
        if (Test-TextValue $Region) {
            $arguments += @("-r", $Region)
        }
        if (Test-TextValue $AccessKey) {
            $arguments += @("-a", $AccessKey)
        }
        if (Test-TextValue $SecretKey) {
            $arguments += @("-s", $SecretKey)
        }

        & $cosCliPath @arguments
        if ($LASTEXITCODE -ne 0) {
            throw "coscli upload failed with exit code $LASTEXITCODE"
        }

        $result.success = $true
        $result.provider = "coscli"
        $result.targetPath = $targetUri
    }
    "huaweicloud-obs" {
        if (-not (Test-TextValue $CloudBucket)) {
            throw "CloudBucket is required when CloudType is huaweicloud-obs."
        }

        $obsUtilPath = Resolve-ExecutablePath -PreferredPath $UploaderPath -CommandNames @("obsutil")
        if (-not (Test-TextValue $obsUtilPath)) {
            throw "obsutil was not found. Set UploaderPath or install obsutil."
        }

        $objectKey = Join-ObjectPath -Parts @($CloudPath, $fileName)
        $targetUri = "obs://{0}/{1}" -f $CloudBucket, $objectKey
        $arguments = @("cp", $SourcePath, $targetUri)
        if (Test-TextValue $Endpoint) {
            $arguments += @("-e=$Endpoint")
        }
        if (Test-TextValue $AccessKey) {
            $arguments += @("-i=$AccessKey")
        }
        if (Test-TextValue $SecretKey) {
            $arguments += @("-k=$SecretKey")
        }

        & $obsUtilPath @arguments
        if ($LASTEXITCODE -ne 0) {
            throw "obsutil upload failed with exit code $LASTEXITCODE"
        }

        $result.success = $true
        $result.provider = "obsutil"
        $result.targetPath = $targetUri
    }
    default {
        throw "Unsupported CloudType: $CloudType"
    }
}

Write-BackupLog -Message ("Upload completed: {0}" -f $result.targetPath) -LogFile $LogFile
[PSCustomObject]$result
