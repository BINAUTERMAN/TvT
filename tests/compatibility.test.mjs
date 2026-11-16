import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const browserPath =
  process.env.PLAYWRIGHT_EXECUTABLE_PATH ??
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';

test('首页匿名慢请求不阻塞登录后加载，旧响应不覆盖新账号数据（原生桥替身）', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 800, height: 1000 },
    });
    await page.addInitScript(() => {
      let playlistRequests = 0;
      window.delayedAnonymous = '';
      const song = {
        hash: 'a'.repeat(32),
        name: '登录后的推荐',
        filename: '登录后的推荐',
        author_name: '测试歌手',
      };
      window.Native = {
        request(id, action, source) {
          const payload = JSON.parse(source);
          let value = true;
          if (action === 'account')
            value = { loggedIn: true, name: '测试账号', avatar: '' };
          if (action === 'catalog') {
            if (payload.route === 'playlists') {
              playlistRequests++;
              if (playlistRequests === 1) {
                window.delayedAnonymous = id;
                return;
              }
              value = { data: [{ id: 123, name: '登录后的歌单', imgurl: '' }] };
            } else if (
              payload.route === 'daily' ||
              payload.route === 'recommend'
            )
              value = { data: [song] };
            else value = { data: [] };
          }
          if (action === 'favorites' || action === 'cacheList') value = [];
          if (action === 'settings') value = {};
          if (action === 'updateSettings' || action === 'updateCheck')
            value = { configured: false, enabled: false };
          if (action === 'updateStatus') value = { state: 'idle' };
          setTimeout(() => window.nativeResolve(id, value));
        },
      };
    });
    await page.goto('http://127.0.0.1:5173');
    await page.locator('.daily-mix').waitFor({ timeout: 2000 });
    await page
      .locator('.personal-home')
      .getByText('登录后的歌单', { exact: true })
      .waitFor();
    assert.ok(await page.evaluate(() => window.delayedAnonymous));
    await page.evaluate(() =>
      window.nativeResolve(window.delayedAnonymous, {
        data: [{ id: 456, name: '过期匿名歌单' }],
      }),
    );
    await page.getByRole('button', { name: '刷新推荐', exact: true }).click();
    await page.locator('.daily-mix').waitFor();
    assert.equal(
      await page.getByText('过期匿名歌单', { exact: true }).count(),
      0,
    );
    await page
      .locator('.personal-home')
      .getByText('登录后的歌单', { exact: true })
      .waitFor();
  } finally {
    await browser.close();
  }
});

test('慢会员和内容请求占用线程时，认证仍可执行', () => {
  const java = path.join(
    process.env.JAVA_HOME ??
      path.join(process.env.USERPROFILE, '.jdks/jbr-17.0.14'),
    'bin',
  );
  fs.mkdirSync('build/test/request-workers', { recursive: true });
  execFileSync(path.join(java, 'javac.exe'), [
    '-encoding',
    'UTF-8',
    '-d',
    'build/test/request-workers',
    'android/src/cn/ting/music/RequestWorkers.java',
    'tests/RequestWorkersCheck.java',
  ]);
  assert.match(
    execFileSync(
      path.join(java, 'java.exe'),
      [
        '-cp',
        'build/test/request-workers',
        'cn.ting.music.RequestWorkersCheck',
      ],
      { encoding: 'utf8' },
    ),
    /REQUEST_WORKERS_PASS/,
  );
});

async function setup(page, choices = false) {
  await page.addInitScript((choices) => {
    let loggedIn = false;
    window.compatCalls = [];
    window.delayedVip = [];
    window.compatPreferences = {
      autoVip: false,
      resumeOnLaunch: false,
      floatingLyrics: false,
      lyricsTranslation: true,
      preloadAudio: true,
    };
    window.Native = {
      request(id, action, source) {
        const payload = JSON.parse(source);
        window.compatCalls.push({ action, payload });
        let value = true;
        if (action === 'account')
          value = { loggedIn, name: '兼容测试账号', avatar: '' };
        if (action === 'loginMobile' || action === 'chooseLoginAccount') {
          if (choices && action === 'loginMobile')
            value = {
              requiresAccount: true,
              choices: [{ key: 'account-a', name: '测试甲', avatar: '' }],
            };
          else {
            loggedIn = true;
            value = { loggedIn: true };
          }
        }
        if (action === 'vipStatus') {
          window.delayedVip.push(id);
          return;
        }
        if (action === 'settings') {
          Object.assign(window.compatPreferences, payload);
          value = { ...window.compatPreferences };
        }
        if (action === 'catalog') value = { data: [] };
        if (action === 'favorites' || action === 'cacheList') value = [];
        if (action === 'updateSettings' || action === 'updateCheck')
          value = { configured: false, enabled: false, currentVersion: '5.5' };
        if (action === 'updateStatus') value = { state: 'idle' };
        setTimeout(() => window.nativeResolve(id, value));
      },
    };
  }, choices);
  await page.goto('http://127.0.0.1:5173');
}

async function openSettings(page) {
  await page
    .locator('.topbar')
    .getByRole('button', { name: '账号与设置' })
    .click();
  await page
    .locator('.sheet-panel[aria-label="account"]')
    .getByRole('button', { name: '偏好设置', exact: true })
    .click();
  await page.locator('.preferences-panel').waitFor();
}

test('短信和多账号登录成功不等待 VIP；权益失败不反转登录结果', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    for (const choices of [false, true]) {
      const page = await browser.newPage({
        viewport: { width: 800, height: 1000 },
      });
      await setup(page, choices);
      await page
        .locator('.topbar')
        .getByRole('button', { name: '账号与设置' })
        .click();
      await page.getByRole('textbox', { name: '手机号码' }).fill('13800000000');
      await page.getByRole('textbox', { name: '短信验证码' }).fill('123456');
      await page.getByRole('button', { name: '登录', exact: true }).click();
      if (choices)
        await page.getByRole('button', { name: /测试甲.*账号 1/ }).click();
      await page
        .getByText('已登录酷狗账号', { exact: true })
        .waitFor({ timeout: 2000 });
      await page
        .locator('.account-summary')
        .getByText('兼容测试账号')
        .waitFor();
      assert.equal(await page.evaluate(() => window.delayedVip.length), 1);
      await page.evaluate(() => {
        window.nativeResolve(
          window.delayedVip.shift(),
          null,
          '模拟会员请求超时',
        );
      });
      await page.getByText('VIP 状态暂不可用', { exact: true }).waitFor();
      assert.equal(
        await page.getByRole('button', { name: '退出登录' }).count(),
        1,
      );
      assert.equal(await page.locator('.login-error').count(), 0);
      await page.close();
    }
  } finally {
    await browser.close();
  }
});

test('手机、平板横竖屏与大字号偏好设置可滚动且无横向溢出', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    for (const [width, height] of [
      [390, 844],
      [800, 1280],
      [1280, 800],
      [900, 480],
      [700, 700],
    ]) {
      const page = await browser.newPage({ viewport: { width, height } });
      await setup(page);
      await page.addStyleTag({ content: ':root { font-size: 21px; }' });
      await openSettings(page);
      const panel = page.locator('.sheet-panel[aria-label="settings"]');
      const toggle = panel
        .getByText('预缓存后续歌曲', { exact: true })
        .locator('..')
        .getByRole('checkbox');
      await toggle.uncheck();
      assert.equal(await toggle.isChecked(), false);
      await panel.getByRole('button', { name: '深色', exact: true }).click();
      assert.equal(
        await page.locator('html').getAttribute('data-theme'),
        'dark',
      );
      const geometry = await panel.evaluate((element) => {
        const r = element.getBoundingClientRect();
        const sections = [
          ...element.querySelectorAll('.preference-section'),
        ].map((el) => el.getBoundingClientRect().left);
        return {
          width: r.width,
          top: r.top,
          bottom: r.bottom,
          clientWidth: element.clientWidth,
          scrollWidth: element.scrollWidth,
          columns: new Set(sections).size,
        };
      });
      assert.ok(
        geometry.top >= -1 && geometry.bottom <= height + 1,
        JSON.stringify(geometry),
      );
      assert.ok(
        geometry.scrollWidth <= geometry.clientWidth + 1,
        JSON.stringify(geometry),
      );
      assert.equal(geometry.columns, width >= 700 ? 2 : 1);
      if (width >= 700) assert.ok(geometry.width >= 600);
      await panel
        .getByRole('button', { name: '开源许可', exact: true })
        .scrollIntoViewIfNeeded();
      await page.screenshot({
        path: `artifacts/preferences-${width}x${height}.png`,
      });
      await page.close();
    }
  } finally {
    await browser.close();
  }
});

test('缺少新版 JS 方法的 WebView 能启动，生成问卷标识并恢复前台', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 800, height: 1000 },
    });
    const errors = [];
    page.on('pageerror', (error) => errors.push(error.message));
    await page.addInitScript(() => {
      Array.prototype.at = undefined;
      String.prototype.replaceAll = undefined;
      crypto.randomUUID = undefined;
      document.getAnimations = undefined;
    });
    await setup(page);
    await openSettings(page);
    const ids = await page.evaluate(async () => {
      const { submissionId } = await import('/src/compatibility.ts');
      window.dispatchEvent(new Event('ting:resume'));
      return Array.from({ length: 10 }, submissionId);
    });
    assert.equal(new Set(ids).size, 10);
    assert.ok(
      ids.every((id) =>
        /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(
          id,
        ),
      ),
    );
    assert.deepEqual(errors, []);
  } finally {
    await browser.close();
  }
});
