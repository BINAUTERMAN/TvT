import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chromium } from 'playwright';

test('重新检查版本和安装失败后同步下载状态，允许重新下载', async () => {
  const browser = await chromium.launch({
    executablePath:
      process.env.PLAYWRIGHT_EXECUTABLE_PATH ??
      'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    headless: true,
  });
  try {
    const page = await browser.newPage();
    await page.addInitScript(() => {
      window.Native = {
        request(id, action) {
          let result = {};
          let error;
          if (action === 'updateSettings')
            result = { configured: true, enabled: true };
          if (action === 'updateCheck')
            result = {
              configured: true,
              available: true,
              versionCode: 100009,
              version: '5.6',
            };
          if (action === 'updateStatus') result = { state: 'idle' };
          if (action === 'updateInstall') error = '安装包已失效，请重新下载';
          setTimeout(() => window.nativeResolve(id, result, error));
        },
      };
    });
    await page.goto('http://127.0.0.1:5173');
    const value = await page.evaluate(async () => {
      const { updates, checkUpdates, installUpdate } = await import(
        '/src/updates.ts'
      );
      while (updates.checking)
        await new Promise((resolve) => setTimeout(resolve, 10));
      updates.state = 'ready';
      await checkUpdates(true);
      const afterCheck = updates.state;
      updates.state = 'ready';
      await installUpdate();
      return { afterCheck, afterInstall: updates.state, error: updates.error };
    });
    assert.equal(value.afterCheck, 'idle');
    assert.equal(value.afterInstall, 'idle');
    assert.match(value.error, /重新下载/);
  } finally {
    await browser.close();
  }
});
