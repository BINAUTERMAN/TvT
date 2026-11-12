import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const publisher = fileURLToPath(
  new URL('../scripts/publish-gitee-release.mjs', import.meta.url),
);
const mock = new URL('./fixtures/gitee-fetch.mjs', import.meta.url).href;
async function publish(mode, verify) {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'tvt-gitee-test-'));
  try {
    await fs.mkdir(path.join(directory, 'config'));
    await fs.mkdir(path.join(directory, 'artifacts/release'), {
      recursive: true,
    });
    await fs.writeFile(
      path.join(directory, 'config/update.json'),
      JSON.stringify({
        giteeRepository: 'example/TvT',
        projectRepository: 'example/TvT',
      }),
    );
    await fs.writeFile(
      path.join(directory, 'config/release-notes.txt'),
      '修复桌面歌词多项bug\n优化UI显示\n',
    );
    const bytes = Buffer.from('Protocol fixture only; not an installable APK');
    await fs.writeFile(
      path.join(directory, 'artifacts/release/TvT-5.4.apk'),
      bytes,
    );
    await fs.writeFile(
      path.join(directory, 'artifacts/release/update-manifest.json'),
      JSON.stringify({
        schema: 1,
        versionCode: 100006,
        versionName: '5.4',
        tag: '5.4',
        file: 'TvT-5.4.apk',
        sha256: createHash('sha256').update(bytes).digest('hex'),
      }),
    );
    const result = spawnSync(process.execPath, ['--import', mock, publisher], {
      cwd: directory,
      encoding: 'utf8',
      timeout: 15000,
      env: {
        ...process.env,
        TING_GITEE_REPOSITORY: '',
        GITEE_TOKEN: 'fixture-secret-not-real',
        GITEE_TEST_MODE: mode,
      },
    });
    assert.equal(result.error, undefined);
    assert.ok(
      await fs.stat(path.join(directory, 'trace.json')).catch(() => null),
      result.stderr,
    );
    assert.ok(
      !(result.stdout + result.stderr).includes('fixture-secret-not-real'),
    );
    const trace = JSON.parse(
      await fs.readFile(path.join(directory, 'trace.json'), 'utf8'),
    );
    await verify(result, trace);
  } finally {
    await fs.rm(directory, { recursive: true, force: true });
  }
}

test('Gitee 发布先保持预览，匿名校验两份附件后才转稳定版', async () => {
  await publish('new', (result, trace) => {
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(trace.writes, [
      'create-preview',
      'upload:TvT-5.4.apk',
      'upload:update-manifest.json',
      'publish-stable',
    ]);
    assert.equal(trace.released, true);
  });
});
test('Gitee 下载摘要不符时停止发布', async () => {
  await publish('new-corrupt', (result, trace) => {
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /匿名下载校验失败/);
    assert.equal(trace.released, false);
    assert.ok(!trace.writes.includes('publish-stable'));
  });
});
test('已发布的稳定版重跑只检查，不覆盖附件', async () => {
  await publish('stable', (result, trace) => {
    assert.equal(result.status, 0, result.stderr);
    assert.equal(trace.downloads.length, 2);
    assert.deepEqual(trace.writes, []);
  });
});
test('已有稳定版内容冲突时失败且不覆盖', async () => {
  await publish('stable-corrupt', (result, trace) => {
    assert.notEqual(result.status, 0);
    assert.deepEqual(trace.writes, []);
  });
});
