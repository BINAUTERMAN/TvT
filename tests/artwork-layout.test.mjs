import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chromium } from 'playwright';

const browserPath =
  process.env.PLAYWRIGHT_EXECUTABLE_PATH ??
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';

test('主页加载横图、竖图和大图后封面保持等宽正方形（原生桥替身）', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    for (const viewport of [
      { width: 360, height: 800 },
      { width: 390, height: 844 },
      { width: 768, height: 1024 },
      { width: 1280, height: 800 },
    ]) {
      const page = await browser.newPage({ viewport });
      await page.route('https://covers.test/**', (route) => {
        const index = Number(new URL(route.request().url()).pathname.slice(1));
        const [width, height] = [
          [2400, 400],
          [400, 2400],
          [1600, 1600],
        ][index % 3];
        return route.fulfill({
          contentType: 'image/svg+xml',
          body: `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}"><rect width="100%" height="100%" fill="teal"/></svg>`,
        });
      });
      await page.addInitScript(() => {
        const songs = Array.from({ length: 12 }, (_, index) => ({
          id: String(index),
          title: `测试歌曲 ${index}`,
          artist: '测试歌手',
          hash: String(index).padStart(32, 'a'),
          cover: `https://covers.test/${index}`,
          duration: 200,
        }));
        window.Native = {
          request(id, action, source) {
            const payload = JSON.parse(source);
            let result = true;
            if (action === 'account')
              result = { loggedIn: true, name: '封面测试', avatar: '' };
            if (action === 'settings') result = { autoVip: false };
            if (action === 'catalog')
              result = ['recommend', 'daily'].includes(payload.route)
                ? { data: songs }
                : { data: [] };
            if (action === 'favorites') result = [];
            if (action === 'cacheList')
              result = { tracks: [], jobs: [], bytes: 0 };
            if (action === 'starStatus') result = { eligible: false };
            if (action === 'updateSettings' || action === 'updateCheck')
              result = { configured: false, enabled: false };
            if (action === 'updateStatus') result = { state: 'idle' };
            if (action === 'notificationStatus')
              result = {
                enabled: true,
                permission: true,
                needsAttention: false,
              };
            setTimeout(() => window.nativeResolve(id, result), 20);
          },
        };
      });
      await page.goto('http://127.0.0.1:5173');
      await page.locator('.recommend-grid .album-item').nth(11).waitFor();
      await page.locator('.recommend-grid img').evaluateAll((images) =>
        Promise.all(
          images.map((image) => {
            image.loading = 'eager';
            return image.decode();
          }),
        ),
      );
      await page.waitForFunction(
        () =>
          !document.querySelector(
            '[class*="-enter-active"], [class*="-leave-active"]',
          ),
      );
      const sizes = await page.locator('.recommend-grid').evaluate((grid) => ({
        width: grid.getBoundingClientRect().width,
        covers: [...grid.querySelectorAll('.artwork')].map((cover) => {
          const bounds = cover.getBoundingClientRect();
          return { width: bounds.width, height: bounds.height };
        }),
        overflow:
          document.querySelector('main').scrollWidth -
          document.querySelector('main').clientWidth,
      }));
      const columns = viewport.width < 700 ? 3 : viewport.width <= 1050 ? 4 : 6;
      assert.ok(
        sizes.covers.every((cover) => cover.width <= sizes.width / columns + 1),
        JSON.stringify(sizes),
      );
      assert.ok(
        sizes.covers.every((cover) => Math.abs(cover.width - cover.height) < 1),
        JSON.stringify(sizes),
      );
      assert.ok(
        sizes.covers.every(
          (cover) => Math.abs(cover.width - sizes.covers[0].width) < 1,
        ),
      );
      assert.ok(sizes.overflow <= 1, `主页横向溢出 ${sizes.overflow}px`);
      await page.screenshot({
        path: `artifacts/home-loaded-covers-${viewport.width}.png`,
      });
      await page.close();
    }
  } finally {
    await browser.close();
  }
});
