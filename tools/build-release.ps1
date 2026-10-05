Set-Location (Join-Path $PSScriptRoot "..")
& .\gradlew.bat clean assembleRelease
if ($LASTEXITCODE -ne 0) { throw "Release build failed." }
Write-Host "APK: app\build\outputs\apk\release\app-release.apk"
