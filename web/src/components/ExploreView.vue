<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { catalog, entitiesFrom, rows, tracksFrom } from '../catalog';
import { playlistGroups, songCategories } from '../discovery';
import type { Category, CategoryGroup } from '../discovery';
import type { MusicEntity, Track } from '../types';
import EntityGrid from './EntityGrid.vue';
import TrackList from './TrackList.vue';
import ContentTransition from './ContentTransition.vue';
const emit = defineEmits<{
  open: [entity: MusicEntity];
  menu: [track: Track];
}>();
const lists = ref<MusicEntity[]>([]),
  ranks = ref<MusicEntity[]>([]),
  tracks = ref<Track[]>([]);
const groups = ref<CategoryGroup[]>([]),
  scenes = ref<Category[]>([]);
const mode = ref<'lists' | 'songs'>('lists');
const group = ref(0),
  category = ref(0),
  scene = ref(0);
const busy = ref(false),
  rankBusy = ref(false),
  error = ref(''),
  rankError = ref(''),
  categoryError = ref('');
const currentGroup = computed(() =>
  groups.value.find((item) => item.id === group.value),
);
const tags = computed(() => currentGroup.value?.children ?? []);
const hiddenLists = new Set<number>();
const hiddenScenes = new Set<number>();
const emptyNotice = ref('');
const selectedName = computed(() =>
  mode.value === 'lists'
    ? (tags.value.find((item) => item.id === category.value)?.name ??
      '精选歌单')
    : (scenes.value.find((item) => item.id === scene.value)?.name ??
      '分类歌曲'),
);
const empty = computed(
  () =>
    !busy.value &&
    !error.value &&
    (mode.value === 'lists' ? !lists.value.length : !tracks.value.length),
);
let version = 0;
async function load(refresh = false) {
  const request = ++version;
  busy.value = true;
  error.value = '';
  lists.value = [];
  tracks.value = [];
  emptyNotice.value = '';
  try {
    if (mode.value === 'lists') {
      const value = await catalog(
        'playlists',
        { category: category.value },
        refresh,
      );
      if (request === version) {
        lists.value = entitiesFrom(value, 'playlist');
        if (!lists.value.length && category.value > 0) hideEmptyCategory();
      }
    } else if (scene.value) {
      const value = await catalog(
        'sceneSongs',
        { id: String(scene.value) },
        refresh,
      );
      if (request === version) {
        tracks.value = tracksFrom(value);
        if (!tracks.value.length) hideEmptyCategory();
      }
    } else error.value = '歌曲分类暂未加载，请重试';
  } catch (cause) {
    if (request === version) error.value = (cause as Error).message;
  } finally {
    if (request === version) busy.value = false;
  }
}
function hideEmptyCategory() {
  const name = selectedName.value;
  if (mode.value === 'lists') {
    hiddenLists.add(category.value);
    groups.value = groups.value
      .map((item) => ({
        ...item,
        children: item.children.filter((child) => !hiddenLists.has(child.id)),
      }))
      .filter((item) => item.children.length);
  } else {
    hiddenScenes.add(scene.value);
    scenes.value = scenes.value.filter((item) => !hiddenScenes.has(item.id));
  }
  emptyNotice.value = `“${name}”暂无可用内容，已隐藏此标签，请选择其他分类。`;
}
async function loadRanks(refresh = false) {
  rankBusy.value = true;
  rankError.value = '';
  try {
    const value = await catalog('ranks', {}, refresh);
    const direct = entitiesFrom(value, 'rank');
    ranks.value = direct.length
      ? direct
      : rows(value).flatMap((item) => entitiesFrom(item, 'rank'));
    if (!ranks.value.length) rankError.value = '暂未获取到音乐榜单';
  } catch (cause) {
    rankError.value = (cause as Error).message;
  } finally {
    rankBusy.value = false;
  }
}
async function loadCategories(refresh = false) {
  categoryError.value = '';
  try {
    if (mode.value === 'lists') {
      groups.value = playlistGroups(await catalog('tags', {}, refresh))
        .map((item) => ({
          ...item,
          children: item.children.filter((child) => !hiddenLists.has(child.id)),
        }))
        .filter((item) => item.children.length);
      if (!groups.value.some((item) => item.id === group.value))
        group.value = groups.value[0]?.id ?? 0;
      if (!groups.value.length) categoryError.value = '歌单分类暂未加载';
    } else {
      scenes.value = songCategories(
        await catalog('scenes', {}, refresh),
      ).filter((item) => !hiddenScenes.has(item.id));
      if (!scenes.value.some((item) => item.id === scene.value))
        scene.value = scenes.value[0]?.id ?? 0;
      if (!scenes.value.length) categoryError.value = '歌曲分类暂未加载';
    }
  } catch (cause) {
    categoryError.value = (cause as Error).message;
  }
}
async function chooseMode(value: 'lists' | 'songs') {
  version++;
  busy.value = true;
  error.value = '';
  lists.value = [];
  tracks.value = [];
  mode.value = value;
  await loadCategories();
  if (mode.value === value) void load();
}
function select(id: number) {
  if (mode.value === 'lists') category.value = id;
  else scene.value = id;
  void load();
}
function selectGroup(id: number) {
  group.value = id;
  category.value =
    groups.value.find((item) => item.id === id)?.children[0]?.id ?? 0;
  void load();
}
async function retry() {
  hiddenLists.clear();
  hiddenScenes.clear();
  await loadCategories(true);
  void load(true);
}
onMounted(() => {
  void loadRanks();
  void loadCategories();
  void load();
});
</script>
<template>
  <section class="page explore-page">
    <h1>发现</h1>
    <section class="music-section rank-section">
      <div class="section-heading"><h2>排行榜</h2></div>
      <ContentTransition :identity="rankBusy ? 'loading' : 'ready'">
        <EntityGrid
          v-if="ranks.length"
          :items="ranks.slice(0, 18)"
          shelf
          @open="emit('open', $event)"
        />
        <p v-if="rankBusy" class="muted">正在获取音乐榜单…</p>
        <div v-if="rankError" class="inline-error">
          {{ rankError }}
          <button class="text-button" @click="loadRanks(true)">重试</button>
        </div>
      </ContentTransition>
    </section>
    <section class="music-section">
      <div class="section-heading"><h2>分类浏览</h2></div>
      <div
        class="segmented discover-types"
        role="tablist"
        aria-label="发现内容类型"
      >
        <button
          role="tab"
          :aria-selected="mode === 'lists'"
          :class="{ selected: mode === 'lists' }"
          @click="chooseMode('lists')"
        >
          歌单
        </button>
        <button
          role="tab"
          :aria-selected="mode === 'songs'"
          :class="{ selected: mode === 'songs' }"
          @click="chooseMode('songs')"
        >
          歌曲
        </button>
      </div>
      <div
        v-if="mode === 'lists'"
        class="category-groups"
        role="tablist"
        aria-label="歌单分类分组"
      >
        <button
          v-for="item in groups"
          :key="item.id"
          role="tab"
          :aria-selected="group === item.id"
          :class="{ selected: group === item.id }"
          @click="selectGroup(item.id)"
        >
          {{ item.name }}
        </button>
      </div>
      <div class="chips category-tags">
        <button
          v-if="mode === 'lists'"
          :class="{ selected: category === 0 }"
          :aria-pressed="category === 0"
          @click="select(0)"
        >
          全部
        </button>
        <button
          v-for="tag in mode === 'lists' ? tags : scenes"
          :key="tag.id"
          :class="{
            selected: (mode === 'lists' ? category : scene) === tag.id,
          }"
          :aria-pressed="(mode === 'lists' ? category : scene) === tag.id"
          @click="select(tag.id)"
        >
          {{ tag.name }}
        </button>
      </div>
      <div v-if="categoryError" class="inline-error">
        {{ categoryError }}
        <button class="text-button" @click="retry">重新加载分类</button>
      </div>
    </section>
    <section class="music-section">
      <div class="section-heading">
        <h2>{{ selectedName }}</h2>
      </div>
      <ContentTransition
        :identity="`${mode}:${category}:${scene}:${busy ? 'loading' : 'ready'}`"
      >
        <EntityGrid
          v-if="mode === 'lists'"
          :items="lists"
          @open="emit('open', $event)"
        />
        <TrackList v-else :tracks="tracks" @menu="emit('menu', $event)" />
        <div v-if="empty" class="empty-inline">
          <p v-if="emptyNotice">{{ emptyNotice }}</p>
          <p>
            {{
              mode === 'lists'
                ? '此分类暂时没有公开歌单'
                : '此分类暂时没有可播放歌曲'
            }}，可以换个标签或重新加载。
          </p>
          <button class="text-button" @click="load(true)">重新加载</button>
        </div>
      </ContentTransition>
    </section>
    <p v-if="busy" class="loading">正在加载音乐…</p>
    <div v-if="error" class="inline-error">
      {{ error }}
      <button class="text-button" @click="retry">重试</button>
    </div>
  </section>
</template>
