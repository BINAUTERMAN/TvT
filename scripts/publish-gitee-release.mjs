import fs from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';

const config = JSON.parse(await fs.readFile('config/update.json', 'utf8'));
const repository = process.env.TING_GITEE_REPOSITORY || config.giteeRepository;
if (!repository) {
  console.log('未配置 Gitee 仓库，跳过同步。');
  process.exit(0);
}
if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repository))
  throw new Error('Gitee 仓库配置无效');
const token = process.env.GITEE_TOKEN;
if (!token)
  throw new Error(
    '请在 GitHub Actions Secrets 中配置 GITEE_TOKEN；不要写入仓库或 APK。',
  );

const directory = path.resolve('artifacts/release');
const manifestBytes = await fs.readFile(
  path.join(directory, 'update-manifest.json'),
);
const manifest = JSON.parse(manifestBytes.toString('utf8'));
if (
  manifest.schema !== 1 ||
  !Number.isSafeInteger(manifest.versionCode) ||
  manifest.versionCode < 1 ||
  !/^[A-Za-z0-9_.-]+\.apk$/.test(manifest.file) ||
  !/^[a-f0-9]{64}$/.test(manifest.sha256)
)
  throw new Error('更新清单无效');
const apk = await fs.readFile(path.join(directory, manifest.file));
if (createHash('sha256').update(apk).digest('hex') !== manifest.sha256)
  throw new Error('APK 与清单摘要不一致，停止同步');
if (apk.length > 100 * 1024 * 1024)
  throw new Error('APK 超过 Gitee 单附件容量限制');
const tag = manifest.tag || manifest.versionName;
if (typeof tag !== 'string' || !/^[A-Za-z0-9_.-]+$/.test(tag))
  throw new Error('发行标签无效');
const api = `https://gitee.com/api/v5/repos/${repository}`;

async function request(url, options = {}) {
  const response = await fetch(url, {
    ...options,
    signal: AbortSignal.timeout(180000),
  });
  if (!response.ok) throw new Error(`Gitee 同步失败：HTTP ${response.status}`);
  return response;
}
async function write(url, method, data) {
  const response = await request(url, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...data, access_token: token }),
  });
  return response.json();
}

const repo = await (await request(api)).json();
if (repo.private !== false || !repo.default_branch)
  throw new Error('Gitee 仓库必须公开且包含默认分支');
const existingResponse = await fetch(`${api}/releases/tags/${tag}`, {
  signal: AbortSignal.timeout(30000),
});
if (!existingResponse.ok && existingResponse.status !== 404)
  throw new Error(`读取 Gitee 发行版失败：HTTP ${existingResponse.status}`);
const existing = existingResponse.ok ? await existingResponse.json() : null;
const githubRepository = config.projectRepository || config.repository;
const notes = await fs.readFile('config/release-notes.txt', 'utf8');
const details = {
  tag_name: tag,
  target_commitish: repo.default_branch,
  name: `TvT ${manifest.versionName}`,
  body: `${notes.trim()}\n\n[发行说明与源代码](https://github.com/${githubRepository}/releases/tag/${tag})`,
  prerelease: true,
};

// 同名稳定发行版只校验，避免覆盖已经被用户使用的安装包。
let release = existing;
if (!existing) release = await write(`${api}/releases`, 'POST', details);
else if (existing.prerelease)
  release = await write(`${api}/releases/${existing.id}`, 'PATCH', details);

if (!existing || existing.prerelease) {
  const present = new Set((release.assets || []).map((asset) => asset.name));
  for (const [name, bytes] of [
    [manifest.file, apk],
    ['update-manifest.json', manifestBytes],
  ]) {
    if (present.has(name)) continue;
    const data = new FormData();
    data.set('access_token', token);
    data.set(
      'file',
      new Blob([bytes], {
        type: name.endsWith('.apk')
          ? 'application/vnd.android.package-archive'
          : 'application/json',
      }),
      name,
    );
    await request(`${api}/releases/${release.id}/attach_files`, {
      method: 'POST',
      body: data,
    });
  }
}
release = await (await request(`${api}/releases/tags/${tag}`)).json();
for (const [name, bytes] of [
  [manifest.file, apk],
  ['update-manifest.json', manifestBytes],
]) {
  const asset = (release.assets || []).find((asset) => asset.name === name);
  if (!asset) throw new Error(`Gitee 未返回已上传附件：${name}`);
  const url = new URL(asset.browser_download_url);
  if (
    url.origin !== 'https://gitee.com' ||
    url.username ||
    url.password ||
    url.search ||
    url.hash ||
    url.pathname.includes('%') ||
    !url.pathname.startsWith(`/${repository}/releases/download/`)
  )
    throw new Error('Gitee 返回了非预期附件地址');
  const response = await request(url);
  const received = Buffer.from(await response.arrayBuffer());
  if (
    received.length !== bytes.length ||
    !createHash('sha256')
      .update(received)
      .digest()
      .equals(createHash('sha256').update(bytes).digest())
  )
    throw new Error(
      `Gitee 附件匿名下载校验失败：${name}；发行版保持未发布状态`,
    );
}
if (!existing || existing.prerelease)
  await write(`${api}/releases/${release.id}`, 'PATCH', { prerelease: false });
console.log(`Gitee 同步完成并通过匿名下载校验：${repository} ${tag}`);
