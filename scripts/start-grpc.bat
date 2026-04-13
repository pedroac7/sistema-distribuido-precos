@echo off
setlocal

set "SCRIPT_DIR=%~dp0"

start "GATEWAY_GRPC" cmd /k ""%SCRIPT_DIR%run-module.bat" "GATEWAY_GRPC" "api-gateway" "gateway.Main" 8080 9090 grpc 100 grpc"
start "VALIDADOR1_GRPC" cmd /k ""%SCRIPT_DIR%run-module.bat" "VALIDADOR1_GRPC" "validador-precos" "validador.Main" 8081 127.0.0.1 9090 grpc grpc"
start "VALIDADOR2_GRPC" cmd /k ""%SCRIPT_DIR%run-module.bat" "VALIDADOR2_GRPC" "validador-precos" "validador.Main" 8084 127.0.0.1 9090 grpc grpc"
start "REPOSITORIO1_GRPC" cmd /k ""%SCRIPT_DIR%run-module.bat" "REPOSITORIO1_GRPC" "repositorio-precos" "repositorio.Main" 8082 127.0.0.1 9090 grpc grpc"
start "REPOSITORIO2_GRPC" cmd /k ""%SCRIPT_DIR%run-module.bat" "REPOSITORIO2_GRPC" "repositorio-precos" "repositorio.Main" 8083 127.0.0.1 9090 grpc grpc"