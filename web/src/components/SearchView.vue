<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { Search, X, ChevronRight } from 'lucide-vue-next';
import { catalog, entitiesFrom, rows } from '../catalog';
import type { Raw } from '../catalog';
import { normalize } from '../music';
import { library } from '../store';
import type { MusicEntity, Track } from '../types';
import Artwork from './Artwork.vue';
import TrackList from './TrackList.vue';
import EntityGrid from './EntityGrid.vue';
import { hotWordsFrom, suggestionsFrom, sortSearchRows } from '../search';
import type { Suggestion, SearchOrder } from '../search';
type SearchType = 'all' | 'song' | 'author' | 'album' | 'special' | 'lyric';
const props = defineProps<{
  request?: { keyword: string; type?: SearchType; version: number };
  active?: boolean;
}>();
const emit = defineEmits<{
  open: [entity: MusicEntity];
  menu: [track: Track];
  focus: [active: boolean];
}>();
const query = ref('');
const submitted = ref('');
const selected = ref<SearchType>('all');
const busy = ref(false);
const results = ref<Record<string, Raw[]>>({});
const errors = ref<Record<string, string>>({});
const page = ref(1);
const order = ref<SearchOrder>('heat');
const suggestions = ref<Suggestion[]>([]);
const hotWords = ref<string[]>([]);
const focused = ref(false);
const searchInput = ref<HTMLInputElement>();
let keyboardWasVisible = false;
let focusHeight = 0;
let focusWidth = 0;
const activeSuggestion = ref(-1);
let suggestTimer = 0;
let suggestVersion = 0;
const showSuggestions = computed(
  () => focused.value && suggestions.value.length > 0,
);
let version = 0;
const tabs: { id: SearchType; label: string }[] = [
  { id: 'all', label: '综合' },
  { id: 'song', label: '歌曲' },
  { id: 'author', label: '歌手' },
  { id: 'album', label: '专辑' },
  { id: 'special', label: '歌单' },
  { id: 'lyric', label: '歌词' },
];
const sorted = (type: string) =>
  sortSearchRows(results.value[type] ?? [], submitted.value, order.value);
const songs = computed(() =>
  sorted(selected.value === 'lyric' ? 'lyric' : 'song')
    .map(normalize)
    .filter((track) => track.hash),
);
const artists = computed(() => entitiesFrom(sorted('author'), 'artist'));
const albums = computed(() => entitiesFrom(sorted('album'), 'album'));
const playlists = computed(() => entitiesFrom(sorted('special'), 'playlist'));
const best = computed(() =>
  artists.value.find((artist) => artist.name === submitted.value),
);
const hasMore = computed(
  () =>
    selected.value !== 'all' &&
    (results.value[selected.value]?.length ?? 0) >= page.value * 30,
);
async function search(more = false) {
  const keyword = query.value.trim();
  if (!keyword) return;
  stopSuggestions();
  focused.value = false;
  searchInput.value?.blur();
  const currentVersion = ++version;
  busy.value = true;
  if (!more) {
    page.value = 1;
    if (keyword !== submitted.value) results.value = {};
    errors.value = {};
  }
  submitted.value = keyword;
  library.searches = [
    keyword,
    ...library.searches.filter((item) => item !== keyword),
  ].slice(0, 10);
  const types =
    selected.value === 'all'
      ? ['song', 'author', 'album', 'special']
      : [selected.value];
  await Promise.allSettled(
    types.map(async (type) => {
      try {
        const response = await catalog('search', {
          keyword,
          type,
          page: page.value,
        });
        if (version !== currentVersion) return;
        results.value[type] = more
          ? [...(results.value[type] ?? []), ...rows(response)]
          : rows(response);
        errors.value[type] = '';
      } catch (error) {
        if (version === currentVersion)
          errors.value[type] = (error as Error).message;
      }
    }),
  );
  if (version === currentVersion) busy.value = false;
}
function choose(type: SearchType) {
  selected.value = type;
  page.value = 1;
  if (submitted.value && (type === 'all' || !results.value[type]))
    void search();
}
function searchFor(keyword: string) {
  query.value = keyword;
  void search();
}
function stopSuggestions() {
  clearTimeout(suggestTimer);
  suggestVersion++;
  suggestions.value = [];
  activeSuggestion.value = -1;
}
function scheduleSuggestions() {
  stopSuggestions();
  const keyword = query.value.trim();
  if (!keyword || !focused.value || document.hidden || props.active === false)
    return;
  const request = suggestVersion;
  suggestTimer = window.setTimeout(async () => {
    try {
      const response = await catalog('searchSuggest', { keyword });
      if (
        request === suggestVersion &&
        !document.hidden &&
        props.active !== false
      )
        suggestions.value = suggestionsFrom(response);
    } catch {
      /* 联想失败不阻断正常搜索。 */
    }
  }, 220);
}
function focusSearch() {
  focused.value = true;
  focusHeight = window.visualViewport?.height ?? window.innerHeight;
  focusWidth = window.visualViewport?.width ?? window.innerWidth;
  scheduleSuggestions();
}
function blurSearch() {
  focused.value = false;
  stopSuggestions();
}
function chooseSuggestion(item: Suggestion) {
  selected.value = item.type === 'album' ? 'album' : 'all';
  searchFor(item.keyword);
}
function keydown(event: KeyboardEvent) {
  if (event.isComposing) return;
  if (event.key === 'Escape') {
    event.stopPropagation();
    searchInput.value?.blur();
    blurSearch();
    return;
  }
  if (!showSuggestions.value) return;
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault();
    const direction = event.key === 'ArrowDown' ? 1 : -1;
    activeSuggestion.value =
      activeSuggestion.value < 0
        ? direction > 0
          ? 0
          : suggestions.value.length - 1
        : (activeSuggestion.value + direction + suggestions.value.length) %
          suggestions.value.length;
  } else if (event.key === 'Enter' && activeSuggestion.value >= 0) {
    event.preventDefault();
    chooseSuggestion(suggestions.value[activeSuggestion.value]!);
  }
}
function clearSearch() {
  query.value = '';
  submitted.value = '';
  results.value = {};
  stopSuggestions();
}
function loadMore() {
  page.value++;
  void search(true);
}
function visibility() {
  if (document.hidden) stopSuggestions();
}
function keyboardChanged(event: Event) {
  const visible = (event as CustomEvent<{ visible: boolean }>).detail.visible;
  if (visible) keyboardWasVisible = true;
  else if (keyboardWasVisible && focused.value) {
    keyboardWasVisible = false;
    searchInput.value?.blur();
    blurSearch();
  }
}
function viewportChanged() {
  if (!focused.value || !window.visualViewport) return;
  const viewport = window.visualViewport;
  if (Math.abs(viewport.width - focusWidth) > 40) {
    focusHeight = viewport.height;
    focusWidth = viewport.width;
    keyboardWasVisible = false;
  } else if (viewport.height < focusHeight - 120) keyboardWasVisible = true;
  else if (keyboardWasVisible && viewport.height >= focusHeight - 50) {
    keyboardWasVisible = false;
    searchInput.value?.blur();
    blurSearch();
  }
}
watch(focused, (active) => emit('focus', active));
watch(query, scheduleSuggestions);
watch(
  () => props.active,
  (active) => {
    if (!active) {
      stopSuggestions();
      focused.value = false;
    }
  },
);
onMounted(() => {
  window.addEventListener('ting:keyboard', keyboardChanged);
  window.visualViewport?.addEventListener('resize', viewportChanged);
  document.addEventListener('visibilitychange', visibility);
  void catalog('searchHot')
    .then((value) => {
      if (!document.hidden) hotWords.value = hotWordsFrom(value);
    })
    .catch(() => {});
});
onUnmounted(() => {
  emit('focus', false);
  window.removeEventListener('ting:keyboard', keyboardChanged);
  window.visualViewport?.removeEventListener('resize', viewportChanged);
  stopSuggestions();
  version++;
  document.removeEventListener('visibilitychange', visibility);
});
watch(
  () => props.request,
  (request) => {
    if (request) {
      query.value = request.keyword;
      selected.value = request.type ?? 'all';
      void search();
    }
  },
  { immediate: true },
);
</script>
<template>
  <section class="page search-page">
    <h1>搜索</h1>
    <form class="search-box" @submit.prevent="search()">
      <Search :size="22" />
      <input
        ref="searchInput"
        v-model="query"
        aria-label="搜索歌曲或歌手"
        :placeholder="
          selected === 'lyric' ? '输入记得的一句歌词' : '歌曲、歌手、专辑或歌词'
        "
        enterkeyhint="search"
        role="combobox"
        aria-autocomplete="list"
        :aria-expanded="showSuggestions"
        aria-controls="search-suggestions"
        :aria-activedescendant="
          activeSuggestion >= 0 ? `suggestion-${activeSuggestion}` : undefined
        "
        @focus="focusSearch"
        @blur="blurSearch"
        @keydown="keydown"
      />
      <button
        v-if="query"
        class="icon"
        type="button"
        aria-label="清空"
        @click="clearSearch"
      >
        <X :size="18" />
      </button>
      <button class="search-submit">搜索</button>
    </form>
    <Transition name="suggestions">
      <div
        v-if="showSuggestions"
        id="search-suggestions"
        class="search-suggestions"
        role="listbox"
        aria-label="热门搜索联想"
      >
        <button
          v-for="(item, index) in suggestions"
          :id="`suggestion-${index}`"
          :key="item.keyword"
          type="button"
          role="option"
          :aria-selected="activeSuggestion === index"
          :class="{ active: activeSuggestion === index }"
          @pointerdown.prevent
          @click="chooseSuggestion(item)"
        >
          <Search :size="17" />
          <span>{{ item.keyword }}</span>
          <small>
            {{ item.type === 'album' ? '专辑' : index < 3 ? '热门' : '' }}
          </small>
        </button>
      </div>
    </Transition>
    <template v-if="submitted">
      <div class="segmented search-types" role="tablist" aria-label="搜索类型">
        <button
          v-for="item in tabs"
          :key="item.id"
          role="tab"
          :aria-label="item.id === 'lyric' ? '歌词搜歌' : item.label"
          :aria-selected="selected === item.id"
          :class="{ selected: selected === item.id }"
          @click="choose(item.id)"
        >
          {{ item.label }}
        </button>
      </div>
      <div class="search-order" aria-label="搜索结果排序">
        <button
          :aria-pressed="order === 'heat'"
          :class="{ selected: order === 'heat' }"
          @click="order = 'heat'"
        >
          热度优先
        </button>
        <button
          :aria-pressed="order === 'match'"
          :class="{ selected: order === 'match' }"
          @click="order = 'match'"
        >
          匹配优先
        </button>
        <small class="muted">按已加载结果排序</small>
      </div>
      <button
        v-if="selected === 'all' && best"
        class="artist-match"
        @click="emit('open', best)"
      >
        <Artwork :src="best.cover" :seed="best.name" />
        <span>
          <small>歌手</small>
          <strong>{{ best.name }}</strong>
          <span>查看歌手主页</span>
        </span>
        <ChevronRight :size="21" />
      </button>
      <template v-if="selected === 'all' || selected === 'author'">
        <div
          v-if="(artists.length && !best) || selected === 'author'"
          class="section-heading"
        >
          <h2>歌手</h2>
          <button
            v-if="selected === 'all'"
            class="text-button"
            @click="choose('author')"
          >
            全部
            <ChevronRight :size="16" />
          </button>
        </div>
        <EntityGrid
          v-if="selected === 'author' || (selected === 'all' && !best)"
          :items="selected === 'all' ? artists.slice(0, 4) : artists"
          @open="emit('open', $event)"
        />
      </template>
      <template
        v-if="selected === 'all' || selected === 'song' || selected === 'lyric'"
      >
        <div v-if="songs.length" class="section-heading">
          <h2>{{ selected === 'lyric' ? '歌词匹配歌曲' : '歌曲' }}</h2>
          <button
            v-if="selected === 'all'"
            class="text-button"
            @click="choose('song')"
          >
            全部
            <ChevronRight :size="16" />
          </button>
        </div>
        <TrackList
          :tracks="selected === 'all' ? songs.slice(0, 6) : songs"
          @menu="emit('menu', $event)"
        />
      </template>
      <template v-if="selected === 'all' || selected === 'album'">
        <div v-if="albums.length" class="section-heading">
          <h2>专辑</h2>
          <button
            v-if="selected === 'all'"
            class="text-button"
            @click="choose('album')"
          >
            全部
            <ChevronRight :size="16" />
          </button>
        </div>
        <EntityGrid
          :items="selected === 'all' ? albums.slice(0, 4) : albums"
          @open="emit('open', $event)"
        />
      </template>
      <template v-if="selected === 'all' || selected === 'special'">
        <div v-if="playlists.length" class="section-heading">
          <h2>公开歌单</h2>
          <button
            v-if="selected === 'all'"
            class="text-button"
            @click="choose('special')"
          >
            全部
            <ChevronRight :size="16" />
          </button>
        </div>
        <EntityGrid
          :items="selected === 'all' ? playlists.slice(0, 4) : playlists"
          @open="emit('open', $event)"
        />
      </template>
      <p v-if="busy" class="loading" role="status">正在查找音乐…</p>
      <p
        v-if="!busy && selected !== 'all' && errors[selected]"
        class="inline-error"
      >
        {{ errors[selected] }}
        <button class="text-button" @click="search()">重试</button>
      </p>
      <p
        v-if="!busy && !Object.values(results).some((list) => list.length)"
        class="empty"
      >
        没有找到结果，可以换一个关键词。
      </p>
      <button v-if="hasMore && !busy" class="load-more" @click="loadMore">
        加载更多
      </button>
    </template>
    <template v-else>
      <template v-if="library.searches.length">
        <div class="section-heading">
          <h2>最近搜索</h2>
          <button class="text-button" @click="library.searches = []">
            清空
          </button>
        </div>
        <div class="chips">
          <button
            v-for="word in library.searches.slice(0, 10)"
            :key="word"
            @click="searchFor(word)"
          >
            {{ word }}
          </button>
        </div>
      </template>
      <template v-if="hotWords.length">
        <div class="section-heading"><h2>热搜</h2></div>
        <div class="chips">
          <button v-for="word in hotWords" :key="word" @click="searchFor(word)">
            {{ word }}
          </button>
        </div>
      </template>
    </template>
  </section>
</template>
