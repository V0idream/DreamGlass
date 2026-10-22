param([switch]$UseMavenMirror)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    $gradleArgs = @('--no-daemon', '--console=plain', ':app:assembleDebug', ':app:lintDebug')
    if ($UseMavenMirror) { $gradleArgs += '-PuseMavenMirror=true' }
    & .\gradlew.bat @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw "Build or Lint failed (exit $LASTEXITCODE)." }
    Write-Output "APK: $projectRoot\app\build\outputs\apk\debug\app-debug.apk"
} finally { Pop-Location }
