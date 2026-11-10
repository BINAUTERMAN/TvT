$ErrorActionPreference = 'Stop'
$workspacePath = Split-Path -Parent $PSScriptRoot
Set-Location $workspacePath
$sdkPath = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$javaPath = if ($env:JAVA_HOME) { $env:JAVA_HOME } elseif (Test-Path "$env:USERPROFILE/.jdks/jbr-17.0.14") { "$env:USERPROFILE/.jdks/jbr-17.0.14" } else { 'C:\Program Files\Android\Android Studio\jbr' }
$toolPath = Join-Path $sdkPath 'build-tools\34.0.0'
$androidJar = Join-Path $sdkPath 'platforms\android-34\android.jar'
function Run-Checked([string]$program, [string[]]$arguments) {
    & $program @arguments
    if ($LASTEXITCODE -ne 0) { throw "构建失败：$program" }
}
Run-Checked 'npm.cmd' @('run', 'build')
foreach ($relativePath in @('build/classes', 'build/generated', 'build/assets/web')) {
    $targetPath = [IO.Path]::GetFullPath((Join-Path $workspacePath $relativePath))
    $buildRootPath = [IO.Path]::GetFullPath((Join-Path $workspacePath 'build')) + [IO.Path]::DirectorySeparatorChar
    if (!$targetPath.StartsWith($buildRootPath, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid build output path' }
    if (Test-Path -LiteralPath $targetPath) { Remove-Item -LiteralPath $targetPath -Recurse -Force }
}
New-Item -ItemType Directory -Force build/classes,build/assets/web,artifacts | Out-Null
Copy-Item -Path web/dist/* -Destination build/assets/web -Recurse -Force
$updateConfig = Get-Content config/update.json -Raw | ConvertFrom-Json
if ($env:TING_UPDATE_REPOSITORY) { $updateConfig.repository = $env:TING_UPDATE_REPOSITORY }
[IO.File]::WriteAllText((Join-Path $workspacePath 'build/assets/update.json'), ($updateConfig | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
Run-Checked "$toolPath/aapt2.exe" @('compile', '--dir', 'android/res', '-o', 'build/resources.zip')
Run-Checked "$toolPath/aapt2.exe" @('link', '-o', 'build/base.apk', '-I', $androidJar, '--manifest', 'android/AndroidManifest.xml', '--java', 'build/generated', '-A', 'build/assets', 'build/resources.zip')
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$archive = [IO.Compression.ZipFile]::Open((Join-Path $workspacePath 'build/base.apk'), [IO.Compression.ZipArchiveMode]::Update)
try {
    foreach ($entry in @($archive.Entries)) {
        if (!$entry.FullName.Contains('\')) { continue }
        $normalized = $archive.CreateEntry($entry.FullName.Replace('\', '/'))
        $inputStream = $entry.Open()
        $outputStream = $normalized.Open()
        try { $inputStream.CopyTo($outputStream) } finally { $inputStream.Dispose(); $outputStream.Dispose() }
        $entry.Delete()
    }
    if ($null -eq $archive.GetEntry('assets/web/index.html')) { throw 'Web assets are missing from APK' }
} finally { $archive.Dispose() }
$sourceFiles = @(Get-ChildItem android/src,build/generated -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
Run-Checked "$javaPath/bin/javac.exe" (@('-encoding','UTF-8','-source','8','-target','8','-classpath',$androidJar,'-d','build/classes') + $sourceFiles)
Run-Checked "$javaPath/bin/jar.exe" @('cf','build/classes.jar','-C','build/classes','.')
Run-Checked "$javaPath/bin/java.exe" @('-cp',"$toolPath/lib/d8.jar",'com.android.tools.r8.D8','--lib',$androidJar,'--min-api','26','--output','build','build/classes.jar')
Copy-Item build/base.apk build/unsigned.apk -Force
Run-Checked "$javaPath/bin/jar.exe" @('uf','build/unsigned.apk','-C','build','classes.dex')
Run-Checked "$toolPath/zipalign.exe" @('-f','4','build/unsigned.apk','build/aligned.apk')
$keyPath = if ($env:TING_KEYSTORE_PATH) { $env:TING_KEYSTORE_PATH } else { Join-Path $workspacePath 'build/preview.keystore' }
if (!$env:TING_SIGNING_PASSWORD) { $env:TING_SIGNING_PASSWORD = 'android' }
if (!(Test-Path $keyPath)) {
    if ($env:CI) { throw 'Release signing key must be configured in CI' }
    Run-Checked "$javaPath/bin/keytool.exe" @('-genkeypair','-keystore',$keyPath,'-storepass','android','-keypass','android','-alias','ting-preview','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=Ting Preview,O=Ting,C=CN')
}
Run-Checked "$javaPath/bin/java.exe" @('-jar',"$toolPath/lib/apksigner.jar",'sign','--ks',$keyPath,'--ks-pass','env:TING_SIGNING_PASSWORD','--out','artifacts/TvT-0.5.2-preview.apk','build/aligned.apk')
Run-Checked "$javaPath/bin/java.exe" @('-jar',"$toolPath/lib/apksigner.jar",'verify','--verbose','artifacts/TvT-0.5.2-preview.apk')
Write-Host 'APK 已生成：artifacts/TvT-0.5.2-preview.apk'
