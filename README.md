# Papelería: saldo de clientes

Gestión de saldo prepago. Backend Java 21 + Spring Boot + SQLite; frontend React + TypeScript servido por el propio backend.

## Ejecutarlo para verlo

La forma más simple: **doble clic en `iniciar.bat`**. Arranca la aplicación, espera a que responda y abre el navegador en la URL HTTPS de Tailscale (`https://<dominio>.ts.net`); si Serve no responde, usa http://localhost:8080 y avisa. Al cerrar la ventana (o con `Ctrl+C`) se detiene y limpia los procesos.

| Fichero | Para qué |
|---|---|
| `iniciar.bat` | Arranca backend y frontend (un solo proceso: el frontend va dentro del jar) y abre el navegador: HTTPS de Tailscale si está disponible; si no, localhost. Si ya está en marcha, solo abre el navegador. |
| `construir.bat` | Instala dependencias, pasa los tests del frontend, compila el frontend y empaqueta el jar. Hay que ejecutarlo la primera vez y tras cambiar código. |
| `crear-acceso-directo.bat` | Crea el acceso directo «Papeleria» en el Escritorio. |

Datos: `%LOCALAPPDATA%\Papeleria\data\papeleria.db`. Para usar otra ruta, define la variable `PAPELERIA_DB` antes de lanzarlo. Con `PAPELERIA_NO_BROWSER=1` no abre el navegador.

Los lanzadores siguen el patrón de `panel_soporte` (`run.bat` + `scripts\run.ps1`).

### Sin los `.bat`

Requiere Java 21. El jar ya incluye el frontend.

```powershell
cd backend
java -jar target\papeleria-1.0.0.jar --app.db-path=C:\Temp\papeleria-demo\papeleria.db
```

Abre **http://localhost:8080**. En el primer arranque ve a **Ajustes** y crea el PIN (4 a 8 dígitos).

- `--app.db-path` es el fichero de la base de datos; se crea solo. Sin él se usa `%ProgramData%\Papeleria\data\papeleria.db`.
- La app escucha solo en `127.0.0.1`. Para pararla: `Ctrl+C`.
- Si no existe el jar o has cambiado código, reconstruye:

```powershell
cd frontend
npm install
npm run build                 # deja el frontend en backend/src/main/resources/static
cd ..\backend
mvn package -DskipTests
```

## Desarrollo (recarga en caliente)

Dos terminales:

```powershell
cd backend ; java -jar target\papeleria-1.0.0.jar --app.db-path=C:\Temp\papeleria-demo\papeleria.db
cd frontend ; npm run dev     # http://localhost:5173 (proxy a :8080)
```

## Tests

```powershell
cd backend  ; mvn test                         # 29 pruebas
cd frontend ; npm test                         # 27 pruebas unitarias
cd frontend ; npm run build ; cd ..\backend ; mvn package -DskipTests ; cd ..\frontend
$env:PW_CHANNEL="msedge" ; npm run test:e2e    # 21 pruebas E2E (usa el Edge instalado)
```

## Restablecer el PIN

Desde el PC servidor, con la app parada o en marcha:

```powershell
java -jar backend\target\papeleria-1.0.0.jar --app.db-path=<ruta> --reset-pin
```

Más información: `PENDIENTE.md` (lo que falta), `frontend/DESIGN.md`, `frontend/VERIFICACION.md`.
