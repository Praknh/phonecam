@echo off
setlocal EnableExtensions
title PhoneCam
cd /d "%~dp0"

set "APPDIR=%LOCALAPPDATA%\PhoneCam"
set "VENV=%APPDIR%\venv"
set "PYEXE=%VENV%\Scripts\python.exe"
set "RECEIVER=%~dp0windows\receiver.py"
set "REQS=%~dp0windows\requirements.txt"

if not exist "%RECEIVER%" (
    echo Cannot find windows\receiver.py next to this file.
    echo Extract the whole zip first, then run PhoneCam.bat from the extracted PhoneCam folder.
    pause
    exit /b 1
)

if exist "%APPDIR%\setup_done.txt" if exist "%PYEXE%" goto run

echo ============================================
echo  PhoneCam first-time setup (only done once)
echo ============================================
echo.

call :findpython
if not defined BASEPY (
    echo Installing Python 3.12. Please wait, this can take a few minutes...
    winget install -e --id Python.Python.3.12 --silent --accept-package-agreements --accept-source-agreements
    call :findpython
)
if not defined BASEPY (
    echo.
    echo Could not install Python automatically.
    echo Opening the Python download page. Install Python 3.12, tick "Add python.exe to PATH",
    echo then double-click PhoneCam.bat again.
    start "" "https://www.python.org/downloads/"
    pause
    exit /b 1
)
echo Using Python: %BASEPY%

if not exist "%APPDIR%" mkdir "%APPDIR%"
echo.
echo Installing components. Please wait...
"%BASEPY%" -m venv "%VENV%"
if errorlevel 1 goto fail
"%PYEXE%" -m pip install --upgrade pip --quiet
"%PYEXE%" -m pip install -r "%REQS%"
if errorlevel 1 goto fail

call :findobs
if not defined OBSDIR (
    echo.
    echo To use PhoneCam as a camera in Zoom, Meet or Teams, OBS Studio is needed.
    echo It is free. Windows will ask for permission to install it.
    choice /c YN /m "Install OBS Studio now"
    if not errorlevel 2 (
        winget install -e --id OBSProject.OBSStudio --silent --accept-package-agreements --accept-source-agreements
        call :findobs
    )
)
if defined OBSDIR call :regcam

echo done> "%APPDIR%\setup_done.txt"
echo.
echo Setup finished.
echo.

:run
if exist "%APPDIR%\venv_gestures\Scripts\python.exe" set "PYEXE=%APPDIR%\venv_gestures\Scripts\python.exe"
echo Starting PhoneCam. Open the PhoneCam app on your phone and tap Start.
echo.
"%PYEXE%" "%RECEIVER%" %*
if errorlevel 1 (
    echo.
    echo PhoneCam stopped with an error. See the messages above.
    pause
)
exit /b

:fail
echo.
echo Setup failed. Check your internet connection and run PhoneCam.bat again.
pause
exit /b 1

:findpython
set "BASEPY="
for %%V in (3.12 3.11 3.10) do (
    if not defined BASEPY (
        for /f "delims=" %%P in ('py -%%V -c "import sys;print(sys.executable)" 2^>nul') do set "BASEPY=%%P"
    )
)
if defined BASEPY goto :eof
for %%D in ("%LOCALAPPDATA%\Programs\Python\Python312" "%LOCALAPPDATA%\Programs\Python\Python311" "%LOCALAPPDATA%\Programs\Python\Python310" "%ProgramFiles%\Python312" "%ProgramFiles%\Python311") do (
    if exist "%%~D\python.exe" if not defined BASEPY set "BASEPY=%%~D\python.exe"
)
goto :eof

:findobs
set "OBSDIR="
if exist "%ProgramFiles%\obs-studio\bin\64bit\obs64.exe" set "OBSDIR=%ProgramFiles%\obs-studio"
goto :eof

:regcam
set "VCAMBAT=%OBSDIR%\data\obs-plugins\win-dshow\virtualcam-install.bat"
if not exist "%VCAMBAT%" goto :eof
echo.
echo Registering the virtual camera. Windows will ask for permission, please click Yes.
powershell -NoProfile -Command "Start-Process -FilePath '%VCAMBAT%' -WorkingDirectory '%OBSDIR%\data\obs-plugins\win-dshow' -Verb RunAs -Wait" >nul 2>&1
goto :eof
