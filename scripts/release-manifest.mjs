import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';

const manifest = fs.readFileSync('android/AndroidManifest.xml', 'utf8');
const versionCode = Number(manifest.match(/android:versionCode="(\d+)"/)?.[1]);
const versionName = manifest.match(/android:versionName="([^"]+)"/)?.[1];
if (!versionCode || !/^\d+(?:\.\d+){1,3}(?:-[A-Za-z0-9.-]+)?$/.test(versionName ?? ''))
  throw new Error('版本信息无效');
const original = process.argv[2];
if (!original || !fs.existsSync(original))
  throw new Error('请提供已签名的 APK');
fs.mkdirSync('artifacts/release', { recursive: true });
const file = `TvT-${versionName}.apk`;
const destination = path.join('artifacts/release', file);
fs.copyFileSync(original, destination);
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
      tag: versionName,
      file,
      sha256,
      rollback: process.env.TING_ROLLBACK === 'true',
    },
    null,
    2,
  ) + '\n',
);
console.log(`发行包与更新清单已生成：${file}`);
