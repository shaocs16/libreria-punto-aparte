@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\crear-acceso-directo.ps1"
set "EXIT_CODE=%ERRORLEVEL%"
if not "%EXIT_CODE%"=="0" (
  echo.
  echo [ERROR] No se pudo crear el acceso directo del Escritorio.
  pause
)
endlocal & exit /b %EXIT_CODE%
