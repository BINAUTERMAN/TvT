import fs from 'node:fs';
const code = Number(process.env.TING_VERSION_CODE);
if (!Number.isSafeInteger(code) || code < 100 || code > 2100000000)
  throw new Error('安装序号无效');
const version = JSON.parse(fs.readFileSync('package.json', 'utf8')).version.replace(/\.0$/, '');
const filename = 'android/AndroidManifest.xml';
fs.writeFileSync(
  filename,
  fs
    .readFileSync(filename, 'utf8')
    .replace(/android:versionCode="\d+"/, `android:versionCode="${code}"`)
    .replace(/android:versionName="[^"]+"/, `android:versionName="${version}"`),
);
