import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';

const manifest = fs.readFileSync('android/AndroidManifest.xml', 'utf8');
const versionCode = Number(manifest.match(/android:versionCode="(\d+)"/)?.[1]);
const versionName = manifest.match(/android:versionName="([^"]+)"/)?.[1];
const { releaseTag } = JSON.parse(fs.readFileSync('package.json', 'utf8'));
const tag = releaseTag || versionName;
if (typeof tag !== 'string' || !/^[A-Za-z0-9_.-]+$/.test(tag))
  throw new Error('发行标签无效');
if (
  !versionCode ||
  !/^\d+(?:\.\d+){1,3}(?:-[A-Za-z0-9.-]+)?$/.test(versionName ?? '')
)
  throw new Error('版本信息无效');
const original = process.argv[2];
if (!original || !fs.existsSync(original) || !fs.statSync(original).isFile())
  throw new Error('请提供已签名的 APK');
const artifactRoot = fs.realpathSync('artifacts');
const source = fs.realpathSync(original);
const inside = path.relative(artifactRoot, source);
if (
  inside === '..' ||
  inside.startsWith('..' + path.sep) ||
  path.isAbsolute(inside) ||
  ![`TvT-${versionName}-preview.apk`, `TvT-${versionName}.apk`].includes(
    path.basename(source),
  )
)
  throw new Error('发行包必须来自 artifacts 内的当前版本构建产物');
const header = Buffer.alloc(4);
const descriptor = fs.openSync(source, 'r');
try {
  fs.readSync(descriptor, header, 0, 4, 0);
} finally {
  fs.closeSync(descriptor);
}
if (!header.equals(Buffer.from([0x50, 0x4b, 0x03, 0x04])))
  throw new Error('发行包不是 APK/ZIP 格式');
fs.mkdirSync('artifacts/release', { recursive: true });
const file = `TvT-${versionName}.apk`;
const destination = path.join('artifacts/release', file);
if (source !== path.resolve(destination)) fs.copyFileSync(source, destination);
const sha256 = createHash('sha256')
  .update(fs.readFileSync(destination))
  .digest('hex');
fs.writeFileSync(
  'artifacts/release/update-manifest.json',
  JSON.stringify(
    {
      schema: 1,
      versionCode,
      versionName,
      tag,
      file,
      sha256,
      rollback: process.env.TING_ROLLBACK === 'true',
    },
    null,
    2,
  ) + '\n',
);
console.log(`发行包与更新清单已生成：${file}`);
