@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "ROOT=%~dp0"
set "FRONTEND=%ROOT%frontend"
set "BACKEND=%ROOT%backend"

echo ============================================================
echo   Papeleria - Construccion (frontend + backend)
echo ============================================================
echo.

where java >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Java no esta instalado o no esta en el PATH. Se necesita Java 21 o superior.
  goto :error
)
where mvn >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Maven no esta instalado o no esta en el PATH.
  goto :error
)
where node >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Node.js no esta instalado. Instale Node.js 20 LTS o superior: https://nodejs.org/
  echo         Alternativa: winget install OpenJS.NodeJS.LTS
  goto :error
)
for /f "tokens=1 delims=." %%V in ('node -p "process.versions.node"') do set "NODE_MAJOR=%%V"
if !NODE_MAJOR! LSS 20 (
  echo [ERROR] Se requiere Node.js 20 o superior. Version detectada:
  node --version
  goto :error
)

echo [OK] Java:
java -version 2>&1 | findstr /i "version"
echo [OK] Node.js:
node --version
echo.

pushd "%FRONTEND%" || goto :error

echo [1/4] Instalando dependencias del frontend (npm ci)...
call npm ci
if errorlevel 1 goto :step_error
echo.

echo [2/4] Tests del frontend...
call npm test
if errorlevel 1 goto :step_error
echo.

echo [3/4] Compilando el frontend (queda dentro del backend)...
call npm run build
if errorlevel 1 goto :step_error
echo.
popd

echo [4/4] Empaquetando el backend (jar con el frontend incluido)...
pushd "%BACKEND%" || goto :error
call mvn -B -q package -DskipTests
if errorlevel 1 goto :step_error
popd

if not exist "%BACKEND%\target\papeleria-1.0.0.jar" (
  echo [ERROR] No se genero backend\target\papeleria-1.0.0.jar
  goto :error
)

echo.
echo ============================================================
echo   Construccion correcta
echo ============================================================
echo Jar: backend\target\papeleria-1.0.0.jar
echo Para arrancar: iniciar.bat
echo.
exit /b 0

:step_error
set "EXIT_CODE=%ERRORLEVEL%"
popd
echo.
echo [ERROR] La construccion ha fallado.
if "%EXIT_CODE%"=="0" set "EXIT_CODE=1"
exit /b %EXIT_CODE%

:error
echo.
echo La construccion no se ha completado.
exit /b 1
