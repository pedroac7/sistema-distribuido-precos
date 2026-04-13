@echo off
setlocal

set "SCRIPT_DIR=%~dp0"

start "GATEWAY_HTTP" cmd /k ""%SCRIPT_DIR%run-module.bat" "GATEWAY_HTTP" "api-gateway" "gateway.Main" 8080 9090 http 100 http"
start "VALIDADOR1_HTTP" cmd /k ""%SCRIPT_DIR%run-module.bat" "VALIDADOR1_HTTP" "validador-precos" "validador.Main" 8081 127.0.0.1 9090 http http"
start "VALIDADOR2_HTTP" cmd /k ""%SCRIPT_DIR%run-module.bat" "VALIDADOR2_HTTP" "validador-precos" "validador.Main" 8084 127.0.0.1 9090 http http"
start "REPOSITORIO1_HTTP" cmd /k ""%SCRIPT_DIR%run-module.bat" "REPOSITORIO1_HTTP" "repositorio-precos" "repositorio.Main" 8082 127.0.0.1 9090 http http"
start "REPOSITORIO2_HTTP" cmd /k ""%SCRIPT_DIR%run-module.bat" "REPOSITORIO2_HTTP" "repositorio-precos" "repositorio.Main" 8083 127.0.0.1 9090 http http"