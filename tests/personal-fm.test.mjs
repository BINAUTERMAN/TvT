import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import fs from 'node:fs/promises';

const browserPath =
  process.env.PLAYWRIGHT_EXECUTABLE_PATH ??
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';

test('随心听声波按播放启停，真实能量优先，减少动态效果时静止（原生桥替身）', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
    });
    await setup(page);
    await page.evaluate(() => {
      Object.assign(window.fmState, {
        personalFm: true,
        playing: true,
        queue: [
          {
            id: 'wave',
            hash: 'a'.repeat(32),
            title: '声波测试',
            artist: '测试歌手',
            cover: '',
            album: '',
            albumId: '',
            audioId: '1',
            duration: 240,
          },
        ],
      });
      window.nativePlayback(window.fmState);
    });
    await page.locator('.fm-equalizer-playing').waitFor();
    assert.equal(await page.locator('.fm-equalizer i').count(), 5);
    await page.evaluate(() =>
      window.dispatchEvent(
        new CustomEvent('ting:fm-levels', {
          detail: [0.2, 0.4, 0.8, 0.6, 0.3],
        }),
      ),
    );
    await page.locator('.fm-equalizer-live').waitFor();
    assert.equal(
      await page
        .locator('.fm-equalizer i')
        .nth(2)
        .evaluate((element) => element.style.getPropertyValue('--fm-level')),
      '0.8',
    );
    await page.evaluate(() => window.nativePlayback({ playing: false }));
    await page.waitForTimeout(300);
    assert.equal(
      await page.locator('.fm-equalizer-playing, .fm-equalizer-live').count(),
      0,
    );
    await page.evaluate(() => window.nativePlayback({ playing: true }));
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.waitForTimeout(80);
    assert.equal(
      await page.locator('.fm-equalizer-playing, .fm-equalizer-live').count(),
      0,
    );
    assert.ok(
      await page.evaluate(() =>
        window.fmCalls.some(
          (call) =>
            call.action === 'fmVisualize' && call.payload.enabled === false,
        ),
      ),
    );
    assert.equal(
      await page.evaluate(() =>
        window.fmCalls.some((call) => /permission/i.test(call.action)),
      ),
      false,
    );
  } finally {
    await browser.close();
  }
});
async function setup(page, loggedIn = true) {
  await page.addInitScript((loggedIn) => {
    window.fmCalls = [];
    window.fmFailure = false;
    window.fmState = {
      queue: [],
      index: 0,
      playing: false,
      loading: false,
      position: 0,
      duration: 240,
      mode: 0,
      sleepAt: 0,
      personalFm: false,
      fmStarting: false,
      fmBusy: false,
      fmWaiting: false,
      fmError: '',
    };
    const song = {
      id: 'a'.repeat(32),
      hash: 'a'.repeat(32),
      title: '随心听测试歌曲',
      artist: '测试歌手',
      album: '',
      cover: '',
      albumId: '1',
      audioId: '2',
      duration: 240,
    };
    window.Native = {
      request(id, action, source) {
        const payload = JSON.parse(source);
        window.fmCalls.push({ action, payload });
        let result = true;
        if (action === 'account')
          result = { loggedIn, name: loggedIn ? '测试账号' : '', avatar: '' };
        if (action === 'settings')
          result = {
            autoVip: false,
            preloadAudio: false,
            resumeOnLaunch: false,
          };
        if (action === 'cacheList') result = { tracks: [], jobs: [], bytes: 0 };
        if (action === 'favorites') result = [];
        if (action === 'catalog') result = { data: [] };
        if (action === 'starStatus') result = { eligible: false };
        if (action === 'notificationStatus')
          result = { enabled: true, needsAttention: false };
        if (action.startsWith('update'))
          result = { configured: false, enabled: false, state: 'idle' };
        if (action === 'state') window.nativePlayback?.(window.fmState);
        if (action === 'fmStart') {
          Object.assign(window.fmState, {
            fmStarting: true,
            fmBusy: true,
            fmError: '',
          });
          window.nativePlayback(window.fmState);
          setTimeout(() => {
            if (window.fmFailure)
              Object.assign(window.fmState, {
                fmStarting: false,
                fmBusy: false,
                fmError: '平台要求安全验证，请稍后重试或使用扫码登录',
              });
            else
              Object.assign(window.fmState, {
                queue: [song],
                personalFm: true,
                fmStarting: false,
                fmBusy: false,
                playing: true,
              });
            window.nativePlayback(window.fmState);
          }, 180);
        }
        if (action === 'fmRetry') {
          Object.assign(window.fmState, {
            fmBusy: false,
            fmWaiting: false,
            fmError: '',
            playing: true,
          });
          window.nativePlayback(window.fmState);
        }
        if (action === 'fmStop') {
          Object.assign(window.fmState, {
            personalFm: false,
            fmBusy: false,
            fmError: '',
          });
          window.nativePlayback(window.fmState);
        }
        if (action === 'toggle') {
          window.fmState.playing = !window.fmState.playing;
          window.nativePlayback(window.fmState);
        }
        if (action === 'lyricDocument')
          result = { lines: [], translationAvailable: false };
        setTimeout(() => window.nativeResolve(id, result), 5);
      },
    };
  }, loggedIn);
  await page.goto('http://127.0.0.1:5173');
  await page.getByRole('button', { name: '开启随心听', exact: true }).waitFor();
  await page.waitForTimeout(350);
}

test('随心听手机、平板、深浅主题及有限动画布局（原生桥替身）', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    await fs.mkdir('artifacts/personal-fm', { recursive: true });
    for (const width of [360, 390, 768, 1280]) {
      for (const theme of ['light', 'dark']) {
        const page = await browser.newPage({
          viewport: { width, height: 900 },
          reducedMotion: 'reduce',
          colorScheme: theme,
        });
        await setup(page);
        await page.evaluate(
          (theme) => (document.documentElement.dataset.theme = theme),
          theme,
        );
        const card = page.locator('.fm-card');
        assert.match(
          (await card.textContent()).replace(/\s/g, ''),
          /想一直听下去？合你口味的好歌，无限续杯～\(｡•̀ᴗ-\)✧/,
        );
        assert.equal(
          await page.evaluate(() =>
            window.fmCalls.some((call) => call.action === 'fmStart'),
          ),
          false,
        );
        const metrics = await card.evaluate((element) => ({
          width: element.clientWidth,
          scroll: element.scrollWidth,
          height: element.clientHeight,
          play: element.querySelector('.fm-play').getBoundingClientRect().width,
        }));
        assert.ok(
          metrics.scroll <= metrics.width &&
            metrics.play >= 44 &&
            metrics.height >= 120,
        );
        assert.equal(
          await page.evaluate(
            () => document.documentElement.scrollWidth > innerWidth,
          ),
          false,
        );
        assert.equal(
          await page.evaluate(
            () =>
              document
                .getAnimations()
                .filter((animation) => animation.playState === 'running')
                .length,
          ),
          0,
        );
        await card.screenshot({
          path: `artifacts/personal-fm/card-${width}-${theme}.png`,
        });
        await page.close();
      }
    }
  } finally {
    await browser.close();
  }
});

test('首次启动、播放页、暂停、反馈与退出随心听（原生桥替身）', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
      reducedMotion: 'reduce',
    });
    const errors = [];
    page.on('pageerror', (error) => errors.push(error.message));
    await setup(page);
    await page.getByRole('button', { name: '开启随心听', exact: true }).click();
    await page
      .getByRole('button', { name: '退出随心听', exact: true })
      .waitFor();
    assert.equal(
      await page.evaluate(
        () => window.fmCalls.filter((call) => call.action === 'fmStart').length,
      ),
      1,
    );
    await page
      .locator('.player-screen')
      .getByRole('button', { name: '不喜欢，减少推荐', exact: true })
      .click();
    assert.ok(
      await page.evaluate(() =>
        window.fmCalls.some(
          (call) =>
            call.action === 'fmDislike' && call.payload.id === 'a'.repeat(32),
        ),
      ),
    );
    await page
      .locator('.player-screen')
      .getByRole('button', { name: '暂停', exact: true })
      .click();
    await page.getByRole('button', { name: '收起播放页', exact: true }).click();
    assert.match(await page.locator('.fm-status').textContent(), /已暂停/);
    const starts = await page.evaluate(
      () => window.fmCalls.filter((call) => call.action === 'fmStart').length,
    );
    await page
      .getByRole('button', { name: '打开随心听播放页', exact: true })
      .click();
    assert.equal(
      await page.evaluate(
        () => window.fmCalls.filter((call) => call.action === 'fmStart').length,
      ),
      starts,
    );
    await page.getByRole('button', { name: '退出随心听', exact: true }).click();
    await page
      .getByRole('button', { name: '切换播放模式', exact: true })
      .waitFor();
    assert.deepEqual(errors, []);
  } finally {
    await browser.close();
  }
});

test('平台限制、续曲失败重试和未登录入口（原生桥替身）', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 360, height: 800 },
      reducedMotion: 'reduce',
    });
    await setup(page);
    await page.evaluate(() => (window.fmFailure = true));
    await page.getByRole('button', { name: '开启随心听', exact: true }).click();
    await page
      .getByText('平台要求安全验证，请稍后重试或使用扫码登录', { exact: true })
      .waitFor();
    assert.equal(await page.locator('.player-screen').count(), 0);
    await page.evaluate(() => (window.fmFailure = false));
    await page.getByRole('button', { name: '重试随心听', exact: true }).click();
    await page
      .getByRole('button', { name: '退出随心听', exact: true })
      .waitFor();
    await page.evaluate(() => {
      Object.assign(window.fmState, {
        playing: false,
        fmWaiting: true,
        fmError: '网络连接失败，请检查网络后重试',
      });
      window.nativePlayback(window.fmState);
    });
    const before = await page.locator('.transport').boundingBox();
    await page
      .locator('.player-screen')
      .getByRole('button', { name: '重试随心听', exact: true })
      .click();
    assert.deepEqual(await page.locator('.transport').boundingBox(), before);
    assert.equal(await page.evaluate(() => window.fmState.fmError), '');
    await page.close();
    const guest = await browser.newPage({ reducedMotion: 'reduce' });
    await setup(guest, false);
    await guest
      .getByRole('button', { name: '开启随心听', exact: true })
      .click();
    await guest.locator('.account-panel').waitFor();
    assert.equal(
      await guest.evaluate(() =>
        window.fmCalls.some((call) => call.action === 'fmStart'),
      ),
      false,
    );
  } finally {
    await browser.close();
  }
});
