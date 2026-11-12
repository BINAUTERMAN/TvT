$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
$javaPath = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { "$env:USERPROFILE/.jdks/jbr-17.0.14/bin" }
New-Item -ItemType Directory -Force build/test | Out-Null
if (!(Test-Path build/test/json.jar)) {
    Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar' -OutFile build/test/json.jar
}
& "$javaPath/javac.exe" -encoding UTF-8 -cp build/test/json.jar -d build/test android/src/cn/ting/music/UpdateSource.java tests/UpdateSourceCheck.java
if ($LASTEXITCODE -ne 0) { throw 'Update test compile failed' }
& "$javaPath/java.exe" -cp 'build/test;build/test/json.jar' cn.ting.music.UpdateSourceCheck
if ($LASTEXITCODE -ne 0) { throw 'Update source tests failed' }
