# Formato deliberadamente simple: no ejecuta codigo ni expande variables.
function Import-PapeleriaEnv {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) { return }
    $values = @{}
    $lineNumber = 0
    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        $lineNumber++
        $text = $line.Trim()
        if (-not $text -or $text.StartsWith('#')) { continue }
        if ($text -notmatch '^(PAPELERIA_ALLOWED_HOSTS|PAPELERIA_DB|PAPELERIA_NO_BROWSER)\s*=(.*)$') {
            throw "Configuracion .env no valida en linea $lineNumber. Use PAPELERIA_ALLOWED_HOSTS, PAPELERIA_DB o PAPELERIA_NO_BROWSER."
        }
        $name = $Matches[1]
        $value = $Matches[2].Trim()
        if ($values.ContainsKey($name)) { throw "Variable duplicada en .env: $name (linea $lineNumber)." }
        if ($value.StartsWith('"') -or $value.StartsWith("'")) {
            $quote = $value.Substring(0, 1)
            if ($value.Length -lt 2 -or -not $value.EndsWith($quote)) {
                throw "Comillas sin cerrar en .env, linea $lineNumber."
            }
            $value = $value.Substring(1, $value.Length - 2)
        }
        if ([string]::IsNullOrWhiteSpace($value)) {
            throw "Valor vacio en .env, linea $lineNumber. Comente la linea si no necesita la variable."
        }
        $values[$name] = $value
    }

    # Validar todo antes de modificar el entorno. Las variables externas tienen prioridad.
    foreach ($name in $values.Keys) {
        if ($null -eq [Environment]::GetEnvironmentVariable($name, 'Process')) {
            [Environment]::SetEnvironmentVariable($name, $values[$name], 'Process')
        }
    }
}
