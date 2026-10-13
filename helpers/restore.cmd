@echo off
setlocal
rem Usage: restore.cmd C:\absolute\state\restore-jobs\JOB\job.properties [--recover]
if "%~1"=="" goto usage
if not "%~3"=="" goto usage
set "HELPER=%VAULTBACKUP_HELPER_JAR%"
if not defined HELPER set "HELPER=%~dp0vaultbackup-restore-1.1.0.jar"
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"
echo Japanese instructions: see docs\OFFLINE-RESTORE.md (UTF-8).
echo Cancelled jobs can never be resumed; no automatic restart is performed.
echo Disable hosting auto-restart and confirm the server JVM is completely stopped.
set "CONFIRM="
set /p "CONFIRM=Type STOPPED to proceed: "
if not "%CONFIRM%"=="STOPPED" exit /b 2
if "%~2"=="--recover" goto recover
if not "%~2"=="" goto usage
"%JAVA%" -jar "%HELPER%" --job "%~1" --manual --i-confirm-server-stopped
exit /b %errorlevel%
:recover
"%JAVA%" -jar "%HELPER%" --job "%~1" --recover --i-confirm-server-stopped
exit /b %errorlevel%
:usage
echo Usage: restore.cmd C:\absolute\job.properties [--recover]
exit /b 2
