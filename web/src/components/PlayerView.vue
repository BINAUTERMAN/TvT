<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  ChevronDown,
  SlidersHorizontal,
  Heart,
  Shuffle,
  Repeat,
  Repeat1,
  SkipBack,
  SkipForward,
  Pause,
  Play,
  ListMusic,
  AudioLines,
  Moon,
  Languages,
} from 'lucide-vue-next';
import { native } from '../bridge';
import { clock } from '../music';
import {
  current,
  player,
  command,
  toggleFavorite,
  isLiked,
  preferences,
  updatePreference,
} from '../store';
import type { LyricLine, Track, LyricDocument } from '../types';
import Artwork from './Artwork.vue';
import ContentTransition from './ContentTransition.vue';
const props = defineProps<{ open: boolean }>();
const emit = defineEmits<{
  close: [];
  menu: [track: Track];
  sheet: [name: string];
}>();
const lyricsOpen = ref(false);
const lyrics = ref<LyricLine[]>([]);
const lyricStatus = ref('');
const translationAvailable = ref(false);
const translationRequested = ref(false);
function toggleTranslation() {
  const wasOpen = lyricsOpen.value;
  lyricsOpen.value = true;
  translationRequested.value = true;
  if (!translationAvailable.value || !wasOpen) {
    if (!preferences.lyricsTranslation)
      void updatePreference('lyricsTranslation', true);
    return;
  }
  void updatePreference('lyricsTranslation', !preferences.lyricsTranslation);
}
const activeLyric = computed(() => {
  let active = -1;
  lyrics.value.forEach((line, index) => {
    if (line.time <= player.position) active = index;
  });
  return active;
});
const liked = computed(() => current.value && isLiked(current.value));
const qualityName = computed(
  () =>
    (
      ({
        '128': '标准',
        '320': '高品',
        flac: '无损',
        high: 'Hi-Res',
      }) as Record<string, string>
    )[player.quality || '128'],
);
const drag = ref({ x: 0, y: 0 });
let pointer = { x: 0, y: 0 };
let dragged = false;
function dragStart(event: PointerEvent) {
  pointer = { x: event.clientX, y: event.clientY };
  dragged = false;
  (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
}
function dragMove(event: PointerEvent) {
  if (!(event.currentTarget as HTMLElement).hasPointerCapture(event.pointerId))
    return;
  drag.value = { x: event.clientX - pointer.x, y: event.clientY - pointer.y };
  if (Math.abs(drag.value.x) + Math.abs(drag.value.y) > 12) dragged = true;
}
function dragEnd() {
  const { x, y } = drag.value;
  drag.value = { x: 0, y: 0 };
  if (y > 100 && y > Math.abs(x)) emit('close');
  else if (Math.abs(x) > 90 && Math.abs(x) > Math.abs(y))
    void command(x < 0 ? 'next' : 'previous');
}
function revealLyrics() {
  if (!dragged) lyricsOpen.value = !lyricsOpen.value;
}
function coverTransition(element: Element) {
  if (matchMedia('(prefers-reduced-motion: reduce)').matches) return;
  const artwork = element.querySelector('.player-art .artwork');
  const small = document.querySelector('.mini-info .artwork');
  if (!artwork || !small) return;
  const target = artwork.getBoundingClientRect(),
    source = small.getBoundingClientRect();
  artwork.animate(
    [
      {
        transform: `translate(${source.x - target.x}px,${source.y - target.y}px) scale(${source.width / target.width})`,
        borderRadius: '12px',
      },
      { transform: 'none', borderRadius: '20px' },
    ],
    { duration: 480, easing: 'cubic-bezier(.16,1,.3,1)' },
  );
}
let lyricVersion = 0;
watch([() => current.value?.id, () => props.open], async () => {
  if (!props.open || document.hidden) return;
  const version = ++lyricVersion;
  lyrics.value = [];
  translationAvailable.value = false;
  translationRequested.value = false;
  lyricStatus.value = '';
  if (!current.value?.hash) {
    lyricStatus.value = '本地歌曲暂未匹配歌词';
    return;
  }
  lyricStatus.value = '正在寻找歌词…';
  try {
    const content = await native<LyricDocument>('lyricDocument', current.value);
    if (version !== lyricVersion) return;
    lyrics.value = content.lines;
    translationAvailable.value = content.translationAvailable;
    lyricStatus.value = lyrics.value.length ? '' : '暂时没有这首歌的歌词';
  } catch {
    if (version === lyricVersion) lyricStatus.value = '歌词加载失败';
  }
});
watch(
  [activeLyric, lyricsOpen],
  () => {
    if (lyricsOpen.value && !document.hidden)
      document.querySelector('.lyric-line.active')?.scrollIntoView({
        behavior: matchMedia('(prefers-reduced-motion: reduce)').matches
          ? 'instant'
          : 'smooth',
        block: 'center',
      });
  },
  { flush: 'post' },
);
</script>
<template>
  <Transition name="player" @enter="coverTransition">
    <div
      v-if="open && current"
      class="player-screen"
      :style="{
        '--cover-hue':
          [...current.title].reduce((sum, c) => sum + c.charCodeAt(0), 0) % 360,
      }"
    >
      <header>
        <button class="icon" aria-label="收起播放页" @click="emit('close')">
          <ChevronDown :size="27" />
        </button>
        <span class="eyebrow">
          {{ lyricsOpen ? '随歌而行' : '此刻正在播放' }}
        </span>
        <button
          class="icon"
          aria-label="歌曲菜单"
          @click="emit('menu', current)"
        >
          <SlidersHorizontal :size="22" />
        </button>
      </header>
      <div class="player-layout">
        <ContentTransition
          :identity="lyricsOpen ? 'lyrics' : 'cover'"
          class="player-visual"
        >
          <div v-if="!lyricsOpen" class="cover-stage">
            <div
              class="listening-orbit"
              :style="{
                '--progress': `${player.duration ? (player.position / player.duration) * 360 : 0}deg`,
              }"
              aria-hidden="true"
            />
            <button
              class="player-art"
              aria-label="展开歌词，左右滑动切歌，下滑收起"
              :style="{
                transform: `translate(${drag.x * 0.4}px,${drag.y * 0.5}px) rotate(${drag.x * 0.025}deg)`,
              }"
              @pointerdown="dragStart"
              @pointermove="dragMove"
              @pointerup="dragEnd"
              @pointercancel="dragEnd"
              @click="revealLyrics"
            >
              <Artwork
                :key="current.id"
                :src="current.cover"
                :seed="current.title"
              />
            </button>
            <Transition name="lyric-caption" mode="out-in">
              <p :key="activeLyric" class="lyric-caption">
                {{ lyrics[activeLyric]?.text || '让旋律，留在此刻' }}
              </p>
            </Transition>
          </div>
          <div v-else class="lyrics">
            <p v-if="!lyrics.length" class="muted">{{ lyricStatus }}</p>
            <p
              v-else-if="
                translationRequested &&
                preferences.lyricsTranslation &&
                !translationAvailable
              "
              class="lyric-translation-status"
            >
              平台暂未提供这首歌的译文
            </p>
            <button
              v-for="(line, index) in lyrics"
              :key="index"
              class="lyric-line"
              :class="{ active: index === activeLyric }"
              @click="command('seek', { position: line.time })"
            >
              <span>{{ line.text }}</span>
              <small
                v-if="preferences.lyricsTranslation && line.translation"
                class="lyric-translation"
              >
                {{ line.translation }}
              </small>
            </button>
          </div>
        </ContentTransition>
        <div class="player-controls">
          <div class="now-title">
            <div>
              <h2>{{ current.title }}</h2>
              <p>{{ current.artist }}</p>
            </div>
            <button
              class="icon"
              aria-label="喜欢这首歌"
              :class="{ 'heart-liked': liked }"
              :aria-pressed="!!liked"
              @click="toggleFavorite(current)"
            >
              <Heart :size="25" :fill="liked ? 'currentColor' : 'none'" />
            </button>
          </div>
          <div class="playback-status" role="status" aria-live="polite">
            <Transition name="status" mode="out-in">
              <span v-if="player.loading" key="loading">正在加载音频…</span>
              <span v-else-if="player.interrupted" key="interrupted">
                已为其他声音暂停，结束后自动继续
              </span>
            </Transition>
          </div>
          <input
            class="seek"
            type="range"
            aria-label="播放进度"
            min="0"
            :max="player.duration || 1"
            :value="player.position"
            @change="
              command('seek', {
                position: Number(($event.target as HTMLInputElement).value),
              })
            "
          />
          <div class="time-row">
            <span>{{ clock(player.position) }}</span>
            <span>{{ clock(player.duration) }}</span>
          </div>
          <div class="transport">
            <button
              class="icon"
              aria-label="切换播放模式"
              @click="command('mode', { mode: (player.mode + 1) % 3 })"
            >
              <component
                :is="
                  player.mode === 2
                    ? Shuffle
                    : player.mode === 1
                      ? Repeat1
                      : Repeat
                "
                :size="23"
              />
            </button>
            <button
              class="icon"
              aria-label="上一首"
              @click="command('previous')"
            >
              <SkipBack :size="30" fill="currentColor" />
            </button>
            <button
              class="big-play"
              :aria-label="player.playing ? '暂停' : '播放'"
              @click="command('toggle')"
            >
              <component
                :is="player.playing ? Pause : Play"
                :size="32"
                fill="currentColor"
              />
            </button>
            <button class="icon" aria-label="下一首" @click="command('next')">
              <SkipForward :size="30" fill="currentColor" />
            </button>
            <button
              class="icon"
              aria-label="播放队列"
              @click="emit('sheet', 'queue')"
            >
              <ListMusic :size="24" />
            </button>
          </div>
          <div class="player-tools">
            <button
              :class="{ accent: lyricsOpen }"
              @click="lyricsOpen = !lyricsOpen"
            >
              <AudioLines :size="20" />
              歌词
            </button>
            <button
              :aria-pressed="
                lyricsOpen &&
                translationAvailable &&
                preferences.lyricsTranslation
              "
              :class="{
                accent:
                  lyricsOpen &&
                  translationAvailable &&
                  preferences.lyricsTranslation,
              }"
              @click="toggleTranslation"
            >
              <Languages :size="20" />
              译文
            </button>
            <button @click="emit('sheet', 'sleep')">
              <Moon :size="20" />
              {{ player.sleepAt ? '已定时' : '定时' }}
            </button>
            <button @click="emit('sheet', 'sound')">
              <SlidersHorizontal :size="20" />
              {{ qualityName }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </Transition>
</template>
