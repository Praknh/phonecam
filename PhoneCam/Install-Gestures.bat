@echo off
title PhoneCam Gestures Installer
cd /d "%~dp0\windows"

echo [setup] Installing MediaPipe for gesture recognition...
pip install mediapipe
echo [setup] Testing gesture engine...
python gestures.py
pause
