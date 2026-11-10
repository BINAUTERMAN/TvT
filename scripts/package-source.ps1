$ErrorActionPreference = 'Stop'
$workspacePath = Split-Path -Parent $PSScriptRoot
Set-Location $workspacePath
$versionName = ([xml](Get-Content android/AndroidManifest.xml -Raw)).manifest.GetAttribute('versionName', 'http://schemas.android.com/apk/res/android')
$sourcePath = Join-Path $workspacePath "build/source-tvt-$versionName"
$verifiedSourcePath = [IO.Path]::GetFullPath($sourcePath)
$verifiedBuildPath = [IO.Path]::GetFullPath((Join-Path $workspacePath 'build')) + [IO.Path]::DirectorySeparatorChar
if (!$verifiedSourcePath.StartsWith($verifiedBuildPath, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid source staging path' }
if (Test-Path -LiteralPath $verifiedSourcePath) { Remove-Item -LiteralPath $verifiedSourcePath -Recurse -Force }
New-Item -ItemType Directory -Force $sourcePath | Out-Null
$extensions = @('.svg','.java','.vue','.ts','.mjs','.json','.css','.html','.xml','.gradle','.properties','.ps1','.yml','.yaml','.txt')
$files = @('README.md','功能使用教程.md','LICENSE','package.json','package-lock.json','.gitignore','.gitattributes','.prettierrc.json','assets/tvt.svg')
foreach ($folder in @('android','web','tests','scripts','config','.github')) {
    $files += Get-ChildItem -LiteralPath (Join-Path $workspacePath $folder) -Recurse -File -Force | ForEach-Object {
        $relative = $_.FullName.Substring($workspacePath.Length + 1).Replace('\','/')
        if ($relative -match '(^|/)(build|dist|node_modules|\.git|\.gradle|\.idea|\.state|\.vscode)(/|$)') { return }
        if ($relative -match '(^|/)(local\.properties|google-services\.json|.*(?:credential|secret|token|session|account-export|login-state).*\.json)$') { return }
        if ($extensions -contains $_.Extension.ToLowerInvariant()) { $relative }
    }
}
$files = @($files | Select-Object -Unique)
foreach ($file in $files) {
    $destination = Join-Path $sourcePath $file
    New-Item -ItemType Directory -Force (Split-Path -Parent $destination) | Out-Null
    Copy-Item -LiteralPath (Join-Path $workspacePath $file) -Destination $destination -Force
}
Compress-Archive -Path "$sourcePath/*" -DestinationPath "artifacts/TvT-$versionName-source.zip" -Force
Get-FileHash "artifacts/TvT-$versionName-preview.apk","artifacts/TvT-$versionName-source.zip" -Algorithm SHA256 | Format-Table -AutoSize
