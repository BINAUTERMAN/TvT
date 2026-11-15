$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
$javaPath = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { "$env:USERPROFILE/.jdks/jbr-17.0.14/bin" }
$androidJar = "$env:LOCALAPPDATA/Android/Sdk/platforms/android-34/android.jar"
New-Item -ItemType Directory -Force build/test/survey-native | Out-Null
if (!(Test-Path build/test/json.jar)) { Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar' -OutFile build/test/json.jar }
if (!(Test-Path build/test/survey-native/localhost.p12)) {
  & "$javaPath/keytool.exe" -genkeypair -alias localhost -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore build/test/survey-native/localhost.p12 -storepass tvt-test-only -keypass tvt-test-only -dname 'CN=localhost' -ext 'SAN=dns:localhost,ip:127.0.0.1' -validity 30
  if ($LASTEXITCODE -ne 0) { throw '测试证书生成失败' }
}
& "$javaPath/javac.exe" -encoding UTF-8 -cp "build/test/json.jar;$androidJar" -d build/test/survey-native android/src/cn/ting/music/SurveyFeedback.java android/src/cn/ting/music/StarPrompt.java tests/SurveyHttpCheck.java
if ($LASTEXITCODE -ne 0) { throw '原生问卷测试编译失败' }
$env:TVT_TEST_JAVA = "$javaPath/java.exe"
& node scripts/test-survey-native.mjs
if ($LASTEXITCODE -ne 0) { throw '原生 HTTPS 本地联调失败' }
