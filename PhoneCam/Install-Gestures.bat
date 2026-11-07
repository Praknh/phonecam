@echo off
setlocal EnableExtensions
title PhoneCam gesture setup
rem Adds hand-gesture detection (thumbs up = fireworks, peace = balloons, ...).
rem It builds a separate Python environment so the normal PhoneCam setup is never touched.

set "APPDIR=%LOCALAPPDATA%\PhoneCam"
set "VENV=%APPDIR%\venv"
set "GVENV=%APPDIR%\venv_gestures"
set "REQS=%~dp0windows\requirements.txt"

if not exist "%VENV%\Scripts\python.exe" (
    echo Please double-click PhoneCam.bat once first so the basic setup is done,
    echo then run this file again.
    pause
    exit /b 1
)

echo ============================================
echo  Installing hand-gesture detection
echo ============================================
echo This downloads roughly 200-400 MB and can take several minutes. Please wait...
echo.

if exist "%GVENV%" rmdir /s /q "%GVENV%"
"%VENV%\Scripts\python.exe" -m venv "%GVENV%"
if errorlevel 1 goto fail
"%GVENV%\Scripts\python.exe" -m pip install --upgrade pip --quiet
"%GVENV%\Scripts\python.exe" -m pip install -r "%REQS%" mediapipe
if errorlevel 1 goto fail

echo.
echo Checking hand tracking and downloading its small model...
"%GVENV%\Scripts\python.exe" "%~dp0windows\gestures.py"
if errorlevel 1 (
    echo.
    echo Hand tracking is installed, but its model could not be downloaded just now.
    echo Check your internet connection and run this file again. PhoneCam also retries on start.
)

echo.
echo Done! PhoneCam.bat will now use gesture detection automatically.
echo Hold a gesture for half a second in front of the camera:
echo   thumbs up = fireworks      peace sign = balloons      two peace signs = confetti
echo   I-love-you sign (thumb + index + little finger) = hearts      thumbs down = rain
pause
exit /b 0

:fail
echo.
echo Gesture setup failed, so it was not installed. PhoneCam still works as before,
echo and you can start the effects by pressing keys 1 to 5 in the preview window.
if exist "%GVENV%" rmdir /s /q "%GVENV%"
pause
exit /b 1
