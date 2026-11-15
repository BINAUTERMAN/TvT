import https from 'node:https';
import http from 'node:http';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { randomBytes } from 'node:crypto';
import assert from 'node:assert/strict';
import { openStore, createSurveyServer } from '../server/survey/server.mjs';

const root = await mkdtemp(join(tmpdir(), 'tvt-native-'));
const store = openStore(join(root, 'answers.sqlite'), randomBytes(32));
const backend = createSurveyServer({
  store,
  publicOrigin: 'https://localhost',
  staticDir: root,
});
await new Promise((done) => backend.listen(0, '127.0.0.1', done));
const pfx = await readFile('build/test/survey-native/localhost.p12');
const tls = https.createServer(
  { pfx, passphrase: 'tvt-test-only' },
  (request, response) => {
    if (request.url !== '/api/feedback') {
      request.resume();
      if (request.url === '/denied') {
        response.writeHead(403);
        response.end();
      }
      if (request.url === '/limited') {
        response.writeHead(429);
        response.end();
      }
      if (request.url === '/challenge')
        response.end('<html>Cloudflare verification</html>');
      if (request.url === '/redirect') {
        response.writeHead(302, { Location: '/api/feedback' });
        response.end();
      }
      if (request.url === '/wrong-receipt')
        response.end(JSON.stringify({ accepted: true, receipt: 'invalid' }));
      if (request.url === '/large') response.end('x'.repeat(5000));
      return;
    }
    const upstream = http.request(
      {
        host: '127.0.0.1',
        port: backend.address().port,
        path: request.url,
        method: request.method,
        headers: {
          ...request.headers,
          host: 'localhost',
          'cf-connecting-ip': '192.0.2.1',
        },
      },
      (result) => {
        response.writeHead(result.statusCode, result.headers);
        result.pipe(response);
      },
    );
    request.pipe(upstream);
  },
);
await new Promise((done) => tls.listen(0, '127.0.0.1', done));
try {
  const androidJar = join(
    process.env.LOCALAPPDATA,
    'Android/Sdk/platforms/android-34/android.jar',
  );
  const result = await promisify(execFile)(
    process.env.TVT_TEST_JAVA,
    [
      '-Djavax.net.ssl.trustStore=' +
        resolve('build/test/survey-native/localhost.p12'),
      '-Djavax.net.ssl.trustStorePassword=tvt-test-only',
      '-cp',
      ['build/test/survey-native', 'build/test/json.jar', androidJar].join(';'),
      'cn.ting.music.SurveyHttpCheck',
      `https://127.0.0.1:${tls.address().port}`,
    ],
    { timeout: 45000 },
  );
  process.stdout.write(result.stdout);
  assert.equal([...store.rows()].length, 1);
  assert.equal([...store.rows()][0].optimization, '原生 HTTPS 本地联调');
  process.stdout.write(
    '真实服务加密保存与幂等回执验证通过（本地 HTTPS，非公网或真机提交）\n',
  );
} finally {
  await new Promise((done) => tls.close(done));
  await new Promise((done) => {
    backend.close(done);
    backend.closeAllConnections();
  });
  await rm(root, { recursive: true, force: true });
}
