@echo off
setlocal

set "SCRIPT_DIR=%~dp0"

start "GATEWAY_UDP" cmd /k ""%SCRIPT_DIR%run-module.bat" "GATEWAY_UDP" "api-gateway" "gateway.Main" 8080 9090 udp 100 udp"
start "VALIDADOR1_UDP" cmd /k ""%SCRIPT_DIR%run-module.bat" "VALIDADOR1_UDP" "validador-precos" "validador.Main" 8081 127.0.0.1 9090 udp udp"
start "VALIDADOR2_UDP" cmd /k ""%SCRIPT_DIR%run-module.bat" "VALIDADOR2_UDP" "validador-precos" "validador.Main" 8084 127.0.0.1 9090 udp udp"
start "REPOSITORIO1_UDP" cmd /k ""%SCRIPT_DIR%run-module.bat" "REPOSITORIO1_UDP" "repositorio-precos" "repositorio.Main" 8082 127.0.0.1 9090 udp udp"
start "REPOSITORIO2_UDP" cmd /k ""%SCRIPT_DIR%run-module.bat" "REPOSITORIO2_UDP" "repositorio-precos" "repositorio.Main" 8083 127.0.0.1 9090 udp udp"