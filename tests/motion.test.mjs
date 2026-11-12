import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chromium } from 'playwright';

const browserPath =
  process.env.PLAYWRIGHT_EXECUTABLE_PATH ??
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';

async function setup(page) {
  await page.addInitScript(() => {
    window.motionSamples = [];
    document.addEventListener('transitionrun', (event) => {
      if (event.propertyName !== 'opacity') return;
      const element = event.target;
      requestAnimationFrame(() =>
        requestAnimationFrame(() => {
          window.motionSamples.push({
            element,
            opacity: Number(getComputedStyle(element).opacity),
          });
        }),
      );
    });
    const song = {
      id: 'motion-song',
      title: '动画验证歌曲',
      artist: '测试歌手',
      album: '',
      cover: '',
      hash: 'a'.repeat(32),
      albumId: '',
      audioId: '',
      duration: 240,
    };
    window.motionState = {
      queue: [song],
      index: 0,
      playing: true,
      loading: false,
      position: 0,
      duration: 240,
      mode: 0,
      sleepAt: 0,
    };
    localStorage.setItem(
      'playlists',
      JSON.stringify([{ id: 'saved', name: '本地测试歌单', tracks: [song] }]),
    );
    window.Native = {
      request(id, action, source) {
        const payload = JSON.parse(source);
        let result = true;
        let delay = 10;
        if (action === 'account')
          result = { loggedIn: true, name: '动画测试账号', avatar: '' };
        if (action === 'settings')
          result = {
            preloadAudio: true,
            autoVip: false,
            floatingLyrics: false,
            lyricsTranslation: true,
            resumeOnLaunch: false,
          };
        if (action === 'cacheList') result = { tracks: [], jobs: [], bytes: 0 };
        if (action === 'favorites') result = [];
        if (action === 'notificationStatus')
          result = { enabled: true, permission: true, needsAttention: false };
        if (action === 'starStatus') result = { eligible: false };
        if (action === 'updateSettings' || action === 'updateCheck')
          result = { configured: false, enabled: false };
        if (action === 'updateStatus') result = { state: 'idle' };
        if (action === 'vipStatus')
          result = {
            label: '普通用户',
            active: false,
            rights: [],
            todayReceipts: [],
            todayClaimed: false,
            claimKnown: true,
            checkedAt: 1,
          };
        if (action === 'cloudPlaylists') {
          delay = 350;
          result = {
            data: {
              info: [{ global_collection_id: 'cloud', name: '云端测试歌单' }],
            },
          };
        }
        if (action === 'lyricDocument')
          result = {
            lines: [{ time: 0, text: '测试歌词' }],
            translationAvailable: false,
          };
        if (action === 'catalog') {
          delay = 140;
          result = { data: [] };
          if (payload.route === 'playlists')
            result = { data: [{ id: 'public', name: '公开测试歌单' }] };
          if (payload.route === 'ranks')
            result = { data: [{ id: 'rank', name: '测试排行榜' }] };
          if (
            ['playlist', 'rankSongs', 'daily', 'recommend'].includes(
              payload.route,
            )
          )
            result = { data: [song] };
          if (payload.route === 'search' && payload.data.type === 'song')
            result = { data: [song] };
        }
        if (action === 'state') window.nativePlayback?.(window.motionState);
        setTimeout(() => window.nativeResolve(id, result), delay);
      },
    };
  });
  await page.goto('http://127.0.0.1:5173');
  await page.locator('.mini-info').waitFor();
  await settle(page);
}

async function settle(page) {
  await page.waitForFunction(
    () =>
      !document.querySelector(
        '[class*="-enter-active"], [class*="-leave-active"]',
      ),
  );
  await page.evaluate(() =>
    Promise.allSettled(
      document.getAnimations().map((animation) => animation.finished),
    ),
  );
  await page.evaluate(() => {
    window.motionSamples = [];
  });
}

async function moving(page, selector) {
  await page.waitForFunction(
    (selector) => {
      if (
        window.motionSamples.some(
          ({ element, opacity }) =>
            element.matches(selector) && opacity > 0.02 && opacity < 0.98,
        )
      )
        return true;
      const element = document.querySelector(selector);
      if (!element) return false;
      const opacity = Number(getComputedStyle(element).opacity);
      return opacity > 0.02 && opacity < 0.98;
    },
    selector,
    { timeout: 3000 },
  );
  await page.evaluate(() => {
    window.motionSamples = [];
  });
}

async function closeDrawer(page) {
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '关闭', exact: true })
    .click();
  await page.locator('.modal-backdrop').waitFor({ state: 'detached' });
}

test('账号、设置、权益和云歌单使用连续过渡，离场按钮不能重复操作（原生桥替身）', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    for (const viewport of [
      { width: 390, height: 844 },
      { width: 1280, height: 800 },
    ]) {
      const page = await browser.newPage({ viewport });
      const errors = [];
      page.on('pageerror', (error) => errors.push(error.message));
      await setup(page);
      await page.getByRole('button', { name: '账号与设置' }).click();
      await moving(page, '.sheet-panel');
      await settle(page);
      await page
        .getByRole('button', { name: '读取云端歌单', exact: true })
        .click();
      await page.getByRole('button', { name: '云端测试歌单 导入' }).waitFor();
      await moving(page, '.account-panel .content-pane');
      await settle(page);
      await page.getByRole('button', { name: '概念版 VIP 与领取记录' }).click();
      await page.waitForFunction(
        () => document.querySelector('.sheet-leave-active')?.inert,
      );
      await moving(page, '.sheet-panel[aria-label="vip"]');
      await settle(page);
      await closeDrawer(page);
      await page.getByRole('button', { name: '账号与设置' }).click();
      await page
        .getByRole('dialog')
        .getByRole('button', { name: '偏好设置', exact: true })
        .click();
      await moving(page, '.sheet-panel[aria-label="settings"]');
      await settle(page);
      await page.getByRole('button', { name: '开源许可', exact: true }).click();
      await moving(page, '.sheet-panel[aria-label="licenses"]');
      await settle(page);
      await closeDrawer(page);
      assert.deepEqual(errors, []);
      assert.equal(await page.locator('[inert]').count(), 0);
      await page.close();
    }
  } finally {
    await browser.close();
  }
});

test('歌单、排行榜、搜索和资料库切换均有过渡且保留搜索输入（原生桥替身）', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
    });
    await setup(page);
    await page
      .locator('.entity-card')
      .filter({ hasText: '公开测试歌单' })
      .first()
      .click();
    await moving(page, '.detail-page');
    await settle(page);
    await page.getByRole('button', { name: '返回', exact: true }).click();
    await settle(page);
    await page
      .locator('.bottom-nav')
      .getByRole('button', { name: '发现', exact: true })
      .click();
    await moving(page, '.explore-page');
    await page.locator('.rank-section .entity-card').first().click();
    await moving(page, '.detail-page');
    await settle(page);
    await page.getByRole('button', { name: '返回', exact: true }).click();
    await settle(page);
    await page
      .locator('.bottom-nav')
      .getByRole('button', { name: '搜索', exact: true })
      .click();
    const input = page.getByLabel('搜索歌曲或歌手', { exact: true });
    await input.fill('动画验证');
    await input.press('Enter');
    await page.locator('.search-page .track-row').first().waitFor();
    await moving(page, '.search-page > .content-stage > .content-pane');
    await settle(page);
    await page.getByRole('tab', { name: '歌曲', exact: true }).click();
    await moving(
      page,
      '.search-page .content-stage .content-stage > .content-pane',
    );
    await settle(page);
    await page.getByRole('tab', { name: '专辑', exact: true }).click();
    await page.getByRole('tab', { name: '歌曲', exact: true }).click();
    await settle(page);
    assert.equal(await page.locator('.search-page [inert]').count(), 0);
    assert.ok((await page.locator('.search-page .track-row').count()) > 0);
    await page
      .locator('.bottom-nav')
      .getByRole('button', { name: '资料库', exact: true })
      .click();
    await settle(page);
    await page.getByRole('button', { name: '本地测试歌单 1 首歌曲' }).click();
    await moving(page, '.library-page .content-pane');
    await settle(page);
    await page
      .locator('.bottom-nav')
      .getByRole('button', { name: '搜索', exact: true })
      .click();
    await settle(page);
    assert.equal(await input.inputValue(), '动画验证');
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth > innerWidth,
      ),
      false,
    );
  } finally {
    await browser.close();
  }
});

test('音频加载及中断提示不移动封面、进度条和播放按钮（原生桥替身）', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    for (const viewport of [
      { width: 390, height: 844 },
      { width: 844, height: 390 },
      { width: 1280, height: 800 },
    ]) {
      const page = await browser.newPage({ viewport });
      await setup(page);
      await page.locator('.mini-info').click();
      await settle(page);
      const positions = () =>
        page.locator('.player-screen').evaluate((element) =>
          ['.player-art', '.seek', '.transport', '.player-tools'].map(
            (selector) => {
              const rect = element
                .querySelector(selector)
                .getBoundingClientRect();
              return {
                x: rect.x,
                y: rect.y,
                width: rect.width,
                height: rect.height,
              };
            },
          ),
        );
      const before = await positions();
      for (const state of [
        { loading: true },
        { loading: false, interrupted: true },
        { interrupted: false },
      ]) {
        await page.evaluate((state) => window.nativePlayback(state), state);
        await settle(page);
        assert.deepEqual(await positions(), before);
      }
      await page.close();
    }
  } finally {
    await browser.close();
  }
});

test('减少动态效果与进入后台会停止界面动画（原生桥替身）', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
      reducedMotion: 'reduce',
    });
    await setup(page);
    await page.getByRole('button', { name: '账号与设置' }).click();
    await settle(page);
    assert.equal(
      await page.evaluate(
        () =>
          document
            .getAnimations()
            .filter((animation) => animation.playState === 'running').length,
      ),
      0,
    );
    await closeDrawer(page);
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    await page.locator('.mini-info').click();
    await moving(page, '.player-screen');
    await page.evaluate(() => {
      Object.defineProperty(document, 'hidden', {
        configurable: true,
        value: true,
      });
      document.dispatchEvent(new Event('visibilitychange'));
    });
    assert.equal(
      await page.evaluate(
        () =>
          document
            .getAnimations()
            .filter((animation) => animation.playState === 'running').length,
      ),
      0,
    );
    await page.evaluate(() => {
      Object.defineProperty(document, 'hidden', {
        configurable: true,
        value: false,
      });
      document.dispatchEvent(new Event('visibilitychange'));
    });
    await settle(page);
    assert.equal(await page.locator('body.backgrounded').count(), 0);
  } finally {
    await browser.close();
  }
});
