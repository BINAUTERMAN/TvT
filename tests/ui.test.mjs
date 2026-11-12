import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import fs from 'node:fs/promises';
import ts from 'typescript';
import { pathToFileURL } from 'node:url';

const browserPath =
  process.env.PLAYWRIGHT_EXECUTABLE_PATH ??
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const fixture = JSON.parse(
  await fs.readFile(new URL('./fixtures/search.json', import.meta.url), 'utf8'),
);
const musicSource = await fs.readFile(
  new URL('../web/src/music.ts', import.meta.url),
  'utf8',
);
const musicJs = ts.transpileModule(musicSource, {
  compilerOptions: {
    module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022,
  },
}).outputText;
await fs.mkdir('build/test', { recursive: true });
await fs.writeFile('build/test/music.mjs', musicJs);
const { normalize, parseLrc } = await import(
  pathToFileURL(process.cwd() + '/build/test/music.mjs')
);

const discoveryJs = ts.transpileModule(
  await fs.readFile(
    new URL('../web/src/discovery.ts', import.meta.url),
    'utf8',
  ),
  {
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022,
    },
  },
).outputText;
await fs.writeFile('build/test/discovery.mjs', discoveryJs);
const { playlistGroups } = await import(
  pathToFileURL(process.cwd() + '/build/test/discovery.mjs')
);
const rankFixture = JSON.parse(
  await fs.readFile(new URL('./fixtures/rank.json', import.meta.url), 'utf8'),
);
const tagFixture = JSON.parse(
  await fs.readFile(
    new URL('./fixtures/discovery-tags.json', import.meta.url),
    'utf8',
  ),
);

test('真实榜单标准哈希与分类分组映射', () => {
  const songs = rankFixture.data.songlist
    .map(normalize)
    .filter((track) => track.hash);
  assert.equal(songs.length, rankFixture.data.songlist.length);
  assert.ok(songs.every((track) => track.title && track.artist && track.cover));
  const groups = playlistGroups(tagFixture);
  assert.equal(groups.length, 2);
  assert.equal(groups[0].children.length, 2);
  assert.ok(!groups[0].children.some((item) => item.id === groups[0].id));
});

async function settled(page) {
  await page.waitForFunction(
    () =>
      !document.querySelector(
        '[class*="-enter-active"], [class*="-leave-active"]',
      ),
  );
  await page.evaluate(() =>
    Promise.allSettled(
      document
        .getAnimations()
        .filter(
          (animation) =>
            animation.effect?.getComputedTiming().iterations !== Infinity,
        )
        .map((animation) => animation.finished),
    ),
  );
}

test('真实接口字段映射与多时间标签歌词解析', () => {
  const track = normalize(fixture.data.lists[0]);
  assert.match(track.hash, /^[a-f0-9]{32}$/);
  assert.ok(track.title && track.artist && track.cover.startsWith('https://'));
  assert.equal(
    normalize({
      audio_info: { hash: track.hash },
      backup_img: '',
      album_info: {
        sizable_cover: 'http://imge.kugou.com/test/{size}/album.jpg',
      },
    }).cover,
    'https://imge.kugou.com/test/400/album.jpg',
  );
  assert.deepEqual(
    parseLrc('[offset:100]\n[00:01.50][00:03.00]第一行\n[00:02.00]第二行'),
    [
      { time: 1.6, text: '第一行' },
      { time: 2.1, text: '第二行' },
      { time: 3.1, text: '第一行' },
    ],
  );
});

test('手机、平板交互与主题、歌单持久化', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    for (const [width, height] of [
      [360, 800],
      [390, 844],
      [768, 1024],
      [1280, 800],
    ]) {
      const page = await browser.newPage({
        viewport: { width, height },
        deviceScaleFactor: 1,
      });
      const errors = [];
      page.on('pageerror', (error) => errors.push(error.message));
      await page.addInitScript((data) => {
        let state = {
          queue: [],
          index: 0,
          playing: false,
          loading: false,
          position: 0,
          duration: 300,
          mode: 0,
          sleepAt: 0,
        };
        window.Native = {
          request(id, action, source) {
            const payload = JSON.parse(source);
            let response = true;
            if (action === 'search') response = data;
            if (action === 'account')
              response = { loggedIn: true, name: '测试用户', avatar: '' };
            if (action === 'catalog')
              response = ['daily', 'recommend'].includes(payload.route)
                ? data
                : { data: [] };
            if (action === 'favorites') response = data.data.lists;
            if (action === 'settings')
              response = {
                autoVip: true,
                resumeOnLaunch: true,
                floatingLyrics: false,
              };
            if (action === 'lyricDocument')
              response = {
                lines: [
                  { time: 0, text: '第一行歌词' },
                  { time: 3, text: '第二行歌词' },
                ],
                translationAvailable: false,
              };
            if (action === 'lyrics')
              response = '[00:00.00]第一行歌词\n[00:03.00]第二行歌词';
            if (action === 'play') {
              state.queue = payload.tracks;
              state.index = payload.index;
              state.playing = true;
            }
            if (action === 'toggle') state.playing = !state.playing;
            if (['play', 'toggle', 'state'].includes(action))
              setTimeout(() => window.nativePlayback?.(state));
            setTimeout(() => window.nativeResolve(id, response));
          },
        };
      }, fixture);
      await page.goto('http://127.0.0.1:5173');
      await page.locator('.recommend-grid .album-item').first().waitFor();
      assert.equal(
        await page.evaluate(
          () => document.documentElement.scrollWidth > innerWidth,
        ),
        false,
      );
      await page.screenshot({
        path: `artifacts/home-${width}.png`,
        fullPage: true,
      });
      await page.locator('.recommend-grid .album-item').first().click();
      await page.locator('.mini-info').click();
      await page.locator('.player-screen').waitFor();
      await page
        .locator('.player-screen')
        .evaluate((element) =>
          Promise.allSettled(
            element
              .getAnimations({ subtree: true })
              .map((animation) => animation.finished),
          ),
        );
      await page.screenshot({ path: `artifacts/player-${width}.png` });
      await page.getByRole('button', { name: '收起播放页' }).click();
      await page
        .locator('.topbar')
        .getByRole('button', { name: '账号与设置' })
        .click();
      await page
        .getByRole('dialog')
        .getByRole('button', { name: '偏好设置', exact: true })
        .click();
      await page.getByRole('button', { name: '深色', exact: true }).click();
      assert.equal(
        await page.locator('html').getAttribute('data-theme'),
        'dark',
      );
      await page.getByRole('button', { name: '关闭', exact: true }).click();
      await page
        .locator(width < 700 ? '.bottom-nav' : '.sidebar nav')
        .getByRole('button', { name: '资料库' })
        .click();
      await page.getByRole('button', { name: '创建第一张歌单' }).click();
      await page.getByRole('textbox', { name: '歌单名称' }).fill('通勤音乐');
      await page.getByRole('button', { name: '创建歌单', exact: true }).click();
      await page.getByRole('heading', { name: '通勤音乐' }).waitFor();
      await page.reload();
      assert.ok(
        (
          await page.evaluate(() =>
            JSON.parse(localStorage.getItem('playlists')),
          )
        ).some((p) => p.name === '通勤音乐'),
      );
      assert.deepEqual(errors, []);
      await page.close();
    }
  } finally {
    await browser.close();
  }
});

test('手机验证码登录需主动发送，错误可恢复且不保存手机号', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
    });
    await page.addInitScript((data) => {
      window.testCalls = [];
      let signedIn = false;
      let attempts = 0;
      window.Native = {
        request(id, action) {
          window.testCalls.push(action);
          let result = true;
          let error;
          if (action === 'search') result = data;
          if (action === 'account')
            result = { loggedIn: signedIn, name: '测试用户' };
          if (action === 'loginMobile') {
            attempts++;
            if (attempts === 1) error = '验证码错误，请重新输入';
            else {
              signedIn = true;
              result = { loggedIn: true, name: '测试用户' };
            }
          }
          setTimeout(() => window.nativeResolve(id, result, error));
        },
      };
    }, fixture);
    await page.goto('http://127.0.0.1:5173');
    await page.getByRole('button', { name: '账号与设置' }).click();
    const send = page.getByRole('button', { name: '获取验证码', exact: true });
    assert.equal(await send.isDisabled(), true);
    await page.getByRole('textbox', { name: '手机号码' }).fill('13800000000');
    assert.equal(
      await page.evaluate(() => window.testCalls.includes('sendCode')),
      false,
    );
    await send.click();
    await page.getByText('验证码已发送，请留意短信').waitFor();
    await page.getByRole('textbox', { name: '短信验证码' }).fill('123456');
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.getByRole('alert').getByText('验证码错误，请重新输入').waitFor();
    await settled(page);
    await page.screenshot({ path: 'artifacts/login-390.png' });
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page
      .locator('.account-summary')
      .getByText('测试用户', { exact: true })
      .waitFor();
    assert.equal(
      await page.evaluate(() =>
        JSON.stringify(localStorage).includes('13800000000'),
      ),
      false,
    );
  } finally {
    await browser.close();
  }
});

test('更新选择需要确认，跳过只针对当前版本且可关闭确认提示', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
    });
    await page.addInitScript(() => {
      let choices = {
        enabled: true,
        dontWarn: false,
        skip: 0,
        configured: true,
        currentVersion: '0.3.1',
        repository: 'example/music',
      };
      window.updateChoices = [];
      window.Native = {
        request(id, action, source) {
          const data = JSON.parse(source);
          let result = true;
          if (action === 'account')
            result = { loggedIn: false, name: '', avatar: '' };
          if (action === 'catalog') result = { data: [] };
          if (action === 'updateSettings') {
            Object.assign(choices, data);
            result = { ...choices };
            window.updateChoices.push({ ...data });
          }
          if (action === 'updateCheck')
            result = {
              configured: true,
              available: true,
              versionCode: 100001,
              version: '0.4.0',
              notes: '修复推荐专辑封面\n改善播放过渡',
              ignored: choices.skip === 100001,
            };
          if (action === 'updateStatus') result = { state: 'idle' };
          setTimeout(() => window.nativeResolve(id, result));
        },
      };
    });
    await page.goto('http://127.0.0.1:5173');
    await page.getByText('修复推荐专辑封面', { exact: false }).waitFor();
    await page.getByRole('button', { name: '跳过此版本', exact: true }).click();
    await page
      .getByRole('alertdialog')
      .getByText('你确定要这么做吗，可能会影响某些功能')
      .waitFor();
    await page.getByRole('button', { name: '取消', exact: true }).click();
    assert.equal(
      await page.evaluate(() => window.updateChoices.some((data) => data.skip)),
      false,
    );
    await page.getByRole('button', { name: '跳过此版本', exact: true }).click();
    await page.getByRole('checkbox', { name: '再也不提醒' }).check();
    await page.getByRole('button', { name: '确定', exact: true }).click();
    await page.getByRole('alertdialog').waitFor({ state: 'detached' });
    assert.ok(
      await page.evaluate(() =>
        window.updateChoices.some(
          (data) => data.skip === 100001 && data.dontWarn,
        ),
      ),
    );
    await page.getByRole('checkbox', { name: '自动检查更新' }).uncheck();
    await page.waitForFunction(() =>
      window.updateChoices.some((data) => data.enabled === false),
    );
    assert.equal(await page.getByRole('alertdialog').count(), 0);
  } finally {
    await browser.close();
  }
});

test('发现分类、封面对齐、固定播放栏、音质列表、队列排序与系统主题', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
    });
    const errors = [];
    page.on('pageerror', (error) => errors.push(error.message));
    await page.addInitScript((data) => {
      let state = {
        queue: [],
        index: 0,
        playing: false,
        loading: false,
        position: 0,
        duration: 200,
        mode: 0,
        sleepAt: 0,
        quality: '128',
      };
      window.featureCalls = [];
      window.Native = {
        request(id, action, source) {
          const payload = JSON.parse(source);
          window.featureCalls.push({ action, payload });
          let result = true;
          if (action === 'account')
            result = { loggedIn: true, name: '测试用户', avatar: '' };
          if (action === 'vipStatus')
            result = {
              label: '概念版 VIP',
              active: true,
              expiresAt: 1794365975000,
              rights: [],
              today: '2026-11-10',
              todayClaimed: true,
              todayReceipts: [{ type: 'tvip', label: '畅听 VIP' }],
              claimKnown: true,
              checkedAt: 1794283200000,
            };
          if (action === 'favorites') result = [];
          if (action === 'settings')
            result = {
              autoVip: true,
              resumeOnLaunch: true,
              floatingLyrics: false,
              lyricsTranslation: payload.lyricsTranslation ?? true,
            };
          if (action === 'updateSettings')
            result = { enabled: false, configured: false };
          if (action === 'updateCheck') result = { configured: false };
          if (action === 'updateStatus') result = { state: 'idle' };
          if (action === 'catalog') {
            if (['daily', 'recommend'].includes(payload.route)) result = data;
            else if (payload.route === 'playlists')
              result = {
                data: {
                  special_list: [
                    {
                      global_collection_id: '1',
                      specialname: '短歌单',
                      imgurl: '',
                    },
                    {
                      global_collection_id: '2',
                      specialname: '这是一张标题特别长也不能挤歪封面的歌单名字',
                      imgurl: '',
                    },
                  ],
                },
              };
            else if (payload.route === 'ranks')
              result = {
                data: { info: [{ rankid: 1, rankname: '热歌榜', imgurl: '' }] },
              };
            else if (payload.route === 'rankSongs')
              result = {
                data: {
                  songlist: data.data.lists.slice(0, 2).map((song) => ({
                    songname: song.SongName,
                    author_name: song.SingerName,
                    audio_info: { hash_128: song.FileHash },
                    album_info: { sizable_cover: song.Image },
                  })),
                },
              };
            else if (payload.route === 'tags')
              result = { data: [{ tag_id: 1, tag_name: '华语' }] };
            else if (payload.route === 'scenes')
              result = { data: { list: [{ scene_id: 2, title: '睡前' }] } };
            else if (payload.route === 'sceneSongs')
              result = {
                data: { list: data.data.lists.map((song) => ({ song })) },
              };
            else if (payload.route === 'vip') result = { data: { is_vip: 1 } };
            else result = { data: [] };
          }
          if (action === 'qualityInfo')
            result = [
              {
                quality: '128',
                label: '标准',
                exists: true,
                restricted: false,
                size: 10000,
              },
              {
                quality: '320',
                label: '高品',
                exists: true,
                restricted: true,
                size: 20000,
              },
              {
                quality: 'flac',
                label: '无损 FLAC',
                exists: true,
                restricted: true,
                size: 30000,
              },
              {
                quality: 'high',
                label: 'Hi-Res',
                exists: false,
                restricted: false,
                size: 0,
              },
            ];
          if (action === 'quality') state.quality = payload.quality;
          if (action === 'lyrics') result = '[00:00.00]测试歌词';
          if (action === 'lyricDocument')
            result = {
              lines: [
                { time: 0, text: 'A quiet morning', translation: '安静的清晨' },
                {
                  time: 3,
                  text: 'The day begins',
                  translation: '新的一天开始',
                },
              ],
              translationAvailable: true,
            };
          if (action === 'play') {
            state.queue = payload.tracks.map((track, index) => ({
              ...track,
              title: index === 0 ? track.title + '.mp3' : track.title,
            }));
            state.index = payload.index;
            state.playing = true;
          }
          if (action === 'move') {
            const [track] = state.queue.splice(payload.from, 1);
            state.queue.splice(payload.to, 0, track);
            if (state.index === payload.from) state.index = payload.to;
          }
          if (['play', 'state', 'quality', 'move'].includes(action))
            setTimeout(() =>
              window.nativePlayback?.({ ...state, queue: [...state.queue] }),
            );
          setTimeout(() => window.nativeResolve(id, result));
        },
      };
    }, fixture);
    await page.goto('http://127.0.0.1:5173');
    await page
      .locator('.personal-home .entity-shelf .entity-card')
      .first()
      .waitFor();
    const coverTops = await page
      .locator('.personal-home .entity-shelf .artwork')
      .evaluateAll((nodes) =>
        nodes.map((node) => node.getBoundingClientRect().top),
      );
    assert.ok(Math.abs(coverTops[0] - coverTops[1]) < 1);
    const gallery = page.locator('.personal-home .entity-shelf');
    assert.equal(await gallery.evaluate((node) => node.scrollLeft), 0);
    await page.locator('.recommend-grid .album-item').first().click();
    await page.evaluate(() =>
      window.nativePlayback({
        position: 1,
        playing: true,
        favoritesReady: true,
        liked: false,
      }),
    );
    const before = await page.locator('.bottom-dock').boundingBox();
    await page.locator('main').evaluate((node) => (node.scrollTop = 600));
    const after = await page.locator('.bottom-dock').boundingBox();
    assert.ok(Math.abs(before.y - after.y) < 1);
    await page
      .locator('.bottom-nav')
      .getByRole('button', { name: '发现', exact: true })
      .click();
    const headings = await page.locator('.explore-page h2').allTextContents();
    assert.equal(headings[0], '排行榜');
    await page.locator('.rank-section .entity-card').first().click();
    await page.locator('.detail-page .track-row').first().waitFor();
    assert.equal(await page.locator('.detail-page .track-row').count(), 2);
    await page.getByRole('button', { name: '返回', exact: true }).click();
    await page.getByRole('tab', { name: '歌曲', exact: true }).click();
    await page.getByRole('button', { name: '睡前', exact: true }).waitFor();
    await page.locator('.explore-page .track-row').first().waitFor();
    await settled(page);
    await page.screenshot({ path: 'artifacts/discover-390.png' });
    await page.locator('.mini-info').click();
    await page
      .locator('.player-screen')
      .getByRole('button', { name: '译文', exact: true })
      .click();
    await page
      .locator('.lyric-translation')
      .getByText('安静的清晨', { exact: true })
      .waitFor();
    await settled(page);
    await page.screenshot({ path: 'artifacts/translation-390.png' });
    await page
      .locator('.player-screen')
      .getByRole('button', { name: '译文', exact: true })
      .click();
    await page.waitForFunction(
      () => document.querySelectorAll('.lyric-translation').length === 0,
    );
    await page
      .locator('.player-screen')
      .getByRole('button', { name: '歌词', exact: true })
      .click();
    await page
      .locator('.player-screen')
      .getByRole('button', { name: '标准', exact: true })
      .click();
    await page.getByRole('radiogroup', { name: '播放音质' }).waitFor();
    assert.equal(await page.getByRole('dialog').locator('select').count(), 0);
    assert.equal(
      await page.getByRole('radio', { name: /Hi-Res/ }).isDisabled(),
      true,
    );
    await page.getByRole('radio', { name: /无损 FLAC/ }).click();
    await page.waitForFunction(() =>
      window.featureCalls.some(
        (call) => call.action === 'quality' && call.payload.quality === 'flac',
      ),
    );
    await settled(page);
    await page.screenshot({ path: 'artifacts/quality-390.png' });
    await page.getByRole('button', { name: '关闭', exact: true }).click();
    await page
      .locator('.player-screen')
      .getByRole('button', { name: '播放队列', exact: true })
      .click();
    const firstTitle = await page
      .locator('.queue-track strong')
      .first()
      .textContent();
    assert.ok(!firstTitle.endsWith('.mp3'));
    const handles = page.getByRole('button', { name: '拖动排序' });
    const first = await handles.nth(0).boundingBox(),
      second = await handles.nth(1).boundingBox();
    await page.mouse.move(
      first.x + first.width / 2,
      first.y + first.height / 2,
    );
    await page.mouse.down();
    await page.mouse.move(
      second.x + second.width / 2,
      second.y + second.height / 2,
      { steps: 8 },
    );
    await page.mouse.up();
    await page.waitForFunction(() =>
      window.featureCalls.some(
        (call) =>
          call.action === 'move' &&
          call.payload.from === 0 &&
          call.payload.to === 1,
      ),
    );
    await settled(page);
    await page.screenshot({ path: 'artifacts/queue-390.png' });
    await page.getByRole('button', { name: '关闭', exact: true }).click();
    await page.getByRole('button', { name: '收起播放页' }).click();
    await page.evaluate(() =>
      window.dispatchEvent(
        new CustomEvent('ting:appearance', { detail: { dark: true } }),
      ),
    );
    assert.equal(await page.locator('html').getAttribute('data-theme'), 'dark');
    await page
      .locator('.topbar')
      .getByRole('button', { name: '账号与设置' })
      .click();
    await page
      .locator('.account-summary')
      .getByText('测试用户', { exact: true })
      .waitFor();
    await page
      .locator('.account-summary')
      .getByText('概念版 VIP', { exact: true })
      .waitFor();
    await settled(page);
    await page.screenshot({ path: 'artifacts/account-dark-390.png' });
    assert.deepEqual(errors, []);
  } finally {
    await browser.close();
  }
});

test('空分类给出反馈且不保留上一分类内容，无译文时不伪造翻译', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
    });
    await page.addInitScript(() => {
      window.lyricRequests = 0;
      const state = {
        queue: [
          {
            id: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
            hash: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
            title: 'A sample song',
            artist: 'Test artist',
            album: '',
            cover: '',
            audioId: '1',
            albumId: '1',
            duration: 60,
          },
        ],
        index: 0,
        position: 0,
        duration: 60,
        playing: false,
        loading: false,
        mode: 0,
        sleepAt: 0,
      };
      const settings = {
        lyricsTranslation: true,
        floatingLyrics: false,
        autoVip: false,
        resumeOnLaunch: false,
      };
      window.Native = {
        request(id, action, source) {
          const payload = JSON.parse(source);
          let result = true;
          if (action === 'account')
            result = { loggedIn: false, name: '', avatar: '' };
          if (action === 'settings') {
            Object.assign(settings, payload);
            result = { ...settings };
          }
          if (action === 'updateSettings')
            result = { enabled: false, configured: false };
          if (action === 'updateCheck') result = { configured: false };
          if (action === 'updateStatus') result = { state: 'idle' };
          if (action === 'state')
            setTimeout(() => window.nativePlayback(state));
          if (action === 'lyricDocument') {
            window.lyricRequests++;
            result = {
              lines: [{ time: 0, text: 'A quiet morning' }],
              translationAvailable: false,
            };
          }
          if (action === 'catalog') {
            if (payload.route === 'playlists')
              result =
                payload.data.category === 2
                  ? { data: { special_list: [] } }
                  : {
                      data: {
                        special_list: [
                          {
                            global_collection_id: 'sample',
                            specialname: '初始歌单',
                          },
                        ],
                      },
                    };
            else if (payload.route === 'tags')
              result = {
                data: [
                  {
                    tag_id: 99,
                    tag_name: '分组',
                    son: [
                      { tag_id: 1, tag_name: '有内容' },
                      { tag_id: 2, tag_name: '空分类' },
                    ],
                  },
                ],
              };
            else result = { data: [] };
          }
          setTimeout(() => window.nativeResolve(id, result));
        },
      };
    });
    await page.goto('http://127.0.0.1:5173');
    await page
      .locator('.bottom-nav')
      .getByRole('button', { name: '发现', exact: true })
      .click();
    await page
      .locator('.explore-page')
      .getByText('初始歌单', { exact: true })
      .waitFor();
    await page.getByRole('button', { name: '空分类', exact: true }).click();
    await page.getByText('此分类暂时没有公开歌单', { exact: false }).waitFor();
    assert.equal(
      await page.getByRole('button', { name: '空分类', exact: true }).count(),
      0,
    );
    assert.equal(
      await page
        .locator('.explore-page .entity-card')
        .filter({ hasText: '初始歌单' })
        .count(),
      0,
    );
    await page.locator('.mini-info').click();
    await page
      .locator('.player-screen')
      .getByRole('button', { name: '译文', exact: true })
      .click();
    await page.getByText('平台暂未提供这首歌的译文', { exact: true }).waitFor();
    assert.equal(await page.locator('.lyric-translation').count(), 0);
    assert.equal(await page.evaluate(() => window.lyricRequests), 1);
  } finally {
    await browser.close();
  }
});

test('34175 显示账号选择且已领取 VIP 使用云端状态', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
    });
    await page.addInitScript(() => {
      let loggedIn = false;
      window.loginCalls = [];
      window.Native = {
        request(id, action, source) {
          const payload = JSON.parse(source);
          window.loginCalls.push({ action, payload });
          let result = true;
          if (action === 'account')
            result = { loggedIn, name: loggedIn ? '测试乙' : '', avatar: '' };
          if (action === 'loginMobile')
            result = {
              requiresAccount: true,
              choices: [
                { key: 'choice-a', name: '测试甲', avatar: '' },
                { key: 'choice-b', name: '测试乙', avatar: '' },
              ],
            };
          if (action === 'chooseLoginAccount') {
            loggedIn = true;
            result = { loggedIn: true, name: '测试乙', avatar: '' };
          }
          if (action === 'settings')
            result = {
              autoVip: true,
              resumeOnLaunch: false,
              floatingLyrics: false,
              lyricsTranslation: true,
            };
          if (action === 'vipStatus')
            result = {
              label: '概念版 VIP',
              active: true,
              expiresAt: 1794365975000,
              rights: [
                { label: '概念版 VIP', active: true, expiresAt: 1794365975000 },
                { label: '畅听 VIP', active: true, expiresAt: 1794494340000 },
              ],
              today: '2026-11-10',
              todayClaimed: true,
              todayReceipts: [{ type: 'tvip', label: '畅听 VIP' }],
              claimKnown: true,
              checkedAt: 1794283200000,
            };
          if (action === 'catalog') result = { data: [] };
          if (action === 'favorites') result = [];
          if (action === 'updateSettings')
            result = { enabled: false, configured: false };
          if (action === 'updateCheck') result = { configured: false };
          if (action === 'updateStatus') result = { state: 'idle' };
          setTimeout(() => window.nativeResolve(id, result));
        },
      };
    });
    await page.goto('http://127.0.0.1:5173');
    await page
      .locator('.topbar')
      .getByRole('button', { name: '账号与设置' })
      .click();
    await page.getByRole('textbox', { name: '手机号码' }).fill('13800000000');
    await page.getByRole('textbox', { name: '短信验证码' }).fill('123456');
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.getByLabel('选择酷狗账号').waitFor();
    assert.equal(
      await page.getByRole('textbox', { name: '短信验证码' }).count(),
      0,
    );
    await page.screenshot({ path: 'artifacts/login-choices-390.png' });
    await page.getByRole('button', { name: /测试乙.*账号 2/ }).click();
    await page
      .locator('.account-summary')
      .getByText('概念版 VIP', { exact: true })
      .waitFor();
    assert.deepEqual(
      await page.evaluate(
        () =>
          window.loginCalls.find((item) => item.action === 'chooseLoginAccount')
            .payload,
      ),
      { key: 'choice-b' },
    );
    assert.equal(
      await page.evaluate(() =>
        JSON.stringify(localStorage).includes('123456'),
      ),
      false,
    );
    await page.getByRole('button', { name: '概念版 VIP 与领取记录' }).click();
    await page.getByRole('button', { name: '今日已领取' }).waitFor();
    assert.equal(
      await page.getByRole('button', { name: '今日已领取' }).isDisabled(),
      true,
    );
    assert.equal(await page.locator('.vip-right').count(), 2);
    await page.getByText('今日已领取：畅听 VIP', { exact: true }).waitFor();
    assert.equal(
      await page
        .getByText('高音质权益可前往官方 App 领取。', { exact: true })
        .count(),
      0,
    );
    assert.match(
      await page.locator('.vip-right').first().textContent(),
      /2026.*11.*11.*10:59/,
    );
    await page.screenshot({ path: 'artifacts/vip-390.png' });
    assert.equal(
      await page.evaluate(
        () =>
          window.loginCalls.filter((item) => item.action === 'autoVip').length,
      ),
      0,
    );
  } finally {
    await browser.close();
  }
});

test('VIP 未确认到账不能显示已领取，确认后同步权益', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
    });
    await page.addInitScript(() => {
      window.claimConfirmed = false;
      const status = {
        label: '普通账号',
        active: false,
        expiresAt: 0,
        rights: [],
        today: '2026-11-10',
        todayClaimed: false,
        claimKnown: true,
        checkedAt: 1794283200000,
      };
      const fallback = {
        loggedIn: true,
        auto: true,
        today: '2026-11-10',
        mode: 'listen',
        claims: 0,
        maxClaims: 8,
        done: false,
        nextInMs: 0,
        message: '',
        error: '',
        probe: {
          checked: true,
          ok: false,
          kind: 'changed',
          code: 20001,
          message: '参数错误（20001）',
          checkedAt: 1794283200000,
        },
      };
      window.Native = {
        request(id, action) {
          let result = true;
          if (action === 'account')
            result = { loggedIn: true, name: '测试用户', avatar: '' };
          if (action === 'vipStatus') result = status;
          if (action === 'checkinStatus') result = { ...fallback };
          if (action === 'checkinStep') {
            if (window.claimConfirmed)
              Object.assign(status, {
                label: '概念版 VIP',
                active: true,
                todayClaimed: true,
              });
            result = {
              ...fallback,
              claimed: window.claimConfirmed,
              message: window.claimConfirmed ? '平台已确认今日畅听领取' : '',
              error: window.claimConfirmed
                ? ''
                : '平台记录暂未确认到账，请稍后刷新状态',
              status: { ...status },
            };
          }
          if (action === 'settings') result = { autoVip: false };
          if (action === 'catalog') result = { data: [] };
          if (action === 'favorites') result = [];
          if (action === 'updateSettings')
            result = { enabled: false, configured: false };
          if (action === 'updateCheck') result = { configured: false };
          if (action === 'updateStatus') result = { state: 'idle' };
          setTimeout(() => window.nativeResolve(id, result));
        },
      };
    });
    await page.goto('http://127.0.0.1:5173');
    await page
      .locator('.topbar')
      .getByRole('button', { name: '账号与设置' })
      .click();
    await page.getByRole('button', { name: '概念版 VIP 与领取记录' }).click();
    await page.getByRole('button', { name: '领取一天畅听 VIP' }).click();
    await page
      .getByRole('alert')
      .getByText('平台记录暂未确认到账，请稍后刷新状态', { exact: true })
      .waitFor();
    assert.equal(
      await page.getByRole('button', { name: '今日已领取' }).count(),
      0,
    );
    await page.evaluate(() => {
      window.claimConfirmed = true;
    });
    await page.getByRole('button', { name: '领取一天畅听 VIP' }).click();
    await page.getByRole('button', { name: '今日已领取' }).waitFor();
    await page
      .locator('.vip-status')
      .getByText('概念版 VIP', { exact: true })
      .waitFor();
  } finally {
    await browser.close();
  }
});

test('探活失败提示改用畅听，探活恢复后按次累计签到奖励', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
    });
    await page.addInitScript(() => {
      const status = {
        label: '普通账号',
        active: false,
        expiresAt: 0,
        rights: [],
        todayReceipts: [],
        today: '2026-11-10',
        todayClaimed: false,
        claimKnown: true,
        checkedAt: 1794283200000,
      };
      const state = (probeOk, claims) => ({
        loggedIn: true,
        auto: true,
        today: '2026-11-10',
        mode: probeOk ? 'reward' : 'listen',
        claims,
        maxClaims: 8,
        done: false,
        nextInMs: probeOk ? 42000 : 0,
        message: '',
        error: '',
        probe: {
          checked: true,
          ok: probeOk,
          kind: probeOk ? 'ok' : 'changed',
          code: probeOk ? 20002 : 20001,
          message: probeOk ? '' : '参数错误（20001）',
          checkedAt: 1794283200000,
        },
      });
      window.vipCheckinState = state;
      window.vipProbed = false;
      window.Native = {
        request(id, action) {
          let result = true;
          if (action === 'account')
            result = { loggedIn: true, name: '测试用户', avatar: '' };
          if (action === 'vipStatus') result = status;
          if (action === 'checkinStatus')
            result = window.vipCheckinState(window.vipProbed, 0);
          if (action === 'checkinProbe') {
            window.vipProbed = true;
            result = window.vipCheckinState(true, 0);
          }
          if (action === 'checkinStep')
            result = {
              ...window.vipCheckinState(true, 1),
              claimed: true,
              message: '第 1 次签到奖励已领取',
            };
          if (action === 'catalog') result = { data: [] };
          if (action === 'favorites') result = [];
          if (action === 'settings') result = { autoVip: true };
          if (action === 'updateSettings' || action === 'updateCheck')
            result = { configured: false, enabled: false };
          if (action === 'updateStatus') result = { state: 'idle' };
          setTimeout(() => window.nativeResolve(id, result));
        },
      };
    });
    await page.goto('http://127.0.0.1:5173');
    await page
      .locator('.topbar')
      .getByRole('button', { name: '账号与设置' })
      .click();
    await page.getByRole('button', { name: '概念版 VIP 与领取记录' }).click();
    await page
      .getByText(
        '概念版VIP接口测试失败，无法享受高音质服务，推荐自行前往官方软件领取',
        { exact: true },
      )
      .waitFor();
    await page
      .getByText('平台返回：参数错误（20001）', { exact: true })
      .waitFor();
    await page.getByRole('button', { name: '领取一天畅听 VIP' }).waitFor();
    await page.screenshot({ path: 'artifacts/vip-fallback-390.png' });
    assert.equal(
      await page.getByRole('button', { name: '重新探活接口' }).count(),
      0,
    );
    await page.evaluate(() => {
      window.vipProbed = true;
      window.dispatchEvent(
        new CustomEvent('ting:checkin', {
          detail: window.vipCheckinState(true, 0),
        }),
      );
    });
    await page.getByText('今日签到奖励 0/8', { exact: true }).waitFor();
    assert.equal(
      await page
        .getByText(
          '概念版VIP接口测试失败，无法享受高音质服务，推荐自行前往官方软件领取',
          { exact: true },
        )
        .count(),
      0,
    );
    await page.getByRole('button', { name: '签到领 VIP' }).click();
    await page.getByText('今日签到奖励 1/8', { exact: true }).waitFor();
    await page.getByRole('button', { name: '继续签到（1/8）' }).waitFor();
    await page.getByText('第 1 次签到奖励已领取', { exact: true }).waitFor();
    await page.screenshot({ path: 'artifacts/vip-reward-390.png' });
  } finally {
    await browser.close();
  }
});

test('热门联想、两种排序、歌词搜歌、原唱与十条搜索记录', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
    });
    await page.addInitScript(() => {
      localStorage.setItem(
        'searches',
        JSON.stringify(
          Array.from({ length: 12 }, (_, index) => `旧搜索${index}`),
        ),
      );
      window.searchCalls = [];
      const songs = [
        {
          SongName: '晴天现场',
          SingerName: '测试演唱者',
          HeatLevel: 9,
          IsOriginal: 0,
          FileHash: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
        },
        {
          SongName: '晴天',
          SingerName: '测试原唱',
          HeatLevel: 5,
          IsOriginal: 1,
          FileHash: 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
        },
      ];
      window.Native = {
        request(id, action, source) {
          const payload = JSON.parse(source);
          window.searchCalls.push({ action, payload });
          let result = true;
          if (action === 'account')
            result = { loggedIn: false, name: '', avatar: '' };
          if (action === 'settings') result = { autoVip: false };
          if (action === 'updateSettings')
            result = { enabled: false, configured: false };
          if (action === 'updateCheck') result = { configured: false };
          if (action === 'updateStatus') result = { state: 'idle' };
          if (action === 'catalog') {
            if (payload.route === 'searchSuggest')
              result = {
                data: [
                  {
                    LableName: '',
                    RecordDatas: [
                      { HintInfo: '晴天翻唱', Hot: 5 },
                      { HintInfo: '晴天', Hot: 100 },
                    ],
                  },
                ],
              };
            else if (payload.route === 'searchHot')
              result = {
                data: { list: [{ keywords: [{ keyword: '热门词条' }] }] },
              };
            else if (payload.route === 'search')
              result = {
                data: {
                  lists: ['song', 'lyric'].includes(payload.data.type)
                    ? songs
                    : [],
                },
              };
            else result = { data: [] };
          }
          setTimeout(() => window.nativeResolve(id, result));
        },
      };
    });
    await page.goto('http://127.0.0.1:5173');
    await page
      .locator('.bottom-nav')
      .getByRole('button', { name: '搜索', exact: true })
      .click();
    assert.equal(
      await page
        .locator('.search-page .chips')
        .first()
        .locator('button')
        .count(),
      10,
    );
    const input = page.getByRole('combobox', { name: '搜索歌曲或歌手' });
    assert.equal(
      await page.getByText('只记得歌词？用歌词找歌', { exact: true }).count(),
      0,
    );
    await input.fill('晴天');
    await page.waitForFunction(() =>
      document
        .querySelector('.app-shell')
        ?.classList.contains('search-editing'),
    );
    await page.waitForFunction(
      () =>
        getComputedStyle(document.querySelector('.bottom-dock')).visibility ===
        'hidden',
    );
    assert.equal(
      await page
        .locator('.bottom-dock')
        .evaluate((element) => getComputedStyle(element).visibility),
      'hidden',
    );
    await page.getByRole('listbox', { name: '热门搜索联想' }).waitFor();
    assert.match(await page.getByRole('option').first().textContent(), /晴天/);
    assert.ok(
      !(await page.getByRole('option').first().textContent()).includes('翻唱'),
    );
    await page.screenshot({ path: 'artifacts/search-suggestions-390.png' });
    await input.press('ArrowUp');
    assert.equal(
      await input.getAttribute('aria-activedescendant'),
      'suggestion-1',
    );
    await input.press('ArrowDown');
    await input.press('Enter');
    await page.locator('.search-page .track-row').first().waitFor();
    assert.match(
      await page.locator('.search-page .track-row').first().textContent(),
      /晴天现场/,
    );
    await page.getByRole('button', { name: '匹配优先', exact: true }).click();
    await settled(page);
    assert.match(
      await page.locator('.search-page .track-row').first().textContent(),
      /测试原唱/,
    );
    assert.equal(await page.locator('.search-page .original-tag').count(), 1);
    for (const [width, height] of [
      [360, 800],
      [390, 844],
      [768, 1024],
      [1280, 800],
    ]) {
      await page.setViewportSize({ width, height });
      const tabsFit = await page
        .locator('.search-types')
        .evaluate((element) => {
          const bounds = element.getBoundingClientRect();
          return [...element.querySelectorAll('button')].every((button) => {
            const rect = button.getBoundingClientRect();
            return (
              rect.left >= bounds.left &&
              rect.right <= bounds.right + 1 &&
              button.scrollWidth <= button.clientWidth
            );
          });
        });
      assert.equal(tabsFit, true, `Search tabs overflow at ${width}`);
    }
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByRole('tab', { name: '歌词搜歌', exact: true }).click();
    await page.getByText('歌词匹配歌曲', { exact: true }).waitFor();
    assert.ok(
      await page.evaluate(() =>
        window.searchCalls.some(
          (call) =>
            call.payload.route === 'search' &&
            call.payload.data.type === 'lyric',
        ),
      ),
    );
    assert.equal(
      await page.evaluate(
        () => JSON.parse(localStorage.getItem('searches')).length,
      ),
      10,
    );
    await settled(page);
    await page.screenshot({ path: 'artifacts/search-lyrics-390.png' });
    await input.fill('晴天');
    await input.focus();
    await page.getByRole('listbox', { name: '热门搜索联想' }).waitFor();
    await input.fill('新词');
    const before = await page.evaluate(
      () =>
        window.searchCalls.filter(
          (call) => call.payload.route === 'searchSuggest',
        ).length,
    );
    await input.blur();
    await page.waitForFunction(
      () =>
        !document
          .querySelector('.app-shell')
          ?.classList.contains('search-editing'),
    );
    await page
      .locator('.bottom-nav')
      .getByRole('button', { name: '发现', exact: true })
      .click();
    await page.waitForTimeout(350);
    assert.equal(
      await page.evaluate(
        () =>
          window.searchCalls.filter(
            (call) => call.payload.route === 'searchSuggest',
          ).length,
      ),
      before,
    );
  } finally {
    await browser.close();
  }
});

test('通知未授权引导、拒绝反馈和取消后不再提醒', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
    });
    await page.addInitScript(() => {
      window.notificationOpens = 0;
      window.Native = {
        request(id, action, source) {
          const payload = JSON.parse(source);
          let result = true;
          if (
            action === 'notificationStatus' ||
            action === 'notificationOpen'
          ) {
            if (Object.hasOwn(payload, 'dontRemind'))
              localStorage.setItem(
                'test-notification-muted',
                String(payload.dontRemind),
              );
            result = {
              enabled: false,
              permission: false,
              lockscreen: 'disabled',
              floating: 'disabled',
              needsAttention: true,
              dontRemind:
                localStorage.getItem('test-notification-muted') === 'true',
            };
            if (action === 'notificationOpen') {
              window.notificationOpens++;
              setTimeout(() =>
                window.dispatchEvent(
                  new CustomEvent('ting:notification-result', {
                    detail: result,
                  }),
                ),
              );
            }
          }
          if (action === 'account')
            result = { loggedIn: false, name: '', avatar: '' };
          if (action === 'settings') result = { autoVip: false };
          if (action === 'catalog') result = { data: [] };
          if (action === 'updateSettings')
            result = { enabled: false, configured: false };
          if (action === 'updateCheck') result = { configured: false };
          if (action === 'updateStatus') result = { state: 'idle' };
          setTimeout(() => window.nativeResolve(id, result));
        },
      };
    });
    await page.goto('http://127.0.0.1:5173');
    await page.getByRole('dialog', { name: 'notifications' }).waitFor();
    await page
      .getByText('你未开启锁屏通知和悬浮通知，这会影响体验', { exact: true })
      .waitFor();
    await page.getByRole('button', { name: '开启', exact: true }).click();
    assert.equal(await page.evaluate(() => window.notificationOpens), 1);
    await page
      .getByText('你未开启锁屏通知和悬浮通知，这会影响体验', { exact: true })
      .waitFor();
    await page.screenshot({
      path: 'artifacts/notification-permission-390.png',
    });
    const actionBoxes = await page
      .locator('.notification-panel .confirmation-actions > button')
      .evaluateAll((items) =>
        items.map((item) => {
          const rect = item.getBoundingClientRect();
          return {
            x: rect.x,
            y: rect.y,
            width: rect.width,
            height: rect.height,
          };
        }),
      );
    assert.equal(actionBoxes.length, 3);
    assert.ok(
      Math.max(...actionBoxes.map((item) => item.y)) -
        Math.min(...actionBoxes.map((item) => item.y)) <
        1,
    );
    assert.ok(
      Math.max(...actionBoxes.map((item) => item.height)) -
        Math.min(...actionBoxes.map((item) => item.height)) <
        1,
    );
    assert.ok(
      Math.max(...actionBoxes.map((item) => item.width)) -
        Math.min(...actionBoxes.map((item) => item.width)) <
        1,
    );
    await page.getByRole('checkbox', { name: '不再提醒', exact: true }).check();
    await page.getByRole('button', { name: '取消', exact: true }).click();
    await page
      .getByRole('dialog', { name: 'notifications' })
      .waitFor({ state: 'detached' });
    assert.equal(
      await page.getByRole('dialog', { name: 'notifications' }).count(),
      0,
    );
    await page.reload();
    await page.waitForTimeout(250);
    assert.equal(
      await page.getByRole('dialog', { name: 'notifications' }).count(),
      0,
    );
  } finally {
    await browser.close();
  }
});

test('歌曲缓存完成后进入本地音乐，空搜索历史收起整段区域', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
    });
    await page.addInitScript((data) => {
      const cache = { tracks: [], jobs: [], bytes: 0 };
      window.delayCacheRead = false;
      window.cacheCalls = [];
      window.finishCache = () => {
        cache.tracks = cache.jobs.map((job) => ({
          ...job.track,
          uri: `ting-cache://${job.hash}`,
          cached: true,
          cacheQuality: '128',
        }));
        cache.jobs = [];
        cache.bytes = 100000;
        window.dispatchEvent(new Event('ting:cache-changed'));
      };
      window.Native = {
        request(id, action, source) {
          const payload = JSON.parse(source);
          window.cacheCalls.push({ action, payload });
          let result = true;
          if (action === 'account')
            result = { loggedIn: false, name: '', avatar: '' };
          if (action === 'settings') result = { autoVip: false };
          if (action === 'cacheList') {
            result = JSON.parse(JSON.stringify(cache));
            if (window.delayCacheRead) {
              window.delayCacheRead = false;
              setTimeout(() => window.nativeResolve(id, result), 100);
              return;
            }
          }
          if (action === 'cacheSong') {
            cache.jobs = [
              {
                hash: payload.hash,
                track: payload,
                state: 'pending',
                message: '',
              },
            ];
            result = { state: 'pending' };
          }
          if (action === 'cacheRemove') {
            cache.tracks = cache.tracks.filter(
              (track) => track.hash !== payload.hash,
            );
            result = true;
          }
          if (action === 'catalog') {
            if (payload.route === 'searchHot')
              result = {
                data: { list: [{ keywords: [{ keyword: '热搜示例' }] }] },
              };
            else if (payload.route === 'search' && payload.data.type === 'song')
              result = data;
            else result = { data: [] };
          }
          if (action === 'updateSettings')
            result = { configured: false, enabled: false };
          if (action === 'updateCheck') result = { configured: false };
          if (action === 'updateStatus') result = { state: 'idle' };
          setTimeout(() => window.nativeResolve(id, result));
        },
      };
    }, fixture);
    await page.goto('http://127.0.0.1:5173');
    await page
      .locator('.bottom-nav')
      .getByRole('button', { name: '搜索', exact: true })
      .click();
    await page.getByText('热搜示例', { exact: true }).waitFor();
    assert.equal(
      await page
        .getByRole('heading', { name: '最近搜索', exact: true })
        .count(),
      0,
    );
    assert.equal(
      await page
        .getByText('搜索你喜欢的歌手，发现专辑和歌单。', { exact: true })
        .count(),
      0,
    );
    const hotGap = await page.evaluate(
      () =>
        document
          .querySelector('.search-page .section-heading')
          .getBoundingClientRect().top -
        document.querySelector('.search-box').getBoundingClientRect().bottom,
    );
    assert.ok(hotGap < 65, `Empty history left a gap of ${hotGap}`);
    await page.getByRole('combobox', { name: '搜索歌曲或歌手' }).fill('纯音乐');
    await page
      .locator('.search-box')
      .getByRole('button', { name: '搜索', exact: true })
      .click();
    await page
      .locator('.search-page .track-row')
      .first()
      .getByRole('button', { name: '歌曲菜单' })
      .click();
    assert.equal(
      await page.getByRole('button', { name: '下载歌曲', exact: true }).count(),
      0,
    );
    await page.getByRole('button', { name: '缓存歌曲', exact: true }).click();
    await page
      .getByText('正在缓存，完成后会出现在本地音乐', { exact: true })
      .waitFor();
    await page
      .locator('.bottom-nav')
      .getByRole('button', { name: '资料库', exact: true })
      .click();
    await page.getByRole('button', { name: /本地音乐/ }).click();
    await page
      .getByText('正在缓存，完成后即可离线播放', { exact: true })
      .waitFor();
    assert.equal(await page.locator('.library-page .track-row').count(), 0);
    await page.evaluate(() => {
      window.delayCacheRead = true;
      window.dispatchEvent(new Event('ting:cache-changed'));
      setTimeout(() => window.finishCache(), 10);
    });
    await page
      .locator('.library-page .track-row')
      .first()
      .getByText('已缓存', { exact: true })
      .waitFor();
    await page.locator('.library-page .track-main').first().click();
    assert.ok(
      await page.evaluate(() =>
        window.cacheCalls.some(
          (call) =>
            call.action === 'play' &&
            call.payload.tracks[0].uri.startsWith('ting-cache://'),
        ),
      ),
    );
    await page
      .locator('.library-page .track-row')
      .first()
      .getByRole('button', { name: '歌曲菜单' })
      .click();
    await page.getByRole('button', { name: '删除缓存', exact: true }).click();
    await page.waitForFunction(
      () => JSON.parse(localStorage.getItem('local') || '[]').length === 0,
    );
    await page.reload();
    await page
      .locator('.bottom-nav')
      .getByRole('button', { name: '搜索', exact: true })
      .click();
    await page.getByRole('button', { name: '清空', exact: true }).click();
    assert.equal(
      await page
        .getByRole('heading', { name: '最近搜索', exact: true })
        .count(),
      0,
    );
    await page.getByText('热搜示例', { exact: true }).waitFor();
    await page.screenshot({ path: 'artifacts/search-empty-history-390.png' });
  } finally {
    await browser.close();
  }
});

test('精选歌单优先使用真实封面，失败后切换备用封面，不使用创建者头像', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
    });
    const requested = [];
    await page.route('https://covers.test/**', async (route) => {
      requested.push(route.request().url());
      if (route.request().url().includes('missing'))
        await route.fulfill({ status: 404, body: '' });
      else
        await route.fulfill({
          contentType: 'image/svg+xml',
          body: '<svg xmlns="http://www.w3.org/2000/svg" width="40" height="40"><rect width="40" height="40" fill="teal"/></svg>',
        });
    });
    await page.addInitScript(() => {
      window.Native = {
        request(id, action, source) {
          let value = true;
          const data = JSON.parse(source);
          if (action === 'account') value = { loggedIn: false };
          if (action === 'settings') value = { autoVip: false };
          if (action === 'catalog')
            value =
              data.route === 'playlists'
                ? {
                    data: {
                      special_list: [
                        {
                          specialid: 1,
                          specialname: '真实封面',
                          pic: 'https://covers.test/avatar.jpg',
                          flexible_cover:
                            'http://covers.test/{size}/primary.jpg',
                          imgurl: 'https://covers.test/backup.jpg',
                        },
                        {
                          specialid: 2,
                          specialname: '备用封面',
                          pic: 'https://covers.test/avatar.jpg',
                          flexible_cover: 'https://covers.test/missing.jpg',
                          imgurl: 'https://covers.test/fallback.jpg',
                        },
                      ],
                    },
                  }
                : { data: [] };
          if (action === 'updateSettings' || action === 'updateCheck')
            value = { configured: false, enabled: false };
          if (action === 'updateStatus') value = { state: 'idle' };
          setTimeout(() => window.nativeResolve(id, value));
        },
      };
    });
    await page.goto('http://127.0.0.1:5173');
    const cards = page.locator('.personal-home .entity-card');
    await cards.nth(1).scrollIntoViewIfNeeded();
    await page.waitForFunction(
      () =>
        [...document.querySelectorAll('.personal-home .entity-card img')]
          .length === 2 &&
        [...document.querySelectorAll('.personal-home .entity-card img')].every(
          (img) => img.naturalWidth > 0,
        ),
    );
    assert.equal(
      await cards.nth(0).locator('img').getAttribute('src'),
      'https://covers.test/400/primary.jpg',
    );
    assert.equal(
      await cards.nth(1).locator('img').getAttribute('src'),
      'https://covers.test/fallback.jpg',
    );
    assert.ok(requested.some((url) => url.includes('missing')));
    assert.ok(!requested.some((url) => url.includes('avatar')));
  } finally {
    await browser.close();
  }
});

test('概念版权益有效时停用签到，预缓存开关交由原生层保存', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
    });
    await page.addInitScript(() => {
      const settings = {
        preloadAudio: true,
        autoVip: false,
        resumeOnLaunch: false,
        floatingLyrics: false,
      };
      window.preferenceCalls = [];
      window.Native = {
        request(id, action, source) {
          const payload = JSON.parse(source);
          window.preferenceCalls.push({ action, payload });
          let value = true;
          if (action === 'account')
            value = { loggedIn: true, name: '测试用户', avatar: '' };
          if (action === 'settings') {
            Object.assign(settings, payload);
            value = { ...settings };
          }
          if (action === 'vipStatus')
            value = {
              label: '概念版 VIP',
              active: true,
              expiresAt: 1794365975000,
              rights: [
                {
                  type: 'svip',
                  label: '概念版 VIP',
                  active: true,
                  expiresAt: 1794365975000,
                },
              ],
              todayReceipts: [],
              today: '2026-11-10',
              todayClaimed: false,
              claimKnown: true,
              checkedAt: 1794283200000,
            };
          if (action === 'catalog') value = { data: [] };
          if (action === 'favorites') value = [];
          if (action === 'updateSettings' || action === 'updateCheck')
            value = { configured: false, enabled: false };
          if (action === 'updateStatus') value = { state: 'idle' };
          setTimeout(() => window.nativeResolve(id, value));
        },
      };
    });
    await page.goto('http://127.0.0.1:5173');
    await page
      .locator('.topbar')
      .getByRole('button', { name: '账号与设置' })
      .click();
    await page.getByRole('button', { name: '概念版 VIP 与领取记录' }).click();
    const claim = page.getByRole('button', {
      name: '概念版 VIP 生效中',
      exact: true,
    });
    await claim.waitFor();
    assert.equal(await claim.isDisabled(), true);
    await page
      .getByText('概念版 VIP 有效期间，跳过畅听领取。', { exact: true })
      .waitFor();
    assert.ok((await page.locator('.vip-panel').innerText()).length < 180);
    await settled(page);
    await page.screenshot({ path: 'artifacts/vip-compact-390.png' });
    assert.equal(
      await page.evaluate(
        () =>
          window.preferenceCalls.filter((item) => item.action === 'autoVip')
            .length,
      ),
      0,
    );
    await page.getByRole('button', { name: '关闭', exact: true }).click();
    await page
      .locator('.topbar')
      .getByRole('button', { name: '账号与设置' })
      .click();
    await page.getByRole('button', { name: '偏好设置', exact: true }).click();
    const toggle = page
      .getByText('预缓存后续歌曲', { exact: true })
      .locator('..')
      .getByRole('checkbox');
    await toggle.uncheck();
    await page.waitForFunction(() =>
      window.preferenceCalls.some(
        (item) =>
          item.action === 'settings' && item.payload.preloadAudio === false,
      ),
    );
    assert.equal(await toggle.isChecked(), false);
  } finally {
    await browser.close();
  }
});

test('播放五首后的 Star 提醒排队显示，两种选择均只出现一次', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    for (const choice of ['下次一定', '这就去！']) {
      const page = await browser.newPage({
        viewport: { width: 390, height: 844 },
      });
      await page.addInitScript(() => {
        window.starEligible = false;
        window.starCalls = [];
        window.Native = {
          request(id, action, source) {
            const payload = JSON.parse(source);
            window.starCalls.push({ action, payload });
            let value = true;
            if (action === 'account') value = { loggedIn: false };
            if (action === 'catalog') value = { data: [] };
            if (action === 'settings')
              value = { autoVip: false, preloadAudio: true };
            if (action === 'updateSettings' || action === 'updateCheck')
              value = { configured: false, enabled: false };
            if (action === 'updateStatus') value = { state: 'idle' };
            if (action === 'starStatus')
              value = {
                eligible:
                  window.starEligible &&
                  !localStorage.getItem('test-star-shown'),
              };
            if (action === 'starConsume') {
              value =
                window.starEligible && !localStorage.getItem('test-star-shown');
              if (value) localStorage.setItem('test-star-shown', 'true');
            }
            setTimeout(() => window.nativeResolve(id, value));
          },
        };
      });
      await page.goto('http://127.0.0.1:5173');
      assert.equal(await page.locator('.star-prompt').count(), 0);
      await page
        .locator('.topbar')
        .getByRole('button', { name: '账号与设置' })
        .click();
      await page.getByRole('button', { name: '偏好设置', exact: true }).click();
      await page.evaluate(() => {
        window.starEligible = true;
        window.dispatchEvent(new Event('ting:star-ready'));
      });
      await page.waitForFunction(() =>
        window.starCalls.some((item) => item.action === 'starStatus'),
      );
      assert.equal(await page.locator('.star-prompt').count(), 0);
      await page
        .getByText('桌面歌词支持大部分自定义内容。', {
          exact: true,
        })
        .waitFor();
      assert.equal(
        await page
          .getByText('基于 MoeKoeMusic 的接口生态重新设计。', { exact: false })
          .count(),
        0,
      );
      await page.getByRole('button', { name: '关闭', exact: true }).click();
      await page.locator('.star-prompt').waitFor();
      await page
        .getByText(
          '你已经使用本软件播放5首歌了，如果喜欢TvT点颗Star ⭐怎么样？',
          { exact: true },
        )
        .waitFor();
      await settled(page);
      const boxes = await page
        .locator('.star-actions button')
        .evaluateAll((items) =>
          items.map((item) => {
            const box = item.getBoundingClientRect();
            return { width: box.width, height: box.height, top: box.top };
          }),
        );
      assert.equal(boxes[0].width, boxes[1].width);
      assert.equal(boxes[0].height, boxes[1].height);
      assert.equal(boxes[0].top, boxes[1].top);
      await settled(page);
      await page.screenshot({
        path: `artifacts/star-prompt-${choice === '下次一定' ? 'later' : 'open'}-390.png`,
      });
      await page.getByRole('button', { name: choice, exact: true }).click();
      await page.locator('.star-prompt').waitFor({ state: 'detached' });
      await page.evaluate(() =>
        window.dispatchEvent(new Event('ting:star-ready')),
      );
      await page.waitForFunction(
        () =>
          window.starCalls.filter((item) => item.action === 'starStatus')
            .length >= 3,
      );
      assert.equal(await page.locator('.star-prompt').count(), 0);
      assert.equal(
        await page.evaluate(
          () =>
            window.starCalls.filter((item) => item.action === 'starConsume')
              .length,
        ),
        1,
      );
      assert.equal(
        await page.evaluate(
          () =>
            window.starCalls.filter((item) => item.action === 'openRepository')
              .length,
        ),
        choice === '这就去！' ? 1 : 0,
      );
      await page.reload();
      await page.waitForFunction(() =>
        window.starCalls.some((item) => item.action === 'starStatus'),
      );
      assert.equal(await page.locator('.star-prompt').count(), 0);
      await page.close();
    }
  } finally {
    await browser.close();
  }
});
