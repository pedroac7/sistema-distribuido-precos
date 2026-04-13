@echo off
setlocal enabledelayedexpansion

if "%~4"=="" (
    echo Uso: run-module.bat ^<windowTitle^> ^<moduleDir^> ^<mainClass^> ^<args...^>
    pause
    exit /b 1
)

set "WINDOW_TITLE=%~1"
set "MODULE_DIR=%~2"
set "MAIN_CLASS=%~3"
shift
shift
shift

set "JAVA_ARGS="
:collect_args
if "%~1"=="" goto after_collect
set "JAVA_ARGS=!JAVA_ARGS! "%~1""
shift
goto collect_args

:after_collect

REM Primeiro tenta usar a pasta atual como raiz do repo
if exist "%CD%\api-gateway" if exist "%CD%\validador-precos" if exist "%CD%\repositorio-precos" (
    set "ROOT_DIR=%CD%"
) else (
    REM Se nao der, usa o pai da pasta scripts
    pushd "%~dp0.."
    set "ROOT_DIR=%CD%"
    popd
)

set "MODULE_PATH=%ROOT_DIR%\%MODULE_DIR%"

title %WINDOW_TITLE%

if not exist "%MODULE_PATH%\" (
    echo O sistema nao pode encontrar o caminho especificado.
    echo Falha ao acessar o modulo %MODULE_DIR%.
    echo Caminho esperado: "%MODULE_PATH%"
    pause
    exit /b 1
)

cd /d "%MODULE_PATH%" || (
    echo O sistema nao pode encontrar o caminho especificado.
    echo Falha ao acessar o modulo %MODULE_DIR%.
    echo Caminho esperado: "%MODULE_PATH%"
    pause
    exit /b 1
)

if not exist "target\classes" (
    echo Pasta target\classes nao encontrada em %MODULE_DIR%.
    echo Rode: mvn -q -DskipTests package
    pause
    exit /b 1
)

if not exist "target\dependency" (
    echo Pasta target\dependency nao encontrada em %MODULE_DIR%.
    echo Rode: mvn -q dependency:copy-dependencies
    pause
    exit /b 1
)

echo Iniciando %WINDOW_TITLE%...
echo Modulo: %MODULE_DIR%
echo Pasta: %CD%
echo Classe principal: %MAIN_CLASS%
echo Argumentos Java: %JAVA_ARGS%
echo.

java -cp "target\classes;target\dependency\*" %MAIN_CLASS% %JAVA_ARGS%

set "EXIT_CODE=%ERRORLEVEL%"
echo.
echo Processo %WINDOW_TITLE% finalizado com codigo %EXIT_CODE%.
pause
exit /b %EXIT_CODE%