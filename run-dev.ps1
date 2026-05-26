param(
    [switch]$CompileOnly,
    [int]$Port = 8084
)

$moduleScript = Join-Path $PSScriptRoot "springboot-demo\run-dev.ps1"
if (-not (Test-Path $moduleScript)) {
    throw "Module script not found: $moduleScript"
}

$invokeParams = @{
    Port = $Port
}

if ($CompileOnly) {
    $invokeParams.CompileOnly = $true
}

& $moduleScript @invokeParams
