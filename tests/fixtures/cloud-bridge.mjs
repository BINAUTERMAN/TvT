export async function cloudBridge(page, options = {}) {
  await page.addInitScript(
    ({ emptyCovers, loggedIn }) => {
      const song = (index) => ({
        id: String(index).repeat(32),
        hash: String(index).repeat(32),
        title: `测试歌曲${index}`,
        artist: '测试歌手',
        album: '',
        cover: `https://covers.test/${index}`,
        albumId: '1',
        audioId: String(index),
        duration: 240,
        fileId: String(index),
      });
      window.cloudCalls = [];
      window.cloudFailure = '';
      window.importIncomplete = false;
      window.importFailure = '';
      window.cloudFixture = {
        accountKey: 'test-scope',
        totalVersion: 1,
        playlists: [
          {
            id: 'remote-0-10',
            listId: '10',
            type: 0,
            name: '短歌单',
            description: '',
            cover: 'https://covers.test/0',
            count: 2,
            isDefault: false,
            sort: 1,
            version: 1,
            globalId: '10',
          },
          {
            id: 'remote-0-11',
            listId: '11',
            type: 0,
            name: '这是一张用于检查多行标题排版的很长很长的云端歌单',
            description: '',
            cover: 'https://covers.test/1',
            count: 1,
            isDefault: false,
            sort: 2,
            version: 1,
            globalId: '11',
          },
        ],
      };
      if (emptyCovers)
        window.cloudFixture.playlists.forEach((list) => {
          list.cover = '';
        });
      window.cloudTracks = {
        'remote-0-10': [song(1), song(2)],
        'remote-0-11': [song(3)],
      };
      window.cloudPlayback = {
        queue: [song(1)],
        index: 0,
        playing: true,
        loading: false,
        position: 0,
        duration: 240,
        mode: 0,
        sleepAt: 0,
      };
      window.Native = {
        request(id, action, source) {
          const payload = JSON.parse(source);
          window.cloudCalls.push({ action, payload });
          let value = true,
            error = '',
            delay = 10;
          const state = window.cloudFixture;
          if (action === 'account')
            value = { loggedIn, name: loggedIn ? '测试账号' : '', avatar: '' };
          if (action === 'settings')
            value = {
              autoVip: false,
              preloadAudio: false,
              resumeOnLaunch: false,
            };
          if (action === 'favorites') value = [];
          if (action === 'catalog') value = { data: [] };
          if (action === 'cacheList')
            value = { tracks: [], jobs: [], bytes: 0 };
          if (action === 'starStatus') value = { eligible: false };
          if (action === 'notificationStatus')
            value = { enabled: true, needsAttention: false };
          if (action.startsWith('update'))
            value = { configured: false, enabled: false, state: 'idle' };
          if (action === 'state') window.nativePlayback(window.cloudPlayback);
          if (action === 'lyricDocument')
            value = { lines: [], translationAvailable: false };
          if (action === 'cloudPlaylist') {
            const operation = payload.operation;
            if (
              window.cloudFailure &&
              operation !== 'list' &&
              operation !== 'tracks'
            )
              error = window.cloudFailure;
            else {
              if (operation === 'create') {
                const createdId = 'remote-0-12';
                state.playlists.push({
                  id: createdId,
                  listId: '12',
                  type: 0,
                  name: payload.name,
                  description: '',
                  cover: '',
                  count: 0,
                  isDefault: false,
                  sort: 3,
                  version: 0,
                  globalId: '12',
                });
                window.cloudTracks[createdId] = [];
                state.createdId = createdId;
              }
              if (operation === 'update')
                Object.assign(
                  state.playlists.find((list) => list.id === payload.id),
                  { name: payload.name, description: payload.description },
                );
              if (operation === 'delete')
                state.playlists = state.playlists.filter(
                  (list) => list.id !== payload.id,
                );
              if (operation === 'sortLists')
                state.playlists.sort(
                  (a, b) =>
                    payload.ids.indexOf(a.id) - payload.ids.indexOf(b.id),
                );
              if (operation === 'sortTracks')
                window.cloudTracks[payload.id].sort(
                  (a, b) =>
                    payload.fileIds.indexOf(a.fileId) -
                    payload.fileIds.indexOf(b.fileId),
                );
              if (operation === 'removeTracks')
                window.cloudTracks[payload.id] = window.cloudTracks[
                  payload.id
                ].filter((song) => !payload.fileIds.includes(song.fileId));
              if (operation === 'addTracks')
                window.cloudTracks[payload.id].push(
                  ...payload.tracks.map((track) => ({
                    ...track,
                    fileId: '99',
                  })),
                );
              value = [
                'tracks',
                'sortTracks',
                'removeTracks',
                'addTracks',
              ].includes(operation)
                ? {
                    accountKey: state.accountKey,
                    totalVersion: state.totalVersion,
                    id: payload.id,
                    listVersion: 1,
                    tracks: window.cloudTracks[payload.id],
                    count: window.cloudTracks[payload.id].length,
                  }
                : JSON.parse(JSON.stringify(state));
              if (operation === 'cover')
                value = {
                  id: payload.id,
                  cover: window.cloudTracks[payload.id]?.[0]?.cover || '',
                };
            }
          }
          if (action === 'importPlaylist') {
            if (payload.operation === 'pending') value = { pending: false };
            else {
              delay = 250;
              error = window.importFailure;
              value = window.importIncomplete
                ? { state: 3 }
                : {
                    success: true,
                    cloudId: 'remote-0-10',
                    name: '短歌单',
                    count: 1,
                    missed: 0,
                  };
            }
          }
          if (action === 'importPlaylistImage') error = '已取消选择截图';
          setTimeout(() => window.nativeResolve(id, value, error), delay);
        },
      };
    },
    { emptyCovers: !!options.emptyCovers, loggedIn: !options.loggedOut },
  );
  await page.route('https://covers.test/**', (route) => {
    const vertical = route.request().url().endsWith('1');
    return route.fulfill({
      contentType: 'image/svg+xml',
      body: `<svg xmlns="http://www.w3.org/2000/svg" width="${vertical ? 300 : 1600}" height="${vertical ? 1600 : 300}"><rect width="100%" height="100%" fill="#6e84a3"/></svg>`,
    });
  });
  await page.goto('http://127.0.0.1:5173');
  await page.getByRole('button', { name: '资料库', exact: true }).click();
  if (options.loggedOut)
    await page
      .getByRole('button', { name: '登录后使用歌单', exact: true })
      .waitFor();
  else await page.locator('.cloud-playlists .playlist-card').nth(1).waitFor();
}
