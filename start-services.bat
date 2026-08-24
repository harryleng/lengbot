@echo off
chcp 65001 >nul 2>&1
setlocal EnableDelayedExpansion

title lengbot dev services

REM ============================================================
REM  lengbot 开发环境一键启动脚本
REM  启动: PostgreSQL 16 / Redis / MinIO
REM  说明: 三个组件均为绿色免安装版, 以普通用户进程方式运行,
REM        不需要管理员权限, 重启电脑后双击本文件即可启动。
REM ============================================================

REM ===== 路径配置 (如需修改请只改这里) =====
set "PG_HOME=D:\lengbot\infra\postgresql\pgsql"
set "PG_BIN=%PG_HOME%\bin"
set "PG_DATA=D:\lengbot\infra\pgdata"
set "PG_PORT=5432"
set "PG_USER=postgres"
set "PG_PASSWORD=postgres"
set "PG_APP_DB=lengbot"

set "REDIS_HOME=D:\lengbot\infra\redis"
set "REDIS_EXE=%REDIS_HOME%\redis-server.exe"
set "REDIS_PORT=6379"
set "REDIS_PASSWORD=123456"

set "MINIO_HOME=D:\lengbot\infra\minio"
set "MINIO_EXE=%MINIO_HOME%\minio.exe"
set "MINIO_DATA=D:\lengbot\infra\minio-data"
set "MINIO_PORT=9000"
set "MINIO_CONSOLE=9001"
set "MINIO_ROOT_USER=minioadmin"
set "MINIO_ROOT_PASSWORD=minioadmin"

echo ===================================================
echo   lengbot dev services - starting
echo ===================================================

REM ===== [1/3] PostgreSQL =====
echo.
echo [1/3] PostgreSQL ...
if not exist "%PG_BIN%\pg_ctl.exe" (
    echo   ERROR: PostgreSQL not found at %PG_BIN%
    echo   Please re-run the install step.
    goto :error
)
if not exist "%PG_DATA%" mkdir "%PG_DATA%"
if not exist "%PG_DATA%\PG_VERSION" (
    echo   First run: initializing new PostgreSQL cluster ...
    echo|set /p="%PG_PASSWORD%" > "%TEMP%\pg_pw.txt"
    "%PG_BIN%\initdb.exe" -D "%PG_DATA%" -E UTF8 --locale=C -U %PG_USER% --auth=scram-sha-256 --pwfile="%TEMP%\pg_pw.txt" > "%TEMP%\pg_initdb.log" 2>&1
    if errorlevel 1 (
        del "%TEMP%\pg_pw.txt" 2>nul
        echo   ERROR: initdb failed, see %TEMP%\pg_initdb.log
        goto :error
    )
    del "%TEMP%\pg_pw.txt" 2>nul
    echo   Cluster initialized.
)
"%PG_BIN%\pg_ctl.exe" status -D "%PG_DATA%" >nul 2>&1
if "%errorlevel%"=="0" (
    echo   PostgreSQL already running.
) else (
    "%PG_BIN%\pg_ctl.exe" start -D "%PG_DATA%" -l "%PG_DATA%\pg.log" -w -o "-p %PG_PORT%"
    if errorlevel 1 (
        echo   ERROR: failed to start PostgreSQL, see %PG_DATA%\pg.log
        goto :error
    )
    echo   PostgreSQL started on port %PG_PORT%.
)

REM ===== ensure application database exists =====
set "PGPASSWORD=%PG_PASSWORD%"
"%PG_BIN%\psql.exe" -U %PG_USER% -h localhost -p %PG_PORT% -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='%PG_APP_DB%'" 2>nul | findstr /r "^1$" >nul
if errorlevel 1 (
    "%PG_BIN%\psql.exe" -U %PG_USER% -h localhost -p %PG_PORT% -d postgres -c "CREATE DATABASE %PG_APP_DB%;" 2>nul
    echo   Created database '%PG_APP_DB%'.
) else (
    echo   Database '%PG_APP_DB%' already exists.
)

REM ===== [2/3] Redis =====
echo.
echo [2/3] Redis ...
tasklist | findstr /i "redis-server" >nul 2>&1
if "%errorlevel%"=="0" (
    echo   Redis already running.
) else (
    if exist "%REDIS_EXE%" (
        start "Redis" /min "%REDIS_EXE%" --port %REDIS_PORT% --requirepass %REDIS_PASSWORD% --dir "%REDIS_HOME%"
        echo   Redis started on port %REDIS_PORT%.
    ) else (
        echo   ERROR: %REDIS_EXE% not found.
        goto :error
    )
)

REM ===== [3/3] MinIO =====
echo.
echo [3/3] MinIO ...
tasklist | findstr /i "minio.exe" >nul 2>&1
if "%errorlevel%"=="0" (
    echo   MinIO already running.
) else (
    if exist "%MINIO_EXE%" (
        set "MINIO_ROOT_USER=%MINIO_ROOT_USER%"
        set "MINIO_ROOT_PASSWORD=%MINIO_ROOT_PASSWORD%"
        start "MinIO" /min "%MINIO_EXE%" server "%MINIO_DATA%" --address ":%MINIO_PORT%" --console-address ":%MINIO_CONSOLE%"
        echo   MinIO started. API :%MINIO_PORT%  Console :%MINIO_CONSOLE%.
    ) else (
        echo   ERROR: %MINIO_EXE% not found.
        goto :error
    )
)

echo.
echo ===================================================
echo  All services started successfully.
echo.
echo  PostgreSQL : localhost:%PG_PORT%   user=%PG_USER%   password=%PG_PASSWORD%
echo  Redis      : localhost:%REDIS_PORT%   password=%REDIS_PASSWORD%
echo  MinIO API  : http://localhost:%MINIO_PORT%   (accessKey=%MINIO_ROOT_USER%  secretKey=%MINIO_ROOT_PASSWORD%)
echo  MinIO UI   : http://localhost:%MINIO_CONSOLE%
echo ===================================================
echo.
echo  Services keep running after you close this window.
echo  Press any key to exit.
pause >nul
goto :eof

:error
echo.
echo  Startup aborted due to the error(s) above.
pause >nul
exit /b 1

