param(
    [switch]$CompileOnly,
    [int]$Port = 8084
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$agentConfigPath = Join-Path $projectRoot "agent.local.env"
if (Test-Path $agentConfigPath) {
    foreach ($line in Get-Content -Path $agentConfigPath) {
        $trimmedLine = $line.Trim()
        if (-not $trimmedLine -or $trimmedLine.StartsWith('#')) { continue }
        $entry = $trimmedLine -split '=', 2
        if ($entry.Count -ne 2) { continue }
        $name = $entry[0].Trim()
        $value = $entry[1].Trim()
        if ($name -notmatch '^(AGENT_(MODEL|ROUTING)_|TABLE_OCR_|TENCENT_OCR_|OCR_PROVIDER$)') { continue }
        if (-not $value) { continue }
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
    Write-Host "Loaded local Agent settings from agent.local.env (file is excluded from Git)."
}
if ($env:AGENT_MODEL_ENABLED -eq 'true' -and [string]::IsNullOrWhiteSpace($env:AGENT_MODEL_API_KEY)) {
    $env:AGENT_MODEL_ENABLED = 'false'
    Write-Warning "AGENT_MODEL_API_KEY is empty. Model calls are disabled for this run; fill the key in agent.local.env and restart to enable them."
}
$sourceRoot = Join-Path $projectRoot "src\main\java"
$resourceRoot = Join-Path $projectRoot "src\main\resources"
$targetRoot = Join-Path $projectRoot "target"
$classesRoot = Join-Path $targetRoot "classes"
# Use one argument directory per launcher process so a stale Windows process cannot
# keep the next restart from updating javac/java argument files.
$argRoot = Join-Path $targetRoot ("codex-run-" + $PID)
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
            # Compare numeric version components; lexical ordering incorrectly puts 4.7.2
            # ahead of 4.13.0 and can mix incompatible ANTLR runtimes on the classpath.
            Sort-Object {
                $numericVersion = [regex]::Match($_.Version, '^\d+(?:\.\d+){0,3}')
                if ($numericVersion.Success) {
                    $components = $numericVersion.Value.Split('.')
                    while ($components.Count -lt 4) { $components += '0' }
                    [version]($components -join '.')
                } else {
                    [version]'0.0.0.0'
                }
            } -Descending |
            Select-Object -First 1 -ExpandProperty FullName
    }
if (-not $jarPaths) {
    throw "No jar files were found in the local Maven cache."
}

$javaFiles = Get-ChildItem -Path $sourceRoot -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
if (-not $javaFiles) {
    throw "No Java source files were found."
}

$lombokJar = $jarPaths | Where-Object { $_ -match '\\org\\projectlombok\\lombok\\' } | Select-Object -First 1
if (-not $lombokJar) {
    throw "Lombok jar was not found in the local Maven cache."
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
    "-processorpath"
    $lombokJar
    "-processor"
    'lombok.launch.AnnotationProcessorHider$AnnotationProcessor'
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
