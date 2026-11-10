import type { LyricLine, Track } from './types';
type Raw = Record<string, unknown>;
const text = (value: unknown) => String(value ?? '').replace(/<[^>]*>/g, '');
export function cleanTitle(value: string): string {
  return value.replace(/\.(mp3|flac|m4a|ogg|wav|aac|ape)$/i, '').trim();
}
export function sameTrack(left: Track, right: Track): boolean {
  return (
    (!!left.hash && left.hash.toLowerCase() === right.hash.toLowerCase()) ||
    (!!left.audioId &&
      left.audioId !== '0' &&
      left.audioId === right.audioId) ||
    left.id === right.id
  );
}
export function normalize(raw: Raw): Track {
  const base = (raw.audio_info ?? raw) as Raw;
  const info = (raw.base ?? raw) as Raw;
  const authors = (raw.authors ??
    info.authors ??
    raw.singerinfo ??
    raw.Singers ??
    []) as Raw[];
  const author = Array.isArray(authors) ? authors[0] : undefined;
  const albumInfo = (raw.album_info ?? raw.albuminfo ?? {}) as Raw;
  const file = text(
    info.FileName ??
      info.filename ??
      info.file_name ??
      info.audio_name ??
      info.name ??
      raw.name,
  );
  const parts = file.split(' - ');
  const deprecated = (raw.deprecated ?? {}) as Raw;
  const hash =
    [
      base.hash,
      base.Hash,
      base.FileHash,
      base.filehash,
      base.file_hash,
      raw.hash,
      base.hash_128,
      deprecated.hash,
      base.hash_320,
    ]
      .map(text)
      .find((value) => /^[a-f0-9]{32}$/i.test(value))
      ?.toLowerCase() ?? '';
  return {
    id: hash || text(info.album_audio_id ?? info.audio_id),
    hash,
    title: cleanTitle(
      text(
        info.SongName ?? info.songname ?? info.song_name ?? info.audio_name,
      ) ||
        parts.slice(1).join(' - ') ||
        file,
    ),
    artist:
      text(
        info.SingerName ??
          info.singername ??
          info.author_name ??
          author?.author_name ??
          author?.name,
      ) ||
      parts[0] ||
      '未知歌手',
    album: text(
      info.AlbumName ??
        info.album_name ??
        albumInfo.album_name ??
        albumInfo.name,
    ),
    cover:
      [
        albumInfo.sizable_cover,
        albumInfo.cover,
        raw.sizable_cover,
        raw.cover,
        raw.album_cover,
        raw.Image,
        raw.image,
        raw.img,
        raw.backup_img,
        (raw.trans_param as Raw | undefined)?.union_cover,
      ]
        .filter((value): value is string => typeof value === 'string')
        .find((value) => /^https?:\/\//.test(value))
        ?.replaceAll('{size}', '400')
        .replace('http:', 'https:') ?? '',
    albumId: text(
      info.AlbumID ?? info.album_id ?? raw.album_id ?? albumInfo.album_id,
    ),
    audioId: text(
      info.AlbumAudioID ??
        info.MixSongID ??
        info.mixsongid ??
        info.album_audio_id ??
        info.audio_id,
    ),
    duration: Number(base.Duration ?? base.duration ?? base.timelength ?? 0),
    artistId: text(
      author?.author_id ??
        author?.id ??
        info.author_id ??
        (Array.isArray(raw.SingerId) ? raw.SingerId[0] : undefined),
    ),
    original:
      Number(
        info.IsOriginal ??
          info.is_original ??
          raw.IsOriginal ??
          (raw.trans_param as Raw | undefined)?.is_original,
      ) === 1,
    heat: Number(info.HeatLevel ?? info.Heat ?? info.heat ?? 0) || 0,
  };
}
export function parseLrc(source: string): LyricLine[] {
  const lines: LyricLine[] = [];
  const offset = Number(source.match(/\[offset:([+-]?\d+)\]/)?.[1] ?? 0) / 1000;
  for (const line of source.split('\n')) {
    const tags = [...line.matchAll(/\[(\d+):(\d+(?:\.\d+)?)\]/g)];
    const content = line.replace(/\[[^\]]*\]/g, '').trim();
    for (const tag of tags)
      if (content)
        lines.push({
          time: Number(tag[1]) * 60 + Number(tag[2]) + offset,
          text: content,
        });
  }
  return lines.sort((a, b) => a.time - b.time);
}
export function clock(seconds: number): string {
  const value = Math.max(0, Math.floor(seconds || 0));
  return `${Math.floor(value / 60)}:${String(value % 60).padStart(2, '0')}`;
}
