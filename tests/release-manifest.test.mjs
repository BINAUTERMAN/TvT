import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';

test('更新清单保留 5.5 版本号与独立的 5.5v 发行标签', async () => {
  const root = await mkdtemp(join(tmpdir(), 'tvt-release-'));
  try {
    await mkdir(join(root, 'android'));
    await writeFile(
      join(root, 'android/AndroidManifest.xml'),
      '<manifest android:versionCode="100007" android:versionName="5.5" />',
    );
    await writeFile(
      join(root, 'package.json'),
      JSON.stringify({ releaseTag: '5.5v' }),
    );
    const apk = Buffer.from('release manifest fixture; not an installable APK');
    await writeFile(join(root, 'fixture.apk'), apk);
    const result = spawnSync(
      process.execPath,
      [
        fileURLToPath(
          new URL('../scripts/release-manifest.mjs', import.meta.url),
        ),
        'fixture.apk',
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
    assert.equal(manifest.versionName, '5.5');
    assert.equal(manifest.tag, '5.5v');
    assert.equal(manifest.file, 'TvT-5.5.apk');
    assert.equal(
      manifest.sha256,
      createHash('sha256').update(apk).digest('hex'),
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
