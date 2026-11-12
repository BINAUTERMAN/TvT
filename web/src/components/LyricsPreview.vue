<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue';
import { native } from '../bridge';
const props = defineProps<{
  settings: { width: number; size: number } & object;
}>();
const preview = ref<{ image: string; width: number; height: number }>();
const error = ref(false);
let timer = 0;
let version = 0;
let rendering = false;
let disposed = false;
function schedule() {
  clearTimeout(timer);
  version++;
  if (!document.hidden && !disposed) timer = window.setTimeout(render, 50);
}
async function render() {
  if (document.hidden || disposed || rendering) return;
  const request = version;
  rendering = true;
  try {
    const result = await native<{
      image: string;
      width: number;
      height: number;
    }>('floatingPreview', props.settings);
    if (!result?.image?.startsWith('data:image/png;base64,'))
      throw new Error('预览暂不可用');
    if (request === version && !document.hidden && !disposed) {
      preview.value = result;
      error.value = false;
    }
  } catch {
    if (request === version && !document.hidden && !disposed)
      error.value = true;
  } finally {
    rendering = false;
    if (request !== version && !document.hidden && !disposed)
      timer = window.setTimeout(render, 0);
  }
}
watch(() => props.settings, schedule, { deep: true });
const sentinel = ref<HTMLElement>();
const pinned = ref(false);
const paused = ref(document.hidden);
let observer: IntersectionObserver | undefined;
function observe() {
  observer?.disconnect();
  paused.value = document.hidden;
  schedule();
  if (document.hidden || !sentinel.value) return;
  observer = new IntersectionObserver(
    ([entry]) => {
      if (entry && !document.hidden)
        pinned.value =
          entry.boundingClientRect.top < (entry.rootBounds?.top ?? 0);
    },
    {
      root: sentinel.value.closest('.sheet-panel'),
      rootMargin: '-8px 0px 0px 0px',
    },
  );
  observer.observe(sentinel.value);
}
onMounted(() => {
  observe();
  document.addEventListener('visibilitychange', observe);
});
onUnmounted(() => {
  disposed = true;
  version++;
  clearTimeout(timer);
  observer?.disconnect();
  document.removeEventListener('visibilitychange', observe);
});
</script>
<template>
  <div ref="sentinel" class="preview-sentinel" aria-hidden="true" />
  <div
    class="lyrics-preview-dock"
    :class="{ 'is-pinned': pinned, 'is-paused': paused }"
  >
    <div class="lyrics-preview-stage" aria-label="桌面歌词外观预览">
      <img
        v-if="preview"
        class="floating-preview"
        :src="preview.image"
        :width="preview.width"
        :height="preview.height"
        :style="{ width: `${settings.width}%` }"
        alt="桌面歌词效果"
      />
      <button v-if="error" class="text-button" @click="schedule">
        重新加载预览
      </button>
    </div>
  </div>
</template>
<style scoped>
.preview-sentinel {
  height: 1px;
}
.lyrics-preview-dock {
  position: sticky;
  top: -24px;
  z-index: 4;
  padding: 8px 0 12px;
  background: var(--bg);
  overflow-anchor: none;
}
.lyrics-preview-stage {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  min-height: 142px;
  padding: 14px 0;
  border: 1px solid var(--line);
  border-radius: 22px;
  background: var(--surface);
  overflow: hidden;
  transition:
    min-height 0.28s cubic-bezier(0.22, 1, 0.36, 1),
    box-shadow 0.28s,
    border-radius 0.28s;
}
.floating-preview {
  display: block;
  height: auto;
  max-width: 100%;
  object-fit: contain;
}
.is-pinned .lyrics-preview-stage {
  min-height: 84px;
  border-radius: 18px;
  box-shadow:
    0 8px 24px #00000012,
    0 1px 3px #00000008;
  animation: preview-lift 0.32s cubic-bezier(0.22, 1, 0.36, 1);
}
.is-pinned .floating-preview {
  width: auto !important;
  max-height: 70px;
}
@keyframes preview-lift {
  0% {
    transform: translateY(3px) scale(0.985);
  }
  65% {
    transform: translateY(-1px) scale(1.006);
  }
  100% {
    transform: translateY(0) scale(1);
  }
}
.is-paused .lyrics-preview-stage {
  animation: none;
  transition: none;
}
@media (prefers-reduced-motion: reduce) {
  .lyrics-preview-stage,
  .is-pinned .lyrics-preview-stage {
    animation: none;
    transition: none;
  }
}
@media (max-height: 500px) {
  .is-pinned .lyrics-preview-stage {
    min-height: 64px;
    padding: 4px 0;
  }
  .is-pinned .floating-preview {
    max-height: 48px;
  }
}
</style>
