<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { Music2 } from 'lucide-vue-next';
const props = defineProps<{
  src?: string;
  alternatives?: string[];
  seed?: string;
}>();
const sources = computed(() => [
  ...new Set([props.src, ...(props.alternatives || [])].filter(Boolean)),
]);
const index = ref(0);
const source = computed(() => sources.value[index.value]);
const loaded = ref(false);
watch(sources, (next, previous) => {
  if (
    next.length === previous.length &&
    next.every((url, index) => url === previous[index])
  )
    return;
  index.value = 0;
});
watch(source, () => {
  loaded.value = false;
});
function nextSource() {
  loaded.value = false;
  index.value++;
}
</script>
<template>
  <div
    class="artwork"
    :style="{
      '--hue':
        [...(seed || '听')].reduce((n, c) => n + c.charCodeAt(0), 0) % 360,
    }"
  >
    <img
      v-if="source"
      :key="source"
      :src="source"
      alt=""
      loading="lazy"
      :style="{ opacity: loaded ? 1 : 0 }"
      @load="loaded = true"
      @error="nextSource"
    />
    <Music2 v-if="!loaded" :size="34" :stroke-width="1.2" />
  </div>
</template>
