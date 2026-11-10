$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
$javaPath = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { "$env:USERPROFILE/.jdks/jbr-17.0.14/bin" }
$androidJar = "$env:LOCALAPPDATA/Android/Sdk/platforms/android-34/android.jar"
New-Item -ItemType Directory -Force build/test | Out-Null
if (!(Test-Path build/test/json.jar)) { Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar' -OutFile build/test/json.jar }
& "$javaPath/javac.exe" -encoding UTF-8 -cp "build/test/json.jar;$androidJar" -d build/test android/src/cn/ting/music/KugouApi.java android/src/cn/ting/music/LoginProtocol.java android/src/cn/ting/music/AccountStore.java android/src/cn/ting/music/CatalogApi.java android/src/cn/ting/music/FavoritesRepository.java android/src/cn/ting/music/QualityCatalog.java android/src/cn/ting/music/LyricsDocument.java android/src/cn/ting/music/VipRepository.java android/src/cn/ting/music/CheckinRepository.java android/src/cn/ting/music/LoginChoices.java tests/NativeApiSmoke.java tests/CheckinStateCheck.java
if ($LASTEXITCODE -ne 0) { throw 'Native test compile failed' }
& "$javaPath/java.exe" -cp "build/test;build/test/json.jar;$androidJar" cn.ting.music.NativeApiSmoke
$smokeFailed = $LASTEXITCODE -ne 0
& "$javaPath/java.exe" -cp "build/test;build/test/json.jar;$androidJar" cn.ting.music.CheckinStateCheck
$checkinFailed = $LASTEXITCODE -ne 0
# 两项都跑完再判定，避免实时接口不可用时看不到纯逻辑结果。
if ($smokeFailed) { throw 'Native API test failed' }
if ($checkinFailed) { throw 'Checkin state test failed' }
