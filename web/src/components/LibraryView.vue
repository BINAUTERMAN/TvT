<script setup lang="ts">
import { computed } from 'vue';
import {
  Plus,
  Heart,
  Clock3,
  FolderDown,
  ListMusic,
  ArrowLeft,
  Play,
  Music2,
} from 'lucide-vue-next';
import {
  library,
  command,
  play,
  musicCache,
  cacheSong,
  removeCache,
  notify,
} from '../store';
import type { Track } from '../types';
import Artwork from './Artwork.vue';
import TrackList from './TrackList.vue';
import ContentTransition from './ContentTransition.vue';
const collection = defineModel<string>('collection', { default: '' });
const emit = defineEmits<{ menu: [track: Track]; sheet: [name: string] }>();
async function dismissJob(track: Track) {
  try {
    await removeCache(track);
  } catch (error) {
    notify((error as Error).message);
  }
}
const collectionTracks = computed(() => {
  if (collection.value === 'favorites') return library.favorites;
  if (collection.value === 'history') return library.history;
  if (collection.value === 'local') return library.local;
  return library.playlists.find((p) => p.id === collection.value)?.tracks ?? [];
});
const collectionTitle = computed(
  () =>
    ({ favorites: '我喜欢的音乐', history: '最近播放', local: '本地音乐' })[
      collection.value
    ] ??
    library.playlists.find((p) => p.id === collection.value)?.name ??
    '资料库',
);
</script>
<template>
  <section class="page library-page">
    <ContentTransition :identity="collection || 'library'">
      <template v-if="!collection">
        <div class="title-line">
          <h1>资料库</h1>
        </div>
        <div class="library-grid">
          <button
            class="library-card liked-card"
            @click="collection = 'favorites'"
          >
            <Heart :size="29" />
            <strong>我喜欢的音乐</strong>
            <small>{{ library.favorites.length }} 首</small>
          </button>
          <button class="library-card" @click="collection = 'history'">
            <Clock3 :size="29" />
            <strong>最近播放</strong>
            <small>{{ library.history.length }} 首</small>
          </button>
          <button class="library-card" @click="collection = 'local'">
            <FolderDown :size="29" />
            <strong>本地音乐</strong>
            <small>{{ library.local.length }} 首 · 随时离线听</small>
          </button>
        </div>
        <div class="section-heading">
          <h2>我的歌单</h2>
          <button class="text-button" @click="emit('sheet', 'create')">
            <Plus :size="17" />
            新建
          </button>
        </div>
        <div v-if="!library.playlists.length" class="empty compact">
          <ListMusic :size="35" />
          <h3>还没有歌单</h3>
          <p>创建歌单，收藏喜欢的音乐。</p>
          <button class="primary" @click="emit('sheet', 'create')">
            创建第一张歌单
          </button>
        </div>
        <div class="playlist-grid">
          <button
            v-for="list in library.playlists"
            :key="list.id"
            class="playlist-card"
            @click="collection = list.id"
          >
            <Artwork :src="list.tracks[0]?.cover" :seed="list.name" />
            <strong>{{ list.name }}</strong>
            <small>{{ list.tracks.length }} 首歌曲</small>
          </button>
        </div>
      </template>
      <template v-else>
        <button class="text-button back-link" @click="collection = ''">
          <ArrowLeft :size="18" />
          资料库
        </button>
        <h1>{{ collectionTitle }}</h1>
        <div class="collection-actions">
          <span class="muted">{{ collectionTracks.length }} 首歌曲</span>
          <button
            v-if="collection === 'local'"
            class="primary"
            @click="command('import')"
          >
            <Plus :size="17" />
            导入音乐
          </button>
          <button
            v-if="collectionTracks.length"
            class="primary"
            @click="play(collectionTracks)"
          >
            <Play :size="16" fill="currentColor" />
            全部播放
          </button>
        </div>
        <TrackList :tracks="collectionTracks" @menu="emit('menu', $event)" />
        <div
          v-if="collection === 'local' && musicCache.jobs.length"
          class="cache-jobs"
        >
          <article
            v-for="job in musicCache.jobs"
            :key="job.hash"
            class="cache-job"
          >
            <div>
              <strong>{{ job.track.title }}</strong>
              <small>
                {{
                  job.state === 'pending'
                    ? '正在缓存，完成后即可离线播放'
                    : job.message
                }}
              </small>
            </div>
            <button
              v-if="job.state === 'failed'"
              class="text-button"
              @click="cacheSong(job.track)"
            >
              重试
            </button>
            <button class="text-button" @click="dismissJob(job.track)">
              {{ job.state === 'pending' ? '取消' : '移除' }}
            </button>
          </article>
        </div>
        <p
          v-if="
            collection === 'local' &&
            collectionTracks.some((track) => track.cached)
          "
          class="muted cache-note"
        >
          缓存歌曲可离线播放；清除应用数据或卸载会删除缓存。
        </p>
        <div
          v-if="
            !collectionTracks.length &&
            (collection !== 'local' || !musicCache.jobs.length)
          "
          class="empty"
        >
          <Music2 :size="40" />
          <h3>这里还很安静</h3>
          <p>
            {{
              collection === 'local'
                ? '从歌曲菜单缓存音乐，或导入手机中的音乐文件。'
                : '在歌曲菜单中添加音乐，让这里热闹起来。'
            }}
          </p>
        </div>
        <button
          v-if="!['favorites', 'history', 'local'].includes(collection)"
          class="text-button danger"
          @click="emit('sheet', 'delete')"
        >
          删除歌单
        </button>
      </template>
    </ContentTransition>
  </section>
</template>
