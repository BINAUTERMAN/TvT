import { clean, rows } from './catalog';
import type { Raw } from './catalog';

export type SearchOrder = 'heat' | 'match';
export interface Suggestion {
  keyword: string;
  type: 'song' | 'album';
  heat: number;
}
function heat(value: unknown): number {
  const text = String(value ?? '').replaceAll(',', '');
  const numeric = Number.parseFloat(text) || 0;
  return numeric * (text.includes('亿') ? 1e8 : text.includes('万') ? 1e4 : 1);
}
export function suggestionsFrom(value: Raw): Suggestion[] {
  const groups = Array.isArray(value.data) ? (value.data as Raw[]) : [];
  return groups
    .flatMap((group) => {
      const type = String(group.LableName ?? '').includes('专辑')
        ? 'album'
        : 'song';
      return rows(group.RecordDatas)
        .map((item) => ({
          keyword: clean(item.HintInfo).trim(),
          type: type as Suggestion['type'],
          heat: heat(item.Hot),
        }))
        .filter((item) => item.keyword)
        .sort((a, b) => b.heat - a.heat)
        .slice(0, type === 'song' ? 8 : 2);
    })
    .filter(
      (item, index, list) =>
        list.findIndex((other) => other.keyword === item.keyword) === index,
    )
    .slice(0, 10);
}
export function hotWordsFrom(value: Raw): string[] {
  const data = value.data as Raw | undefined;
  const first = rows(data?.list)[0];
  return [
    ...new Set(
      rows(first?.keywords)
        .map((item) => clean(item.keyword).trim())
        .filter(Boolean),
    ),
  ].slice(0, 10);
}
function matchScore(item: Raw, query: string): number {
  const key = query.toLocaleLowerCase().replace(/\s+/g, '');
  const values = [
    item.SongName,
    item.SingerName,
    item.AuthorName,
    item.AlbumName,
    item.specialname,
    item.name,
    item.Lyric,
  ]
    .map((value) => clean(value).toLocaleLowerCase().replace(/\s+/g, ''))
    .filter(Boolean);
  return Math.max(
    0,
    ...values.map((value) =>
      value === key
        ? 4
        : value.startsWith(key)
          ? 3
          : value.includes(key)
            ? 2
            : 0,
    ),
  );
}
export function sortSearchRows(
  list: Raw[],
  query: string,
  order: SearchOrder,
): Raw[] {
  return list
    .map((item, index) => ({
      item,
      index,
      match: matchScore(item, query),
      heat: heat(
        item.HeatLevel ??
          item.Heat ??
          item.heat ??
          item.OwnerCount ??
          item.playcount,
      ),
    }))
    .sort((a, b) => {
      const primary = order === 'heat' ? b.heat - a.heat : b.match - a.match;
      return (
        primary ||
        (order === 'match' ? b.heat - a.heat : b.match - a.match) ||
        a.index - b.index
      );
    })
    .map(({ item }) => item);
}
