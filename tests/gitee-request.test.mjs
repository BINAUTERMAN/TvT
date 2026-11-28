import { test } from 'node:test';
import assert from 'node:assert/strict';
import { giteeRequest, releaseId } from '../scripts/gitee-request.mjs';

test('Gitee 匿名附件允许官方 CDN，拒绝外部域名和降级跳转', async () => {
  let calls = 0;
  const response = await giteeRequest(
    'https://gitee.com/attachments/download/1/app.apk',
    {},
    async (url, options) => {
      assert.equal(options.redirect, 'manual');
      calls++;
      return calls === 1
        ? new Response(null, {
            status: 302,
            headers: {
              location: 'https://foruda.gitee.com/file/app.apk?signature=test',
            },
          })
        : new Response('fixture');
    },
  );
  assert.equal(await response.text(), 'fixture');
  assert.equal(calls, 2);
  for (const location of [
    'http://gitee.com/file',
    'https://gitee.com.attacker.example/file',
    'https://127.0.0.1/file',
    'https://attacker.example/file',
  ]) {
    let attempts = 0;
    await assert.rejects(
      giteeRequest('https://gitee.com/file', {}, async () => {
        attempts++;
        return new Response(null, { status: 302, headers: { location } });
      }),
      /不受信任/,
    );
    assert.equal(attempts, 1);
  }
});

test('Gitee 含令牌请求不跟随重定向，发行编号不能注入路径', async () => {
  for (const options of [
    { method: 'POST', body: 'test-token-only' },
    { headers: { Authorization: 'Bearer test-only' } },
  ]) {
    let calls = 0;
    await assert.rejects(
      giteeRequest(
        'https://gitee.com/api/v5/repos/test/repo/releases',
        options,
        async () => {
          calls++;
          return new Response(null, {
            status: 307,
            headers: { location: 'https://foruda.gitee.com/file' },
          });
        },
      ),
      /重定向/,
    );
    assert.equal(calls, 1);
  }
  assert.equal(releaseId(123), 123);
  for (const value of [
    '../other',
    '123',
    0,
    -1,
    NaN,
    Number.MAX_SAFE_INTEGER + 1,
  ])
    assert.throws(() => releaseId(value), /无效/);
});
