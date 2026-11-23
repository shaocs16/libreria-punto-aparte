$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$runBat = Join-Path $projectRoot "iniciar.bat"
$desktop = [Environment]::GetFolderPath("Desktop")
$shortcutPath = Join-Path $desktop "Papeleria.lnk"

if (-not (Test-Path -LiteralPath $runBat)) {
    throw "No se encuentra el lanzador: $runBat"
}

if (-not (Test-Path -LiteralPath (Join-Path $projectRoot "backend\target\papeleria-1.0.0.jar"))) {
    Write-Warning "El jar aun no existe. Ejecute construir.bat antes del primer arranque."
}

$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $env:ComSpec
$shortcut.Arguments = '/c ""' + $runBat + '""'
$shortcut.WorkingDirectory = $projectRoot
$shortcut.Description = "Iniciar Papeleria - saldo de clientes"
$shortcut.IconLocation = "$env:SystemRoot\System32\shell32.dll,14"
$shortcut.WindowStyle = 1
$shortcut.Save()

Write-Host "Acceso directo creado correctamente:"
Write-Host "  $shortcutPath"
Write-Host "Proyecto enlazado:"
Write-Host "  $projectRoot"
