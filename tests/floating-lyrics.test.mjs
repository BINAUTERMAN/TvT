import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chromium } from 'playwright';

const browserPath =
  process.env.PLAYWRIGHT_EXECUTABLE_PATH ??
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';

async function setup(page, permission = true, expanded = true) {
  await page.addInitScript((permission) => {
    const saved = JSON.parse(localStorage.getItem('test-lyrics') || 'null') || {
      size: 20,
      width: 88,
      locked: false,
      enabled: false,
      permission,
      highlightColor: '#ffe4ac',
      textColor: '#ffffff',
      secondaryColor: '#dedee5',
      backgroundColor: '#1c1c20',
      opacity: 100,
      backgroundOpacity: 75,
      bold: false,
      lineSpacing: 5,
      secondarySize: 70,
      shadow: 3,
      align: 'center',
      mode: 'translation',
      snap: true,
      snapDistance: 24,
    };
    window.lyricsCalls = [];
    window.lyricsSaved = saved;
    const statusDefaults = {
      size: 12,
      width: 38,
      horizontal: 50,
      vertical: 0,
      side: 'auto',
      hideLandscape: true,
      color: '#ffffff',
      sungColor: '#00d9e9',
      error: '',
    };
    const statusSaved = { ...statusDefaults, enabled: false, permission };
    window.Native = {
      request(id, action, source) {
        const payload = JSON.parse(source);
        window.lyricsCalls.push({ action, payload });
        let result = true;
        let error;
        if (action === 'settings')
          result = { autoVip: false, floatingLyrics: saved.enabled };
        if (action === 'account') result = { loggedIn: false };
        if (action === 'catalog') result = { data: [] };
        if (action === 'updateSettings' || action === 'updateCheck')
          result = { configured: false, enabled: false };
        if (action === 'updateStatus') result = { state: 'idle' };
        if (action === 'starStatus') result = { eligible: false };
        if (action === 'overlay') {
          if (!saved.permission && payload.enabled)
            error = '请允许悬浮窗权限，返回后将自动开启桌面歌词';
          else saved.enabled = payload.enabled;
        }
        if (action === 'floatingSettings') {
          if (window.rejectLyricsWrite && Object.keys(payload).length) {
            window.rejectLyricsWrite = false;
            error = '测试：保存失败';
          } else {
            if (payload.resetDefaults)
              Object.assign(saved, {
                size: 20,
                width: 88,
                locked: false,
                highlightColor: '#35baff',
                textColor: '#c5ebff',
                secondaryColor: '#b1d9f0',
                backgroundColor: '#1c1c20',
                opacity: 100,
                backgroundOpacity: 0,
                bold: true,
                lineSpacing: 5,
                secondarySize: 70,
                shadow: 1,
                outline: 1,
                gradient: true,
                smooth: true,
                align: 'center',
                mode: 'next',
                snap: true,
                snapDistance: 24,
              });
            Object.assign(saved, payload);
          }
          result = { ...saved };
        }
        if (
          action === 'statusLyricsSettings' ||
          action === 'statusLyricsPreview'
        ) {
          if (payload.enabled && !permission)
            error = '请允许悬浮窗权限，返回后可预览状态栏歌词';
          else {
            if (payload.resetDefaults)
              Object.assign(statusSaved, statusDefaults);
            Object.assign(statusSaved, payload);
          }
          result = { ...statusSaved };
        }
        localStorage.setItem('test-lyrics', JSON.stringify(saved));
        setTimeout(() => window.nativeResolve(id, result, error), 15);
      },
    };
  }, permission);
  await page.goto('http://127.0.0.1:5173');
  await openSettings(page);
  if (expanded) await expandDetails(page);
}

async function expandDetails(page) {
  for (const details of await page.locator('.floating-settings details').all())
    if (!(await details.evaluate((el) => el.open)))
      await details.locator(':scope > summary').click();
}

async function openSettings(page) {
  await page
    .locator('.topbar')
    .getByRole('button', { name: '账号与设置' })
    .click();
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '偏好设置', exact: true })
    .click();
  await page.getByRole('button', { name: '桌面歌词设置', exact: true }).click();
  await idle(page);
}

async function idle(page) {
  await page.locator('.floating-settings > fieldset:not([disabled])').waitFor();
}

async function change(page, label, value, commit = true) {
  await page.getByLabel(label, { exact: true }).evaluate(
    (input, { value, commit }) => {
      input.value = value;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      if (commit) input.dispatchEvent(new Event('change', { bubbles: true }));
    },
    { value: String(value), commit },
  );
  await idle(page);
}

test('桌面歌词手机和平板布局、配色与参数保存（原生桥替身）', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    for (const viewport of [
      { width: 320, height: 740 },
      { width: 390, height: 844 },
      { width: 800, height: 1280 },
      { width: 1280, height: 800 },
      { width: 844, height: 390 },
    ]) {
      const page = await browser.newPage({
        viewport,
        colorScheme: viewport.width === 800 ? 'dark' : 'light',
      });
      const errors = [];
      page.on('pageerror', (error) => errors.push(error.message));
      await setup(page);
      assert.equal(
        await page.getByLabel('已唱颜色', { exact: true }).inputValue(),
        '#ffe4ac',
      );
      const count = await page.evaluate(
        () =>
          window.lyricsCalls.filter(
            (call) =>
              call.action === 'floatingSettings' &&
              Object.keys(call.payload).length,
          ).length,
      );
      await change(page, '歌词字号', 32, false);
      assert.equal(
        await page
          .locator('.floating-preview')
          .evaluate((el) =>
            getComputedStyle(el).getPropertyValue('--preview-font'),
          ),
        '32px',
      );
      assert.equal(
        await page.evaluate(
          () =>
            window.lyricsCalls.filter(
              (call) =>
                call.action === 'floatingSettings' &&
                Object.keys(call.payload).length,
            ).length,
        ),
        count,
      );
      await change(page, '歌词字号', 32);
      await change(page, '歌词窗口宽度', 100);
      await change(page, '已唱颜色', '#123abc');
      await change(page, '未唱颜色', '#abcdef');
      await change(page, '歌词背景不透明度', 0);
      await change(page, '歌词文字描边', 2);
      await page.getByLabel('字色渐变', { exact: true }).uncheck();
      await idle(page);
      await page.getByLabel('平滑逐字与长句移动', { exact: true }).uncheck();
      await idle(page);
      await page
        .getByLabel('歌词文字对齐', { exact: true })
        .selectOption('left');
      await idle(page);
      assert.equal(
        await page
          .locator('.floating-preview strong span')
          .evaluate((el) => getComputedStyle(el).color),
        'rgb(18, 58, 188)',
      );
      assert.equal(
        await page
          .locator('.floating-preview')
          .evaluate((el) => getComputedStyle(el).textAlign),
        'left',
      );
      await page
        .getByLabel('歌词显示模式', { exact: true })
        .selectOption('single');
      await idle(page);
      assert.equal(await page.locator('.floating-preview small').count(), 0);
      assert.equal(
        await page.getByLabel('歌词副行字号比例', { exact: true }).isDisabled(),
        true,
      );
      await page.getByLabel('靠近屏幕边缘自动吸附', { exact: true }).uncheck();
      await idle(page);
      assert.equal(
        await page.getByLabel('歌词边缘吸附距离', { exact: true }).isDisabled(),
        true,
      );
      await page.getByRole('button', { name: '樱粉', exact: true }).click();
      await idle(page);
      assert.equal(
        await page.getByLabel('已唱颜色', { exact: true }).inputValue(),
        '#ffacd3',
      );
      await page.getByLabel('锁定位置，触摸穿透', { exact: true }).check();
      await idle(page);
      await page
        .getByRole('button', { name: '重置横竖屏位置', exact: true })
        .click();
      await idle(page);
      assert.ok(
        await page.evaluate(() =>
          window.lyricsCalls.some((call) => call.payload.resetPosition),
        ),
      );
      const overflow = await page
        .locator('.floating-settings')
        .evaluate((section) => section.scrollWidth - section.clientWidth);
      assert.ok(
        overflow <= 1,
        `${viewport.width}px layout overflows ${overflow}px`,
      );
      await page.locator('.floating-settings h2').scrollIntoViewIfNeeded();
      await page.screenshot({
        path: `artifacts/lyrics-settings-${viewport.width}.png`,
      });
      await page.reload();
      await openSettings(page);
      await expandDetails(page);
      assert.equal(
        await page.getByLabel('歌词字号', { exact: true }).inputValue(),
        '32',
      );
      assert.equal(
        await page.getByLabel('歌词文字描边', { exact: true }).inputValue(),
        '2',
      );
      assert.equal(
        await page
          .getByLabel('平滑逐字与长句移动', { exact: true })
          .isChecked(),
        false,
      );
      assert.equal(
        await page
          .getByLabel('锁定位置，触摸穿透', { exact: true })
          .isChecked(),
        true,
      );
      assert.deepEqual(errors, []);
      await page.close();
    }
  } finally {
    await browser.close();
  }
});

test('精简分组、滚动预览吸顶与返回展开（原生桥替身）', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
    });
    await setup(page, true, false);
    await page.getByRole('button', { name: '经典蓝', exact: true }).click();
    await idle(page);
    const custom = page.locator('.lyrics-details').first();
    assert.equal(await custom.evaluate((el) => el.open), false);
    const preview = page.locator('.lyrics-preview-dock');
    const initial = await preview.boundingBox();
    const sheet = page.locator('.sheet-panel');
    await sheet.evaluate((el) => {
      el.scrollTop = 420;
    });
    await page.waitForFunction(() =>
      document
        .querySelector('.lyrics-preview-dock')
        ?.classList.contains('is-pinned'),
    );
    await page.waitForFunction(
      () =>
        document.querySelector('.lyrics-preview-stage').getBoundingClientRect()
          .height < 130,
    );
    const pinned = await preview.boundingBox();
    const root = await sheet.boundingBox();
    assert.ok(pinned.y >= root.y && pinned.y < root.y + 25);
    assert.ok(pinned.height < initial.height);
    await sheet.evaluate((el) => {
      el.scrollTop = 650;
    });
    await page.waitForTimeout(100);
    assert.ok(Math.abs((await preview.boundingBox()).y - pinned.y) < 2);
    await page.screenshot({ path: 'artifacts/lyrics-preview-pinned.png' });
    await sheet.evaluate((el) => {
      el.scrollTop = 0;
    });
    await page.waitForFunction(
      () =>
        !document
          .querySelector('.lyrics-preview-dock')
          ?.classList.contains('is-pinned'),
    );
    await custom.locator('summary').click();
    assert.equal(
      await page.getByLabel('已唱颜色', { exact: true }).isVisible(),
      true,
    );
    await sheet.evaluate((el) => {
      el.scrollTop = 0;
    });
    await page.screenshot({ path: 'artifacts/lyrics-settings-compact.png' });
    await browser.close();
  } finally {
    await browser.close();
  }
});

test('参数条防误触、拖动精调与恢复默认确认（原生桥替身）', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
    });
    await setup(page);
    const slider = page.getByLabel('歌词字号', { exact: true });
    await slider.scrollIntoViewIfNeeded();
    const box = await slider.boundingBox();
    await slider.click({ position: { x: box.width - 3, y: box.height / 2 } });
    assert.equal(await slider.inputValue(), '20');
    assert.equal(
      await page.evaluate(
        () =>
          window.lyricsCalls.filter(
            (c) => c.action === 'floatingSettings' && 'size' in c.payload,
          ).length,
      ),
      0,
    );
    const center = box.x + 10 + ((box.width - 20) * (20 - 12)) / (48 - 12);
    await page.mouse.move(center, box.y + box.height / 2);
    await page.mouse.down();
    await page.mouse.move(
      center + (box.width - 20) * 0.3,
      box.y + box.height / 2,
      { steps: 6 },
    );
    await page.mouse.up();
    await idle(page);
    assert.ok(Number(await slider.inputValue()) > 20);
    const previous = Number(await slider.inputValue());
    await page
      .getByRole('button', { name: '增大歌词字号', exact: true })
      .click();
    await idle(page);
    assert.equal(Number(await slider.inputValue()), previous + 1);
    await page
      .getByRole('button', { name: '恢复默认参数', exact: true })
      .click();
    assert.equal(await slider.inputValue(), String(previous + 1));
    await page.getByRole('button', { name: '取消恢复', exact: true }).click();
    assert.equal(
      await page.getByRole('group', { name: '恢复默认参数确认' }).count(),
      0,
    );
    await page
      .getByRole('button', { name: '恢复默认参数', exact: true })
      .click();
    await page
      .getByRole('button', { name: '确认恢复默认', exact: true })
      .click();
    await idle(page);
    assert.equal(await slider.inputValue(), '20');
    assert.equal(
      await page.getByLabel('已唱颜色', { exact: true }).inputValue(),
      '#35baff',
    );
    assert.equal(
      await page.getByLabel('锁定位置，触摸穿透', { exact: true }).isChecked(),
      false,
    );
    assert.ok(
      await page.evaluate(() =>
        window.lyricsCalls.some(
          (c) => c.action === 'floatingSettings' && c.payload.resetDefaults,
        ),
      ),
    );
  } finally {
    await browser.close();
  }
});

test('状态栏歌词独立开关、预览、位置和恢复默认（原生桥替身）', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
    });
    await setup(page);
    const panel = page.locator('.status-lyrics-settings');
    await panel.locator('fieldset:not([disabled])').waitFor();
    const position = panel.getByLabel('状态栏歌词显示位置', { exact: true });
    assert.equal(await position.inputValue(), 'auto');
    assert.equal(
      await position.locator('option:checked').textContent(),
      '屏幕居中',
    );
    await position.selectOption('left');
    await panel.locator('fieldset:not([disabled])').waitFor();
    assert.equal(await position.inputValue(), 'left');
    await position.selectOption('auto');
    await panel.locator('fieldset:not([disabled])').waitFor();
    await panel.getByLabel('开启状态栏歌词', { exact: true }).click();
    await panel.locator('fieldset:not([disabled])').waitFor();
    assert.equal(
      await panel.getByLabel('开启状态栏歌词', { exact: true }).isChecked(),
      true,
    );
    assert.equal(
      await page.getByLabel('显示桌面歌词', { exact: true }).isChecked(),
      false,
    );
    await panel.getByRole('button', { name: '预览状态栏位置（5秒）' }).click();
    await panel.locator('fieldset:not([disabled])').waitFor();
    assert.ok(
      await page.evaluate(() =>
        window.lyricsCalls.some((c) => c.action === 'statusLyricsPreview'),
      ),
    );
    await panel.getByRole('button', { name: '增大状态栏歌词垂直偏移' }).click();
    await panel.locator('fieldset:not([disabled])').waitFor();
    assert.equal(
      await panel
        .getByLabel('状态栏歌词垂直偏移', { exact: true })
        .inputValue(),
      '1',
    );
    await panel
      .getByRole('button', { name: '恢复状态栏默认参数', exact: true })
      .click();
    await panel
      .getByRole('button', { name: '确认恢复状态栏默认', exact: true })
      .click();
    await panel.locator('fieldset:not([disabled])').waitFor();
    assert.equal(
      await panel
        .getByLabel('状态栏歌词垂直偏移', { exact: true })
        .inputValue(),
      '0',
    );
    assert.equal(
      await panel.getByLabel('开启状态栏歌词', { exact: true }).isChecked(),
      true,
    );
  } finally {
    await browser.close();
  }
});

test('桌面歌词保存失败回退、权限拒绝和返回前台同步（原生桥替身）', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
    });
    await setup(page, false);
    await page.getByLabel('显示桌面歌词', { exact: true }).click();
    await idle(page);
    assert.equal(
      await page.getByLabel('显示桌面歌词', { exact: true }).isChecked(),
      false,
    );
    await page
      .getByText('请允许悬浮窗权限，返回后将自动开启桌面歌词', { exact: true })
      .waitFor();
    await page.evaluate(() => {
      window.rejectLyricsWrite = true;
    });
    await change(page, '歌词字号', 40);
    assert.equal(
      await page.getByLabel('歌词字号', { exact: true }).inputValue(),
      '20',
    );
    await page.evaluate(() => {
      window.lyricsSaved.permission = true;
      window.lyricsSaved.enabled = true;
      window.lyricsSaved.locked = true;
      window.dispatchEvent(new Event('ting:resume'));
    });
    await idle(page);
    assert.equal(
      await page.getByLabel('显示桌面歌词', { exact: true }).isChecked(),
      true,
    );
    assert.equal(
      await page.getByLabel('锁定位置，触摸穿透', { exact: true }).isChecked(),
      true,
    );
    const before = await page.evaluate(
      () =>
        window.lyricsCalls.filter((call) => call.action === 'floatingSettings')
          .length,
    );
    await page.evaluate(() => {
      Object.defineProperty(document, 'hidden', {
        configurable: true,
        value: true,
      });
      document.dispatchEvent(new Event('visibilitychange'));
      window.dispatchEvent(new Event('ting:resume'));
    });
    await page.waitForTimeout(100);
    assert.equal(
      await page.evaluate(
        () =>
          window.lyricsCalls.filter(
            (call) => call.action === 'floatingSettings',
          ).length,
      ),
      before,
    );
  } finally {
    await browser.close();
  }
});
