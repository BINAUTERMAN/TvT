<script setup lang="ts">
import { Heart, MoreHorizontal, Play } from 'lucide-vue-next';
import Artwork from './Artwork.vue';
import type { Track } from '../types';
import { library, current, play, toggleFavorite } from '../store';
defineProps<{ tracks: Track[] }>();
defineEmits<{ menu: [track: Track] }>();
</script>
<template>
  <div class="track-list">
    <div
      v-for="(track, index) in tracks"
      :key="track.id"
      class="track-row"
      :class="{ active: current?.id === track.id }"
    >
      <button class="track-main" @click="play(tracks, index)">
        <Artwork :src="track.cover" :seed="track.title" />
        <span class="track-copy">
          <strong>
            {{ track.title }}
            <span v-if="track.original" class="original-tag">原唱</span>
            <span v-if="track.cached" class="original-tag">已缓存</span>
          </strong>
          <small>
            {{ track.artist }}
            <template v-if="track.album">· {{ track.album }}</template>
          </small>
        </span>
        <Play v-if="current?.id === track.id" :size="16" />
      </button>
      <button
        class="icon favorite"
        :aria-label="
          library.favorites.some((t) => t.id === track.id) ? '取消喜欢' : '喜欢'
        "
        @click="toggleFavorite(track)"
      >
        <Heart
          :size="19"
          :fill="
            library.favorites.some((t) => t.id === track.id)
              ? 'currentColor'
              : 'none'
          "
        />
      </button>
      <button class="icon" aria-label="歌曲菜单" @click="$emit('menu', track)">
        <MoreHorizontal :size="21" />
      </button>
    </div>
  </div>
</template>
