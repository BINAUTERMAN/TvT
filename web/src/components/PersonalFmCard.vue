<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { AudioLines, Play, Radio, RefreshCw } from 'lucide-vue-next';
import { native } from '../bridge';
import { current, player, profile } from '../store';

const emit = defineEmits<{ account: []; player: [] }>();
const props = withDefaults(defineProps<{ active?: boolean }>(), {
  active: true,
});
const equalizer = ref<HTMLElement>();
const motionQuery = matchMedia('(prefers-reduced-motion: reduce)');
const reducedMotion = ref(motionQuery.matches);
const liveLevels = ref(false);
const animated = computed(
  () =>
    !!player.personalFm &&
    player.playing &&
    !player.loading &&
    !player.fmWaiting &&
    props.active &&
    !reducedMotion.value,
);
let subscribed = false;
function motionChanged() {
  reducedMotion.value = motionQuery.matches;
}
function resumeVisualizer() {
  if (animated.value)
    void native('fmVisualize', { enabled: true }).catch(() => {
      liveLevels.value = false;
    });
}
function receiveLevels(event: Event) {
  if (!animated.value || document.hidden) return;
  const values: unknown = (event as CustomEvent).detail;
  if (
    !Array.isArray(values) ||
    values.length !== 5 ||
    !values.every(
      (value) => typeof value === 'number' && Number.isFinite(value),
    )
  )
    return;
  liveLevels.value = true;
  equalizer.value
    ?.querySelectorAll('i')
    .forEach((bar, index) =>
      bar.style.setProperty(
        '--fm-level',
        String(Math.max(0.15, Math.min(1, values[index] as number))),
      ),
    );
}
watch(
  animated,
  (enabled) => {
    liveLevels.value = false;
    if (enabled === subscribed) return;
    subscribed = enabled;
    void native('fmVisualize', { enabled }).catch(() => {
      liveLevels.value = false;
    });
  },
  { immediate: true },
);
onMounted(() => {
  motionQuery.addEventListener('change', motionChanged);
  window.addEventListener('ting:fm-levels', receiveLevels);
  window.addEventListener('ting:resume', resumeVisualizer);
});
onUnmounted(() => {
  motionQuery.removeEventListener('change', motionChanged);
  window.removeEventListener('ting:fm-levels', receiveLevels);
  window.removeEventListener('ting:resume', resumeVisualizer);
  if (subscribed)
    void native('fmVisualize', { enabled: false }).catch(() => {});
});
const opening = ref(false);
const requestError = ref('');
const busy = computed(() => opening.value || !!player.fmStarting);
const error = computed(() => requestError.value || player.fmError || '');
const status = computed(() => {
  if (player.fmStarting) return '正在为你寻找好歌…';
  if (player.personalFm) {
    if (player.fmWaiting) return '等待下一杯好歌';
    if (player.playing) return `正在播放 · ${current.value?.title || '随心听'}`;
    if (player.loading) return '正在准备播放…';
    return '随心听已暂停 · 点此查看';
  }
  return '点一下，让喜欢的旋律接着来';
});

function openPlayer() {
  opening.value = false;
  if (!document.hidden) emit('player');
}

watch(
  [() => player.personalFm, () => player.fmStarting, () => player.fmError],
  () => {
    if (!opening.value) return;
    if (player.personalFm && !player.fmStarting) openPlayer();
    else if (!player.fmStarting && player.fmError) opening.value = false;
  },
);

async function start() {
  if (!profile.loggedIn) {
    emit('account');
    return;
  }
  if (player.personalFm) {
    emit('player');
    return;
  }
  if (busy.value) return;
  requestError.value = '';
  opening.value = true;
  try {
    await native('fmStart');
    if (player.personalFm) openPlayer();
    else if (!player.fmStarting) opening.value = false;
  } catch (cause) {
    requestError.value = (cause as Error).message;
    opening.value = false;
  }
}

async function retry() {
  if (!player.personalFm) return start();
  requestError.value = '';
  try {
    await native('fmRetry');
  } catch (cause) {
    requestError.value = (cause as Error).message;
  }
}
</script>

<template>
  <section class="personal-fm" aria-label="随心听">
    <button
      class="fm-card"
      :class="{ 'fm-active': player.personalFm }"
      :aria-label="player.personalFm ? '打开随心听播放页' : '开启随心听'"
      :aria-busy="busy"
      :disabled="busy"
      @click="start"
    >
      <span class="fm-symbol" aria-hidden="true"><Radio :size="29" /></span>
      <span class="fm-copy">
        <strong>随心听</strong>
        <span class="fm-description">
          想一直听下去？合你口味的好歌，
          <span class="fm-refill">
            无限续杯～
            <span class="fm-emoticon">(｡•̀ᴗ-)✧</span>
          </span>
        </span>
        <span class="fm-status" role="status" aria-live="polite">
          <Transition name="status" mode="out-in">
            <small :key="status">{{ status }}</small>
          </Transition>
        </span>
      </span>
      <span class="fm-play" aria-hidden="true">
        <span
          v-if="player.personalFm"
          ref="equalizer"
          class="fm-equalizer"
          :class="{
            'fm-equalizer-playing': animated && !liveLevels,
            'fm-equalizer-live': animated && liveLevels,
          }"
        >
          <i v-for="bar in 5" :key="bar" />
        </span>
        <AudioLines v-else-if="busy" :size="23" />
        <Play v-else :size="23" fill="currentColor" />
      </span>
    </button>
    <div v-if="error" class="inline-error fm-error" role="status">
      <span>{{ error }}</span>
      <button
        class="text-button"
        :disabled="player.fmBusy || busy"
        @click="retry"
      >
        <RefreshCw :size="15" />
        重试随心听
      </button>
    </div>
  </section>
</template>

<style scoped>
.personal-fm {
  margin-top: 18px;
}
.fm-card {
  display: flex;
  align-items: center;
  gap: 18px;
  width: 100%;
  min-height: 144px;
  padding: 22px 24px;
  border: 1px solid var(--line);
  border-radius: 22px;
  text-align: left;
  background: radial-gradient(ellipse at 0% 0%, #b6b4e526, transparent 65%),
    var(--surface);
  transition:
    transform var(--motion-duration) var(--motion-ease),
    background-color 160ms ease,
    opacity 160ms ease;
}
.fm-card:active:not(:disabled) {
  transform: scale(0.985);
}
.fm-card:disabled {
  opacity: 0.75;
}
.fm-symbol,
.fm-play {
  display: grid;
  place-items: center;
  flex-shrink: 0;
}
.fm-equalizer {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  height: 26px;
  width: 26px;
}
.fm-equalizer i {
  display: block;
  width: 3px;
  height: 12px;
  background: currentColor;
  border-radius: 3px;
  transform-origin: center;
}
.fm-equalizer i:nth-child(2) {
  height: 20px;
}
.fm-equalizer i:nth-child(3) {
  height: 26px;
}
.fm-equalizer i:nth-child(4) {
  height: 18px;
}
.fm-equalizer i:nth-child(5) {
  height: 10px;
}
.fm-equalizer-playing i {
  animation: fm-equalizer-bounce 850ms ease-in-out infinite;
}
.fm-equalizer-live i {
  transform: scaleY(var(--fm-level, 0.45));
  transition: transform 100ms linear;
}
.fm-equalizer-playing i:nth-child(2) {
  animation-delay: -220ms;
}
.fm-equalizer-playing i:nth-child(3) {
  animation-delay: -440ms;
}
.fm-equalizer-playing i:nth-child(4) {
  animation-delay: -120ms;
}
.fm-equalizer-playing i:nth-child(5) {
  animation-delay: -320ms;
}
@keyframes fm-equalizer-bounce {
  0%,
  100% {
    transform: scaleY(0.45);
  }
  45% {
    transform: scaleY(1);
  }
  75% {
    transform: scaleY(0.65);
  }
}
.fm-symbol {
  width: 54px;
  height: 54px;
  border-radius: 18px;
  background: var(--bg);
}
.fm-copy {
  display: flex;
  flex-direction: column;
  gap: 7px;
  flex: 1;
  min-width: 0;
}
.fm-copy strong {
  font-size: 23px;
  font-weight: 650;
  letter-spacing: -0.5px;
}
.fm-description {
  font-size: 14px;
  line-height: 1.65;
  color: var(--muted);
  overflow-wrap: anywhere;
}
.fm-emoticon,
.fm-refill {
  white-space: nowrap;
}
.fm-status {
  display: block;
  min-height: 19px;
  line-height: 19px;
  overflow: hidden;
}
.fm-status small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
}
.fm-active .fm-status small {
  color: var(--ink);
}
.fm-play {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: var(--ink);
  color: var(--bg);
}
.fm-error {
  align-items: start;
}
.fm-error > span {
  min-width: 0;
  overflow-wrap: anywhere;
}
.fm-error .text-button {
  flex-shrink: 0;
}
@media (max-width: 480px) {
  .fm-card {
    gap: 12px;
    padding: 20px 16px;
  }
  .fm-symbol {
    display: none;
  }
  .fm-copy strong {
    font-size: 22px;
  }
}
</style>
