@echo off
title PhoneCam HD (USB Mode)
cd /d "%~dp0\windows"

python -c "import av, pyvirtualcam, cv2" 2>nul
if %errorlevel% neq 0 (
    echo [setup] Installing required dependencies...
    pip install -r requirements.txt
)

python receiver.py --usb %*
pause
