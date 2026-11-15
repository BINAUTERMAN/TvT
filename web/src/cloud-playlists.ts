import { reactive, watch } from 'vue';
import { native } from './bridge';
import { profile } from './store';
import type { Track } from './types';

export interface CloudPlaylist {
  id: string;
  listId: string;
  name: string;
  description: string;
  cover: string;
  count: number;
  type: 0 | 1;
  isDefault: boolean;
  isSystem?: boolean;
  sort: number;
  version: number;
  globalId: string;
  sourceId?: string;
}
export interface CloudTrack extends Track {
  fileId: string;
}
interface CloudLists {
  accountKey: string;
  totalVersion: number;
  playlists: CloudPlaylist[];
  createdId?: string;
}
export interface CloudTracks {
  totalVersion?: number;
  accountKey: string;
  id: string;
  listVersion: number;
  tracks: CloudTrack[];
  count: number;
}
export const cloudLibrary = reactive({
  accountKey: '',
  totalVersion: 0,
  playlists: [] as CloudPlaylist[],
  tracks: new Map<string, CloudTracks>(),
  busy: false,
  loaded: false,
  error: '',
});
let epoch = 0;
let loading: Promise<void> | undefined;

function reset() {
  epoch++;
  loading = undefined;
  cloudLibrary.accountKey = '';
  cloudLibrary.playlists = [];
  cloudLibrary.tracks.clear();
  cloudLibrary.error = '';
  cloudLibrary.loaded = false;
  cloudLibrary.busy = false;
}
watch(() => [profile.loggedIn, profile.name], reset);

function applyLists(value: CloudLists) {
  if (!Array.isArray(value.playlists) || !value.accountKey)
    throw new Error('云歌单返回内容异常，请重试');
  if (cloudLibrary.accountKey !== value.accountKey) cloudLibrary.tracks.clear();
  Object.assign(cloudLibrary, {
    accountKey: value.accountKey,
    totalVersion: value.totalVersion,
    playlists: value.playlists,
    loaded: true,
  });
  const ids = new Set(value.playlists.map((list) => list.id));
  for (const id of cloudLibrary.tracks.keys())
    if (!ids.has(id)) cloudLibrary.tracks.delete(id);
}

export function loadCloudPlaylists(): Promise<void> {
  if (loading) return loading;
  if (!profile.loggedIn) return Promise.resolve();
  const ticket = epoch;
  cloudLibrary.busy = true;
  cloudLibrary.error = '';
  const request = native<CloudLists>('cloudPlaylist', { operation: 'list' })
    .then((value) => {
      if (ticket === epoch) applyLists(value);
    })
    .catch((cause: Error) => {
      if (ticket === epoch) cloudLibrary.error = cause.message;
      throw cause;
    })
    .finally(() => {
      if (ticket === epoch) {
        cloudLibrary.busy = false;
        loading = undefined;
      }
    });
  loading = request;
  return request;
}

async function request<T>(
  operation: string,
  payload: Record<string, unknown>,
): Promise<T> {
  if (!profile.loggedIn) throw new Error('请先登录酷狗账号');
  if (!cloudLibrary.loaded) await loadCloudPlaylists();
  if (cloudLibrary.busy) throw new Error('正在同步云歌单，请稍候');
  const ticket = epoch;
  cloudLibrary.busy = true;
  cloudLibrary.error = '';
  try {
    const value = await native<T>('cloudPlaylist', {
      operation,
      accountKey: cloudLibrary.accountKey,
      totalVersion: cloudLibrary.totalVersion,
      ...payload,
    });
    if (ticket !== epoch) throw new Error('账号已变化，请重新打开云歌单');
    return value;
  } catch (cause) {
    if (ticket === epoch) cloudLibrary.error = (cause as Error).message;
    throw cause;
  } finally {
    if (ticket === epoch) cloudLibrary.busy = false;
  }
}

export async function changeCloudLists(
  operation: 'create' | 'collect' | 'update' | 'delete' | 'sortLists',
  payload: Record<string, unknown>,
): Promise<string> {
  const result = await request<CloudLists>(operation, payload);
  applyLists(result);
  return result.createdId || '';
}
export async function loadCloudTracks(id: string): Promise<CloudTracks> {
  const value = await request<CloudTracks>('tracks', { id });
  if (
    value.accountKey !== cloudLibrary.accountKey ||
    !Array.isArray(value.tracks)
  )
    throw new Error('账号或云歌单已变化，请重新加载');
  cloudLibrary.tracks.set(id, value);
  if (typeof value.totalVersion === 'number')
    cloudLibrary.totalVersion = value.totalVersion;
  const list = cloudLibrary.playlists.find((item) => item.id === id);
  if (list) list.count = value.count;
  return value;
}
export async function changeCloudTracks(
  operation: 'addTracks' | 'removeTracks' | 'sortTracks',
  id: string,
  payload: Record<string, unknown>,
): Promise<void> {
  const snapshot = cloudLibrary.tracks.get(id) ?? (await loadCloudTracks(id));
  const value = await request<CloudTracks>(operation, {
    id,
    listVersion: snapshot.listVersion,
    ...payload,
  });
  if (
    value.accountKey !== cloudLibrary.accountKey ||
    !Array.isArray(value.tracks)
  )
    throw new Error('云歌单返回内容异常，请刷新后确认');
  cloudLibrary.tracks.set(id, value);
  const list = cloudLibrary.playlists.find((item) => item.id === id);
  if (typeof value.totalVersion === 'number')
    cloudLibrary.totalVersion = value.totalVersion;
  if (list) list.count = value.count;
}
