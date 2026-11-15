<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import {
  Link,
  ImagePlus,
  CloudDownload,
  CheckCircle2,
  CircleAlert,
} from 'lucide-vue-next';
import { native } from '../bridge';
import { profile } from '../store';
import { cloudLibrary, loadCloudPlaylists } from '../cloud-playlists';
import ContentTransition from './ContentTransition.vue';
import OptionPicker from './OptionPicker.vue';

interface ImportOutcome {
  success?: boolean;
  paused?: boolean;
  cloudId?: string;
  name?: string;
  count?: number;
  missed?: number;
}
const emit = defineEmits<{ account: []; create: []; open: [id: string] }>();
const mode = ref('link');
const url = ref('');
const target = ref('');
const busy = ref(false);
const error = ref('');
const outcome = ref<ImportOutcome>();
const pending = ref(false);
const destinations = computed(() =>
  cloudLibrary.playlists.filter((list) => list.type === 0 && !list.isDefault),
);
let epoch = 0;
let mounted = true;

async function perform(action: string, payload: Record<string, unknown>) {
  if (busy.value) return;
  if (!profile.loggedIn) {
    emit('account');
    return;
  }
  const ticket = epoch;
  busy.value = true;
  pending.value = true;
  error.value = '';
  outcome.value = undefined;
  try {
    const value = await native<ImportOutcome>(action, payload);
    if (ticket !== epoch) return;
    if (
      !value.paused &&
      (!value.success || !value.cloudId || !value.count || value.count < 1)
    )
      throw new Error('平台未确认歌曲入库，导入失败');
    if (!value.paused) {
      outcome.value = value;
      pending.value = false;
      await loadCloudPlaylists();
    }
  } catch (cause) {
    if (ticket === epoch) {
      error.value = (cause as Error).message;
      pending.value = false;
    }
  } finally {
    if (ticket === epoch) busy.value = false;
  }
  if (
    mounted &&
    pending.value &&
    !document.hidden &&
    !document.body.classList.contains('backgrounded')
  )
    void resume();
}
function submit() {
  return perform('importPlaylist', { operation: 'import', url: url.value });
}
function submitImage() {
  return perform('importPlaylistImage', { target: target.value });
}
function resume() {
  return perform('importPlaylist', { operation: 'resume' });
}
async function imageMode() {
  mode.value = 'image';
  error.value = '';
  if (!profile.loggedIn) return;
  try {
    await loadCloudPlaylists();
    target.value = destinations.value[0]?.id ?? '';
  } catch (cause) {
    error.value = (cause as Error).message;
  }
}
function visibility() {
  if (
    !document.hidden &&
    !document.body.classList.contains('backgrounded') &&
    pending.value &&
    !busy.value
  )
    void resume();
}
watch(
  () => [profile.loggedIn, profile.name],
  () => {
    epoch++;
    busy.value = false;
    pending.value = false;
    outcome.value = undefined;
    error.value = '';
    target.value = '';
  },
);
onMounted(async () => {
  document.addEventListener('visibilitychange', visibility);
  window.addEventListener('ting:resume', visibility);
  try {
    await native('importUi', { active: true });
    if (!profile.loggedIn) return;
    const value = await native<{ pending: boolean }>('importPlaylist', {
      operation: 'pending',
    });
    if (mounted && value.pending) {
      pending.value = true;
      void resume();
    }
  } catch {
    /* 未登录或会话已离开时，等待用户主动提交。 */
  }
});
onUnmounted(() => {
  mounted = false;
  epoch++;
  document.removeEventListener('visibilitychange', visibility);
  window.removeEventListener('ting:resume', visibility);
  void native('importUi', { active: false }).catch(() => {});
});
</script>

<template>
  <section class="playlist-import-panel">
    <h2>导入外部歌单</h2>
    <p class="muted">把其他音乐应用的歌单带过来，歌曲会匹配到酷狗曲库。</p>
    <button
      v-if="!profile.loggedIn"
      class="primary full-width"
      @click="emit('account')"
    >
      登录后导入歌单
    </button>
    <template v-else>
      <div class="segmented" aria-label="歌单导入方式">
        <button
          :class="{ selected: mode === 'link' }"
          :disabled="busy"
          @click="mode = 'link'"
        >
          <Link :size="16" />
          分享链接
        </button>
        <button
          :class="{ selected: mode === 'image' }"
          :disabled="busy"
          @click="imageMode"
        >
          <ImagePlus :size="16" />
          歌单截图
        </button>
      </div>
      <ContentTransition :identity="mode">
        <form v-if="mode === 'link'" @submit.prevent="submit">
          <label class="import-label">
            歌单分享链接
            <textarea
              v-model="url"
              class="text-input"
              aria-label="外部歌单分享链接"
              rows="3"
              maxlength="2500"
              placeholder="粘贴公开歌单的链接或分享文案"
              :disabled="busy"
            />
          </label>
          <p class="muted">
            请使用可公开访问的歌单，支持范围以平台实际解析结果为准。
          </p>
          <button class="primary full-width" :disabled="busy || !url.trim()">
            <CloudDownload :size="18" />
            {{ busy ? '正在导入…' : '导入歌单' }}
          </button>
        </form>
        <div v-else>
          <div class="import-destination">
            <span class="import-label">导入到歌单</span>
            <OptionPicker
              v-if="destinations.length"
              v-model="target"
              label="截图导入目标歌单"
              :options="
                destinations.map((list) => ({
                  value: list.id,
                  label: list.name,
                }))
              "
              :disabled="busy"
              full-width
            />
          </div>
          <p class="muted">
            你选择的截图会发送至酷狗识别歌名和歌手，图片仅在本次导入中处理。
          </p>
          <button
            v-if="destinations.length"
            class="primary full-width"
            :disabled="busy || !target"
            @click="submitImage"
          >
            <ImagePlus :size="18" />
            {{ busy ? '正在导入…' : '选择截图并导入' }}
          </button>
          <button
            v-else
            class="primary full-width"
            :disabled="busy"
            @click="emit('create')"
          >
            先新建歌单
          </button>
        </div>
      </ContentTransition>
      <ContentTransition
        :identity="
          busy ? 'busy' : error ? 'error' : outcome ? 'success' : 'idle'
        "
      >
        <p v-if="busy || pending" class="loading" role="status">
          正在解析、匹配并保存歌单，请稍候…
        </p>
        <div
          v-else-if="error"
          class="import-feedback import-failed"
          role="status"
        >
          <CircleAlert :size="21" />
          <div>
            <strong>导入失败</strong>
            <p>{{ error }}</p>
          </div>
        </div>
        <div v-else-if="outcome?.success" class="import-feedback" role="status">
          <CheckCircle2 :size="23" />
          <div>
            <strong>导入成功</strong>
            <p>
              已将 {{ outcome.count }} 首歌曲保存到「{{ outcome.name }}」
              <span v-if="outcome.missed">，{{ outcome.missed }} 首未匹配</span>
              。
            </p>
            <button class="text-button" @click="emit('open', outcome.cloudId!)">
              查看歌单
            </button>
          </div>
        </div>
      </ContentTransition>
    </template>
  </section>
</template>
<style scoped>
.segmented {
  margin-bottom: 18px;
}
.segmented button {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}
.import-label {
  display: block;
  font-size: 14px;
}
.import-label .text-input {
  display: block;
  width: 100%;
  margin-top: 10px;
  font: inherit;
}
.import-label textarea {
  resize: vertical;
  min-height: 100px;
}
.import-destination {
  margin-bottom: 16px;
}
.import-destination .option-picker {
  margin-top: 10px;
}
.import-feedback {
  display: flex;
  gap: 12px;
  margin-top: 20px;
  padding: 18px;
  border: 1px solid var(--line);
  border-radius: 18px;
  background: var(--surface);
}
.import-feedback > div {
  min-width: 0;
}
.import-feedback strong {
  font-size: 16px;
}
.import-feedback p {
  margin: 7px 0 0;
  font-size: 14px;
  overflow-wrap: anywhere;
  color: var(--muted);
}
</style>
