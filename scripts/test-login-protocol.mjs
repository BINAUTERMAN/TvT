import {
  createCipheriv,
  createHash,
  publicEncrypt,
  constants,
} from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
const publicKey =
  'MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDECi0Np2UR87scwrvTr72L6oO01rBbbBPriSDFPxr3Z5syug0O24QyQO8bg27+0+4kBzTBTBOZ/WWU0WryL1JSXRTXLgFVxtzIY41Pe7lPOgsfTCn5kZcvKhYKJesKnnJDNr5/abvTGf+rHG3YRwsCHcQ08/q6ifSioBszvb3QiwIDAQAB';
const sessionKey = 'testsession12345';
const plain = JSON.stringify({
  mobile: '13800000000',
  code: '123456',
  test: '仅测试',
});
const digest = createHash('md5').update(sessionKey).digest('hex');
function encrypt(value, key, iv) {
  const cipher = createCipheriv(
    'aes-256-cbc',
    Buffer.from(key),
    Buffer.from(iv),
  );
  return Buffer.concat([cipher.update(value, 'utf8'), cipher.final()]).toString(
    'hex',
  );
}
const rsaPlain = JSON.stringify({ clienttime_ms: 1234567890, key: sessionKey });
const padded = Buffer.alloc(128);
padded.write(rsaPlain, 'utf8');
const fixture = {
  plain,
  sessionKey,
  rsaPlain,
  aes: encrypt(plain, digest, digest.substring(16)),
  rsa: publicEncrypt(
    {
      key: `-----BEGIN PUBLIC KEY-----\n${publicKey}\n-----END PUBLIC KEY-----`,
      padding: constants.RSA_NO_PADDING,
    },
    padded,
  )
    .toString('hex')
    .toUpperCase(),
  t1: encrypt(
    '|1234567890',
    '5e4ef500e9597fe004bd09a46d8add98',
    '04bd09a46d8add98',
  ),
};
fs.mkdirSync('build/test', { recursive: true });
fs.writeFileSync('build/test/login-protocol.json', JSON.stringify(fixture));
const java = process.env.JAVA_HOME
  ? path.join(process.env.JAVA_HOME, 'bin')
  : path.join(process.env.USERPROFILE, '.jdks/jbr-17.0.14/bin');
const androidJar = path.join(
  process.env.ANDROID_HOME ?? path.join(process.env.LOCALAPPDATA, 'Android/Sdk'),
  'platforms/android-34/android.jar',
);
if (!fs.existsSync('build/test/json.jar')) {
  const response = await fetch(
    'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',
  );
  if (!response.ok) throw new Error('原生协议测试依赖下载失败');
  fs.writeFileSync('build/test/json.jar', Buffer.from(await response.arrayBuffer()));
}
const cp = `build/test/json.jar;${androidJar}`;
execFileSync(
  path.join(java, 'javac.exe'),
  [
    '-encoding',
    'UTF-8',
    '-cp',
    cp,
    '-d',
    'build/test',
    'android/src/cn/ting/music/KugouApi.java',
    'android/src/cn/ting/music/AccountStore.java',
    'android/src/cn/ting/music/LoginProtocol.java',
    'android/src/cn/ting/music/CatalogApi.java',
    'android/src/cn/ting/music/FavoritesRepository.java',
    'android/src/cn/ting/music/QualityCatalog.java',
    'android/src/cn/ting/music/LyricsDocument.java',
    'android/src/cn/ting/music/VipRepository.java',
    'android/src/cn/ting/music/CheckinRepository.java',
    'android/src/cn/ting/music/LoginChoices.java',
    'tests/LoginProtocolCheck.java',
  ],
  { stdio: 'inherit' },
);
execFileSync(
  path.join(java, 'java.exe'),
  [
    '-cp',
    `build/test;${cp}`,
    'cn.ting.music.LoginProtocolCheck',
    'build/test/login-protocol.json',
  ],
  { stdio: 'inherit' },
);
