<#
    kubastion - start everything with one command (Windows).

    Run start.bat (double-click) or this script directly. It starts the backend,
    starts the frontend dev server, waits until both actually answer, then opens
    the browser. Ctrl+C stops both.

    Only Java 21+ and Node 20+ are required: Maven comes from the wrapper
    committed in backend/, and npm install runs by itself the first time.

    Usage:  .\start.ps1  [-BackendPort 8080] [-FrontendPort 4200] [-NoBrowser]
#>

[CmdletBinding()]
param(
    [int]$BackendPort = 8080,
    [int]$FrontendPort = 4200,
    [switch]$NoBrowser
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$logDir = Join-Path $root '.logs'
$children = @()

function Say($text, $colour = 'Gray') { Write-Host $text -ForegroundColor $colour }

# The dev server binds "localhost", which on Windows means ::1, while the
# backend binds 127.0.0.1. Both families have to be probed, and each one needs a
# socket opened for its own address family - a TcpClient built for IPv4 cannot
# reach ::1, and resolving "localhost" only ever yields the first address.
function Test-Port([int]$port) {
    foreach ($address in @([System.Net.IPAddress]::Loopback, [System.Net.IPAddress]::IPv6Loopback)) {
        $client = New-Object System.Net.Sockets.TcpClient($address.AddressFamily)
        try {
            $client.Connect($address, $port)
            return $true
        } catch {
        } finally {
            $client.Dispose()
        }
    }
    return $false
}

function Wait-Port([int]$port, [int]$seconds, $process) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Port $port) { return $true }
        if ($process -and $process.HasExited) { return $false }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

# On Windows, `npm` on PATH can resolve to the extensionless shell script, which
# Start-Process cannot launch. Only .cmd/.bat/.exe are usable here.
function Resolve-Npm {
    foreach ($name in @('npm.cmd', 'npm.exe', 'npm')) {
        $found = Get-Command $name -ErrorAction SilentlyContinue
        if ($found -and $found.Source -and (Test-Path $found.Source)) {
            $extension = [System.IO.Path]::GetExtension($found.Source).ToLower()
            if ($extension -eq '.cmd' -or $extension -eq '.bat' -or $extension -eq '.exe') {
                return $found.Source
            }
        }
    }
    return $null
}

function Start-Piece($file, $arguments, $workdir, $name) {
    $process = Start-Process -FilePath $file -ArgumentList $arguments `
        -WorkingDirectory $workdir -PassThru -NoNewWindow `
        -RedirectStandardOutput (Join-Path $logDir "$name.log") `
        -RedirectStandardError  (Join-Path $logDir "$name.err.log")
    $script:children += $process
    return $process
}

function Show-Log($name) {
    foreach ($suffix in @('.log', '.err.log')) {
        $path = Join-Path $logDir "$name$suffix"
        if (Test-Path $path) {
            $lines = Get-Content $path -Tail 12 -ErrorAction SilentlyContinue
            if ($lines) {
                Say "  --- $name$suffix ---" DarkGray
                foreach ($line in $lines) { Say "  $line" DarkGray }
            }
        }
    }
}

# taskkill /T because mvnw.cmd and npm.cmd each spawn the process that really
# holds the port; killing only the launcher would leave it running.
function Stop-Children($procs) {
    foreach ($proc in $procs) {
        if ($proc -and -not $proc.HasExited) {
            cmd /c "taskkill /PID $($proc.Id) /T /F >nul 2>&1"
        }
    }
}

$exitCode = 0
try {
    Write-Host ""
    Say "  kubastion" Cyan
    Write-Host ""

    # --- prerequisites ------------------------------------------------------
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        throw "Java 21+ not found on PATH. Install a JDK from https://adoptium.net and reopen this window."
    }
    $javaBanner = cmd /c "java -version 2>&1"
    $javaMajor = 0
    if ("$javaBanner" -match '"(\d+)') { $javaMajor = [int]$Matches[1] }
    if ($javaMajor -gt 0 -and $javaMajor -lt 21) {
        throw "Java $javaMajor found, but 21 or newer is required. See https://adoptium.net"
    }

    if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
        throw "Node.js 20+ not found on PATH. Install it from https://nodejs.org and reopen this window."
    }
    $npm = Resolve-Npm
    if (-not $npm) { throw "npm not found on PATH, although node is installed." }

    if (Test-Port $BackendPort)  { throw "Port $BackendPort is already in use. Is kubastion already running?" }
    if (Test-Port $FrontendPort) { throw "Port $FrontendPort is already in use. Close what is using it, or pass -FrontendPort." }

    # --- first-run setup ----------------------------------------------------
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null

    $config = Join-Path $root 'config.yml'
    if (-not (Test-Path $config)) {
        Copy-Item (Join-Path $root 'config.example.yml') $config
        Say "  config.yml created from the example - set your namespace in it." Yellow
    }

    if (-not (Test-Path (Join-Path $root 'frontend\node_modules'))) {
        Say "  installing frontend dependencies (first run only, a few minutes)..." DarkGray
        Push-Location (Join-Path $root 'frontend')
        try {
            & $npm install --no-fund --no-audit
            if ($LASTEXITCODE -ne 0) { throw "npm install failed." }
        } finally {
            Pop-Location
        }
    }

    # --- backend ------------------------------------------------------------
    Say "  starting backend  on port $BackendPort ..." DarkGray
    $backend = Start-Piece (Join-Path $root 'backend\mvnw.cmd') `
        @('spring-boot:run', "-Dspring-boot.run.arguments=--server.port=$BackendPort") `
        (Join-Path $root 'backend') 'backend'

    if (-not (Wait-Port $BackendPort 180 $backend)) {
        Show-Log 'backend'
        throw "The backend did not come up on port $BackendPort."
    }
    Say "  backend  ready" Green

    # --- frontend -----------------------------------------------------------
    Say "  starting frontend on port $FrontendPort ..." DarkGray
    $frontend = Start-Piece $npm @('start', '--', '--port', "$FrontendPort") `
        (Join-Path $root 'frontend') 'frontend'

    if (-not (Wait-Port $FrontendPort 240 $frontend)) {
        Show-Log 'frontend'
        throw "The frontend did not come up on port $FrontendPort."
    }
    Say "  frontend ready" Green

    # 127.0.0.1, not localhost: the dev server binds IPv4 (see angular.json), so
    # opening the IPv4 literal avoids the dual-stack race where the browser
    # reaches for ::1 and the WebSocket upgrade is refused.
    $url = "http://127.0.0.1:$FrontendPort"
    if (-not $NoBrowser) { Start-Process $url | Out-Null }

    Write-Host ""
    Say "  kubastion is running at $url" Cyan
    Say "  Log in through the terminal as you always do, then press Start monitoring." DarkGray
    Say "  Logs: .logs\ - press Ctrl+C here to stop both." DarkGray
    Write-Host ""

    while ($true) {
        Start-Sleep -Seconds 1
        if ($backend.HasExited)  { Say "  backend stopped."  Yellow; Show-Log 'backend';  break }
        if ($frontend.HasExited) { Say "  frontend stopped." Yellow; Show-Log 'frontend'; break }
    }
} catch {
    Write-Host ""
    Write-Host "  x $($_.Exception.Message)" -ForegroundColor Red
    Write-Host ""
    $exitCode = 1
} finally {
    Stop-Children $children
    Say "  stopped." DarkGray
}

exit $exitCode
