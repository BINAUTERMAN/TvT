<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import {
  ArrowUpRight,
  Play,
  RefreshCw,
  Sparkles,
  ChevronRight,
} from 'lucide-vue-next';
import { catalog, entitiesFrom, tracksFrom } from '../catalog';
import type { MusicEntity, Track } from '../types';
import { library, play, profile } from '../store';
import Artwork from './Artwork.vue';
import EntityGrid from './EntityGrid.vue';
import ContentTransition from './ContentTransition.vue';
const emit = defineEmits<{
  open: [entity: MusicEntity];
  menu: [track: Track];
  explore: [];
  account: [];
}>();
const daily = ref<Track[]>([]);
const recommendations = ref<Track[]>([]);
const playlists = ref<MusicEntity[]>([]);
const busy = ref(false);
const loggedIn = ref(false);
const discoveryTracks = computed(() => recommendations.value.slice(0, 12));
const favoriteMix = computed(() => ({
  kind: 'playlist' as const,
  id: 'cloud-favorites',
  name: '我喜欢',
  subtitle: `${library.favorites.length} 首 · 同步酷狗`,
  cover: library.favorites[0]?.cover ?? '',
  presetTracks: library.favorites,
}));
const errors = ref('');
async function load(refresh = false) {
  if (busy.value) return;
  busy.value = true;
  loggedIn.value = profile.loggedIn;
  errors.value = '';
  if (!loggedIn.value) {
    daily.value = [];
    recommendations.value = [];
  }
  const values = await Promise.allSettled([
    ...(loggedIn.value
      ? [
          catalog('daily', {}, refresh).then(
            (value) => (daily.value = tracksFrom(value)),
          ),
          catalog('recommend', {}, refresh).then(
            (value) => (recommendations.value = tracksFrom(value)),
          ),
        ]
      : []),
    catalog('playlists', {}, refresh).then(
      (value) =>
        (playlists.value = entitiesFrom(value, 'playlist').slice(0, 8)),
    ),
  ]);
  errors.value = values
    .filter((value) => value.status === 'rejected')
    .map((value) =>
      String((value as PromiseRejectedResult).reason?.message ?? '加载失败'),
    )
    .join('；');
  busy.value = false;
}
function openDaily() {
  if (daily.value.length)
    emit('open', {
      kind: 'playlist',
      id: 'daily-recommendation',
      name: '每日推荐',
      subtitle: '来自酷狗的账号推荐',
      cover: daily.value[0]?.cover ?? '',
      presetTracks: daily.value,
    });
}
onMounted(() => void load());
watch(
  () => profile.loggedIn,
  () => void load(true),
);
</script>
<template>
  <section class="page personal-home">
    <div class="home-title">
      <h1>现在听</h1>
      <button
        class="icon"
        aria-label="刷新推荐"
        :disabled="busy"
        @click="load(true)"
      >
        <RefreshCw :size="19" />
      </button>
    </div>
    <ContentTransition :identity="busy ? 'loading' : 'ready'">
      <button v-if="daily.length" class="daily-mix" @click="openDaily">
        <div class="daily-covers">
          <Artwork :src="daily[1]?.cover" :seed="daily[1]?.title" />
          <Artwork :src="daily[0]?.cover" :seed="daily[0]?.title" />
        </div>
        <span class="daily-copy">
          <small>根据你的听歌口味</small>
          <strong>每日推荐</strong>
          <span>{{ daily.length }} 首 · 今日更新</span>
        </span>
        <span class="mix-play"><Play :size="23" fill="currentColor" /></span>
      </button>
      <button
        v-else-if="!loggedIn"
        class="account-invitation"
        @click="emit('account')"
      >
        <Sparkles :size="23" />
        <span>
          <strong>登录，听你的推荐</strong>
          <small>读取酷狗账号的每日推荐和喜欢</small>
        </span>
        <ChevronRight :size="20" />
      </button>
      <section v-if="discoveryTracks.length" class="music-section">
        <div class="section-heading">
          <h2>为你推荐</h2>
          <button class="text-button" @click="play(discoveryTracks)">
            <Play :size="16" />
            播放
          </button>
        </div>
        <div class="recommend-grid">
          <button
            v-for="(track, index) in discoveryTracks"
            :key="track.id"
            class="album-item"
            @click="play(discoveryTracks, index)"
          >
            <Artwork :src="track.cover" :seed="track.title" />
            <strong>{{ track.title }}</strong>
            <small>{{ track.artist }}</small>
          </button>
        </div>
      </section>
      <section v-else-if="library.favorites.length" class="music-section">
        <div class="section-heading"><h2>你的音乐</h2></div>
        <EntityGrid :items="[favoriteMix]" shelf @open="emit('open', $event)" />
      </section>
      <section v-if="playlists.length" class="music-section">
        <div class="section-heading">
          <h2>精选歌单</h2>
          <button class="text-button" @click="emit('explore')">
            更多
            <ArrowUpRight :size="17" />
          </button>
        </div>
        <EntityGrid :items="playlists" shelf @open="emit('open', $event)" />
      </section>
      <button class="explore-door" @click="emit('explore')">
        <span>
          <strong>发现更多音乐</strong>
          <small>按风格、心情浏览歌单和排行榜</small>
        </span>
        <ArrowUpRight :size="25" />
      </button>
      <p v-if="busy" class="loading">正在加载推荐…</p>
      <div v-if="errors" class="inline-error">
        {{ errors }}
        <button class="text-button" @click="load(true)">重试</button>
      </div>
    </ContentTransition>
  </section>
</template>
