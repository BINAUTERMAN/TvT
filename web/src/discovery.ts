export interface Category {
  id: number;
  name: string;
}
export interface CategoryGroup extends Category {
  children: Category[];
}
type RecordValue = Record<string, unknown>;
function record(value: unknown): RecordValue | undefined {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as RecordValue)
    : undefined;
}
function category(value: unknown, scene = false): Category | undefined {
  const item = record(value);
  if (!item) return;
  const id = Number(scene ? item.scene_id : item.tag_id);
  const name = String(
    scene
      ? (item.scene_name ?? item.title ?? item.name ?? '')
      : (item.tag_name ?? ''),
  ).trim();
  return id > 0 && name ? { id, name } : undefined;
}
export function playlistGroups(value: unknown): CategoryGroup[] {
  const data = record(value)?.data;
  if (!Array.isArray(data)) return [];
  const groups: CategoryGroup[] = [],
    flat: Category[] = [];
  for (const raw of data) {
    const item = record(raw),
      parent = category(raw);
    if (!item || !parent) continue;
    if (Array.isArray(item.son)) {
      const children = item.son
        .map((child) => category(child))
        .filter((child): child is Category => !!child);
      if (children.length)
        groups.push({
          ...parent,
          children: [
            ...new Map(children.map((child) => [child.id, child])).values(),
          ],
        });
    } else flat.push(parent);
  }
  if (flat.length) groups.push({ id: 0, name: '分类', children: flat });
  return groups;
}
export function songCategories(value: unknown): Category[] {
  const data = record(record(value)?.data),
    list = data?.list;
  if (!Array.isArray(list)) return [];
  return list
    .filter((item) => {
      const value = record(item);
      const count = record(value?.extra)?.song_count;
      return value?.is_publish !== 0 && count !== 0;
    })
    .map((item) => category(item, true))
    .filter((item): item is Category => !!item);
}
