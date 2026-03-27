param(
    [switch]$CompileOnly,
    [int]$Port = 8080
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourceRoot = Join-Path $projectRoot "src\main\java"
$resourceRoot = Join-Path $projectRoot "src\main\resources"
$targetRoot = Join-Path $projectRoot "target"
$classesRoot = Join-Path $targetRoot "classes"
$argRoot = Join-Path $targetRoot "codex-run"
$m2Repository = Join-Path $env:USERPROFILE ".m2\repository"

if (-not (Test-Path $m2Repository)) {
    throw "Local Maven cache not found: $m2Repository"
}

if (-not (Test-Path $classesRoot)) {
    New-Item -ItemType Directory -Path $classesRoot | Out-Null
}

if (-not (Test-Path $argRoot)) {
    New-Item -ItemType Directory -Path $argRoot | Out-Null
}

$javaCommand = if ($env:JAVA_HOME) {
    Join-Path $env:JAVA_HOME "bin\java.exe"
} else {
    "java"
}

$javacCommand = if ($env:JAVA_HOME) {
    Join-Path $env:JAVA_HOME "bin\javac.exe"
} else {
    "javac"
}

$jarPaths = Get-ChildItem -Path $m2Repository -Recurse -Filter *.jar |
    Where-Object { $_.Name -notmatch '(-sources|-javadoc|-tests)\.jar$' } |
    Where-Object { $_.FullName -notmatch '\\commons-io\\commons-io\\' } |
    ForEach-Object {
        try {
            $stream = [System.IO.File]::OpenRead($_.FullName)
            $stream.Close()
            [PSCustomObject]@{
                ArtifactPath = Split-Path $_.Directory.FullName -Parent
                Version = $_.Directory.Name
                FullName = $_.FullName
            }
        } catch {
        }
    } |
    Group-Object ArtifactPath |
    ForEach-Object {
        $_.Group |
            Sort-Object Version -Descending |
            Select-Object -First 1 -ExpandProperty FullName
    }
if (-not $jarPaths) {
    throw "No jar files were found in the local Maven cache."
}

$javaFiles = Get-ChildItem -Path $sourceRoot -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
if (-not $javaFiles) {
    throw "No Java source files were found."
}

$classPath = (($jarPaths + $classesRoot) | Sort-Object -Unique) -join ";"
$javacArgsPath = Join-Path $argRoot "javac.args"
$javaArgsPath = Join-Path $argRoot "java.args"

Set-Content -Path $javacArgsPath -Value @(
    "--release"
    "17"
    "-encoding"
    "UTF-8"
    "-parameters"
    "-cp"
    $classPath
    "-d"
    $classesRoot
    $javaFiles
) -Encoding Ascii

$javacArgFile = "@$javacArgsPath"
& $javacCommand $javacArgFile
if ($LASTEXITCODE -ne 0) {
    throw "javac failed with exit code $LASTEXITCODE"
}

if (Test-Path $resourceRoot) {
    Copy-Item -Path (Join-Path $resourceRoot "*") -Destination $classesRoot -Recurse -Force
}

if ($CompileOnly) {
    Write-Host "Compilation completed."
    exit 0
}

@(
    "-cp"
    $classPath
    "com.example.demo.DemoApplication"
    "--server.port=$Port"
) | Set-Content -Path $javaArgsPath -Encoding Ascii

$javaArgFile = "@$javaArgsPath"
& $javaCommand $javaArgFile
if ($LASTEXITCODE -ne 0) {
    throw "java failed with exit code $LASTEXITCODE"
}
