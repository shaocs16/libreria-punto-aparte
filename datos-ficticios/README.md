# Datos ficticios

Datos inventados para probar la aplicación. No hay personas, teléfonos ni documentos reales: los nombres se generan combinando listas, los documentos empiezan por `F` y los teléfonos son aleatorios.

| Fichero | Contenido |
|---|---|
| `clientes.csv` | 40 clientes (algunos sin teléfono o documento; unos con saldo inicial y otros sin él) |
| `movimientos.csv` | 213 recargas y consumos de esos clientes (el saldo acumulado nunca queda negativo) |

## Cómo importarlos

1. Arranca la aplicación (`iniciar.bat`) y crea el PIN en **Ajustes** si no lo has hecho.
2. Ve a **Importar**, elige **Clientes**, sube `clientes.csv`, revisa la vista previa y pulsa **Confirmar e importar**.
3. Repite con **Movimientos** y `movimientos.csv` (pide el PIN). **Siempre clientes primero**: los movimientos necesitan que los clientes ya existan.

Resultado esperado: 40 clientes, 213 movimientos, saldo total en circulación de **1.423,69 €**, y el panel mostrará los saldos cuadrados.

## Repetir la importación

La aplicación rechaza importar dos veces el mismo fichero (`FILE_ALREADY_IMPORTED`) y los `row_id` de los movimientos ya importados. Para empezar de cero, borra la base de datos (`%LOCALAPPDATA%\Papeleria\data\papeleria.db`) con la aplicación parada, o define `PAPELERIA_DB` con otra ruta.

## Regenerarlos

```powershell
powershell -ExecutionPolicy Bypass -File scripts\generar-datos-ficticios.ps1
```

La semilla es fija, así que se obtienen siempre los mismos ficheros.
