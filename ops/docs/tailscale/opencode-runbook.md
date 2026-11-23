# Manual para OpenCode: configurar Tailscale y `.env` de Papelería

## Cómo usar este documento

Entregar a OpenCode esta petición desde el equipo que vaya a actuar como servidor:

> Lee `ops/docs/tailscale/opencode-runbook.md` y configura este equipo como
> servidor de Papelería. Descubre su IP y dominio Tailscale, revisa el panel web,
> rellena `.env` sin perder valores existentes y verifica el acceso. Pide mi
> intervención para iniciar sesión y mi confirmación antes de cambios de permisos
> que afecten a otros servicios, desactivar caducidad o detener la aplicación.
> No copies la identidad del servidor antiguo ni modifiques datos de clientes.

Este archivo es una guía de ejecución y un registro de lo realizado el
**23/11/2026**; no instala un agente, comando o skill ni se ejecuta automáticamente.
La guía para el operador está en [nuevo-servidor.md](nuevo-servidor.md).
El registro del servidor original está en [setup.md](setup.md).

## 1. Objetivo y límites de seguridad

Estado deseado:

```text
Miembros invitados y aceptados de la tailnet, con dispositivos aprobados
  → HTTPS privado del equipo servidor, puerto 443
  → Tailscale Serve
  → http://127.0.0.1:8080
  → Spring Boot con frontend incluido y SQLite local
```

- Backend solo en loopback; no cambiarlo a `0.0.0.0`.
- No activar Funnel, publicar Vite, abrir puertos del router ni desactivar firewall.
- Conservar la auditoría con identidad Tailscale: no aceptar identidades del frontend.
- No pedir contraseñas, MFA, cookies ni claves API al usuario: que inicie sesión en el navegador.
- No copiar las credenciales internas de Tailscale entre máquinas.
- Antes de modificar algo, leer instrucciones del repositorio y `git status --short`.
  Conservar cambios previos del usuario, su `.env` y cualquier servicio Tailscale existente.
- Solicitar autorización antes de interrumpir la app, sobrescribir configuración
  existente, restringir conexiones de otros servicios o desactivar caducidad.
- No crear cobros, clientes ni movimientos de prueba en una BD real.
- Si una comprobación requiere otro equipo al que no se tiene acceso, marcarla
  como pendiente: una petición desde el propio servidor no prueba el cliente remoto.

## 2. Registro de lo que se hizo en el equipo original

Estos datos son **históricos, no una plantilla para copiar en otro equipo**:

| Dato | Valor observado |
|---|---|
| Equipo | `pc-xabierscs` |
| IP Tailscale | `100.111.204.4` |
| DNSName sin punto final | `pc-xabierscs.tailf234bb.ts.net` |
| Cuenta propietaria | `shao.capilla@gmail.com` |
| Segunda cuenta, miembro | `shaolittlechurch@gmail.com` |
| Cliente detectado | `iphone-15-pro-max`, IP `100.71.114.124` |
| Servicio virtual preexistente | `svc:libreria`, distinto del proxy del equipo |

### Secuencia realizada

1. Se leyeron README, pendientes, YAML, filtros de Host/Origin e identidad,
   configuración de Vite, cliente API y lanzador PowerShell.
2. Se comprobó que Tailscale estaba instalado y conectado con:

   ```powershell
   Get-Command tailscale, java, mvn -ErrorAction SilentlyContinue
   tailscale status --json
   tailscale serve status --json
   tailscale funnel status --json
   tailscale serve --help
   Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue
   ```

   Se detectaron dominio, IP, MagicDNS, certificados HTTPS y las dos cuentas.
   Serve y Funnel no tenían configuración inicial. Java ya escuchaba en loopback.
3. Se abrió `https://login.tailscale.com/admin/machines` en el navegador de OpenCode.
   El usuario completó el inicio de sesión; el panel redirigió a
   `https://console.tailscale.com/admin/machines`.
4. Se activó el proxy privado:

   ```powershell
   tailscale serve --bg --yes http://127.0.0.1:8080
   tailscale serve status --json
   ```

   La configuración mostraba HTTPS en 443 y proxy HTTP a loopback, sin `AllowFunnel`.
5. El usuario eligió solo HTTPS. Se restauró `server.address: 127.0.0.1` frente
   a su cambio inicial a `0.0.0.0`, y se fijó `forward-headers-strategy: none`.
   Inicialmente se añadió el dominio al YAML; posteriormente se extrajo a `.env`.
6. Se comprobó **Users**: propietario y miembro aceptado; ya se exigía aprobación
   de invitados. No fue necesario enviar invitaciones nuevas.
7. En **Settings → Device management** se activó **Manually approve new devices**.
   Se recargó el panel y se comprobó que seguía activado.
8. En **Access controls → JSON editor** se encontró la regla por defecto que
   permitía todas las conexiones. Tras consultar al usuario, se sustituyó por
   `autogroup:member` → `100.111.204.4`, `tcp:443`. El usuario pidió permitir a
   los miembros invitados de esta red, no limitar a dos correos concretos.
   Se conservó la sección SSH existente, sin conceder red al puerto 22.
   No se añadieron permisos Funnel. Se añadieron tests para ambas cuentas:
   aceptar servidor:443 y denegar servidor:8080. Se guardó y se verificó tras recargar.
   La definición de `svc:libreria` no se eliminó, pero el nuevo permiso no concede su 8080.
9. Se activó ejecución desatendida:

   ```powershell
   tailscale set --help
   tailscale set --unattended=true
   ```

   También se consultó `tailscale debug prefs` para diagnóstico. No es necesario
   repetirlo normalmente ni guardar/publicar su salida completa.
10. Se revisó **Machines → equipo → Key expiry**. Primero se conservó el plazo
    de 180 días y se explicó el compromiso entre seguridad y disponibilidad.
    Después el usuario autorizó desactivar la caducidad **solo en el servidor**:
    **Machine settings → Disable key expiry**. Se verificó **No expiry** tras
    recargar y ausencia de `Self.KeyExpiry` en `tailscale status --json`.
    El iPhone conservó su caducidad, 22/05/2027. No se alteró la política global.
11. Se añadieron pruebas de Host HTTPS, Origin correcto y rechazo de identidad
    falsificada desde una IP remota. Las 32 pruebas de backend pasaron.
12. El primer `mvn package` no pudo reempaquetar porque el jar estaba abierto.
    Con autorización del usuario se identificaron y detuvieron solo los procesos
    de esta app y su lanzador; después funcionó `mvn -B package -DskipTests`.
    Se reinició con el mismo lanzador y la misma BD.
13. Se extrajo la configuración de hosts a `PAPELERIA_ALLOWED_HOSTS`, se creó
    `.env.example`, se rellenó `.env` local y se implementó el cargador seguro.
    Pasaron las pruebas del cargador y las 32 de backend; se reconstruyó y reinició
    con autorización. No fue necesario cambiar el frontend.
14. Se comprobó HTTPS con validación normal del certificado: `/api/v1/me` devolvía
    la cuenta del usuario, `/api/v1/health` respondía y Caja cargaba en el navegador.
    No se alteraron saldos. La prueba desde el iPhone quedó pendiente.

## 3. Procedimiento reproducible para otra sesión de OpenCode

### A. Descubrir el estado, sin cambios

Leer al menos:

- `.env.example` y `.env`, si existe (no volcar secretos en la respuesta).
- `backend/src/main/resources/application.yml`.
- `scripts/iniciar.ps1` y `scripts/cargar-env.ps1`.
- `frontend/src/api/client.ts` y `frontend/vite.config.ts`.
- `ops/docs/tailscale/nuevo-servidor.md`.

Ejecutar los comandos de diagnóstico de la sección 2 y extraer solo lo necesario:

```powershell
$ts = tailscale status --json | ConvertFrom-Json
if ($ts.BackendState -ne 'Running') { throw 'Conectar Tailscale antes de continuar.' }
$ServidorDns = ([string]$ts.Self.DNSName).TrimEnd('.')
$ServidorIp = @($ts.Self.TailscaleIPs | Where-Object { $_ -match '^100\.' })[0]
if (-not $ServidorDns -or -not $ServidorIp) { throw 'Faltan dominio o IP del servidor.' }
$ServidorUrl = "https://$ServidorDns"
[pscustomobject]@{
    Equipo = $ts.Self.HostName
    Ip = $ServidorIp
    Dominio = $ServidorDns
    Tailnet = $ts.CurrentTailnet.Name
    MagicDNS = $ts.CurrentTailnet.MagicDNSEnabled
    Caducidad = $ts.Self.KeyExpiry
}
```

Confirmar con el usuario que es la máquina y la tailnet correctas. Si no está
conectada, pedir inicio de sesión; no reutilizar la IP del ejemplo histórico.
No cambiar nombre de máquina por defecto: si se cambia, obtener después su DNSName final.

### B. Abrir el panel web y pedir autenticación

1. Usar el navegador disponible en OpenCode. Cargar su skill/instrucciones antes
   de operarlo si el entorno las proporciona.
2. Abrir `https://console.tailscale.com/admin/machines`.
3. Si aparece login, pedir al usuario que lo complete. No automatizar credenciales.
4. Leer una captura de accesibilidad actual y confirmar la tailnet.
5. No reutilizar IDs de pestañas o referencias de elementos de otra sesión.
   Descubrir las herramientas disponibles; sus nombres pueden variar entre versiones.
6. Si un clic falla, inspeccionar primero el estado para no repetir una acción
   que ya pudiera haberse aplicado. En esta sesión fue necesario enfocar la
   pestaña antes de algunos clics. Ampliar profundidad del snapshot si falta contenido.

Rutas observadas; si cambian, navegar por las etiquetas del panel:

| Pantalla | URL |
|---|---|
| Máquinas | `https://console.tailscale.com/admin/machines` |
| Usuarios | `https://console.tailscale.com/admin/users` |
| Dispositivos | `https://console.tailscale.com/admin/settings/device-management` |
| Editor de política | `https://console.tailscale.com/admin/acls/file` |
| Tests de política | `https://console.tailscale.com/admin/acls/visual/tests` |
| Equipo | `https://console.tailscale.com/admin/machines/IP_REAL_DEL_EQUIPO` |

### C. Rellenar `.env` con la información descubierta

No limitarse a crear una plantilla: dejar la configuración local rellenada para
el servidor detectado. **No tocar el `.env` de otro servidor ni sobrescribirlo entero.**

1. Si existe `.env`, leerlo y conservar sus opciones y comentarios no relacionados.
2. Si no existe, crearlo tomando `.env.example` como referencia.
3. Preparar la línea exacta usando el DNSName real, sin `https://`, sin `/` y sin
   punto final. Este comando genera el texto; no modifica el archivo:

   ```powershell
   "PAPELERIA_ALLOWED_HOSTS=localhost:8080,127.0.0.1:8080,${ServidorDns},${ServidorDns}:443"
   ```

4. Aplicar esa línea con la herramienta de edición de archivos. Si existían otros
   hosts, investigar su uso antes de retirarlos. No introducir duplicados de variable.
5. `PAPELERIA_DB`: conservar la ruta real de la app actual. Confirmar leyendo su
   línea de comandos o el lanzador; **no cambiarla para esta tarea**. En una nueva
   instalación sin datos, acordar la ruta con el usuario. Si se usa la ruta por
   defecto del lanzador, puede dejarse comentada. No copiar una ruta de otro usuario.
6. `PAPELERIA_NO_BROWSER=1`: opcional para no abrir navegador en el arranque;
   configurarla solo si se desea. No afecta al acceso remoto.
7. Comprobar `git check-ignore .env`: el archivo local no debe versionarse.
8. Revisar si Windows tiene variables `PAPELERIA_*` ya definidas: prevalecen sobre
   el archivo. No borrarlas silenciosamente; resolver discrepancias con el usuario.

Formato admitido por el cargador:

```dotenv
# EJEMPLO: sustituir el dominio antes de utilizarlo.
PAPELERIA_ALLOWED_HOSTS=localhost:8080,127.0.0.1:8080,servidor.tailxxxx.ts.net,servidor.tailxxxx.ts.net:443
# PAPELERIA_DB=C:\Papeleria\datos\papeleria.db
# PAPELERIA_NO_BROWSER=1
```

- Solo se admiten esas tres variables; no añadir IP de ACL, correo ni claves Tailscale.
- Valores no vacíos; comillas exteriores opcionales. Comentarios en líneas separadas.
- No hay expansión de `%VARIABLES%`, `$variables` ni ejecución de comandos.
- El cargador valida todo antes de modificar el entorno y no pisa variables externas.
- Si se reutiliza una PowerShell donde ya se importó `.env`, las variables cargadas
  siguen en el proceso y tienen prioridad. Para probar cambios, abrir una PowerShell
  nueva o retirar solo esas variables de proceso de forma explícita y controlada.

### D. Verificar backend y frontend, sin volver a incrustar el dominio

En `backend/src/main/resources/application.yml`:

```yaml
server:
  address: 127.0.0.1
  port: 8080
  forward-headers-strategy: none
# ... conservar spring y las demas secciones ...
app:
  # ... conservar db-path y local-actor-name ...
  allowed-hosts: ${PAPELERIA_ALLOWED_HOSTS:localhost:8080,127.0.0.1:8080}
```

No reemplazar el archivo completo con el fragmento. El cargador se invoca desde
`scripts/iniciar.ps1` antes de calcular opciones y arrancar Java.

Frontend: mantener ``fetch(`/api/v1${path}`, ...)`` y `X-Requested-With: papeleria-ui`
en `frontend/src/api/client.ts`. No definir un dominio Vite ni abrir CORS.
Mantener el build de Vite en `backend/src/main/resources/static`. El proxy de 5173
solo es para desarrollo local; en producción se sirve todo desde Spring Boot.

`java -jar`, Maven o un servicio Windows no cargan `.env` automáticamente. Si no
se usa el lanzador, configurar el entorno del proceso explícitamente y la ruta
mediante `--app.db-path`. No asumir que `PAPELERIA_DB` la interpreta Spring:
es una opción del lanzador. Ver ejemplos en el README.

### E. Configurar permisos en el panel

1. Confirmar miembros y aprobación de invitados en **Users/User management**.
   No enviar invitaciones nuevas sin autorización.
2. Activar aprobación manual de dispositivos nuevos con autorización y verificar
   que servidor y clientes conocidos siguen aprobados. No aprobar equipos desconocidos.
3. Leer y conservar una copia de la política actual. Revisar otros servicios.
4. Acordar con el usuario el alcance. Para los miembros invitados de esta red,
   integrar en `grants` esta regla, con la **IP nueva real**:

   ```json
   {
     "src": ["autogroup:member"],
     "dst": ["IP_REAL_DEL_SERVIDOR"],
     "ip": ["tcp:443"]
   }
   ```

5. Retirar o acotar reglas abiertas únicamente tras confirmar sus consecuencias.
   Las reglas son aditivas: una regla restrictiva no anula `*` → `*`.
   No eliminar reglas SSH, tags o servicios ajenos sin investigarlos.
6. Actualizar tests existentes al destino correcto; usar cuentas reales. Para
   nuevos tests, se puede indicar `"proto": "tcp"` junto a `src`, `accept` y
   `deny`, comprobando servidor:443 permitido y servidor:8080 denegado.
7. Guardar; si hay errores de validación/tests, resolverlos sin ampliar permisos
   indiscriminadamente. Recargar y verificar que se guardó de verdad.
8. No añadir permiso `funnel`. Comprobar MagicDNS y certificados HTTPS.

### F. Activar Serve y decidir caducidad

Primero leer `tailscale serve status --json`. Si 443 o la ruta `/` ya tienen un
destino distinto, detenerse y consultar; no usar `reset` ni sobrescribirlo.
Si no hay conflicto y el usuario autorizó la publicación privada:

```powershell
tailscale set --unattended=true
tailscale serve --bg http://127.0.0.1:8080
tailscale serve status --json
tailscale funnel status --json
```

`--yes` solo si se conoce el cambio y no hay conflicto; en el equipo original
se utilizó tras comprobar que Serve estaba vacío. No confundir `funnel status`
con activar Funnel: el estado puede mostrar el proxy privado. Verificar que
`AllowFunnel` no contenga ningún valor `true` y que no exista permiso público.

Explicar antes de cambiar caducidad:

- Con caducidad hay que reautenticar antes del vencimiento o se interrumpe el acceso.
- Sin caducidad solo en el servidor se evita ese corte, pero la autorización
  persiste hasta retirarlo; hay que revocarlo si se pierde o queda comprometido.

Si el usuario elige desactivarla, ir a **Machines → servidor correcto → Machine
settings → Disable key expiry** y verificar **No expiry** tras recargar. Revisar
`Self.KeyExpiry` por CLI y conservar la caducidad de los clientes y política global.
No aplicar por inercia la decisión tomada en otro equipo.

### G. Tests, empaquetado y reinicio controlado

```powershell
powershell -NoProfile -File scripts\tests\cargar-env.test.ps1
Push-Location backend
mvn -B test
Pop-Location
git diff --check
```

Si solo cambió `.env` y el jar ya soporta `PAPELERIA_ALLOWED_HOSTS`, no recompilar:
basta reiniciar con `iniciar.bat`. Si cambió código/YAML o el jar es anterior:

- Confirmar ventana de corte y ruta actual de BD.
- Identificar PID del listener, proceso Java, posible lanzador Java y PowerShell
  padre con `Get-NetTCPConnection` y `Get-CimInstance Win32_Process`.
- No copiar PIDs históricos ni matar todos los `java.exe`/PowerShell del equipo.
- Detener solo esta app y su lanzador, de forma que el lanzador antiguo no pueda
  ejecutar su limpieza después de arrancar la instancia nueva.
- Usar `construir.bat` para build completo; si el frontend ya está construido y
  no cambió, `mvn -B package -DskipTests` desde `backend` tras pasar los tests.
- Si el empaquetado falla, no arrancar el jar parcial ni anunciar éxito.
- Arrancar mediante `iniciar.bat` o `scripts/iniciar.ps1`, que carga `.env`, desde
  un proceso PowerShell nuevo. Conservar la BD real; no iniciar con una BD vacía.
- Inspeccionar el resultado; no esperar indefinidamente ni usar polling de trabajos
  en segundo plano si el entorno notifica automáticamente su finalización.

### H. Verificación y entrega

Con `$ServidorUrl` recalculada a partir del DNSName real:

```powershell
Get-NetTCPConnection -State Listen -LocalPort 8080
Invoke-RestMethod "$ServidorUrl/api/v1/health"
Invoke-RestMethod "$ServidorUrl/api/v1/me"
tailscale serve status --json
```

Comprobar:

- Listener solo en `127.0.0.1`, no en LAN ni en todas las interfaces.
- HTTPS con validación TLS normal, sin ignorar certificados.
- Identidad de la cuenta en `/api/v1/me`, no actor `local@…` por HTTPS.
- Frontend en el dominio correcto y avisos de salud revisados.
- `.env` real rellenado, ignorado por Git y sin variables externas contradictorias.
- Caducidad según lo autorizado, aprobación activa y política guardada.
- Cliente remoto: pedir abrir la URL con Tailscale y comprobar su propia identidad;
  después desconectarlo y verificar que fallan peticiones nuevas.

Actualizar la documentación del equipo y entregar un resumen breve: URL real,
archivos modificados, tests ejecutados, configuración aplicada y tareas pendientes.
No afirmar que se probó un cliente o un reinicio de Windows sin haberlo hecho.

## 4. Archivos que se crearon o modificaron en esta intervención

| Archivo | Función |
|---|---|
| `backend/src/main/resources/application.yml` | Loopback, cabeceras reenviadas desactivadas y hosts desde variable con fallback local. |
| `backend/src/test/java/com/papeleria/WebSecurityTest.java` | Hosts HTTPS, Origin e identidad remota; lista de hosts inyectada por `PAPELERIA_ALLOWED_HOSTS`. |
| `scripts/iniciar.ps1` | Carga `.env` antes de arrancar la aplicación. |
| `scripts/cargar-env.ps1` | Lector limitado a las tres variables admitidas, sin evaluar comandos. |
| `scripts/tests/cargar-env.test.ps1` | Formato, prioridad, comillas, duplicados, carga atómica y ausencia de ejecución. |
| `.env.example` | Plantilla versionable para otro servidor. |
| `.env` | Hosts reales del equipo original; archivo local ignorado por Git. |
| `README.md` | Uso de `.env`, arranque directo, tests y enlaces de operación. |
| `PENDIENTE.md` / `frontend/VERIFICACION.md` | Estado real y verificaciones aún pendientes. |
| `ops/docs/tailscale/setup.md` | Registro de configuración del servidor original. |
| `ops/docs/tailscale/nuevo-servidor.md` | Guía de instalación y migración para el operador. |
| `ops/docs/tailscale/opencode-runbook.md` | Este registro y procedimiento para otra sesión de OpenCode. |

No se modificó código de frontend: ya utilizaba el mismo origen. `.gitignore`
ya excluía `.env`; se comprobó, no hizo falta añadir esa regla.

## 5. Límites y pendientes que no debe olvidar el siguiente agente

- **Serve y Tailscale desatendido no arrancan Java.** Sigue pendiente el servicio
  Windows de la aplicación para inicio automático y recuperación de caídas.
- Al usar la app manualmente, mantener su ventana abierta y arrancarla tras reiniciar.
- Revisar suspensión del PC según el uso. Existe el comando
  `powercfg /change standby-timeout-ac 0`, pero no se ejecutó en esta intervención.
  No cambiar políticas de energía sin acordarlo.
- Configurar PIN y backups antes del uso real si aún faltan. Se observaron avisos
  de backups y `pinSet: false`; no se configuraron como parte de Tailscale.
- La migración de SQLite requiere copia consistente, una sola instancia aceptando
  escrituras y revisión de rutas. No se ha migrado la BD en esta intervención.
- No se ha realizado la prueba de extremo a extremo desde el segundo equipo.
  Mantenerla pendiente hasta que el usuario o el agente puedan verificarla.
