# Configurar Papelería en un nuevo servidor Windows con Tailscale

Guía paso a paso para que otro PC sea el servidor de esta aplicación.
Basada en el código del proyecto a 23/11/2026.

Si la configuración la va a realizar OpenCode, usar también
[opencode-runbook.md](opencode-runbook.md), con el registro de la intervención y
el procedimiento para descubrir los datos y rellenar `.env`.

> Esta guía no cambia el servidor actual. Los comandos se ejecutan en el **nuevo
> servidor**, salvo cuando se indique «equipo cliente» o «servidor antiguo».
> No copies la IP ni el dominio del servidor antiguo: cada instalación Tailscale
> tiene su propia identidad. No copies claves o carpetas internas de Tailscale.

## Cómo debe quedar

```text
Equipo cliente con Tailscale, usuario invitado y dispositivo aprobado
  → https://DOMINIO_DEL_NUEVO_SERVIDOR/
  → Tailscale Serve: HTTPS privado en el puerto 443
  → http://127.0.0.1:8080 en el servidor
  → Spring Boot: frontend compilado + API + SQLite local
```

Solo se ejecuta una aplicación Java. **No se necesita un servidor Vite en
producción**, ni abrir puertos del router, ni activar Funnel.

## 1. Preparar el nuevo PC

1. Utilizar un Windows compatible con la versión actual de Tailscale y mantenerlo actualizado.
2. Instalar [Tailscale](https://tailscale.com/download/windows).
3. Instalar un **JDK 21**, **Maven** y **Node.js LTS 22 o superior** para construir
   el proyecto. Añadir sus ejecutables al `PATH`. El lanzador admite Node 20+, pero
   para una instalación nueva conviene una versión LTS mantenida.
4. Copiar o clonar el proyecto completo, por ejemplo en
   `C:\Papeleria\libreria-punto-aparte`. No incluir la base de datos en Git.
5. Abrir una nueva PowerShell en esa carpeta y comprobar:

   ```powershell
   java -version
   mvn -version
   node --version
   npm --version
   tailscale version
   ```

Para ejecutar un jar ya construido solo se requiere Java y Tailscale; Maven y
Node se necesitan únicamente si vas a construir el proyecto desde el código.
Cambiar el dominio en `.env` no requiere herramientas de compilación.

## 2. Conectar el servidor a la red Tailscale correcta

1. Iniciar sesión en Tailscale con una cuenta de confianza de **la misma red
   Tailscale** que utilizarán los clientes. No crear otra red por error.
2. Entrar como administrador en https://console.tailscale.com/admin.
3. En **Machines**, localizar el nuevo equipo y aprobarlo si está pendiente.
4. Si quieres un nombre como `servidor-papeleria`, cambiarlo ahora desde el panel
   o mediante `tailscale set --hostname=servidor-papeleria`. No reutilizar nombres
   de máquinas que aún estén activas. Leer después el nombre definitivo asignado.
5. En **DNS**, comprobar que **MagicDNS** y los certificados **HTTPS** están
   habilitados. Tailscale puede pedir confirmar su activación al configurar Serve.
   La emisión de certificados hace público el nombre del dominio en los registros
   de transparencia de certificados; no hace pública la aplicación.

En PowerShell del nuevo servidor:

```powershell
$ts = tailscale status --json | ConvertFrom-Json
$ServidorIp = $ts.Self.TailscaleIPs[0]
$ServidorDns = $ts.Self.DNSName.TrimEnd('.')
$ServidorUrl = "https://$ServidorDns"
$ServidorIp
$ServidorDns
$ServidorUrl
```

Anotar los resultados. Si no está conectado o falta `DNSName`, resolver primero
el inicio de sesión, la aprobación y MagicDNS.

En los fragmentos de esta guía:

- `IP_NUEVO_SERVIDOR`: sustituir por la IP obtenida, por ejemplo `100.x.y.z`.
- `DOMINIO_NUEVO_SERVIDOR`: sustituir por el dominio obtenido, por ejemplo
  `servidor-papeleria.tailabc123.ts.net`, **sin punto final, sin `https://` ni `/`**.

No pegar literalmente estos marcadores en la configuración.

## 3. Configurar usuarios y dispositivos

En el panel de administración:

1. **Users → Invite users:** invitar a las personas que van a usar la aplicación.
2. Pedirles que acepten la invitación y seleccionen esta red en Tailscale.
3. Confirmar que aparecen como miembros aprobados. Mantener la aprobación de
   usuarios invitados activada en **Settings → User management**.
4. **Settings → Device management:** activar **Manually approve new devices**.
5. Aprobar en **Machines** únicamente el servidor y los dispositivos conocidos.
6. Cada persona debe utilizar su propia cuenta Tailscale para que la auditoría
   distinga usuarios. No dar permisos de administrador a quien solo necesita la app.
7. Proteger las cuentas con autenticación multifactor cuando el proveedor lo permita.

## 4. Configurar el backend mediante `.env`

Archivo: **`backend/src/main/resources/application.yml`**.

### 4.1. Mantener el servidor escuchando solo en localhost

La sección `server` debe mantenerse así:

```yaml
server:
  # Tailscale Serve publica HTTPS y conecta al backend por loopback.
  address: 127.0.0.1
  port: 8080
  # ActorFilter debe ver la IP del proxy local, no una cabecera del cliente.
  forward-headers-strategy: none
```

**No poner `0.0.0.0` ni la IP Tailscale en `server.address`.** Serve se encarga
del acceso remoto. No habilitar el procesamiento de cabeceras reenviadas: la
identidad Tailscale se acepta solo cuando el proxy conecta desde loopback.

### 4.2. Configurar el dominio permitido sin modificar código

El backend ya lee los hosts desde una variable. En `application.yml` se mantiene:

```yaml
app:
  # ... conservar db-path y el resto de propiedades existentes ...
  allowed-hosts: ${PAPELERIA_ALLOWED_HOSTS:localhost:8080,127.0.0.1:8080}
```

Desde la raíz, crear el archivo local **solo si todavía no existe**:

```powershell
if (-not (Test-Path -LiteralPath .env)) { Copy-Item .env.example .env }
notepad .env
```

Sustituir la línea de hosts por esta, con el dominio **real del nuevo PC**:

```dotenv
PAPELERIA_ALLOWED_HOSTS=localhost:8080,127.0.0.1:8080,DOMINIO_NUEVO_SERVIDOR,DOMINIO_NUEVO_SERVIDOR:443
# PAPELERIA_DB=C:\Papeleria\datos\papeleria.db
# PAPELERIA_NO_BROWSER=1
```

No copiar el `.env` del servidor antiguo sin cambiar su dominio. Conservar
localhost para el lanzador. No añadir `*.ts.net`, URLs completas ni comodines.
El archivo está excluido de Git; se comparte únicamente `.env.example`.

`iniciar.bat` carga `.env` a través de `scripts/cargar-env.ps1`. Se admiten estas
tres variables, comillas exteriores opcionales y comentarios en líneas separadas.
No usar valores vacíos, variables duplicadas, comentarios al final de un valor
ni expansión de `%VARIABLES%`, `$variables` o comandos. Para omitir una opción,
comentar la línea. Las variables del entorno de Windows tienen prioridad: revisar
que no haya una `PAPELERIA_ALLOWED_HOSTS` antigua definida fuera del archivo.

`app.local-actor-name: ${COMPUTERNAME:servidor}` puede quedarse como está:
identifica las operaciones locales con el nombre del nuevo PC.

> Compilar una vez esta versión para que el jar lea la variable. Después, cambiar
> `.env` solo requiere cerrar y volver a arrancar Papelería, sin recompilar.
> Si se usa `java -jar` directo o un futuro servicio Windows, configurar su entorno
> explícitamente: Java no carga `.env`. Ver los ejemplos del README. Con Java
> directo indicar también `--app.db-path`; `PAPELERIA_DB` es una opción del lanzador.

## 5. Revisar el frontend: no cambiar IP ni dominio

El frontend y la API usan el mismo origen HTTPS. **No hay que añadir la IP nueva
al frontend, crear una variable `VITE_API_URL` ni habilitar CORS.**

### 5.1. Cliente API

Archivo: **`frontend/src/api/client.ts`**, línea 86 actual:

```ts
const res = await fetch(`/api/v1${path}`, {
```

Mantener la ruta relativa. Si la pantalla se abre en el dominio nuevo, la API
se solicita automáticamente al mismo dominio nuevo.

Conservar también la cabecera del cliente API, líneas 55–59:

```ts
const h = new Headers({
  "X-Requested-With": "papeleria-ui",
  Accept: "application/json",
});
```

El backend exige `X-Requested-With` para las operaciones que modifican datos.
No sustituirla por una cabecera `Tailscale-User-*`: esas las añade Serve.

### 5.2. Compilación y desarrollo de Vite

Archivo: **`frontend/vite.config.ts`**, líneas 7–9 actuales:

```ts
build: {
  outDir: "../backend/src/main/resources/static",
  emptyOutDir: true,
```

Mantener esa salida: el frontend compilado se incorpora al jar de Spring Boot.

Las líneas 13–24 incluyen `host: "127.0.0.1"`, `port: 5173`, el proxy hacia
`http://localhost:8080` y la normalización de `Origin`. Son **solo para desarrollo**.
Mantenerlas sin cambios; no sustituir localhost por la IP remota ni publicar 5173.

Los clientes de producción abren el HTTPS de Serve, **no `localhost:5173`**.

## 6. Construir y arrancar la aplicación

Desde la raíz del proyecto en el nuevo servidor:

```powershell
.\construir.bat
```

Antes de reconstruir, cerrar Papelería si está ejecutándose: Windows puede
bloquear el reempaquetado del jar abierto. No continuar si la construcción falla.
El resultado esperado es `backend\target\papeleria-1.0.0.jar` con el frontend incluido.

Para ejecutar también los tests de backend:

```powershell
Push-Location backend
mvn test
Pop-Location
```

Arrancar desde la raíz:

```powershell
.\iniciar.bat
```

1. Confirmar que abre http://localhost:8080.
2. Mantener abierta la ventana del lanzador: al cerrarla se detiene la aplicación.
3. Si la instalación es nueva, crear el PIN en **Ajustes**.
4. Configurar copias de seguridad en un destino independiente y activar la copia diaria.
5. Si estás migrando datos existentes, seguir el paso 11 antes de dar acceso real.

La ruta que usa `iniciar.bat` por defecto es:

```text
%LOCALAPPDATA%\Papeleria\data\papeleria.db
```

Si se lanza el jar directamente sin indicar ruta, el YAML usa otra ubicación:
`%ProgramData%\Papeleria\data\papeleria.db`. **No alternar lanzadores con rutas
distintas**, porque parecería que los datos han desaparecido.

Para elegir una ruta explícita en esa sesión de PowerShell:

```powershell
$env:PAPELERIA_DB = 'C:\Papeleria\datos\papeleria.db'
.\iniciar.bat
```

La variable de sesión no se conserva para futuros dobles clics. Si quieres una
ruta fija, descomentar `PAPELERIA_DB` en `.env` con una ruta absoluta.
No almacenar SQLite en una carpeta
de red ni compartir la misma BD entre dos servidores.

## 7. Restringir la política de acceso al servidor nuevo

En **Access controls → JSON editor**, editar la política existente.
Antes de cambiarla, guardar una copia de su contenido.

La regla para todos los miembros invitados y aceptados de esta red es:

```json
{
  "src": ["autogroup:member"],
  "dst": ["IP_NUEVO_SERVIDOR"],
  "ip": ["tcp:443"]
}
```

Integrarla dentro de la lista `grants`; el fragmento por sí solo **no es una
política completa**. En esta red ya existe una regla hacia `100.111.204.4`:
cambiar su destino al nuevo servidor al hacer el relevo definitivo.

- No dejar una regla `"src": ["*"], "dst": ["*"], "ip": ["*"]` que permita todo.
- Las reglas se suman: añadir una regla restrictiva no limita otra regla abierta.
- Si otros servicios necesitan acceso, revisar sus reglas antes de retirarlas.
- No conceder acceso directo a 8080 ni a Vite 5173.
- No conceder `funnel` en `nodeAttrs`. No ejecutar `tailscale funnel` para publicar.
- No compartir la máquina con usuarios externos a esta red.
- Si mantienes la sección SSH existente, no significa que debas abrir el puerto 22.

También actualizar los destinos de los tests de política existentes. Por cada
usuario real, un test puede tener esta forma dentro de la lista `tests`:

```json
{
  "src": "CORREO_REAL_DEL_USUARIO",
  "accept": ["IP_NUEVO_SERVIDOR:443"],
  "deny": ["IP_NUEVO_SERVIDOR:8080"]
}
```

Sustituir los marcadores, guardar y comprobar que el panel acepta la política.

## 8. Activar HTTPS y ejecución desatendida

En PowerShell del nuevo servidor (como administrador si Tailscale lo requiere):

```powershell
tailscale set --unattended=true
tailscale serve --bg http://127.0.0.1:8080
tailscale serve status
tailscale funnel status --json
```

La salida debe mostrar **el dominio del nuevo servidor**, con el proxy hacia
`http://127.0.0.1:8080`. La configuración no debe tener `AllowFunnel` activado.
`funnel status` puede mostrar el mismo proxy privado: eso no significa que Funnel
esté habilitado; comprobar que no existe un permiso `AllowFunnel: true`.

Si Tailscale pide habilitar HTTPS, seguir el enlace oficial que devuelve, aprobar
el cambio y repetir el comando cuando finalice.

No desactivar el firewall de Windows ni abrir 8080 en la LAN. Serve escucha por
Tailscale y se conecta a Java mediante loopback.

## 9. Configurar la caducidad solo en el servidor

Para seguir la configuración elegida para este proyecto:

1. **Machines → nuevo servidor → Machine settings → Disable key expiry**.
2. Confirmar en **Key expiry** que aparece **No expiry**.
3. Mantener la caducidad normal de los equipos cliente; no cambiarla globalmente.

Esto evita que el servidor pierda acceso cada 180 días, pero su autorización ya
no se retirará automáticamente. Si se pierde, se compromete o deja de usarse,
eliminarlo de **Machines**. Mantener Windows y Tailscale actualizados y proteger
la cuenta administradora. Si prefieres caducidad, conservarla y programar la
reautenticación antes del vencimiento.

## 10. Comprobar todo desde el servidor y desde un cliente

### En el servidor

Recalcular las variables si has abierto otra PowerShell:

```powershell
$ts = tailscale status --json | ConvertFrom-Json
$ServidorDns = $ts.Self.DNSName.TrimEnd('.')
$ServidorUrl = "https://$ServidorDns"
Get-NetTCPConnection -LocalPort 8080 -State Listen
Invoke-RestMethod "$ServidorUrl/api/v1/health"
Invoke-RestMethod "$ServidorUrl/api/v1/me"
```

Resultados esperados:

- Java escucha **solo en `127.0.0.1:8080`**.
- HTTPS funciona con un certificado válido; no ignorar errores de certificado.
- `/api/v1/health` responde; revisar los avisos, no solo el código HTTP.
- `/api/v1/me` devuelve la cuenta Tailscale, no `local@…`, cuando accedes por HTTPS.
- El navegador carga Caja, Clientes y las demás pantallas desde el dominio nuevo.

### En cada equipo cliente

1. Instalar Tailscale; no necesita Java, Maven, Node ni una copia del proyecto.
2. Entrar con la cuenta invitada y aceptada, seleccionar esta red y conectar.
3. Aprobar su dispositivo desde el panel si aparece pendiente.
4. Abrir `https://DOMINIO_NUEVO_SERVIDOR/` con el dominio real.
5. Abrir la misma URL con `/api/v1/me` al final y comprobar que identifica a ese usuario.
6. Verificar que los saldos corresponden a la BD esperada y que los avisos de
   seguridad están resueltos. No crear cobros ficticios en la BD real para probar.
7. Desconectar Tailscale en el cliente y comprobar que las **nuevas peticiones**
   dejan de funcionar; una pantalla ya cargada puede seguir visible.

## 11. Si vas a sustituir al servidor antiguo: trasladar los datos

No omitir este paso si ya hay clientes, saldos o movimientos.

1. Acordar un momento sin operaciones. Avisar a todos los usuarios y detener las
   escrituras en el servidor antiguo.
2. En la aplicación antigua, descargar una copia desde **Ajustes → Descargar copia ahora**.
   La descarga está protegida con PIN: debe estar configurado y debes conocerlo.
   Guardar también una copia de seguridad independiente antes del traslado.
3. Parar la aplicación antigua y no volver a usarla para cobrar después de esa copia.
4. Parar la aplicación nueva. Si ya tiene datos, conservar una copia antes de
   sustituirlos. No sobrescribir una BD ni sus archivos WAL/SHM con la app en marcha.
5. Copiar la copia descargada como `papeleria.db` a la ruta que usará el lanzador
   nuevo. Para evitar mezclar archivos antiguos, es preferible una carpeta de
   datos nueva y vacía y configurar `PAPELERIA_DB` hacia ella.
6. No copiar únicamente `papeleria.db` de un servidor en marcha: con SQLite WAL
   puede faltar información reciente. Utilizar la copia generada por la app.
7. Arrancar el nuevo servidor; comprobar saldos, clientes, historial y reconciliación.
   Una copia existente conserva el PIN: no crear otro si ya está configurado.
8. Revisar Ajustes: rutas de backup del PC antiguo pueden no existir en el nuevo.
   Configurar el destino nuevo, crear una copia y comprobar que se puede recuperar.
9. Cambiar los accesos directos de todos los clientes al dominio nuevo y la regla
   de acceso/tests al destino nuevo. No permitir escrituras en ambos servidores.
10. Tras verificar el relevo, desactivar **solo** Serve de la aplicación antigua:

    ```powershell
    # Ejecutar en el servidor ANTIGUO, si este proxy corresponde solo a Papelería.
    tailscale serve --https=443 off
    ```

11. Si el PC antiguo ya no debe pertenecer a la red, retirarlo de **Machines**.
    Conservar sus datos/backup hasta comprobar la migración; no borrarlos de inmediato.

## 12. Evitar cortes al reiniciar o suspender

- `tailscale serve --bg` persiste tras reiniciar; el modo desatendido mantiene
  Tailscale conectado aunque no haya sesión de escritorio.
- **Ninguna de esas opciones arranca Java.** Actualmente `iniciar.bat` requiere
  ejecución manual y una ventana abierta. No se ha implementado el servicio Windows.
- Para funcionar automáticamente tras reiniciar y recuperarse de caídas, falta
  instalar/configurar el servicio de la app (WinSW u otra solución supervisada),
  con la misma BD, permisos y logs. Hasta entonces, arrancar `iniciar.bat` tras
  cada reinicio y comprobar el acceso remoto.
- En un PC dedicado conectado a corriente, configurar Windows para que no se
  suspenda durante el horario de servicio. Por ejemplo, desde PowerShell elevada:

  ```powershell
  powercfg /change standby-timeout-ac 0
  ```

  Esto desactiva la suspensión automática **con corriente**, no en batería.
  No requiere dejar la pantalla encendida; bloquear la sesión sigue siendo recomendable.

## Problemas habituales

| Síntoma | Qué comprobar |
|---|---|
| `403 FORBIDDEN_HOST` | Revisar `PAPELERIA_ALLOWED_HOSTS` en `.env` y en Windows, sin URL ni punto final; cerrar y arrancar mediante `iniciar.bat`. Reconstruir solo si el jar aún es anterior al soporte de variables. |
| `403 FORBIDDEN_ORIGIN` al guardar | Abrir frontend y API por el mismo HTTPS; conservar rutas relativas y `X-Requested-With`; no usar Vite remoto ni otro proxy que cambie Host. |
| `502` desde Serve | Java está arrancado y responde en `http://localhost:8080`; Serve apunta a `127.0.0.1:8080`. |
| No conecta desde el cliente | Misma tailnet, usuario aceptado, dispositivo aprobado, Tailscale activo y regla dirigida a la IP nueva:443. |
| Sesión `local@…` por HTTPS | Revisar Serve HTTP y `forward-headers-strategy: none`; no usar un simple túnel TCP ni mandar identidades desde el frontend. |
| Error de certificado | Usar el dominio completo del equipo, no su IP; comprobar HTTPS/MagicDNS y la fecha/hora. No omitir la validación TLS. |
| No hay clientes o saldos | Verificar la ruta de BD del lanzador, el usuario Windows y la copia trasladada; no importar encima sin revisar. |
| Error al renombrar/empaquetar el jar | Detener la aplicación antes de reconstruir. |
| Tras reiniciar no funciona | Tailscale puede estar conectado, pero Java no: iniciar la app; falta el servicio Windows. |

## Lista final de comprobación

- [ ] Nuevo equipo en la tailnet correcta y aprobado.
- [ ] IP y dominio nuevos anotados; no se usan los del servidor antiguo.
- [ ] Backend en `127.0.0.1:8080`, `forward-headers-strategy: none`.
- [ ] `.env` con localhost, dominio nuevo y dominio nuevo`:443`; jar compatible con variables.
- [ ] Frontend con `/api/v1` relativo, sin IP remota ni CORS adicional.
- [ ] Serve HTTPS en segundo plano; sin Funnel y sin puertos del router abiertos.
- [ ] ACL miembros → nuevo servidor:443, sin regla de acceso abierto; tests actualizados.
- [ ] Aprobación de usuarios/dispositivos activa y cuentas protegidas.
- [ ] Caducidad desactivada solo en el servidor; clientes conservan la suya.
- [ ] Acceso e identidad comprobados desde un cliente real.
- [ ] Una sola BD y un solo servidor admitiendo escrituras.
- [ ] PIN, backups y rutas del nuevo PC revisados.
- [ ] Suspensión/arranque revisados; se conoce la limitación del servicio Windows.

Referencia del equipo configurado originalmente: [setup.md](setup.md).
