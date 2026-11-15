<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { Cloud, Plus, RefreshCw, ArrowUpDown } from 'lucide-vue-next';
import {
  cloudLibrary,
  loadCloudPlaylists,
  changeCloudLists,
} from '../cloud-playlists';
import { profile, notify } from '../store';
import CloudPlaylistArtwork from './CloudPlaylistArtwork.vue';
import PlaylistOrderEditor from './PlaylistOrderEditor.vue';
import ContentTransition from './ContentTransition.vue';
const emit = defineEmits<{ open: [id: string]; create: []; account: [] }>();
const sorting = ref(false);
const lists = computed(() =>
  cloudLibrary.playlists.filter((list) => !list.isDefault),
);
function artwork(id: string) {
  const list = cloudLibrary.playlists.find((item) => item.id === id);
  return list?.cover || cloudLibrary.tracks.get(id)?.tracks[0]?.cover || '';
}
async function refresh() {
  try {
    await loadCloudPlaylists();
  } catch {
    /* 具体失败在歌单区域显示。 */
  }
}
async function saveOrder(ids: string[]) {
  try {
    await changeCloudLists('sortLists', { ids });
    sorting.value = false;
    notify('歌单顺序已同步');
  } catch {
    /* 保留排序草稿，刷新后可重新调整。 */
  }
}
onMounted(() => {
  if (profile.loggedIn) void refresh();
});
watch(
  () => profile.loggedIn,
  (loggedIn) => {
    sorting.value = false;
    if (loggedIn) void refresh();
  },
);
</script>
<template>
  <section class="cloud-playlists music-section">
    <div class="section-heading">
      <h2>我的歌单</h2>
      <div class="cloud-section-actions">
        <button
          v-if="profile.loggedIn && lists.length > 1"
          class="icon"
          aria-label="歌单排序"
          :disabled="cloudLibrary.busy"
          @click="sorting = !sorting"
        >
          <ArrowUpDown :size="18" />
        </button>
        <button
          v-if="profile.loggedIn"
          class="icon"
          aria-label="刷新云端歌单"
          :disabled="cloudLibrary.busy"
          @click="refresh"
        >
          <RefreshCw :size="18" />
        </button>
        <button
          class="text-button"
          :disabled="cloudLibrary.busy"
          @click="profile.loggedIn ? emit('create') : emit('account')"
        >
          <Plus :size="17" />
          新建歌单
        </button>
      </div>
    </div>
    <div v-if="!profile.loggedIn" class="empty compact">
      <Cloud :size="30" />
      <p>登录酷狗账号，查看和管理你的歌单。</p>
      <button class="primary" @click="emit('account')">登录后使用歌单</button>
    </div>
    <ContentTransition v-else :identity="sorting ? 'sorting' : 'lists'">
      <PlaylistOrderEditor
        v-if="sorting"
        :items="
          lists.map((list) => ({
            id: list.id,
            name: list.name,
            group: list.type,
            subtitle: list.type === 0 ? '自建歌单' : '收藏歌单',
          }))
        "
        :busy="cloudLibrary.busy"
        @save="saveOrder"
        @cancel="sorting = false"
      />
      <div v-else class="playlist-grid">
        <button
          v-for="list in lists"
          :key="list.id"
          class="playlist-card"
          @click="emit('open', list.id)"
        >
          <CloudPlaylistArtwork :list="list" :fallback="artwork(list.id)" />
          <strong>{{ list.name }}</strong>
          <small>
            {{
              list.isSystem ? '系统歌单' : list.type === 0 ? '自建' : '收藏'
            }}
            · {{ list.count }} 首
          </small>
        </button>
      </div>
    </ContentTransition>
    <p v-if="cloudLibrary.busy" class="loading" role="status">正在同步歌单…</p>
    <p
      v-else-if="profile.loggedIn && cloudLibrary.loaded && !lists.length"
      class="empty-inline muted"
    >
      <Cloud :size="25" />
      还没有歌单，新建一张开始收藏吧。
    </p>
    <div v-if="cloudLibrary.error" class="inline-error" role="status">
      <span>{{ cloudLibrary.error }}</span>
      <button
        class="text-button"
        :disabled="cloudLibrary.busy"
        @click="refresh"
      >
        刷新重试
      </button>
    </div>
  </section>
</template>
<style scoped>
.cloud-section-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.cloud-playlists .section-heading {
  flex-wrap: wrap;
  gap: 8px;
}
.empty-inline {
  display: flex;
  align-items: center;
  gap: 10px;
}
</style>
