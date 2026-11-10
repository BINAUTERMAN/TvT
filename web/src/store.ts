import { computed, reactive, watch } from 'vue';
import { native } from './bridge';
import type { Playback, Playlist, Track } from './types';
import { sameTrack, cleanTitle } from './music';
import { tracksFrom, invalidateCatalog } from './catalog';
import { resetVip } from './vip';
import { resetCheckin } from './checkin';

function read<T>(key: string, fallback: T): T {
  try {
    return JSON.parse(localStorage.getItem(key) ?? 'null') ?? fallback;
  } catch {
    return fallback;
  }
}
export const library = reactive({
  favorites: read<Track[]>('favorites', []),
  local: read<Track[]>('local', []),
  history: read<Track[]>('history', []),
  playlists: read<Playlist[]>('playlists', []),
  searches: read<string[]>('searches', []).slice(0, 10),
  theme: read<string>('theme', 'system'),
});
watch(
  library,
  () => {
    Object.entries(library).forEach(([key, value]) =>
      localStorage.setItem(key, JSON.stringify(value)),
    );
  },
  { deep: true },
);
export const player = reactive<Playback>({
  queue: [],
  index: 0,
  playing: false,
  loading: false,
  position: 0,
  duration: 0,
  mode: 0,
  sleepAt: 0,
});
export const current = computed(() => player.queue[player.index]);
export const profile = reactive({ loggedIn: false, name: '', avatar: '' });
export const preferences = reactive({
  preloadAudio: true,
  autoVip: true,
  resumeOnLaunch: true,
  floatingLyrics: false,
  lyricsTranslation: true,
});
export interface CacheJob {
  hash: string;
  track: Track;
  state: 'pending' | 'failed';
  message: string;
}
export const musicCache = reactive({ jobs: [] as CacheJob[], bytes: 0 });
const caching = reactive(new Set<string>());
let cacheRefresh: Promise<void> | undefined;
let cacheRefreshAgain = false;
export function cacheState(track: Track): 'ready' | 'pending' | '' {
  if (library.local.some((item) => item.cached && sameTrack(item, track)))
    return 'ready';
  return caching.has(track.hash) ||
    musicCache.jobs.some(
      (job) => job.hash === track.hash && job.state === 'pending',
    )
    ? 'pending'
    : '';
}
export function refreshMusicCache(): Promise<void> {
  if (cacheRefresh) {
    cacheRefreshAgain = true;
    return cacheRefresh;
  }
  cacheRefresh = native<{ tracks: Track[]; jobs: CacheJob[]; bytes: number }>(
    'cacheList',
  )
    .then((result) => {
      if (!Array.isArray(result.tracks) || !Array.isArray(result.jobs)) return;
      for (const job of result.jobs)
        if (
          job.state === 'failed' &&
          musicCache.jobs.some(
            (previous) =>
              previous.hash === job.hash && previous.state === 'pending',
          )
        )
          notify(job.message);
      const added = result.tracks.filter(
        (track) =>
          !library.local.some((item) => item.cached && sameTrack(track, item)),
      );
      library.local = [
        ...result.tracks,
        ...library.local.filter((track) => !track.cached),
      ];
      musicCache.jobs = result.jobs;
      musicCache.bytes = result.bytes;
      if (added.length)
        notify(`已缓存 ${added.length} 首歌曲，可在本地音乐离线播放`);
    })
    .finally(() => {
      cacheRefresh = undefined;
      if (cacheRefreshAgain) {
        cacheRefreshAgain = false;
        void refreshMusicCache().catch((error: Error) => notify(error.message));
      }
    });
  return cacheRefresh;
}
export async function cacheSong(track: Track) {
  if (cacheState(track) === 'pending') return;
  caching.add(track.hash);
  try {
    await native<{ state: string }>('cacheSong', track);
    await refreshMusicCache();
    const failed = musicCache.jobs.find(
      (job) => job.hash === track.hash && job.state === 'failed',
    );
    if (failed) throw new Error(failed.message);
    notify(
      library.local.some((item) => item.cached && sameTrack(item, track))
        ? '歌曲已缓存，可离线播放'
        : '正在缓存，完成后会出现在本地音乐',
    );
  } catch (error) {
    notify((error as Error).message);
  } finally {
    caching.delete(track.hash);
  }
}
export async function removeCache(track: Track) {
  await native('cacheRemove', { hash: track.hash });
  await refreshMusicCache();
}
const pendingLikes = reactive(new Set<string>());
export function isLiked(track: Track) {
  return library.favorites.some((item) => sameTrack(track, item));
}
function updateLike(track: Track, liked: boolean) {
  if (isLiked(track) === liked) return;
  library.favorites = library.favorites.filter(
    (item) => !sameTrack(track, item),
  );
  if (liked) library.favorites.unshift(track);
}
export async function refreshAccount() {
  const result = await native<typeof profile>('account');
  const changed =
    profile.loggedIn !== result.loggedIn || profile.name !== result.name;
  Object.assign(profile, result);
  profile.avatar = (result.avatar || '')
    .replace('http:', 'https:')
    .replaceAll('{size}', '100');
  if (changed) {
    invalidateCatalog();
    resetVip();
    resetCheckin();
  }
  return result;
}
export async function syncFavorites(refresh = false) {
  if (!profile.loggedIn) return;
  const tracks = tracksFrom(await native('favorites', { refresh }));
  library.favorites = [
    ...tracks,
    ...library.favorites.filter((track) => !!track.uri),
  ];
}
export async function updatePreference(
  key: keyof typeof preferences,
  value: boolean,
) {
  try {
    if (key === 'floatingLyrics') await native('overlay', { enabled: value });
    Object.assign(preferences, await native('settings', { [key]: value }));
  } catch (error) {
    notify((error as Error).message);
  }
}
export const toast = reactive({ text: '' });
let toastTimer = 0;
export function notify(text: string) {
  toast.text = text;
  clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => (toast.text = ''), 4000);
}
window.nativePlayback = (state) => {
  const previous = current.value?.id;
  const { queue, ...progress } = state;
  Object.assign(player, progress);
  if (queue)
    player.queue = queue.map((track) => ({
      ...track,
      title: cleanTitle(track.title),
    }));
  if (
    current.value?.hash &&
    state.favoritesReady &&
    !pendingLikes.has(current.value.hash)
  )
    updateLike(current.value, !!state.liked);
  if (state.error) notify(state.error);
  if (current.value && previous !== current.value.id) {
    library.history = [
      current.value,
      ...library.history.filter((t) => t.id !== current.value!.id),
    ].slice(0, 200);
  }
};
window.nativeImported = (tracks) => {
  library.local = [
    ...new Map([...library.local, ...tracks].map((t) => [t.id, t])).values(),
  ];
  notify(`已导入 ${tracks.length} 首本地音乐`);
};
export async function command(action: string, payload: unknown = {}) {
  try {
    await native(action, payload);
  } catch (e) {
    notify((e as Error).message);
  }
}
export function play(tracks: Track[], index = 0) {
  if (tracks.length) void command('play', { tracks, index });
}
export async function toggleFavorite(track: Track) {
  const key = track.hash || track.id;
  if (pendingLikes.has(key)) return;
  const liked = isLiked(track);
  if (!track.hash) {
    updateLike(track, !liked);
    return;
  }
  if (!profile.loggedIn) {
    notify('请先登录酷狗账号，以同步我喜欢');
    return;
  }
  pendingLikes.add(key);
  updateLike(track, !liked);
  try {
    const result = await native<{ liked: boolean }>('favorite', {
      track,
      liked: !liked,
    });
    updateLike(track, result.liked);
  } catch (error) {
    updateLike(track, liked);
    notify((error as Error).message);
  } finally {
    pendingLikes.delete(key);
  }
}
