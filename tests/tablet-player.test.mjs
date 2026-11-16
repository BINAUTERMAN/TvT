import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chromium } from 'playwright';

const browserPath =
  process.env.PLAYWRIGHT_EXECUTABLE_PATH ??
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
async function openPlayer(page) {
  await page.addInitScript(() => {
    const song = {
      id: 'tablet-player',
      hash: 'a'.repeat(32),
      title:
        '平板播放界面布局测试 · 这是一首名字很长的歌曲，用来检查完整屏幕内的控制按钮',
      artist: '测试歌手甲 / 测试歌手乙 / 测试歌手丙 / 测试歌手丁',
      cover: '',
      album: '',
      albumId: '',
      audioId: '',
      duration: 240,
    };
    const state = {
      queue: [song],
      index: 0,
      position: 12,
      duration: 240,
      playing: false,
      loading: false,
      mode: 0,
      sleepAt: 0,
    };
    window.Native = {
      request(id, action) {
        let value = true;
        if (action === 'account') value = { loggedIn: false };
        if (action === 'catalog') value = { data: [] };
        if (action === 'settings') value = {};
        if (action === 'favorites' || action === 'cacheList') value = [];
        if (action === 'lyricDocument')
          value = {
            translationAvailable: false,
            lines: Array.from({ length: 40 }, (_, index) => ({
              time: index * 3,
              text: `第 ${index + 1} 行歌词，检查歌词区域内部滚动`,
            })),
          };
        if (action === 'updateSettings' || action === 'updateCheck')
          value = { configured: false, enabled: false };
        if (action === 'updateStatus') value = { state: 'idle' };
        if (action === 'state') setTimeout(() => window.nativePlayback(state));
        setTimeout(() => window.nativeResolve(id, value));
      },
    };
  });
  await page.goto('http://127.0.0.1:5173');
  await page.locator('.mini-info').click();
  await page.locator('.player-screen').waitFor();
  await page.waitForFunction(
    () => !document.querySelector('.player-enter-active'),
  );
}

test('平板竖屏单列、横屏双列，播放页铺满且仅歌词内部滚动（原生桥替身）', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    for (const [width, height] of [
      [712, 1096],
      [800, 1280],
      [1096, 712],
      [1280, 800],
      [900, 480],
      [700, 700],
    ]) {
      const page = await browser.newPage({ viewport: { width, height } });
      await openPlayer(page);
      await page.addStyleTag({ content: ':root { font-size: 21px; }' });
      const screen = page.locator('.player-screen');
      const geometry = await screen.evaluate((element) => {
        const box = element.getBoundingClientRect(),
          controls = element
            .querySelector('.player-controls')
            .getBoundingClientRect();
        const cover = element
          .querySelector('.cover-stage')
          .getBoundingClientRect();
        const tools = element
          .querySelector('.player-tools')
          .getBoundingClientRect();
        return {
          top: box.top,
          left: box.left,
          width: box.width,
          height: box.height,
          scroll: element.scrollHeight,
          client: element.clientHeight,
          horizontalOverflow: element.scrollWidth > element.clientWidth,
          coverBottom: cover.bottom,
          coverRight: cover.right,
          controlsTop: controls.top,
          controlsLeft: controls.left,
          toolsBottom: tools.bottom,
        };
      });
      assert.equal(geometry.height, height);
      assert.equal(geometry.width, width);
      assert.ok(
        geometry.scroll <= geometry.client + 1,
        JSON.stringify({ width, height, ...geometry }),
      );
      assert.equal(geometry.horizontalOverflow, false);
      assert.ok(geometry.toolsBottom <= height, JSON.stringify(geometry));
      if (height >= width)
        assert.ok(
          geometry.controlsTop >= geometry.coverBottom,
          JSON.stringify(geometry),
        );
      else
        assert.ok(
          geometry.controlsLeft >= geometry.coverRight,
          JSON.stringify(geometry),
        );
      await page.screenshot({
        path: `artifacts/tablet-player-${width}x${height}.png`,
      });
      await screen.getByRole('button', { name: '歌词', exact: true }).click();
      await screen.locator('.lyric-line').last().waitFor();
      await page.waitForFunction(
        () =>
          !document.querySelector(
            '.content-enter-active, .content-leave-active',
          ),
      );
      assert.ok(
        await screen.evaluate(
          (element) => element.scrollHeight <= element.clientHeight + 1,
        ),
      );
      const lyrics = screen.locator('.lyrics');
      assert.ok(
        await lyrics.evaluate(
          (element) => element.scrollHeight > element.clientHeight,
        ),
      );
      await lyrics.evaluate((element) => {
        element.scrollTop = 300;
      });
      assert.ok(await lyrics.evaluate((element) => element.scrollTop > 0));
      assert.equal(await screen.evaluate((element) => element.scrollTop), 0);
      await page.screenshot({
        path: `artifacts/tablet-lyrics-${width}x${height}.png`,
      });
      await page.close();
    }
  } finally {
    await browser.close();
  }
});
