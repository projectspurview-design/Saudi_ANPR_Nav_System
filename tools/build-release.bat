@echo off
setlocal
cd /d "%~dp0.."
call gradlew.bat clean assembleRelease
if errorlevel 1 (
  echo.
  echo BUILD FAILED
  exit /b 1
)
echo.
echo BUILD SUCCESSFUL
 echo APK: app\build\outputs\apk\release\app-release.apk
