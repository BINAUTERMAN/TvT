import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomBytes, randomUUID } from 'node:crypto';
import http from 'node:http';
import {
  createSurveyServer,
  openStore,
  validate,
} from '../server/survey/server.mjs';

const answers = () => ({
  submissionId: randomUUID(),
  rating: 4,
  optimization: '减少误触',
  favorites: ['layout', 'other'],
  favoriteOther: '声音很干净',
  improvements: '希望越来越好',
  email: 'survey-test@example.com',
  displayId: '验收用户',
  consent: true,
});

test('公网 IP 直连模式只信任本机 Nginx 覆盖的地址，并关闭表单和管理入口', async () => {
  const root = await mkdtemp(join(tmpdir(), 'tvt-direct-'));
  const store = openStore(join(root, 'test.sqlite'), randomBytes(32));
  const server = createSurveyServer({
    store,
    publicOrigin: 'https://203.0.113.10',
    staticDir: root,
    proxyMode: 'direct',
    serveForm: false,
  });
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  const target = `http://127.0.0.1:${server.address().port}`;
  const send = (headers = {}) =>
    request(target + '/api/feedback', {
      method: 'POST',
      headers: {
        Host: '203.0.113.10',
        'Content-Type': 'application/json',
        'X-Real-IP': '192.0.2.1',
        ...headers,
      },
      body: JSON.stringify(answers()),
    });
  try {
    assert.equal((await send()).status, 201);
    assert.equal(
      (await send({ 'X-Real-IP': '', 'CF-Connecting-IP': '192.0.2.2' })).status,
      403,
    );
    assert.equal(
      (await send({ 'X-Real-IP': '192.0.2.1, 192.0.2.2' })).status,
      403,
    );
    for (let i = 1; i < 10; i++)
      assert.equal(
        (
          await send({
            'CF-Connecting-IP': `198.51.100.${i}`,
            'X-Forwarded-For': `198.51.100.${i}`,
          })
        ).status,
        201,
      );
    assert.equal(
      (await send({ 'CF-Connecting-IP': '198.51.100.99' })).status,
      429,
    );
    assert.equal((await send({ 'X-Real-IP': '192.0.2.3' })).status, 201);
    for (const path of [
      '/',
      '/survey.html',
      '/admin',
      '/api/export',
      '/feedback.sqlite',
    ])
      assert.equal(
        (await request(target + path, { headers: { Host: '203.0.113.10' } }))
          .status,
        404,
      );
  } finally {
    await new Promise((done) => {
      server.close(done);
      server.closeAllConnections();
    });
    await rm(root, { recursive: true, force: true });
  }
});

function request(url, options = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request(url, options, (res) => {
      const chunks = [];
      res.on('data', (chunk) => chunks.push(chunk));
      res.on('end', () =>
        resolve({
          status: res.statusCode,
          headers: {
            get: (key) => res.headers[key],
            has: (key) => key in res.headers,
          },
          json: async () => JSON.parse(Buffer.concat(chunks).toString('utf8')),
        }),
      );
    });
    req.on('error', reject);
    req.end(options.body);
  });
}

test('问卷真实 HTTP 接口：校验、加密、幂等、清理、限流与跨站隔离', async () => {
  const root = await mkdtemp(join(tmpdir(), 'tvt-survey-'));
  const key = randomBytes(32);
  const path = join(root, 'test.sqlite');
  const store = openStore(path, key);
  await writeFile(
    join(root, 'survey.html'),
    '<!doctype html><title>TvT</title>',
  );
  const server = createSurveyServer({
    store,
    publicOrigin: 'https://survey.example.com',
    staticDir: root,
  });
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  const target = `http://127.0.0.1:${server.address().port}`;
  let ipIndex = 1;
  const post = (body, headers = {}) =>
    request(target + '/api/feedback', {
      method: 'POST',
      headers: {
        Host: 'survey.example.com',
        'CF-Connecting-IP': `192.0.2.${ipIndex++}`,
        'Content-Type': 'application/json',
        ...headers,
      },
      body: JSON.stringify(body),
    });
  try {
    const value = answers();
    let response = await post(value);
    assert.equal(response.status, 201);
    assert.deepEqual(await response.json(), {
      accepted: true,
      receipt: value.submissionId,
    });
    assert.equal((await post(value)).status, 200);
    assert.equal((await post({ ...value, rating: 3 })).status, 409);
    assert.equal(
      store.db.prepare('SELECT COUNT(*) AS count FROM feedback').get().count,
      1,
    );
    assert.deepEqual([...store.rows()][0], {
      receivedAt: [...store.rows()][0].receivedAt,
      ...validate(value),
    });
    const raw = (await readFile(path)).toString('utf8');
    for (const privateValue of [
      value.email,
      value.displayId,
      value.optimization,
    ])
      assert.ok(!raw.includes(privateValue));
    assert.throws(() => openStore(path, randomBytes(32)), /密钥不匹配/);
    for (const body of [
      { ...answers(), rating: 0 },
      { ...answers(), consent: false },
      { ...answers(), improvements: '😀'.repeat(101) },
      { ...answers(), token: 'must-not-accept' },
      { ...answers(), email: 'invalid' },
      { ...answers(), favorites: ['layout', 'layout'] },
    ])
      assert.equal((await post(body)).status, 400);
    assert.equal(
      (await post({ ...answers(), improvements: '😀'.repeat(100) })).status,
      201,
    );
    assert.equal(
      (await post(answers(), { Origin: 'https://attacker.example' })).status,
      403,
    );
    assert.equal(
      (await post(answers(), { Host: 'attacker.example' })).status,
      403,
    );
    assert.equal(
      (await post(answers(), { 'CF-Connecting-IP': '' })).status,
      403,
    );
    assert.equal(
      (await post(answers(), { 'Content-Type': 'text/plain' })).status,
      415,
    );
    assert.equal(
      (await post({ ...answers(), improvements: 'x'.repeat(9000) })).status,
      413,
    );
    for (let i = 0; i < 10; i++)
      assert.equal(
        (await post(answers(), { 'CF-Connecting-IP': '198.51.100.1' })).status,
        201,
      );
    response = await post(answers(), { 'CF-Connecting-IP': '198.51.100.1' });
    assert.equal(response.status, 429);
    assert.ok(response.headers.has('retry-after'));
    response = await request(target + '/', {
      headers: { Host: 'survey.example.com' },
    });
    assert.equal(response.status, 200);
    assert.ok(
      response.headers
        .get('content-security-policy')
        .includes("frame-ancestors 'none'"),
    );
    assert.equal(
      (
        await request(target + '/api/feedback', {
          headers: { Host: 'survey.example.com' },
        })
      ).status,
      405,
    );
    for (const route of [
      '/feedback.sqlite',
      '/.env',
      '/admin',
      '/api/export',
      '/assets/../server.mjs',
    ])
      assert.equal(
        (
          await request(target + route, {
            headers: { Host: 'survey.example.com' },
          })
        ).status,
        404,
      );
    store.db
      .prepare('UPDATE feedback SET created = ?')
      .run(Date.now() - 181 * 86400000);
    store.purge();
    assert.equal([...store.rows()].length, 0);
    assert.equal(
      validate({ ...answers(), rating: 5, favorites: [] }).optimization,
      '',
    );
    assert.equal(
      validate({ ...answers(), rating: 5, favorites: [] }).favoriteOther,
      '',
    );
  } finally {
    await new Promise((done) => {
      server.close(done);
      server.closeAllConnections();
    });
    await rm(root, { recursive: true, force: true });
  }
});
