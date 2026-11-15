param([switch]$OfflineOnly)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
$fmJavaPath = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { "$env:USERPROFILE/.jdks/jbr-17.0.14/bin" }
$fmAndroidJar = "$env:LOCALAPPDATA/Android/Sdk/platforms/android-34/android.jar"
New-Item -ItemType Directory -Force build/test | Out-Null
if (!(Test-Path build/test/json.jar)) {
    Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar' -OutFile build/test/json.jar
}
$fmSources = @(Get-ChildItem android/src/cn/ting/music -Filter '*.java' | Where-Object { $_.Name -in @('KugouApi.java','LoginProtocol.java','AccountStore.java','CatalogApi.java','FavoritesRepository.java','QualityCatalog.java','LyricsDocument.java','VipRepository.java','CheckinRepository.java','LoginChoices.java','PersonalFmApi.java','PersonalFmSession.java') } | ForEach-Object { $_.FullName })
& "$fmJavaPath/javac.exe" -encoding UTF-8 -cp "build/test/json.jar;$fmAndroidJar" -d build/test @fmSources tests/PersonalFmSessionCheck.java tests/PersonalFmApiCheck.java
if ($LASTEXITCODE -ne 0) { throw 'Personal FM compile failed' }
& "$fmJavaPath/java.exe" '-Dfile.encoding=UTF-8' -cp "build/test;build/test/json.jar;$fmAndroidJar" cn.ting.music.PersonalFmSessionCheck
if ($LASTEXITCODE -ne 0) { throw 'Personal FM boundary checks failed' }
if (!$OfflineOnly) {
    & "$fmJavaPath/java.exe" '-Dfile.encoding=UTF-8' -cp "build/test;build/test/json.jar;$fmAndroidJar" cn.ting.music.PersonalFmApiCheck
    if ($LASTEXITCODE -ne 0) { throw 'Real personal FM API check failed' }
}
