@echo off
setlocal
rem Usage: restore.cmd C:\absolute\state\restore-jobs\JOB\job.properties [--recover [--accept-rollback-directory]]
if "%~1"=="" goto usage
if not "%~4"=="" goto usage
if not "%~3"=="" if not "%~3"=="--accept-rollback-directory" goto usage
if not "%~3"=="" if not "%~2"=="--recover" goto usage
if not "%~2"=="" if not "%~2"=="--recover" goto usage
rem The helper shipped beside this script, unless VAULTBACKUP_HELPER_JAR names another one
rem (for example the copy the plugin installed: state-directory\helper\vaultbackup-restore.jar).
set "HELPER=%VAULTBACKUP_HELPER_JAR%"
if not defined HELPER set "HELPER=%~dp0vaultbackup-restore.jar"
if not exist "%HELPER%" (
  echo Restore helper not found: "%HELPER%"
  echo Set VAULTBACKUP_HELPER_JAR to its path.
  exit /b 2
)
if not exist "%~1" (
  echo Job file not found: "%~1"
  exit /b 2
)
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"
echo Japanese instructions: see docs\OFFLINE-RESTORE.md (UTF-8).
echo Cancelled jobs can never be resumed; no automatic restart is performed.
echo Disable hosting auto-restart and confirm the server JVM is completely stopped.
if "%~2"=="--recover" goto recover
"%JAVA%" -jar "%HELPER%" --job "%~1" --manual --i-confirm-server-stopped
exit /b %errorlevel%
:recover
if "%~3"=="--accept-rollback-directory" goto recoveraccept
"%JAVA%" -jar "%HELPER%" --job "%~1" --recover --i-confirm-server-stopped
exit /b %errorlevel%
:recoveraccept
"%JAVA%" -jar "%HELPER%" --job "%~1" --recover --i-confirm-server-stopped --accept-rollback-directory
exit /b %errorlevel%
:usage
echo Usage: restore.cmd C:\absolute\job.properties [--recover [--accept-rollback-directory]]
exit /b 2
