@echo off
rem USB mode: lower delay than Wi-Fi. Needs USB debugging turned on in the phone's Developer options.
call "%~dp0PhoneCam.bat" --usb %*
