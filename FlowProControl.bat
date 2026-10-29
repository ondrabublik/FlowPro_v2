@echo off
rem Starts the FlowPro GUI (tasks, mesh generator, computations) from the package root
set "FLOWPRO=%~dp0FlowPro"
if not exist "%FLOWPRO%\FlowProControl.jar" (
    echo FlowProControl.jar not found in "%FLOWPRO%".
    echo Build it first: gradlew build
    pause
    exit /b 1
)
where javaw >nul 2>nul
if errorlevel 1 (
    echo Java was not found ^(javaw is not on PATH^). Install Java 17 or newer.
    pause
    exit /b 1
)
cd /d "%FLOWPRO%"
start "" javaw -jar FlowProControl.jar "%FLOWPRO%"
