<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  ArrowLeft,
  Play,
  RefreshCw,
  Pencil,
  ArrowUpDown,
  Trash2,
} from 'lucide-vue-next';
import {
  cloudLibrary,
  loadCloudTracks,
  loadCloudPlaylists,
  changeCloudLists,
  changeCloudTracks,
} from '../cloud-playlists';
import { play, notify } from '../store';
import type { Track } from '../types';
import Artwork from './Artwork.vue';
import TrackList from './TrackList.vue';
import PlaylistOrderEditor from './PlaylistOrderEditor.vue';
import ContentTransition from './ContentTransition.vue';
const props = defineProps<{ id: string }>();
const emit = defineEmits<{ back: []; menu: [track: Track] }>();
const list = computed(() =>
  cloudLibrary.playlists.find((list) => list.id === props.id),
);
const snapshot = computed(() => cloudLibrary.tracks.get(props.id));
const tracks = computed(() => snapshot.value?.tracks ?? []);
const panel = ref('songs');
const name = ref('');
const description = ref('');
const panelError = ref('');
const editable = computed(
  () => list.value?.type === 0 && !list.value.isDefault,
);
async function refresh() {
  panelError.value = '';
  try {
    await loadCloudPlaylists();
    if (list.value) await loadCloudTracks(props.id);
  } catch (cause) {
    panelError.value = (cause as Error).message;
  }
}
function edit() {
  name.value = list.value?.name ?? '';
  description.value = list.value?.description ?? '';
  panel.value = 'edit';
}
async function update() {
  panelError.value = '';
  try {
    await changeCloudLists('update', {
      id: props.id,
      name: name.value,
      description: description.value,
    });
    panel.value = 'songs';
    notify('歌单信息已同步');
  } catch (cause) {
    panelError.value = (cause as Error).message;
  }
}
async function remove() {
  panelError.value = '';
  const collected = list.value?.type === 1;
  try {
    await changeCloudLists('delete', { id: props.id });
    notify(collected ? '已取消收藏' : '歌单已删除');
    emit('back');
  } catch (cause) {
    panelError.value = (cause as Error).message;
  }
}
async function sort(fileIds: string[]) {
  panelError.value = '';
  try {
    await changeCloudTracks('sortTracks', props.id, { fileIds });
    panel.value = 'songs';
    notify('歌曲顺序已同步');
  } catch (cause) {
    panelError.value = (cause as Error).message;
  }
}
watch(
  () => props.id,
  () => {
    panel.value = 'songs';
    void refresh();
  },
  { immediate: true },
);
</script>
<template>
  <section class="cloud-playlist-detail">
    <button class="text-button back-link" @click="emit('back')">
      <ArrowLeft :size="18" />
      资料库
    </button>
    <template v-if="list">
      <div class="entity-heading">
        <Artwork
          :src="list.cover || (list.count > 0 ? tracks[0]?.cover : '')"
          :alternatives="list.count === 0 ? ['/brand/tvt-cover.svg'] : undefined"
          :seed="list.name"
        />
        <div>
          <span class="eyebrow">
            {{
              list.isSystem
                ? '酷狗系统歌单'
                : list.type === 0
                  ? '自建歌单'
                  : '收藏歌单'
            }}
          </span>
          <h1>{{ list.name }}</h1>
          <p class="muted">{{ tracks.length }} 首 · 与酷狗账号同步</p>
        </div>
      </div>
      <p v-if="list.description" class="entity-description">
        {{ list.description }}
      </p>
      <div class="collection-actions cloud-detail-actions">
        <button
          class="primary"
          :disabled="!tracks.length || cloudLibrary.busy"
          @click="play(tracks)"
        >
          <Play :size="16" fill="currentColor" />
          全部播放
        </button>
        <button
          class="icon"
          aria-label="刷新歌单歌曲"
          :disabled="cloudLibrary.busy"
          @click="refresh"
        >
          <RefreshCw :size="19" />
        </button>
        <button
          v-if="editable && !list.isSystem"
          class="text-button"
          :disabled="cloudLibrary.busy"
          @click="edit"
        >
          <Pencil :size="17" />
          编辑歌单
        </button>
        <button
          v-if="editable && tracks.length > 1"
          class="text-button"
          :disabled="cloudLibrary.busy"
          @click="panel = 'sort'"
        >
          <ArrowUpDown :size="17" />
          歌曲排序
        </button>
        <button
          v-if="!list.isDefault && !list.isSystem"
          class="text-button danger"
          :disabled="cloudLibrary.busy"
          @click="panel = 'delete'"
        >
          <Trash2 :size="17" />
          {{ list.type === 1 ? '取消收藏' : '删除歌单' }}
        </button>
      </div>
      <p v-if="list.isSystem" class="muted">
        这是酷狗系统歌单，不能删除整张歌单。
      </p>
      <ContentTransition :identity="panel">
        <form
          v-if="panel === 'edit'"
          class="cloud-edit"
          @submit.prevent="update"
        >
          <label>
            歌单名称
            <input
              v-model="name"
              class="text-input"
              aria-label="歌单名称"
              maxlength="40"
            />
          </label>
          <label>
            简介
            <textarea
              v-model="description"
              class="text-input"
              aria-label="歌单简介"
              maxlength="500"
              rows="3"
            />
          </label>
          <div class="collection-actions">
            <button
              class="primary"
              :disabled="cloudLibrary.busy || !name.trim()"
            >
              保存歌单信息
            </button>
            <button
              type="button"
              class="text-button"
              :disabled="cloudLibrary.busy"
              @click="panel = 'songs'"
            >
              取消编辑
            </button>
          </div>
        </form>
        <PlaylistOrderEditor
          v-else-if="panel === 'sort'"
          :items="
            tracks.map((track) => ({
              id: track.fileId,
              name: track.title,
              subtitle: track.artist,
            }))
          "
          :busy="cloudLibrary.busy"
          @save="sort"
          @cancel="panel = 'songs'"
        />
        <div v-else-if="panel === 'delete'" class="cloud-delete">
          <h2>
            {{ list.type === 1 ? '取消收藏这张歌单？' : '删除这张歌单？' }}
          </h2>
          <p class="muted">
            {{
              list.type === 1
                ? '这张歌单将从当前账号的收藏中移除。'
                : '会同步删除酷狗账号中的这张歌单，音乐文件不受影响。'
            }}
          </p>
          <div class="collection-actions">
            <button
              class="primary danger-bg"
              :disabled="cloudLibrary.busy"
              @click="remove"
            >
              确认{{ list.type === 1 ? '取消收藏' : '删除歌单' }}
            </button>
            <button
              class="text-button"
              :disabled="cloudLibrary.busy"
              @click="panel = 'songs'"
            >
              保留歌单
            </button>
          </div>
        </div>
        <div v-else>
          <TrackList :tracks="tracks" @menu="emit('menu', $event)" />
          <p
            v-if="!tracks.length && !cloudLibrary.busy && snapshot"
            class="empty-inline muted"
          >
            还没有歌曲，从歌曲菜单添加到这张歌单吧。
          </p>
        </div>
      </ContentTransition>
    </template>
    <p v-else-if="!cloudLibrary.busy" class="muted">
      歌单已变化或账号已切换，请返回刷新。
    </p>
    <p v-if="cloudLibrary.busy" class="loading" role="status">正在同步歌单…</p>
    <div
      v-if="panelError || cloudLibrary.error"
      class="inline-error"
      role="status"
    >
      <span>{{ panelError || cloudLibrary.error }}</span>
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
.cloud-detail-actions {
  flex-wrap: wrap;
}
.cloud-edit {
  max-width: 620px;
}
.cloud-edit label {
  display: block;
  margin: 18px 0;
  font-size: 14px;
}
.cloud-edit .text-input {
  display: block;
  margin-top: 9px;
  width: 100%;
}
.cloud-edit textarea {
  resize: vertical;
  min-height: 90px;
  font: inherit;
}
.cloud-delete {
  padding: 20px;
  border-radius: 18px;
  background: var(--surface);
}
</style>
