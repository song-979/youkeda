@echo off
setlocal EnableExtensions

set "SCRIPT_DIR=%~dp0"
set "MAVEN_VERSION=3.9.9"
set "DIST_NAME=apache-maven-%MAVEN_VERSION%"
set "WRAPPER_DIR=%SCRIPT_DIR%.mvn\wrapper"
set "DIST_DIR=%SCRIPT_DIR%.mvn\%DIST_NAME%"
set "ZIP_FILE=%WRAPPER_DIR%\%DIST_NAME%-bin.zip"
set "DOWNLOAD_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MAVEN_VERSION%/%DIST_NAME%-bin.zip"

if defined MAVEN_HOME if exist "%MAVEN_HOME%\bin\mvn.cmd" (
  call "%MAVEN_HOME%\bin\mvn.cmd" %*
  exit /b %ERRORLEVEL%
)

if defined M2_HOME if exist "%M2_HOME%\bin\mvn.cmd" (
  call "%M2_HOME%\bin\mvn.cmd" %*
  exit /b %ERRORLEVEL%
)

set "PATH_MVN="
for %%I in (mvn.cmd) do set "PATH_MVN=%%~$PATH:I"
if defined PATH_MVN (
  call "%PATH_MVN%" %*
  exit /b %ERRORLEVEL%
)

if not exist "%WRAPPER_DIR%" mkdir "%WRAPPER_DIR%" >nul 2>nul
if errorlevel 1 (
  echo Failed to create wrapper cache directory: "%WRAPPER_DIR%"
  exit /b 1
)

if not exist "%DIST_DIR%\bin\mvn.cmd" (
  if not exist "%ZIP_FILE%" (
    powershell -NoProfile -ExecutionPolicy Bypass -Command ^
      "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri '%DOWNLOAD_URL%' -OutFile '%ZIP_FILE%'"
    if errorlevel 1 (
      echo Failed to download Maven %MAVEN_VERSION%.
      exit /b 1
    )
  )

  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ProgressPreference='SilentlyContinue'; Expand-Archive -LiteralPath '%ZIP_FILE%' -DestinationPath '%SCRIPT_DIR%.mvn' -Force"
  if errorlevel 1 (
    echo Failed to unpack Maven %MAVEN_VERSION%.
    exit /b 1
  )
)

if not exist "%DIST_DIR%\bin\mvn.cmd" (
  echo Maven bootstrap failed: "%DIST_DIR%\bin\mvn.cmd" was not created.
  exit /b 1
)

call "%DIST_DIR%\bin\mvn.cmd" %*
exit /b %ERRORLEVEL%
