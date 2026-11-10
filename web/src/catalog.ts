import { native } from './bridge';
import type { MusicEntity, EntityKind, Track } from './types';
import { normalize } from './music';
export type Raw = Record<string, unknown>;
const cache = new Map<string, { time: number; value: Raw }>();
const pending = new Map<string, Promise<Raw>>();
export function invalidateCatalog() {
  cache.clear();
}
export async function catalog(
  route: string,
  data: Raw = {},
  refresh = false,
): Promise<Raw> {
  const key = `${route}:${JSON.stringify(data)}`;
  const saved = cache.get(key);
  if (!refresh && saved && Date.now() - saved.time < 5 * 60000)
    return saved.value;
  if (pending.has(key)) return pending.get(key)!;
  const request = native<Raw>('catalog', { route, data })
    .then((value) => {
      cache.set(key, { time: Date.now(), value });
      if (cache.size > 128) cache.delete(cache.keys().next().value!);
      return value;
    })
    .finally(() => pending.delete(key));
  pending.set(key, request);
  return request;
}
export const clean = (value: unknown): string =>
  String(value ?? '').replace(/<[^>]*>/g, '');
export function cover(value: unknown): string {
  return clean(value).replaceAll('{size}', '400').replace('http:', 'https:');
}
export function rows(
  value: unknown,
  keys = [
    'lists',
    'list',
    'info',
    'songs',
    'song_list',
    'songlist',
    'data',
    'authors',
    'albums',
    'special_list',
    'specials',
    'recommend_list',
    'scene_list',
    'scenes',
    'audios',
    'music',
  ],
): Raw[] {
  if (Array.isArray(value))
    return value.filter((item) => item && typeof item === 'object');
  if (!value || typeof value !== 'object') return [];
  const object = value as Raw;
  for (const key of keys)
    if (Array.isArray(object[key])) return rows(object[key]);
  for (const key of ['data', 'result', 'body', 'song_list', 'list']) {
    const found = rows(object[key], keys);
    if (found.length) return found;
  }
  return [];
}
export function tracksFrom(value: unknown): Track[] {
  return [
    ...new Map(
      rows(value)
        .map((value) => normalize((value.song ?? value) as Raw))
        .filter((track) => track.hash)
        .map((track) => [track.id, track]),
    ).values(),
  ];
}
export function entity(raw: Raw, kind: EntityKind): MusicEntity {
  const base = (raw.base ?? raw) as Raw;
  const author = rows(base.authors)[0];
  // 推荐歌单的 pic 是创建者头像，不能覆盖 imgurl / flexible_cover。
  const playlistCovers = [
    base.flexible_cover,
    base.sizable_cover,
    base.imgurl,
    base.cover,
    base.image,
    base.Image,
  ]
    .map(cover)
    .filter(Boolean);
  const covers = [
    ...new Set(
      kind === 'playlist' && playlistCovers.length
        ? playlistCovers
        : [
            base.Avatar,
            base.SingerImg,
            base.Pic,
            base.pic,
            base.img,
            base.imgurl,
            base.Image,
            base.sizable_avatar,
            base.sizable_cover,
            base.cover,
            base.image,
          ]
            .map(cover)
            .filter(Boolean),
    ),
  ];
  const id =
    kind === 'artist'
      ? (base.AuthorId ??
        base.author_id ??
        base.SingerId ??
        base.singerid ??
        base.id)
      : kind === 'album'
        ? (base.AlbumID ?? base.album_id ?? base.albumid ?? base.id)
        : kind === 'rank'
          ? (base.rankid ?? base.rank_id ?? base.id)
          : (base.global_collection_id ??
            base.gid ??
            base.specialid ??
            base.special_id ??
            base.id);
  const name =
    kind === 'artist'
      ? (base.AuthorName ??
        base.author_name ??
        base.SingerName ??
        base.singername)
      : kind === 'album'
        ? (base.AlbumName ?? base.album_name ?? base.albumname)
        : kind === 'rank'
          ? (base.rankname ?? base.rank_name)
          : (base.specialname ?? base.special_name);
  return {
    kind,
    id: clean(id),
    name: clean(name ?? base.name),
    subtitle: clean(
      base.SingerName ??
        base.singername ??
        base.singer ??
        base.author_name ??
        author?.author_name ??
        author?.name ??
        base.nickname ??
        base.username ??
        base.intro,
    ),
    cover: covers[0] || '',
    coverAlternatives: covers.slice(1),
    description: clean(base.intro ?? base.description),
    count:
      Number(
        base.songcount ??
          base.song_count ??
          base.count ??
          base.music_count ??
          base.songs_count,
      ) || undefined,
  };
}
export function entitiesFrom(value: unknown, kind: EntityKind): MusicEntity[] {
  return rows(value)
    .map((row) => entity(row, kind))
    .filter((item) => item.id && item.name);
}
