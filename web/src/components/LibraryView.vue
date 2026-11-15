<script setup lang="ts">
import { computed, watch } from 'vue';
import {
  Plus,
  Heart,
  Clock3,
  FolderDown,
  ArrowLeft,
  Play,
  Music2,
  CloudDownload,
} from 'lucide-vue-next';
import {
  library,
  command,
  play,
  musicCache,
  cacheSong,
  removeCache,
  notify,
  profile,
} from '../store';
import type { Track } from '../types';
import TrackList from './TrackList.vue';
import ContentTransition from './ContentTransition.vue';
import CloudPlaylistSection from './CloudPlaylistSection.vue';
import CloudPlaylistDetail from './CloudPlaylistDetail.vue';
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
  return [];
});
const collectionTitle = computed(
  () =>
    ({ favorites: '我喜欢的音乐', history: '最近播放', local: '本地音乐' })[
      collection.value
    ] ?? '资料库',
);
watch(
  () => profile.loggedIn,
  (loggedIn) => {
    if (
      !loggedIn &&
      (collection.value === 'favorites' ||
        collection.value.startsWith('remote-'))
    )
      collection.value = '';
  },
);
</script>
<template>
  <section class="page library-page">
    <ContentTransition :identity="collection || 'library'">
      <CloudPlaylistDetail
        v-if="collection.startsWith('remote-')"
        :id="collection"
        @back="collection = ''"
        @menu="emit('menu', $event)"
      />
      <template v-else-if="!collection">
        <div class="title-line">
          <h1>资料库</h1>
        </div>
        <div class="library-grid">
          <button
            class="library-card liked-card"
            @click="
              profile.loggedIn
                ? (collection = 'favorites')
                : emit('sheet', 'account')
            "
          >
            <Heart :size="29" />
            <strong>我喜欢的音乐</strong>
            <small>
              {{
                profile.loggedIn
                  ? `${library.favorites.length} 首`
                  : '登录后同步'
              }}
            </small>
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
        <CloudPlaylistSection
          @open="collection = $event"
          @create="emit('sheet', 'create')"
          @account="emit('sheet', 'account')"
        />
        <button
          v-if="profile.loggedIn"
          class="secondary-button full-width"
          @click="emit('sheet', 'playlistImport')"
        >
          <CloudDownload :size="18" />
          导入外部歌单
        </button>
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
      </template>
    </ContentTransition>
  </section>
</template>
