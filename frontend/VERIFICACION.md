# Verificación del frontend

Resultados de ejecuciones reales (18/11/2026), no solo de comandos previstos.

## Resultados ejecutados

- `npm run typecheck` (`tsc --noEmit`): sin errores.
- `npm test` (Vitest + Testing Library): **27 pruebas correctas** en 2 ficheros.
  - Conversión de importes (`12,50`, `1.250,50`, `1,250.50`, rechazo de `1,234` y de más de 2 decimales, caso `0,29` que falla en coma flotante).
  - Doble clic: una sola petición, con `Idempotency-Key` y `X-Requested-With`.
  - Reintento tras fallo de red con la **misma** clave y clave nueva tras el éxito.
  - Modal de PIN: reintento si es incorrecto, cancelación sin enviar nada, aviso de bloqueo con minutos.
- `npm run build`: correcto; deja el frontend en `backend/src/main/resources/static` con fuentes locales (sin peticiones externas).
- `npm run test:e2e` (Playwright contra el **jar real** con BD limpia, a 1440, 768 y 390 px): **21 pruebas correctas** (7 flujos × 3 anchos).
  1. Primer arranque con creación de PIN, alta de cliente, recarga, consumo con doble clic (un solo cobro), saldo insuficiente, anulación con PIN incorrecto y luego correcto, y ticket.
  2. Auditoría, movimientos, panel y exportación CSV.
  3. Importación: vista previa con errores (confirmar bloqueado), descarte, importación válida y comprobación del saldo inicial.
  4. Ajustes protegidos con PIN y descarga de la copia (`papeleria-AAAAMMDD-HHmm.db`).
  5. Nueve rutas sin desbordamiento horizontal, enlaces profundos y estado vacío.
  6. Teclado: `Ctrl+K`, foco al contenido tras navegar y menú móvil con Escape devolviendo el foco.
  7. Edición de un cliente: el código aparece de solo lectura y se conserva al guardar.
- Backend (`mvn test`): **29 pruebas correctas**, incluida la regresión de caché descrita abajo.

La ejecución completa de las 18 se hizo en dos tandas con el mismo jar: 15 en la pasada completa y las 3 de caja tras ajustar una aserción del propio test (ver abajo). No se repitió la pasada de las 18 de una vez.

## Defectos que encontró esta verificación y se corrigieron

| Defecto | Causa | Corrección |
|---|---|---|
| **El saldo mostrado no se actualizaba tras una recarga** (backend) | `GET /customers/{id}` enviaba `ETag` con la versión del cliente, que no cambia al mover saldo. El navegador revalidaba, Spring contestaba 304 y se reutilizaba el cuerpo antiguo. | Se elimina el `ETag` y se añade `Cache-Control: no-store` a toda `/api/**`. Test de regresión en el backend. |
| Desbordamiento horizontal a 768 px con datos | El `<span class="sr-only">` (absolute) de la cabecera de acciones no quedaba contenido por `.table-wrap`. | `.table-wrap { position: relative }`. Además, `grid-template-columns: minmax(0,1fr)` en `.stack`/`.stack-lg`. |
| El menú móvil no devolvía el foco con Escape | Se enfocaba el botón mientras el contenedor seguía `inert`. | `inert = false` de forma síncrona antes de enfocar. |
| El buscador de la caja perdía el foco al entrar | `Layout` enfocaba `<main>` después del `autoFocus` de la vista. | Solo se enfoca `<main>` si el foco no está ya dentro. |
| La vista previa de importación desaparecía en tablet | El `onSuccess` de "Descartar" se ejecutaba después de una subida nueva y limpiaba el trabajo activo. | Solo limpia si el trabajo activo sigue siendo el descartado. |
| `/clientes/999999` y ticket inexistente sin `<h1>` | Vistas de error sin encabezado principal. | Añadido. |
| `1,234` se leía como 1234 € | La coma se trataba como separador de miles. | Coma como único separador = decimal; más de 2 decimales es inválido. |

## Cosas que no se han comprobado o tienen límites

- **Navegador:** `npx playwright install chromium` falla en este equipo (`self-signed certificate in certificate chain`, proxy TLS corporativo). El E2E se ejecutó con el **Microsoft Edge instalado** (`PW_CHANNEL=msedge`). No se ha probado en Firefox ni Safari.
- **Revisión visual:** se generan capturas en `test-results/`, pero **no se han revisado visualmente**. No se afirma ninguna valoración estética; solo comprobaciones automáticas de desbordamiento, foco y funcionamiento.
- **Aserción de consola de la caja:** el test provoca a propósito un 409 (saldo insuficiente) y un 403 (PIN incorrecto), y Chrome los anota como errores de consola. Se toleran solo esos dos códigos; cualquier otro error de consola o de JavaScript hace fallar el test.
- **Accesibilidad:** hay comprobaciones manuales de teclado y foco automatizadas, pero no se ha pasado una herramienta como axe ni se ha probado con un lector de pantalla.
- **Tailscale / segunda persona:** el acceso por `*.ts.net` no se ha probado; `app.allowed-hosts` aún no incluye ese host.
- **Impresión del ticket:** existe `@media print` para 72 mm, pero no se ha impreso en una impresora real.
- **Dependencias:** `recharts` 2.x marcado como obsoleto por npm (la 3.x es la activa); sin migrar.
