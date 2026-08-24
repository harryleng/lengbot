@echo off
chcp 65001 >nul 2>&1
setlocal

title lengbot dev services - stop

set "PG_BIN=D:\lengbot\infra\postgresql\pgsql\bin"
set "PG_DATA=D:\lengbot\infra\pgdata"

echo Stopping PostgreSQL ...
if exist "%PG_BIN%\pg_ctl.exe" (
    "%PG_BIN%\pg_ctl.exe" stop -D "%PG_DATA%" -m fast >nul 2>&1
)
echo Stopping Redis ...
taskkill /f /im redis-server.exe >nul 2>&1
echo Stopping MinIO ...
taskkill /f /im minio.exe >nul 2>&1
echo Done.
pause

