param(
    [int]$Port = 3306,
    [string]$RootPassword = "root"
)

$ErrorActionPreference = "Stop"

function Resolve-MySqlRoot {
    $path = "C:\Program Files\MySQL\MySQL Server 8.4"
    if (!(Test-Path $path)) {
        throw "MySQL 8.4 was not found at $path"
    }
    return $path
}

function Resolve-ShortBaseDir {
    $lines = cmd /c 'dir /x "C:\Program Files\MySQL"'
    foreach ($line in $lines) {
        if ($line -match '([A-Z0-9~]+)\s+MySQL Server 8\.4$') {
            return "C:/PROGRA~1/MySQL/$($matches[1])/"
        }
    }
    return "C:/Program Files/MySQL/MySQL Server 8.4/"
}

function Test-MySqlConnection {
    param(
        [string]$MysqlExe,
        [int]$Port,
        [string]$Password
    )

    $args = @("-h", "127.0.0.1", "-P", "$Port", "-u", "root")
    if ($Password -ne $null) {
        $args += "-p$Password"
    }
    $args += "-e"
    $args += "SELECT VERSION();"

    & $MysqlExe @args | Out-Null
    return $LASTEXITCODE -eq 0
}

$mysqlRoot = Resolve-MySqlRoot
$mysqld = Join-Path $mysqlRoot "bin\mysqld.exe"
$mysql = Join-Path $mysqlRoot "bin\mysql.exe"
$mysqlAdmin = Join-Path $mysqlRoot "bin\mysqladmin.exe"
$baseDir = Resolve-ShortBaseDir

$workRoot = Join-Path $PSScriptRoot "target\mysql84"
$dataDir = Join-Path $workRoot "data"
$outLog = Join-Path $workRoot "mysqld.out.log"
$errLog = Join-Path $workRoot "mysqld.err.log"
$pidFile = Join-Path $workRoot "mysqld.pid"
$dataArg = ($dataDir -replace "\\", "/") + "/"
$pidArg = $pidFile -replace "\\", "/"

if (!(Test-Path $workRoot)) {
    New-Item -ItemType Directory -Path $workRoot -Force | Out-Null
}

if (!(Test-Path $dataDir)) {
    New-Item -ItemType Directory -Path $dataDir -Force | Out-Null
}

if (!(Test-Path (Join-Path $dataDir "mysql"))) {
    & $mysqld "--initialize-insecure" "--basedir=$baseDir" "--datadir=$dataArg"
    if ($LASTEXITCODE -ne 0) {
        throw "mysqld initialize failed with exit code $LASTEXITCODE"
    }
}

if (Test-Path $outLog) {
    Remove-Item $outLog -Force
}

if (Test-Path $errLog) {
    Remove-Item $errLog -Force
}

$process = Start-Process $mysqld -ArgumentList @(
    "--basedir=$baseDir",
    "--datadir=$dataArg",
    "--port=$Port",
    "--pid-file=$pidArg",
    "--console"
) -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru

Start-Sleep -Seconds 10

if ($process.HasExited) {
    if (Test-Path $errLog) {
        Get-Content $errLog
    }
    throw "mysqld exited unexpectedly."
}

if (Test-MySqlConnection -MysqlExe $mysql -Port $Port -Password $RootPassword) {
    Write-Host "MySQL is running."
    Write-Host "Port: $Port"
    Write-Host "User: root"
    Write-Host "Password: $RootPassword"
    Write-Host "PID: $($process.Id)"
    exit 0
}

if (Test-MySqlConnection -MysqlExe $mysql -Port $Port -Password $null) {
    & $mysqlAdmin "-h" "127.0.0.1" "-P" "$Port" "-u" "root" "password" $RootPassword | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "mysqladmin set password failed with exit code $LASTEXITCODE"
    }
    Write-Host "MySQL initialized and password configured."
    Write-Host "Port: $Port"
    Write-Host "User: root"
    Write-Host "Password: $RootPassword"
    Write-Host "PID: $($process.Id)"
    exit 0
}

if (Test-Path $errLog) {
    Get-Content $errLog
}

throw "MySQL started but verification failed."
