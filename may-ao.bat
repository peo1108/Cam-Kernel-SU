@echo off
rem Customer-like emulator (`scripts/emu_y700.sh user`, row 79 of docs/CAM_CHANGES.md).
rem Double-click: menu, Enter starts it, the menu comes back after each step. Drop a file on
rem this file: start it if needed, install an APK over the Manager, copy anything else (a
rem module zip) to Download. The emulator runs on its own, so closing this window leaves it up.
setlocal
rem Git Bash: the usual places, then bin\ beside the cmd\ that holds git.exe
set "BASH="
for %%b in ("%ProgramFiles%\Git\bin\bash.exe" "%LOCALAPPDATA%\Programs\Git\bin\bash.exe") do (
    if not defined BASH if exist "%%~b" set "BASH=%%~b"
)
if not defined BASH for /f "delims=" %%g in ('where git.exe 2^>nul') do (
    if not defined BASH if exist "%%~dpg..\bin\bash.exe" set "BASH=%%~dpg..\bin\bash.exe"
)
set "EMU=%~dp0scripts\emu_y700.sh"
if not defined BASH (
    echo Khong thay Git Bash, cai Git for Windows truoc.
    pause
    exit /b 1
)

rem the name stays in quotes: a ")" or "&" in it (manager (1).apk) would break this block
if not "%~1"=="" (
    echo Dua "%~nx1" vao may ao ^(APK thi cai, file khac vao Download^)...
    "%BASH%" "%EMU%" user start "%~f1"
    if errorlevel 1 (
        echo.
        echo Loi, xem dong phia tren.
        pause
        exit /b 1
    )
    timeout /t 3 >nul
    exit /b 0
)

set "default=1"
:menu
echo.
echo   1. Bat may ao
echo   2. Va root lai ^(sau khi Manager len ban co LKM moi^)
echo   3. Ve ramdisk goc ^(may chua root^)
echo   4. Tat may ao
echo   0. Thoat ^(may ao van chay^)
echo.
set "pick=%default%"
set /p "pick=Chon [%default%]: "
if "%pick%"=="0" exit /b 0
set "cmd="
if "%pick%"=="1" set "cmd=start"
if "%pick%"=="2" set "cmd=flash"
if "%pick%"=="3" set "cmd=unflash"
if "%pick%"=="4" set "cmd=stop"
if not defined cmd goto menu
"%BASH%" "%EMU%" user %cmd%
if errorlevel 1 echo Loi, xem dong phia tren.
set "default=0"
goto menu
