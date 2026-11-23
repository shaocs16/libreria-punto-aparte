$ErrorActionPreference = "Stop"
$out = Join-Path (Split-Path -Parent $PSScriptRoot) "datos-ficticios"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$utf8Bom = New-Object System.Text.UTF8Encoding $true
$rnd = New-Object System.Random 20261118

function Euros([int]$cents) { "{0},{1:00}" -f [math]::Floor($cents / 100), ($cents % 100) }

$nombres = "Lucía","Mateo","Sofía","Hugo","Martina","Daniel","Valeria","Pablo","Julia","Álvaro","Carla","Adrián","Paula","Diego","Noa","Iván","Irene","Marcos","Alba","Rubén"
$apellidos = "García","Martínez","López","Sánchez","Pérez","Gómez","Ruiz","Hernández","Díaz","Moreno","Muñoz","Álvarez","Romero","Navarro","Torres","Domínguez","Gil","Vázquez","Serrano","Blanco"
$notasCliente = "Cliente habitual","Compra material escolar","Pide factura","Recoge los pedidos por la tarde","Papelería de oficina","Profesora de primaria","Autónomo, compra folios y tóner"

# ---- clientes: 40, algunos sin teléfono/documento, con saldo inicial variado
$clientes = @()
for ($i = 1; $i -le 40; $i++) {
    $nombre = "{0} {1} {2}" -f $nombres[$rnd.Next($nombres.Count)], $apellidos[$rnd.Next($apellidos.Count)], $apellidos[$rnd.Next($apellidos.Count)]
    $tel = if ($i % 7 -eq 0) { "" } else { "6{0:00000000}" -f $rnd.Next(0, 99999999) }
    $doc = if ($i % 5 -eq 0) { "" } else { "F{0:0000000}X" -f (1000000 + $i) }   # prefijo F: claramente ficticio
    $nota = if ($i % 4 -eq 0) { $notasCliente[$rnd.Next($notasCliente.Count)] } else { "" }
    $inicial = if ($i % 6 -eq 0) { 0 } else { 100 * (5 + $rnd.Next(0, 16) * 5) }  # 5 a 80 EUR
    $clientes += [pscustomobject]@{ code = "C{0:000}" -f $i; name = $nombre; phone = $tel; document = $doc; notes = $nota; initial = $inicial }
}

$lines = @("code;name;phone;document;notes;initial_balance")
foreach ($c in $clientes) {
    $bal = if ($c.initial -gt 0) { Euros $c.initial } else { "" }
    $lines += ($c.code, $c.name, $c.phone, $c.document, $c.notes, $bal) -join ";"
}
[IO.File]::WriteAllText((Join-Path $out "clientes.csv"), (($lines -join "`r`n") + "`r`n"), $utf8Bom)

# ---- movimientos: recargas y consumos; el saldo acumulado nunca queda negativo
$consumos = "Fotocopias","Cuaderno A4","Bolígrafos","Carpeta archivadora","Folios 500 hojas","Rotuladores","Pegamento","Calculadora","Mochila","Material escolar","Impresión a color","Encuadernación"
$recargas = 500, 1000, 1500, 2000, 3000, 5000
$mov = @("row_id;customer_code;type;amount;note")
$n = 0
foreach ($c in $clientes) {
    $saldo = $c.initial
    $eventos = $rnd.Next(3, 8)
    for ($e = 0; $e -lt $eventos; $e++) {
        $n++
        $id = "mov-{0:0000}" -f $n
        if ($saldo -lt 300 -or $rnd.Next(0, 4) -eq 0) {
            $cents = $recargas[$rnd.Next($recargas.Count)]
            $saldo += $cents
            $mov += ($id, $c.code, "TOPUP", (Euros $cents), "Recarga en mostrador") -join ";"
        } else {
            $max = [math]::Min($saldo, 2500)
            $cents = $rnd.Next(50, $max + 1)
            $saldo -= $cents
            $mov += ($id, $c.code, "SPEND", (Euros $cents), $consumos[$rnd.Next($consumos.Count)]) -join ";"
        }
    }
}
[IO.File]::WriteAllText((Join-Path $out "movimientos.csv"), (($mov -join "`r`n") + "`r`n"), $utf8Bom)

"Clientes: {0}  Movimientos: {1}" -f $clientes.Count, ($mov.Count - 1)
