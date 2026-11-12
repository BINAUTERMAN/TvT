<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ArrowLeft, Play, Plus, Check } from 'lucide-vue-next';
import {
  catalog,
  clean,
  cover,
  entitiesFrom,
  rows,
  tracksFrom,
} from '../catalog';
import type { Raw } from '../catalog';
import type { MusicEntity, Track } from '../types';
import { library, play, notify } from '../store';
import Artwork from './Artwork.vue';
import TrackList from './TrackList.vue';
import EntityGrid from './EntityGrid.vue';
import ContentTransition from './ContentTransition.vue';
const props = defineProps<{ item: MusicEntity }>();
const emit = defineEmits<{
  back: [];
  open: [item: MusicEntity];
  menu: [track: Track];
}>();
const info = ref<MusicEntity>({ ...props.item });
const tracks = ref<Track[]>([]);
const albums = ref<MusicEntity[]>([]);
const busy = ref(false);
const error = ref('');
const section = ref('songs');
const page = ref(1);
const more = ref(false);
const expanded = ref(false);
const saved = computed(() =>
  library.playlists.some(
    (list) => list.id === `${props.item.kind}-${props.item.id}`,
  ),
);
const kindName = computed(
  () =>
    ({ artist: '歌手', album: '专辑', playlist: '歌单', rank: '排行榜' })[
      props.item.kind
    ],
);
function metadata(value: Raw) {
  const first = rows(value)[0] ?? (value.data as Raw) ?? value;
  info.value = {
    ...info.value,
    description: clean(
      first.intro ?? first.description ?? info.value.description,
    ),
    cover: cover(
      first.sizable_avatar ??
        first.sizable_cover ??
        first.img ??
        first.pic ??
        info.value.cover,
    ),
  };
}
async function load(append = false, refresh = false) {
  if (props.item.presetTracks) {
    tracks.value = props.item.presetTracks;
    return;
  }
  busy.value = true;
  error.value = '';
  const kind = props.item.kind;
  const trackRoute =
    kind === 'artist'
      ? 'artistSongs'
      : kind === 'album'
        ? 'albumSongs'
        : kind === 'rank'
          ? 'rankSongs'
          : 'playlist';
  try {
    const value = await catalog(
      trackRoute,
      {
        id: props.item.id,
        page: page.value,
      },
      refresh,
    );
    const next = tracksFrom(value);
    tracks.value = append
      ? [
          ...new Map(
            [...tracks.value, ...next].map((track) => [track.id, track]),
          ).values(),
        ]
      : next;
    more.value = next.length >= 50;
  } catch (cause) {
    error.value = (cause as Error).message;
  } finally {
    busy.value = false;
  }
}
function reload() {
  page.value = 1;
  void load(false, true);
}
async function loadMore() {
  page.value++;
  await load(true);
  if (error.value) page.value--;
}
async function details() {
  if (props.item.presetTracks) return;
  const route =
    props.item.kind === 'artist'
      ? 'artist'
      : props.item.kind === 'album'
        ? 'album'
        : 'playlistDetail';
  if (props.item.kind !== 'rank') {
    try {
      metadata(await catalog(route, { id: props.item.id }));
    } catch {}
  }
  if (props.item.kind === 'artist') {
    try {
      albums.value = entitiesFrom(
        await catalog('artistAlbums', { id: props.item.id }),
        'album',
      );
    } catch {}
  }
}
function save() {
  if (!tracks.value.length) return;
  const id = `${props.item.kind}-${props.item.id}`;
  const existing = library.playlists.find((list) => list.id === id);
  if (existing) existing.tracks = [...tracks.value];
  else
    library.playlists.push({
      id,
      name: info.value.name,
      tracks: [...tracks.value],
    });
  notify('已保存到资料库');
}
onMounted(() => {
  void load();
  void details();
});
</script>
<template>
  <section class="page detail-page">
    <button class="text-button back-link" @click="emit('back')">
      <ArrowLeft :size="19" />
      返回
    </button>
    <div class="entity-heading" :class="{ artist: item.kind === 'artist' }">
      <Artwork :src="info.cover" :seed="info.name" />
      <div>
        <span class="eyebrow">{{ kindName }}</span>
        <h1>{{ info.name }}</h1>
        <p class="muted">{{ info.subtitle }}</p>
      </div>
    </div>
    <p
      v-if="info.description"
      class="entity-description"
      :class="{ expanded }"
      @click="expanded = !expanded"
    >
      {{ info.description }}
    </p>
    <div class="collection-actions">
      <button class="primary" :disabled="!tracks.length" @click="play(tracks)">
        <Play :size="17" fill="currentColor" />
        播放
      </button>
      <button class="secondary-button" :disabled="!tracks.length" @click="save">
        <component :is="saved ? Check : Plus" :size="18" />
        {{ saved ? '更新收藏' : '保存歌单' }}
      </button>
      <small>{{ tracks.length }} 首已加载</small>
    </div>
    <div v-if="item.kind === 'artist'" class="segmented">
      <button
        :class="{ selected: section === 'songs' }"
        @click="section = 'songs'"
      >
        热门歌曲
      </button>
      <button
        :class="{ selected: section === 'albums' }"
        @click="section = 'albums'"
      >
        专辑
      </button>
    </div>
    <ContentTransition
      :identity="`${section}:${busy && !tracks.length ? 'loading' : 'ready'}`"
    >
      <TrackList
        v-if="section === 'songs'"
        :tracks="tracks"
        @menu="emit('menu', $event)"
      />
      <EntityGrid v-else :items="albums" @open="emit('open', $event)" />
      <p v-if="busy" class="loading">正在加载…</p>
      <div
        v-if="!busy && !error && !tracks.length && section === 'songs'"
        class="empty-inline"
      >
        <p>暂时没有获取到可播放歌曲</p>
        <button class="text-button" @click="reload">重新加载</button>
      </div>
      <div v-if="error" class="inline-error">
        {{ error }}
        <button class="text-button" @click="reload">重试</button>
      </div>
      <button
        v-if="more && !busy && section === 'songs'"
        class="load-more"
        @click="loadMore"
      >
        加载更多
      </button>
    </ContentTransition>
  </section>
</template>
