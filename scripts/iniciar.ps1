$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "cargar-env.ps1")
Import-PapeleriaEnv -Path (Join-Path $projectRoot ".env")

$jar = Join-Path $projectRoot "backend\target\papeleria-1.0.0.jar"
$port = 8080
$baseUrl = "http://localhost:$port"
# La app escucha solo en IPv4 (server.address: 127.0.0.1). Con Invoke-WebRequest,
# "localhost" puede resolverse primero a ::1 y agotar el tiempo de espera.
$probeUrl = "http://127.0.0.1:$port"
$statusUrl = "$probeUrl/api/v1/health"

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

function Test-HttpsHealth {
    param([string]$Url)
    try {
        # Tailscale exige TLS 1.2+; Windows PowerShell 5.1 puede no activarlo por defecto.
        [Net.ServicePointManager]::SecurityProtocol = `
            [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        $response = Invoke-WebRequest -UseBasicParsing -Uri "$Url/api/v1/health" -TimeoutSec 4
        return $response.StatusCode -eq 200
    } catch {
        return $false
    }
}

# Dominio Tailscale: primero el estado real de Tailscale; si no, el de .env.
function Get-BrowserUrl {
    $dns = $null
    if (Get-Command tailscale -ErrorAction SilentlyContinue) {
        try {
            $status = tailscale status --json 2>$null | ConvertFrom-Json
            if ($status.Self -and $status.Self.DNSName) {
                $dns = $status.Self.DNSName.TrimEnd('.')
            }
        } catch { }
    }
    if (-not $dns -and $env:PAPELERIA_ALLOWED_HOSTS) {
        $hosts = @(($env:PAPELERIA_ALLOWED_HOSTS -split ',') |
            ForEach-Object { $_.Trim() -replace ':\d+$', '' } |
            Where-Object { $_ -and $_ -notin @('localhost', '127.0.0.1', '::1') -and $_.Contains('.') })
        $dns = $hosts | Where-Object { $_ -like '*.ts.net' } | Select-Object -First 1
        if (-not $dns) { $dns = $hosts | Select-Object -First 1 }
    }
    if ($dns) {
        $httpsUrl = "https://$dns"
        if (Test-HttpsHealth $httpsUrl) { return $httpsUrl }
        Write-Warning "$httpsUrl no responde. Se abrira $baseUrl. Revise Tailscale Serve y PAPELERIA_ALLOWED_HOSTS."
    }
    return $baseUrl
}

function Open-App {
    if ($env:PAPELERIA_NO_BROWSER -eq "1") { return }
    $url = Get-BrowserUrl
    Write-Host "Abriendo $url"
    Start-Process $url
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
    $ownerId = $busy[0].OwningProcess
    $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$ownerId" -ErrorAction SilentlyContinue
    if ($owner -and $owner.Name -match '^javaw?\.exe$' -and $owner.CommandLine -match 'papeleria-1\.0\.0\.jar') {
        # Doble clic mientras arrancaba: esperar en vez de tratarlo como un puerto ajeno.
        Write-Host "Papeleria esta arrancando (PID $ownerId). Esperando a que responda..."
        $deadline = (Get-Date).AddSeconds(60)
        while ((Get-Date) -lt $deadline) {
            if (Test-PapeleriaRunning) { Open-App; exit 0 }
            Start-Sleep -Milliseconds 500
        }
        Write-Warning "La aplicacion no responde en $probeUrl. Se abrira $baseUrl."
        Open-App
        exit 0
    }
    Write-Error "El puerto $port esta ocupado por otro programa (PID $ownerId). Cierrelo e intentelo de nuevo."
    exit 1
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dbPath) | Out-Null
Write-Host "Papeleria - saldo de clientes"
Write-Host "  Local:          $baseUrl"
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
