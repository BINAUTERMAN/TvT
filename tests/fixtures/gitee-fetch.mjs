import fs from 'node:fs';
import { createHash } from 'node:crypto';

// 仅供发布脚本协议测试，所有请求都在进程内截获。
const mode = process.env.GITEE_TEST_MODE;
const directory = 'artifacts/release/';
const manifest = JSON.parse(
  fs.readFileSync(directory + 'update-manifest.json'),
);
const files = new Map();
const trace = { writes: [], downloads: [], released: false };
const base = 'https://gitee.com/api/v5/repos/example/TvT';
let release = null;
function attach(name, bytes) {
  files.set(name, bytes);
  release.assets.push({
    name,
    browser_download_url: `https://gitee.com/example/TvT/releases/download/5.4/${name}`,
  });
}
if (mode.startsWith('stable')) {
  release = { id: 7, tag_name: '5.4', prerelease: false, assets: [] };
  attach(manifest.file, fs.readFileSync(directory + manifest.file));
  attach(
    'update-manifest.json',
    fs.readFileSync(directory + 'update-manifest.json'),
  );
}
if (mode === 'preview') {
  release = { id: 7, tag_name: '5.4', prerelease: true, assets: [] };
  attach(manifest.file, fs.readFileSync(directory + manifest.file));
}
process.on('exit', () => fs.writeFileSync('trace.json', JSON.stringify(trace)));
const json = (data, status = 200) =>
  new Response(JSON.stringify(data), { status });
globalThis.fetch = async (address, options = {}) => {
  const url = String(address);
  const method = options.method || 'GET';
  if (method === 'GET' && url === base)
    return json({ private: false, default_branch: 'main' });
  if (method === 'GET' && url === base + '/releases/tags/5.4')
    return release ? json(release) : json({}, 404);
  if (method === 'POST' && url === base + '/releases') {
    const data = JSON.parse(options.body);
    if (
      data.access_token !== process.env.GITEE_TOKEN ||
      data.prerelease !== true
    )
      throw new Error('创建时必须携带令牌并保持预览状态');
    trace.writes.push('create-preview');
    release = { id: 7, ...data, assets: [] };
    delete release.access_token;
    return json(release);
  }
  if (method === 'POST' && url === base + '/releases/7/attach_files') {
    if (options.body.get('access_token') !== process.env.GITEE_TOKEN)
      throw new Error('附件缺少认证');
    const file = options.body.get('file');
    trace.writes.push('upload:' + file.name);
    attach(file.name, Buffer.from(await file.arrayBuffer()));
    return json({});
  }
  if (method === 'PATCH' && url === base + '/releases/7') {
    const data = JSON.parse(options.body);
    if (!data.tag_name || !data.name || !data.body)
      return json({ message: 'tag_name, name and body are required' }, 400);
    if (data.prerelease === true) {
      trace.writes.push('update-preview');
      release = { ...release, ...data };
      return json(release);
    }
    if (data.prerelease !== false || trace.downloads.length !== 2)
      throw new Error('匿名校验完成前不得发布');
    trace.writes.push('publish-stable');
    trace.released = true;
    release = { ...release, prerelease: false };
    return json(release);
  }
  if (
    method === 'GET' &&
    url.startsWith('https://gitee.com/example/TvT/releases/download/5.4/')
  ) {
    if (options.headers || options.body) throw new Error('下载校验必须匿名');
    const name = url.split('/').at(-1);
    let bytes = files.get(name);
    if (mode.endsWith('corrupt')) bytes = Buffer.from('wrong bytes');
    trace.downloads.push({
      name,
      sha256: createHash('sha256').update(bytes).digest('hex'),
    });
    return new Response(bytes);
  }
  throw new Error('测试遇到非预期请求：' + method + ' ' + url);
};
