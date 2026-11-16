$ErrorActionPreference = 'Stop'
$dependencyRoot = Split-Path -Parent $PSScriptRoot
$dependencyFolder = Join-Path $dependencyRoot 'build/native-deps'
$assetFolder = Join-Path $dependencyRoot 'build/native-assets'
New-Item -ItemType Directory -Force $dependencyFolder,$assetFolder | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Get-DependencyHash([string]$path) {
    $algorithm = [Security.Cryptography.SHA256]::Create()
    $dependencyStream = [IO.File]::OpenRead($path)
    try { return ([BitConverter]::ToString($algorithm.ComputeHash($dependencyStream))).Replace('-', '') }
    finally { $dependencyStream.Dispose(); $algorithm.Dispose() }
}
$dependencies = Get-Content (Join-Path $dependencyRoot 'config/native-dependencies.json') -Raw | ConvertFrom-Json
foreach ($dependency in $dependencies) {
    $name = "$($dependency.name)-$($dependency.version)"
    $artifactPath = Join-Path $dependencyFolder "$name.$($dependency.extension)"
    if (!(Test-Path -LiteralPath $artifactPath)) {
        $repository = if ($dependency.repository -eq 'google') { 'https://dl.google.com/dl/android/maven2' } else { 'https://repo.maven.apache.org/maven2' }
        $groupPath = $dependency.group.Replace('.', '/')
        Invoke-WebRequest "$repository/$groupPath/$($dependency.name)/$($dependency.version)/$name.$($dependency.extension)" -OutFile $artifactPath
    }
    if ((Get-DependencyHash $artifactPath) -ne $dependency.sha256) { throw "原生依赖摘要不符：$name" }
    $jarPath = Join-Path $dependencyFolder "$name.jar"
    if ($dependency.extension -eq 'aar') {
        $archive = [IO.Compression.ZipFile]::OpenRead($artifactPath)
        try {
            if (!(Test-Path -LiteralPath $jarPath) -or (Get-DependencyHash $jarPath) -ne $dependency.jarSha256) {
                [IO.Compression.ZipFileExtensions]::ExtractToFile($archive.GetEntry('classes.jar'), $jarPath, $true)
            }
            foreach ($entry in $archive.Entries) {
                if ($entry.FullName -ne 'assets/PublicSuffixDatabase.list') { continue }
                $assetPath = Join-Path $assetFolder 'PublicSuffixDatabase.list'
                if (!(Test-Path -LiteralPath $assetPath)) {
                    [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $assetPath, $true)
                }
            }
        } finally { $archive.Dispose() }
        if ((Get-DependencyHash $jarPath) -ne $dependency.jarSha256) { throw "原生依赖 JAR 摘要不符：$name" }
    }
    Write-Output $jarPath
}
