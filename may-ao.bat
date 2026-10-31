@echo off
rem Customer-like emulator (`scripts/emu_y700.sh user`, row 79 of docs/CAM_CHANGES.md).
rem Double-click: menu, Enter starts it, the menu comes back after each step. Drop files on
rem this file: start it if needed, install each APK over the Manager, copy anything else (a
rem module zip) to Download. The emulator runs on its own, so closing this window leaves it up.
setlocal
set "CAM_DROP="
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

if "%~1"=="" goto start_menu
rem Explorer quotes a dropped name only when it has a space: cmd has already cut a name with
rem & , ; = or ^ in it before %1 sees it, and would run what follows an & as a command. The raw
rem line still has it whole: the script splits what stands after this file's name, %* would be
rem parsed again, and `exit` (not /b) drops the rest of the line. A name with &( and no space
rem cannot be helped: cmd stops at a syntax error before this file runs and the window just
rem closes, so rename that one.
setlocal EnableDelayedExpansion
set "line=!cmdcmdline!"
set "dropped=!line:*%~nx0=!"
if "!dropped!"=="!line!" set "dropped="
rem delayed expansion would eat a ! in the names below: for /f carries the value past endlocal
if defined dropped (
    for /f "delims=" %%l in ("!dropped!") do (
        endlocal
        set "CAM_DROP=%%l"
    )
) else endlocal
set "rc=0"
echo Dua file vao may ao ^(APK thi cai, file khac vao Download^)...
if defined CAM_DROP (
    "%BASH%" "%EMU%" user drop
    if errorlevel 1 set "rc=1"
    goto drop_done
)
rem typed at a prompt, where the raw line does not name this file: one by one and in quotes,
rem bash would take a bare ' for a quote
:next_arg
"%BASH%" "%EMU%" user start "%~f1"
if errorlevel 1 set "rc=1"
shift
if not "%~1"=="" goto next_arg
:drop_done
if %rc% neq 0 (
    echo.
    echo Loi, xem dong phia tren.
    pause
) else "%SystemRoot%\System32\timeout.exe" /t 3 >nul 2>&1
if defined CAM_DROP exit %rc%
exit /b %rc%

:start_menu
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
