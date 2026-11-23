$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $projectRoot "backend\target\papeleria-1.0.0.jar"
$port = 8080
$baseUrl = "http://localhost:$port"
$statusUrl = "$baseUrl/api/v1/health"

# Base de datos: PAPELERIA_DB si existe; si no, una carpeta del usuario (no requiere administrador).
if ($env:PAPELERIA_DB) { $dbPath = $env:PAPELERIA_DB }
else { $dbPath = Join-Path $env:LOCALAPPDATA "Papeleria\data\papeleria.db" }

function Test-PapeleriaRunning {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $statusUrl -TimeoutSec 2
        return $response.StatusCode -eq 200
    } catch {
        return $false
    }
}

function Open-App {
    if ($env:PAPELERIA_NO_BROWSER -ne "1") { Start-Process $baseUrl }
}

# En Windows, "java" puede ser un lanzador que crea un proceso hijo: se localiza por linea de comandos.
function Stop-Papeleria {
    Get-CimInstance Win32_Process | Where-Object {
        $_.Name -match '^javaw?\.exe$' -and $_.CommandLine -match 'papeleria-1\.0\.0\.jar'
    } | ForEach-Object {
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }
}

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Error "Java no esta instalado o no esta en el PATH. Se necesita Java 21 o superior."
    exit 1
}

if (-not (Test-Path -LiteralPath $jar)) {
    Write-Error "No se encuentra $jar. Ejecute primero construir.bat"
    exit 1
}

if (Test-PapeleriaRunning) {
    Write-Host "Papeleria ya esta ejecutandose en $baseUrl"
    Open-App
    exit 0
}

$busy = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
if ($busy) {
    Write-Error "El puerto $port esta ocupado por otro programa (PID $($busy[0].OwningProcess)). Cierrelo e intentelo de nuevo."
    exit 1
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dbPath) | Out-Null
Write-Host "Papeleria - saldo de clientes"
Write-Host "  Direccion:      $baseUrl"
Write-Host "  Base de datos:  $dbPath"
Write-Host "  Para parar:     cierre esta ventana o pulse Ctrl+C"
Write-Host ""

$javaProcess = $null
try {
    $javaProcess = Start-Process -FilePath "java" -WorkingDirectory $projectRoot `
        -ArgumentList @("-jar", "`"$jar`"", "--app.db-path=`"$dbPath`"", "--server.port=$port") `
        -NoNewWindow -PassThru

    $deadline = (Get-Date).AddSeconds(60)
    $opened = $false
    while ((Get-Date) -lt $deadline -and -not $javaProcess.HasExited) {
        if (Test-PapeleriaRunning) {
            Open-App
            $opened = $true
            break
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $opened -and -not $javaProcess.HasExited) {
        Write-Warning "La aplicacion tarda mas de 60 s en responder. Revise los mensajes de arriba."
    }

    $javaProcess.WaitForExit()
    exit $javaProcess.ExitCode
} finally {
    Stop-Papeleria
    if ($javaProcess -and -not $javaProcess.HasExited) {
        Stop-Process -Id $javaProcess.Id -Force -ErrorAction SilentlyContinue
    }
}
