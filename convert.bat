@echo off
setlocal
REM Usage: convert.bat [--root=. --recursive=false ...]

where mvn >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Maven was not found in PATH.
    exit /b 1
)

set "ARGS=%*"
if "%ARGS%"=="" set "ARGS=--root=."

echo Running args: %ARGS%
mvn -q exec:java -Dexec.args="%ARGS%"
