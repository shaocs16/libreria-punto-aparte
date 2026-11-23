# Pendiente

Estado a 18/11/2026. Backend y frontend están hechos y verificados (ver `frontend/VERIFICACION.md`). Esto es lo que falta y qué supone no hacerlo.

## 1. Impide el uso real en la papelería

| Pendiente | Si no se hace |
|---|---|
| **Acceso de la 2ª persona (Tailscale):** añadir el `*.ts.net` del equipo a `app.allowed-hosts` (`backend/src/main/resources/application.yml`), ejecutar `tailscale serve --bg 8080` y configurar la ACL (aprobación de dispositivos, expiración de claves, Funnel desactivado). | Solo se puede usar desde el PC servidor. Desde otro equipo responde 403 `FORBIDDEN_HOST`. |
| **Servicio de Windows (WinSW)** que arranque la app con el sistema y la reinicie si cae. Logs rotativos en `%ProgramData%\Papeleria\logs`. | Hay que lanzar `java -jar` a mano. Si el PC se reinicia o se cierra la ventana, la app se detiene y nadie puede cobrar ni consultar saldos. |
| **Instalador `jpackage`** con Java incluido (MSI/EXE). | Hay que tener Java 21 instalado y ejecutar el jar a mano. Sin proceso limpio de actualización. |
| **Documentación de operación** en `ops/docs/`: `instalacion.md`, `restore.md`, `reset-pin.md` y `tailscale/setup.md`. | Nadie más sabría instalar, recuperar o restablecer el PIN. |
| **`Papeleria.exe --reset-pin`** como ejecutable. Hoy es `java -jar papeleria-1.0.0.jar --reset-pin`. | Si olvidan el PIN, solo se arregla desde consola. |
| **Desactivar la suspensión** del PC servidor: `powercfg /change standby-timeout-ac 0`. | Si el PC se suspende, la 2ª persona pierde acceso. |

## 2. Riesgo de pérdida de datos

**Los backups siguen sin activar** (decisión pendiente del documento de arquitectura). La copia diaria está programada pero desactivada. Sin backup, un fallo de disco, un borrado accidental o malware supone **pérdida total e irrecuperable** de saldos e historial.

Mitigaciones que ya existen (no sustituyen a un backup): aviso rojo permanente, copia automática antes de cada migración y botón «Descargar copia ahora».

**Para activarlo:** Ajustes → carpeta de destino (OneDrive, USB o un disco aparte) → copia diaria «Activada». Sin cambios de código.

## 3. Tests que faltan

Hoy hay **29 tests de backend, 27 unitarios de frontend y 21 E2E** (ver `frontend/VERIFICACION.md`). Esto es lo que **no** está cubierto, ordenado por riesgo. Prioridad: **A** = toca dinero o datos, **B** = seguridad o uso diario, **C** = calidad y compatibilidad.

### A. Dinero y datos (backend)

| Test pendiente | Por qué importa |
|---|---|
| **Corte de luz o proceso muerto a mitad de una operación** (matar el proceso durante una importación grande y comprobar que al arrancar no queda nada a medias y la reconciliación cuadra). | Es el riesgo «corte de luz» del documento de arquitectura. Hoy solo se prueba el rollback ante un error, no ante un proceso muerto. |
| **La reconciliación detecta un descuadre real**: alterar a mano `customer_balance` y comprobar `/health` en `DEGRADED`, el aviso rojo y la entrada en auditoría. | El banner existe, pero nunca se ha visto saltar. Es la red de seguridad si un saldo se corrompe. |
| **Conflicto de edición** (`If-Match` con versión antigua → 409 `STALE_VERSION`). | Dos personas editando el mismo cliente. La regla existe y no tiene test. |
| **Baja y reactivación de clientes**: baja con saldo (`CUSTOMER_HAS_BALANCE`), operar con un cliente de baja (`CUSTOMER_INACTIVE`) y reactivar. | Está implementado y probado solo de forma indirecta. |
| **Devolución (`REFUND`) y ajuste (`ADJUST`) completos por API**, con saldo y auditoría. | Solo se prueban sus validaciones (nota obligatoria) a nivel de servicio, no el caso feliz de extremo a extremo. |
| **Anular un ajuste y una devolución**, y fecha antigua (la anulación no tiene límite de tiempo). | La regla «sin límite de fecha» no tiene test propio. |
| **Rotación de copias 7/4/12**: crear copias con fechas simuladas y comprobar cuáles se conservan. | Hoy solo se prueba una copia; la rotación borra ficheros, así que un fallo borraría copias buenas. |
| **Restaurar una copia**: parar, sustituir `papeleria.db`, arrancar y comprobar que los saldos cuadran. | Un backup que nunca se ha restaurado no se sabe si funciona. Depende de `restore.md`. |
| **Copia previa a la migración** automatizada. | Se comprobó una vez a mano, no hay test. |

### A. Importación

| Test pendiente | Por qué importa |
|---|---|
| **XLSX grande** (decenas de miles de filas) y comprobar memoria constante. | Se prometió lectura en streaming; solo se probó un XLSX de 1 fila. |
| **XLSX con macros (`.xlsm` renombrado)**, **XXE** y **zip bomb**. | Medidas de seguridad implementadas sin un test que las demuestre. |
| **Fichero de más de 50 MB** → 413 `FILE_TOO_LARGE`. | El límite existe, no hay prueba. |
| **Importación concurrente** con cobros en mostrador mientras se importa. | La importación bloquea las escrituras unos segundos; falta ver que el cobro espera y no falla. |
| **CSV con comillas, saltos de línea dentro de un campo, BOM y separadores mezclados.** | Los separadores `;` y `,` y las codificaciones UTF-8 y Windows-1252 ya están probados; estos casos límite no. |

### B. Seguridad y uso diario

| Test pendiente | Por qué importa |
|---|---|
| **Bloqueo de PIN que sobrevive a un reinicio** de la aplicación. | Está guardado en BD, pero el test no reinicia el contexto. |
| **`--reset-pin` automatizado** (hoy solo se probó a mano y a nivel de servicio). | Es la única vía si se olvida el PIN. |
| **Cabeceras de seguridad** (`Content-Security-Policy`, `X-Frame-Options`, `Cache-Control: no-store`) en todas las rutas, y subida multipart sin `X-Requested-With`. | Hoy solo se prueba Host, Origin y la cabecera de Tailscale. |
| **Dos personas a la vez** (dos navegadores, dos usuarios Tailscale) cobrando al mismo cliente. | Es el escenario real del negocio. Solo hay un test de concurrencia a nivel de servicio. |
| **Filtros y paginación**: saldo bajo, inactivos, orden, páginas, filtros del historial y de la auditoría. | Muchos caminos de la interfaz sin prueba. |
| **E2E pendientes:** devolución y ajuste con PIN, baja/reactivación, cambiar PIN, PIN bloqueado en pantalla y edición con conflicto de versión. | Los flujos con PIN más delicados solo están probados en unitarios o por API. |
| **Tests unitarios de componentes sin cubrir:** `CustomerPicker` (flechas, Enter, Escape), `LedgerTable` (diálogo de anulación), `ImportsPage` y `SettingsPage`. | Solo `MoneyForm`, el modal de PIN y el parseo de importes tienen test unitario. |

### C. Calidad y compatibilidad

| Test pendiente | Detalle |
|---|---|
| **Impresión del ticket** en una impresora real de 80 mm y en A4. | El CSS de 72 mm existe y nunca se ha imprimido. |
| **Revisión visual** de las capturas de `frontend/test-results/`. | Se generaron pero nadie las ha mirado. |
| **Accesibilidad con axe** y con un lector de pantalla (NVDA). | Hoy solo hay comprobaciones de teclado, foco y desbordamiento. |
| **Firefox y Safari**, y un **móvil real** (no solo el viewport de 390 px). | Todo se probó con Edge. |
| **Acceso por Tailscale de extremo a extremo** (HTTPS, cabeceras de usuario, ACL). | Requiere la configuración de la sección 1. |
| **Rendimiento con datos reales:** búsqueda y dashboard con 10.000 clientes ya medidos en JUnit, falta probarlos desde el navegador (carga de página, tabla de 200 filas). | Los objetivos del documento se midieron en backend, no en la interfaz. |
| **Auditoría de dependencias:** `npm audit` y OWASP Dependency-Check para Java. | No se han ejecutado. |
| **Servicio de Windows:** reinicio automático si la app cae y arranque tras reiniciar el PC. | Depende de WinSW (sección 1). |
| **Chromium de Playwright:** `playwright install` falla por el certificado corporativo. | Mientras tanto el E2E depende de tener Edge instalado. |

## 4. Menores

- `recharts` 2.x está marcada como obsoleta en npm (la 3.x es la activa).
- `PRAGMA integrity_check` solo se ejecuta los domingos a las 03:30; `/health` muestra `not-run` hasta entonces.
- La hora de la copia diaria es fija (22:00); se cambia con `app.backup-cron`.

## Orden recomendado

1. Activar el backup.
2. Tests **A** de datos: restaurar una copia, descuadre de reconciliación y proceso muerto a mitad de importación (comprueban que los datos sobreviven).
3. Tailscale y `allowed-hosts`.
4. WinSW y documentos de operación.
5. `jpackage`, prueba del ticket impreso y tests **B** y **C**.
