import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  mkdtemp,
  mkdir,
  writeFile,
  readFile,
  rm,
  symlink,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';

test('更新清单保留 5.10 版本号与独立的 5.10v 发行标签', async () => {
  const root = await mkdtemp(join(tmpdir(), 'tvt-release-'));
  try {
    await mkdir(join(root, 'android'));
    await mkdir(join(root, 'artifacts'));
    await writeFile(
      join(root, 'android/AndroidManifest.xml'),
      '<manifest android:versionCode="100013" android:versionName="5.10" />',
    );
    await writeFile(
      join(root, 'package.json'),
      JSON.stringify({ releaseTag: '5.10v' }),
    );
    const apk = Buffer.concat([
      Buffer.from([0x50, 0x4b, 0x03, 0x04]),
      Buffer.from('manifest fixture; not an installable APK'),
    ]);
    await writeFile(join(root, 'artifacts/TvT-5.10-preview.apk'), apk);
    const result = spawnSync(
      process.execPath,
      [
        fileURLToPath(
          new URL('../scripts/release-manifest.mjs', import.meta.url),
        ),
        'artifacts/TvT-5.10-preview.apk',
      ],
      { cwd: root, encoding: 'utf8' },
    );
    assert.equal(result.status, 0, result.stderr);
    const manifest = JSON.parse(
      await readFile(
        join(root, 'artifacts/release/update-manifest.json'),
        'utf8',
      ),
    );
    assert.equal(manifest.versionName, '5.10');
    assert.equal(manifest.tag, '5.10v');
    assert.equal(manifest.file, 'TvT-5.10.apk');
    assert.equal(
      manifest.sha256,
      createHash('sha256').update(apk).digest('hex'),
    );
    const run = (source) =>
      spawnSync(
        process.execPath,
        [
          fileURLToPath(
            new URL('../scripts/release-manifest.mjs', import.meta.url),
          ),
          source,
        ],
        { cwd: root, encoding: 'utf8' },
      );
    await mkdir(join(root, 'outside'));
    await writeFile(join(root, 'outside/TvT-5.10-preview.apk'), apk);
    assert.notEqual(run('outside/TvT-5.10-preview.apk').status, 0);
    await symlink(
      join(root, 'outside'),
      join(root, 'artifacts/redirect'),
      'junction',
    );
    assert.notEqual(run('artifacts/redirect/TvT-5.10-preview.apk').status, 0);
    await writeFile(
      join(root, 'artifacts/TvT-5.10-preview.apk'),
      'private fixture, not APK',
    );
    assert.notEqual(run('artifacts/TvT-5.10-preview.apk').status, 0);
    assert.deepEqual(
      await readFile(join(root, 'artifacts/release/TvT-5.10.apk')),
      apk,
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
