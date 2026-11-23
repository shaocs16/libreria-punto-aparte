$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'cargar-env.ps1')

$names = @('PAPELERIA_ALLOWED_HOSTS', 'PAPELERIA_DB', 'PAPELERIA_NO_BROWSER')
$saved = @{}
foreach ($name in $names) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    [Environment]::SetEnvironmentVariable($name, $null, 'Process')
}
$tempFile = Join-Path ([System.IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString() + '.env')
function Assert-Equal($actual, $expected) {
    if ($actual -cne $expected) { throw "Esperado '$expected', obtenido '$actual'." }
}
function Set-TestFile([string]$Text) { [System.IO.File]::WriteAllText($tempFile, $Text) }
function Assert-Invalid([string]$Text) {
    Set-TestFile $Text
    $failed = $false
    try { Import-PapeleriaEnv -Path $tempFile } catch { $failed = $true }
    if (-not $failed) { throw 'Se esperaba un error de formato.' }
}

try {
    Import-PapeleriaEnv -Path $tempFile # Archivo inexistente: solo configuracion por defecto.
    Assert-Equal $env:PAPELERIA_DB $null
    Set-TestFile @'
# comentario

PAPELERIA_ALLOWED_HOSTS=localhost:8080,servidor.ts.net
PAPELERIA_DB = "C:\Datos con espacios\papeleria.db"
PAPELERIA_NO_BROWSER='1'
'@
    Import-PapeleriaEnv -Path $tempFile
    Assert-Equal $env:PAPELERIA_ALLOWED_HOSTS 'localhost:8080,servidor.ts.net'
    Assert-Equal $env:PAPELERIA_DB 'C:\Datos con espacios\papeleria.db'
    Assert-Equal $env:PAPELERIA_NO_BROWSER '1'

    Set-TestFile 'PAPELERIA_DB=C:\otra.db'
    Import-PapeleriaEnv -Path $tempFile
    Assert-Equal $env:PAPELERIA_DB 'C:\Datos con espacios\papeleria.db' # Entorno externo gana.

    Assert-Invalid 'SIN_IGUAL'
    Assert-Invalid 'JAVA_TOOL_OPTIONS=-javaagent:algo'
    Assert-Invalid 'PAPELERIA_DB="sin cerrar'
    Assert-Invalid 'PAPELERIA_DB='
    Assert-Invalid "PAPELERIA_DB=a`nPAPELERIA_DB=b"

    [Environment]::SetEnvironmentVariable('PAPELERIA_DB', $null, 'Process')
    Assert-Invalid "PAPELERIA_DB=no-aplicar.db`nLINEA_INVALIDA"
    Assert-Equal $env:PAPELERIA_DB $null # No deja una carga parcial.

    Set-TestFile 'PAPELERIA_DB=$(throw "no ejecutar")'
    Import-PapeleriaEnv -Path $tempFile
    Assert-Equal $env:PAPELERIA_DB '$(throw "no ejecutar")'
    Write-Host 'OK: carga .env, prioridad, comillas, formato, carga atomica y ausencia de ejecucion.'
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
    if (Test-Path -LiteralPath $tempFile) { Remove-Item -LiteralPath $tempFile }
}
