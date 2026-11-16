$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
$nativeJars = @(& "$PSScriptRoot/native-dependencies.ps1")
$javaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { "$env:USERPROFILE/.jdks/jbr-17.0.14/bin" }
$sdkPath = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$androidJar = Join-Path $sdkPath 'platforms/android-34/android.jar'
$classpath = (@('build/test/json.jar',$androidJar) + $nativeJars) -join ';'
New-Item -ItemType Directory -Force build/test/network-safety | Out-Null
if (!(Test-Path build/test/json.jar)) { Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar' -OutFile build/test/json.jar }
& "$javaBin/javac.exe" -encoding UTF-8 -sourcepath android/src -cp $classpath -d build/test/network-safety tests/NetworkSafetyCheck.java
if ($LASTEXITCODE -ne 0) { throw '网络与空值测试编译失败' }
& "$javaBin/java.exe" -cp "build/test/network-safety;$classpath" cn.ting.music.NetworkSafetyCheck
if ($LASTEXITCODE -ne 0) { throw '网络与空值测试失败' }
