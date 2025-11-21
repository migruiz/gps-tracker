@echo off
setlocal enabledelayedexpansion

:: Script to build and run GPS Tracker Docker image locally
:: Requires Docker CLI

set "SCRIPT_DIR=%~dp0"
set "IMAGE_NAME=migruiz/gps-tracker:latest"
set "CONTAINER_NAME=gps-tracker-web"
set "DOCKERFILE_DIR=%SCRIPT_DIR%"
set "HOST_PORT=3000"
set "CONTAINER_PORT=3000"
set "ENV_FILE=%SCRIPT_DIR%.env.local"

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

echo [INFO] Checking for existing container with name %CONTAINER_NAME% ...
docker inspect %CONTAINER_NAME% >nul 2>&1
if not errorlevel 1 (
    echo [INFO] Stopping existing container %CONTAINER_NAME% ...
    docker stop %CONTAINER_NAME%
    if errorlevel 1 (
        echo [WARN] Failed to stop container, attempting to remove anyway...
    )
    echo [INFO] Removing existing container %CONTAINER_NAME% ...
    docker rm %CONTAINER_NAME%
    if errorlevel 1 (
        echo [ERROR] Failed to remove existing container.
        exit /b 1
    )
    echo [INFO] Existing container removed successfully.
)

echo [INFO] Running container %CONTAINER_NAME% from image %IMAGE_NAME% ...
docker run -d --name %CONTAINER_NAME% -p %HOST_PORT%:%CONTAINER_PORT% %IMAGE_NAME%
if errorlevel 1 (
    echo [ERROR] Failed to run container.
    exit /b 1
)

echo [INFO] Container %CONTAINER_NAME% is running successfully.
echo [INFO] Access the application at http://localhost:%HOST_PORT%
echo [INFO] To view logs: docker logs %CONTAINER_NAME%
echo [INFO] To stop: docker stop %CONTAINER_NAME%
echo [INFO] To remove: docker rm %CONTAINER_NAME%
exit /b 0
