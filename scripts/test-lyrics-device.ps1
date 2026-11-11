param([Parameter(Mandatory=$true)][string]$Serial)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
& "$PSScriptRoot/test-device.ps1" -BuildOnly
$adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
$version = ([xml](Get-Content android/AndroidManifest.xml -Raw)).manifest.GetAttribute('versionName', 'http://schemas.android.com/apk/res/android')
& $adb -s $Serial install -r "artifacts/TvT-$version-preview.apk"
if ($LASTEXITCODE -ne 0) { throw 'App install failed' }
& $adb -s $Serial install -r build/device/test.apk
if ($LASTEXITCODE -ne 0) { throw 'Test install failed' }
& $adb -s $Serial shell appops set cn.ting.music SYSTEM_ALERT_WINDOW allow
if ($LASTEXITCODE -ne 0) { throw 'Overlay permission setup failed' }
$result = & $adb -s $Serial shell am instrument -w cn.ting.music.tests/cn.ting.music.tests.LyricsChecks
$result | Tee-Object artifacts/device-lyrics.txt
& $adb -s $Serial pull /sdcard/Android/data/cn.ting.music/files/lyrics-verification artifacts
if (($result -join "`n") -notmatch 'LYRICS_NATIVE_PASS') { throw 'Native lyrics integration failed' }
