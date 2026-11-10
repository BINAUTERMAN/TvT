<script setup lang="ts">
import Artwork from './Artwork.vue';
import type { MusicEntity } from '../types';
import { ref, watch, nextTick } from 'vue';
const props = defineProps<{ items: MusicEntity[]; shelf?: boolean }>();
const container = ref<HTMLElement>();
watch(
  () => props.items[0]?.id,
  async () => {
    await nextTick();
    if (props.shelf && container.value) container.value.scrollLeft = 0;
  },
  { immediate: true },
);
defineEmits<{ open: [entity: MusicEntity] }>();
</script>
<template>
  <div ref="container" :class="shelf ? 'entity-shelf' : 'entity-grid'">
    <button
      v-for="item in items"
      :key="`${item.kind}:${item.id}`"
      class="entity-card"
      :class="{ portrait: item.kind === 'artist' }"
      @click="$emit('open', item)"
    >
      <Artwork
        :src="item.cover"
        :alternatives="item.coverAlternatives"
        :seed="item.name"
      />
      <strong>{{ item.name }}</strong>
      <small>
        {{
          item.kind === 'artist'
            ? '歌手'
            : item.subtitle ||
              (item.count
                ? `${item.count} 首`
                : item.kind === 'album'
                  ? '专辑'
                  : item.kind === 'rank'
                    ? '排行榜'
                    : '歌单')
        }}
      </small>
    </button>
  </div>
</template>
