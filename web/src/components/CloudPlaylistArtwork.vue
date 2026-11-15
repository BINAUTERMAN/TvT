<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { native } from '../bridge';
import { cloudLibrary } from '../cloud-playlists';
import type { CloudPlaylist } from '../cloud-playlists';
import Artwork from './Artwork.vue';
const props = defineProps<{ list: CloudPlaylist; fallback?: string }>();
const root = ref<HTMLElement>();
const source = computed(
  () => props.list.cover || (props.list.count > 0 ? props.fallback : '') || '',
);
let observer: IntersectionObserver | undefined;
let disposed = false;
let pending = false;
let attempted = '';
let deferred = '';
async function load() {
  const key = `${props.list.id}:${props.list.version}:${props.list.count}`;
  if (
    disposed ||
    pending ||
    document.hidden ||
    document.body.classList.contains('backgrounded') ||
    source.value ||
    attempted === key ||
    !props.list.count
  )
    return;
  pending = true;
  attempted = key;
  const scope = cloudLibrary.accountKey;
  const id = props.list.id;
  try {
    const value = await native<{ id: string; cover: string }>('cloudPlaylist', {
      operation: 'cover',
      accountKey: scope,
      id,
    });
    if (disposed || scope !== cloudLibrary.accountKey || value.id !== id)
      return;
    if (document.hidden || document.body.classList.contains('backgrounded'))
      deferred = value.cover;
    else props.list.cover = value.cover;
  } catch {
    attempted = '';
  } finally {
    pending = false;
  }
}
function visibility() {
  observer?.disconnect();
  if (document.hidden || document.body.classList.contains('backgrounded'))
    return;
  if (deferred) {
    props.list.cover = deferred;
    deferred = '';
  }
  if (root.value && observer) observer.observe(root.value);
  else void load();
}
watch(() => [props.list.id, props.list.version, props.list.count], visibility);
watch(source, (value) => {
  if (!value) visibility();
});
onMounted(() => {
  if ('IntersectionObserver' in window)
    observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) void load();
      },
      { rootMargin: '120px' },
    );
  visibility();
  document.addEventListener('visibilitychange', visibility);
  window.addEventListener('ting:resume', visibility);
});
onUnmounted(() => {
  disposed = true;
  observer?.disconnect();
  document.removeEventListener('visibilitychange', visibility);
  window.removeEventListener('ting:resume', visibility);
});
</script>
<template>
  <div ref="root" class="cloud-playlist-artwork">
    <Artwork
      :src="source"
      :alternatives="list.count === 0 ? ['/brand/tvt-cover.svg'] : undefined"
      :seed="list.name"
    />
  </div>
</template>
<style scoped>
.cloud-playlist-artwork {
  width: 100%;
  aspect-ratio: 1;
  margin-bottom: 8px;
  flex-shrink: 0;
}
.cloud-playlist-artwork :deep(.artwork) {
  width: 100%;
  height: 100%;
}
</style>
