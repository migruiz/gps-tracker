@echo off
setlocal enabledelayedexpansion

:: Script to build and push Docker image for GPS Tracker Web App
:: Requires Docker CLI and a Docker Hub access token stored in dockerhub_credentials.txt

set "SCRIPT_DIR=%~dp0"
set "IMAGE_NAME=migruiz/gps-tracker:latest"
set "DOCKERFILE_DIR=%SCRIPT_DIR%"
set "CREDENTIALS_FILE=%SCRIPT_DIR%dockerhub_credentials.txt"
set "ENV_FILE=%SCRIPT_DIR%.env.local"

if not exist "%CREDENTIALS_FILE%" (
    echo [ERROR] Credentials file not found at "%CREDENTIALS_FILE%".
    echo Create the file with the format: username:token
    exit /b 1
)

if not exist "%ENV_FILE%" (
    echo [ERROR] Environment file not found at "%ENV_FILE%".
    echo Create .env.local with NEXT_PUBLIC_GOOGLE_MAPS_API_KEY
    exit /b 1
)

:: Read Google Maps API key from .env.local
for /f "usebackq tokens=1,* delims==" %%a in ("%ENV_FILE%") do (
    if "%%a"=="NEXT_PUBLIC_GOOGLE_MAPS_API_KEY" (
        set "GOOGLE_MAPS_KEY=%%b"
    )
)

if not defined GOOGLE_MAPS_KEY (
    echo [ERROR] NEXT_PUBLIC_GOOGLE_MAPS_API_KEY not found in .env.local
    exit /b 1
)

for /f "usebackq tokens=1,2 delims=:" %%a in ("%CREDENTIALS_FILE%") do (
    set "DOCKERHUB_USERNAME=%%a"
    set "DOCKERHUB_TOKEN=%%b"
    goto :after_credentials
)

:after_credentials
if not defined DOCKERHUB_USERNAME (
    echo [ERROR] Docker Hub username missing in credentials file.
    exit /b 1
)

if not defined DOCKERHUB_TOKEN (
    echo [ERROR] Docker Hub token missing in credentials file.
    exit /b 1
)

where docker >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker CLI not found in PATH.
    exit /b 1
)

echo [INFO] Building image %IMAGE_NAME% with Google Maps API key...
docker build --build-arg NEXT_PUBLIC_GOOGLE_MAPS_API_KEY=%GOOGLE_MAPS_KEY% --file "%DOCKERFILE_DIR%Dockerfile" --tag %IMAGE_NAME% %DOCKERFILE_DIR%
if errorlevel 1 (
    echo [ERROR] Docker build failed.
    exit /b 1
)

echo [INFO] Authenticating to Docker Hub...
echo %DOCKERHUB_TOKEN% | docker login --username %DOCKERHUB_USERNAME% --password-stdin
if errorlevel 1 (
    echo [ERROR] Docker login failed.
    exit /b 1
)

echo [INFO] Pushing image %IMAGE_NAME% ...
docker push %IMAGE_NAME%
set "PUSH_STATUS=%ERRORLEVEL%"

docker logout >nul 2>&1

if not "%PUSH_STATUS%"=="0" (
    echo [ERROR] Docker push failed.
    exit /b %PUSH_STATUS%
)

echo [INFO] Image pushed successfully.
exit /b 0
