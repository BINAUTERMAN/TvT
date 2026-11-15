import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import { cloudBridge } from './fixtures/cloud-bridge.mjs';
const browserPath =
  process.env.PLAYWRIGHT_EXECUTABLE_PATH ??
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';

test('空歌单默认封面在刷新和切换编辑面板后保持可见（原生桥替身）', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
      reducedMotion: 'reduce',
    });
    await cloudBridge(page);
    await page.evaluate(() => {
      Object.assign(window.cloudFixture.playlists[0], {
        name: '空测试歌单',
        cover: '',
        count: 0,
      });
      window.cloudTracks['remote-0-10'] = [];
    });
    const visibleCover = (selector) =>
      page.waitForFunction(
        (selector) => {
          const image = document.querySelector(selector);
          return (
            image &&
            image.complete &&
            image.naturalWidth > 0 &&
            getComputedStyle(image).opacity === '1'
          );
        },
        selector,
        { timeout: 3000 },
      );
    await page
      .getByRole('button', { name: '刷新云端歌单', exact: true })
      .click();
    await page
      .getByRole('button', { name: '空测试歌单 自建 · 0 首', exact: true })
      .waitFor();
    const cardImage =
      '.cloud-playlists .playlist-card:first-child .artwork img';
    await visibleCover(cardImage);
    assert.match(
      await page.locator(cardImage).getAttribute('src'),
      /\/brand\/tvt-cover\.svg$/,
    );
    const corner = await page.locator(cardImage).evaluate((image) => {
      const canvas = document.createElement('canvas');
      canvas.width = canvas.height = 72;
      const context = canvas.getContext('2d');
      context.drawImage(image, 0, 0, 72, 72);
      return [...context.getImageData(2, 2, 1, 1).data];
    });
    assert.deepEqual(corner, [247, 247, 247, 255]);
    await page
      .getByRole('button', { name: '刷新云端歌单', exact: true })
      .click();
    await visibleCover(cardImage);
    await page
      .getByRole('button', { name: '空测试歌单 自建 · 0 首', exact: true })
      .click();
    const detailImage = '.cloud-playlist-detail .entity-heading .artwork img';
    await visibleCover(detailImage);
    await page.getByRole('button', { name: '编辑歌单', exact: true }).click();
    await page.getByLabel('歌单名称', { exact: true }).waitFor();
    await visibleCover(detailImage);
    await page.getByRole('button', { name: '取消编辑', exact: true }).click();
    await visibleCover(detailImage);
  } finally {
    await browser.close();
  }
});

test('默认收藏标记为酷狗系统歌单，不提供无效的删除和改名入口（原生桥替身）', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
      reducedMotion: 'reduce',
    });
    await cloudBridge(page);
    await page.evaluate(() => {
      window.cloudFixture.playlists[0].isSystem = true;
      window.cloudFixture.playlists[0].name = '默认收藏';
    });
    await page
      .getByRole('button', { name: '刷新云端歌单', exact: true })
      .click();
    await page
      .getByRole('button', { name: '默认收藏 系统歌单 · 2 首', exact: true })
      .click();
    await page.locator('.cloud-playlist-detail .track-row').nth(1).waitFor();
    assert.equal(
      await page.getByRole('button', { name: '删除歌单', exact: true }).count(),
      0,
    );
    assert.equal(
      await page.getByRole('button', { name: '编辑歌单', exact: true }).count(),
      0,
    );
    await page
      .getByText('这是酷狗系统歌单，不能删除整张歌单。', { exact: true })
      .waitFor();
    assert.equal(
      await page.evaluate(() =>
        window.cloudCalls.some((call) =>
          ['delete', 'update'].includes(call.payload.operation),
        ),
      ),
      false,
    );
  } finally {
    await browser.close();
  }
});

test('歌单只有酷狗同步入口，创建调用原生接口且失败不生成本地副本（原生桥替身）', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
      reducedMotion: 'reduce',
    });
    await page.addInitScript(() =>
      localStorage.setItem(
        'playlists',
        JSON.stringify([{ id: 'legacy', name: '旧的本地歌单', tracks: [] }]),
      ),
    );
    await cloudBridge(page);
    assert.equal(
      await page
        .getByRole('heading', { name: '我的歌单', exact: true })
        .count(),
      1,
    );
    assert.equal(
      await page.getByText('旧的本地歌单', { exact: true }).count(),
      0,
    );
    await page.getByRole('button', { name: '新建歌单', exact: true }).click();
    assert.equal(await page.getByText('本地歌单', { exact: true }).count(), 0);
    await page.getByLabel('歌单名称', { exact: true }).fill('通勤音乐');
    await page.getByRole('button', { name: '创建歌单', exact: true }).click();
    await page
      .getByRole('heading', { name: '通勤音乐', exact: true })
      .waitFor();
    assert.equal(
      await page.evaluate(
        () =>
          window.cloudCalls.filter(
            (call) =>
              call.action === 'cloudPlaylist' &&
              call.payload.operation === 'create',
          ).length,
      ),
      1,
    );
    assert.deepEqual(
      await page.evaluate(() =>
        JSON.parse(localStorage.getItem('playlists')).map((list) => list.id),
      ),
      ['legacy'],
    );
    await page
      .getByRole('button', { name: '资料库', exact: true })
      .first()
      .click();
    await page.getByRole('button', { name: '新建歌单', exact: true }).click();
    await page.evaluate(
      () => (window.cloudFailure = '创建失败：平台拒绝本次请求'),
    );
    await page.getByLabel('歌单名称', { exact: true }).fill('失败的歌单');
    await page.getByRole('button', { name: '创建歌单', exact: true }).click();
    await page
      .getByRole('dialog')
      .getByText('创建失败：平台拒绝本次请求', { exact: true })
      .waitFor();
    assert.equal(
      await page
        .getByRole('heading', { name: '失败的歌单', exact: true })
        .count(),
      0,
    );
  } finally {
    await browser.close();
  }
});

test('未登录不能读取或修改歌单，收藏与新建引导登录（原生桥替身）', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
      reducedMotion: 'reduce',
    });
    await cloudBridge(page, { loggedOut: true });
    assert.equal(
      await page.locator('.cloud-playlists .playlist-card').count(),
      0,
    );
    assert.equal(
      await page
        .getByRole('button', { name: '导入外部歌单', exact: true })
        .count(),
      0,
    );
    assert.equal(
      await page.evaluate(() =>
        window.cloudCalls.some((call) => call.action === 'cloudPlaylist'),
      ),
      false,
    );
    await page.getByRole('button', { name: '新建歌单', exact: true }).click();
    await page
      .getByRole('heading', { name: '登录酷狗账号', exact: true })
      .waitFor();
    await page.getByRole('button', { name: '关闭', exact: true }).click();
    await page.locator('.liked-card').click();
    await page
      .getByRole('heading', { name: '登录酷狗账号', exact: true })
      .waitFor();
    assert.equal(await page.locator('.playlist-create').count(), 0);
  } finally {
    await browser.close();
  }
});

test('重新进入资料库自动补取云歌单封面，无需先打开歌单（原生桥替身）', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
      reducedMotion: 'reduce',
    });
    await cloudBridge(page, { emptyCovers: true });
    const coversReady = () =>
      page.waitForFunction(() => {
        const images = [
          ...document.querySelectorAll('.cloud-playlists .artwork img'),
        ];
        return (
          images.length === 2 &&
          images.every((image) => image.complete && image.naturalWidth > 0)
        );
      });
    await coversReady();
    assert.equal(
      await page.evaluate(() =>
        window.cloudCalls.some((call) => call.payload.operation === 'tracks'),
      ),
      false,
    );
    assert.equal(
      await page.evaluate(
        () =>
          window.cloudCalls.filter((call) => call.payload.operation === 'cover')
            .length,
      ),
      2,
    );
    await page.reload();
    await page.getByRole('button', { name: '资料库', exact: true }).click();
    await coversReady();
    assert.equal(
      await page.evaluate(() =>
        window.cloudCalls.some((call) => call.payload.operation === 'tracks'),
      ),
      false,
    );
  } finally {
    await browser.close();
  }
});

test('云歌单长标题与横竖封面在手机平板始终顶部对齐（原生桥替身）', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    for (const width of [360, 390, 768, 1280]) {
      const page = await browser.newPage({
        viewport: { width, height: 900 },
        reducedMotion: 'reduce',
      });
      await cloudBridge(page);
      await page.locator('.cloud-playlists img').evaluateAll((images) =>
        Promise.all(
          images.map((image) => {
            image.loading = 'eager';
            return image.decode();
          }),
        ),
      );
      const bounds = await page
        .locator('.cloud-playlists .artwork')
        .evaluateAll((elements) =>
          elements.map((element) => {
            const r = element.getBoundingClientRect();
            return { top: r.top, width: r.width, height: r.height };
          }),
        );
      assert.ok(Math.abs(bounds[0].top - bounds[1].top) < 1);
      assert.ok(bounds.every((r) => Math.abs(r.width - r.height) < 1));
      assert.ok(Math.abs(bounds[0].width - bounds[1].width) < 1);
      assert.equal(
        await page.evaluate(
          () => document.documentElement.scrollWidth > innerWidth,
        ),
        false,
      );
      await page.locator('.cloud-playlists').screenshot({
        path: `artifacts/personal-fm/cloud-layout-${width}.png`,
      });
      await page.close();
    }
  } finally {
    await browser.close();
  }
});

test('云歌单编辑、拖动歌曲排序、移除和确认删除（原生桥替身）', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
      reducedMotion: 'reduce',
    });
    await cloudBridge(page);
    await page.locator('.cloud-playlists .playlist-card').first().click();
    await page.locator('.cloud-playlist-detail .track-row').nth(1).waitFor();
    await page.getByRole('button', { name: '编辑歌单', exact: true }).click();
    await page.getByLabel('歌单名称', { exact: true }).fill('改名后的云歌单');
    await page.getByLabel('歌单简介', { exact: true }).fill('测试简介');
    await page
      .getByRole('button', { name: '保存歌单信息', exact: true })
      .click();
    await page
      .getByRole('heading', { name: '改名后的云歌单', exact: true })
      .waitFor();
    await page.getByRole('button', { name: '歌曲排序', exact: true }).click();
    const handles = page.locator('.playlist-order-row .queue-grip');
    const from = await handles.first().boundingBox(),
      to = await handles.nth(1).boundingBox();
    await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2);
    await page.mouse.down();
    await page.mouse.move(to.x + to.width / 2, to.y + to.height / 2, {
      steps: 8,
    });
    await page.mouse.up();
    assert.equal(
      await page.evaluate(
        () =>
          window.cloudCalls.filter(
            (call) => call.payload.operation === 'sortTracks',
          ).length,
      ),
      0,
    );
    await page.getByRole('button', { name: '保存顺序', exact: true }).click();
    await page.locator('.cloud-playlist-detail .track-row').nth(1).waitFor();
    assert.deepEqual(
      await page.evaluate(
        () =>
          window.cloudCalls.findLast(
            (call) => call.payload.operation === 'sortTracks',
          ).payload.fileIds,
      ),
      ['2', '1'],
    );
    await page
      .locator('.cloud-playlist-detail .track-row')
      .first()
      .getByRole('button', { name: '歌曲菜单', exact: true })
      .click();
    await page
      .getByRole('button', { name: '从当前列表移除', exact: true })
      .click();
    await page.waitForFunction(
      () =>
        document.querySelectorAll('.cloud-playlist-detail .track-row')
          .length === 1,
    );
    await page.getByRole('button', { name: '删除歌单', exact: true }).click();
    assert.equal(
      await page.evaluate(() =>
        window.cloudCalls.some((call) => call.payload.operation === 'delete'),
      ),
      false,
    );
    await page
      .getByRole('button', { name: '确认删除歌单', exact: true })
      .click();
    await page.locator('.cloud-playlists').waitFor();
    assert.equal(
      await page.locator('.cloud-playlists .playlist-card').count(),
      1,
    );
  } finally {
    await browser.close();
  }
});

test('外部导入只显示最终成功或失败，截图歌单选择使用主题菜单（原生桥替身）', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
      reducedMotion: 'reduce',
      colorScheme: 'dark',
    });
    await cloudBridge(page);
    await page
      .getByRole('button', { name: '导入外部歌单', exact: true })
      .click();
    await page
      .getByLabel('外部歌单分享链接', { exact: true })
      .fill('https://music.163.com/playlist?id=1');
    await page.getByRole('button', { name: '导入歌单', exact: true }).click();
    await page.getByText('导入成功', { exact: true }).waitFor();
    assert.equal(
      await page.getByText('本机导入记录', { exact: true }).count(),
      0,
    );
    assert.equal(
      await page.getByRole('button', { name: '刷新进度', exact: true }).count(),
      0,
    );
    await page.evaluate(() => (window.importIncomplete = true));
    await page.getByRole('button', { name: '导入歌单', exact: true }).click();
    await page.getByText('导入失败', { exact: true }).waitFor();
    assert.equal(await page.getByText('导入成功', { exact: true }).count(), 0);
    await page.getByRole('button', { name: '歌单截图', exact: true }).click();
    const picker = page.getByRole('combobox', {
      name: '截图导入目标歌单',
      exact: true,
    });
    await picker.click();
    await page.getByRole('option').nth(1).click();
    assert.match(await picker.textContent(), /很长很长/);
    assert.equal(
      await page.locator('.playlist-import-panel select').count(),
      0,
    );
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth > innerWidth,
      ),
      false,
    );
    await picker.click();
    await page
      .locator('.playlist-import-panel')
      .screenshot({ path: 'artifacts/personal-fm/import-picker-dark.png' });
  } finally {
    await browser.close();
  }
});
