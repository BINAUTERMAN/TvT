param([switch]$NetworkOnly,[switch]$CatalogOnly,[switch]$DiscoveryOnly,[switch]$CacheOnly,[switch]$BuildOnly)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
$sdkPath = "$env:LOCALAPPDATA/Android/Sdk"
$toolPath = "$sdkPath/build-tools/34.0.0"
$javaPath = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { "$env:USERPROFILE/.jdks/jbr-17.0.14/bin" }
New-Item -ItemType Directory -Force build/device/classes | Out-Null
& "$toolPath/aapt2.exe" link -o build/device/base.apk -I "$sdkPath/platforms/android-34/android.jar" --manifest tests/device/AndroidManifest.xml
if ($LASTEXITCODE -ne 0) { throw 'Test resource compile failed' }
& "$javaPath/javac.exe" -encoding UTF-8 -source 8 -target 8 -cp "$sdkPath/platforms/android-34/android.jar" -d build/device/classes tests/device/DeviceSmoke.java tests/device/NetworkDiagnostics.java tests/device/CatalogChecks.java tests/device/DiscoveryChecks.java tests/device/CacheChecks.java tests/device/BufferChecks.java tests/device/StarChecks.java tests/device/FocusProbeActivity.java tests/device/LyricsChecks.java tests/device/LyricsInteractionChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Test compile failed' }
& "$javaPath/jar.exe" cf build/device/classes.jar -C build/device/classes .
& "$javaPath/java.exe" -cp "$toolPath/lib/d8.jar" com.android.tools.r8.D8 --lib "$sdkPath/platforms/android-34/android.jar" --min-api 26 --output build/device build/device/classes.jar
if ($LASTEXITCODE -ne 0) { throw 'Test DEX compile failed' }
Copy-Item build/device/base.apk build/device/unsigned.apk -Force
& "$javaPath/jar.exe" uf build/device/unsigned.apk -C build/device classes.dex
& "$toolPath/zipalign.exe" -f 4 build/device/unsigned.apk build/device/aligned.apk
& "$javaPath/java.exe" -jar "$toolPath/lib/apksigner.jar" sign --ks build/preview.keystore --ks-pass pass:android --out build/device/test.apk build/device/aligned.apk
if ($BuildOnly) { Write-Host 'Device test APK compiled; no device tests executed'; return }
adb install -r build/device/test.apk
if ($LASTEXITCODE -ne 0) { throw 'Test APK install failed' }
if ($CacheOnly) {
    adb shell am instrument -w cn.ting.music.tests/cn.ting.music.tests.CacheChecks | Tee-Object artifacts/device-cache.txt
} elseif ($DiscoveryOnly) {
    adb shell am instrument -w cn.ting.music.tests/cn.ting.music.tests.DiscoveryChecks | Tee-Object artifacts/device-discovery-vip.txt
} elseif ($CatalogOnly) {
    adb shell am instrument -w cn.ting.music.tests/cn.ting.music.tests.CatalogChecks | Tee-Object artifacts/device-catalog.txt
} elseif ($NetworkOnly) {
    adb shell am instrument -w cn.ting.music.tests/cn.ting.music.tests.NetworkDiagnostics | Tee-Object artifacts/device-network.txt
} else {
    adb shell am instrument -w cn.ting.music.tests/cn.ting.music.tests.DeviceSmoke | Tee-Object artifacts/device-smoke.txt
}
