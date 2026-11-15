$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
& npm.cmd exec vite build web -- --config web/vite.survey.config.ts
if ($LASTEXITCODE -ne 0) { throw '问卷界面构建失败' }
$packageRoot = Join-Path (Get-Location) 'build/survey-package'
New-Item -ItemType Directory -Force $packageRoot | Out-Null
Copy-Item -LiteralPath server/survey/server.mjs,server/survey/tvt-survey.service,server/survey/tvt-survey-purge.service,server/survey/tvt-survey-purge.timer,server/survey/tvt-survey-cert-renew.service,server/survey/tvt-survey-cert-renew.timer,server/survey/tvt-survey-firewall.service,server/survey/firewall.nft,server/survey/nginx-direct.conf.template,server/survey/cloudflared.example.yml,server/survey/.env.example,server/survey/README.md -Destination $packageRoot -Force
New-Item -ItemType Directory -Force (Join-Path $packageRoot 'public') | Out-Null
Copy-Item -Path build/survey-web/* -Destination (Join-Path $packageRoot 'public') -Recurse -Force
New-Item -ItemType Directory -Force artifacts | Out-Null
$env:TVT_SURVEY_PACKAGE_ROOT = $packageRoot
$env:TVT_SURVEY_PACKAGE_OUTPUT = Join-Path (Get-Location) 'artifacts/TvT-survey-server.zip'
@'
import os, zipfile
from pathlib import Path
root = Path(os.environ['TVT_SURVEY_PACKAGE_ROOT'])
with zipfile.ZipFile(os.environ['TVT_SURVEY_PACKAGE_OUTPUT'], 'w', zipfile.ZIP_DEFLATED) as archive:
    for path in root.rglob('*'):
        if path.is_file():
            archive.write(path, path.relative_to(root).as_posix())
'@ | python -B -
if ($LASTEXITCODE -ne 0) { throw '问卷部署包压缩失败' }
Write-Host '问卷部署包：artifacts/TvT-survey-server.zip（不包含密钥和答案）'
