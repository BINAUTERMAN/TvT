$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
$sdkPath = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$javaPath = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { "$env:USERPROFILE/.jdks/jbr-17.0.14/bin" }
$testRoot = Join-Path (Get-Location) 'build/update-download-test'
New-Item -ItemType Directory -Force "$testRoot/classes", "$testRoot/assets" | Out-Null
Copy-Item config/update.json "$testRoot/assets/update.json" -Force
'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="cn.ting.music.updatechecks" android:versionCode="1" android:versionName="1"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34"/><uses-permission android:name="android.permission.INTERNET"/><application/><instrumentation android:name="cn.ting.music.tests.UpdateDownloadChecks" android:targetPackage="cn.ting.music.updatechecks"/></manifest>' | Set-Content "$testRoot/AndroidManifest.xml"
function Run-Checked([string]$program, [string[]]$arguments) {
    & $program @arguments
    if ($LASTEXITCODE -ne 0) { throw "Device update check failed: $program" }
}
$nativeJars = @(& "$PSScriptRoot/native-dependencies.ps1")
$androidJar = "$sdkPath/platforms/android-34/android.jar"
$toolPath = "$sdkPath/build-tools/34.0.0"
Run-Checked "$toolPath/aapt2.exe" @('link', '-o', "$testRoot/base.apk", '-I', $androidJar, '--manifest', "$testRoot/AndroidManifest.xml", '-A', "$testRoot/assets")
$sources = @('android/src/cn/ting/music/AppUpdater.java', 'android/src/cn/ting/music/UpdateSource.java', 'android/src/cn/ting/music/AppHttp.java', 'tests/device/UpdateDownloadChecks.java')
Run-Checked "$javaPath/javac.exe" (@('-encoding','UTF-8','-source','8','-target','8','-cp', ((@($androidJar) + $nativeJars) -join ';'), '-d', "$testRoot/classes") + $sources)
Run-Checked "$javaPath/jar.exe" @('cf', "$testRoot/classes.jar", '-C', "$testRoot/classes", '.')
Run-Checked "$javaPath/java.exe" (@('-cp', "$sdkPath/build-tools/36.0.0/lib/d8.jar", 'com.android.tools.r8.D8', '--lib', $androidJar, '--min-api', '26', '--output', $testRoot, "$testRoot/classes.jar") + $nativeJars)
Copy-Item "$testRoot/base.apk" "$testRoot/unsigned.apk" -Force
Run-Checked "$javaPath/jar.exe" @('uf', "$testRoot/unsigned.apk", '-C', $testRoot, 'classes.dex')
Run-Checked "$toolPath/zipalign.exe" @('-f','4', "$testRoot/unsigned.apk", "$testRoot/aligned.apk")
Run-Checked "$javaPath/java.exe" @('-jar', "$toolPath/lib/apksigner.jar", 'sign', '--ks', 'build/preview.keystore', '--ks-pass', 'pass:android', '--out', "$testRoot/test.apk", "$testRoot/aligned.apk")
Run-Checked 'adb' @('install','-r', "$testRoot/test.apk")
$output = & adb shell am instrument -w cn.ting.music.updatechecks/cn.ting.music.tests.UpdateDownloadChecks
$output | Tee-Object artifacts/update-download-device.txt
if ($LASTEXITCODE -ne 0 -or !($output -match 'UPDATE_DOWNLOAD_STATE_DEVICE_PASS')) { throw 'Update download state checks failed' }
