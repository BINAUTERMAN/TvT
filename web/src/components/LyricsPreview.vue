<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';
const sentinel = ref<HTMLElement>();
const pinned = ref(false);
const paused = ref(document.hidden);
let observer: IntersectionObserver | undefined;
function observe() {
  observer?.disconnect();
  paused.value = document.hidden;
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
      <span class="preview-caption">外观预览</span>
      <slot />
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
.preview-caption {
  font-size: 10px;
  color: var(--muted);
  letter-spacing: 0.1em;
}
.is-pinned .lyrics-preview-stage {
  min-height: 84px;
  border-radius: 18px;
  box-shadow:
    0 8px 24px #00000012,
    0 1px 3px #00000008;
  animation: preview-lift 0.32s cubic-bezier(0.22, 1, 0.36, 1);
}
.is-pinned :deep(.floating-preview) {
  font-size: min(var(--preview-font), 22px) !important;
  padding: 6px 10px;
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
  .is-pinned :deep(.floating-preview small) {
    display: none;
  }
}
</style>
