<script setup lang="ts">
import { ref } from 'vue';
import { profile } from '../store';
import { changeCloudLists } from '../cloud-playlists';
const emit = defineEmits<{ created: [id: string]; account: [] }>();
const name = ref('');
const privateList = ref(true);
const busy = ref(false);
const error = ref('');
async function create() {
  if (busy.value || !name.value.trim()) return;
  if (!profile.loggedIn) {
    emit('account');
    return;
  }
  busy.value = true;
  error.value = '';
  try {
    const id = await changeCloudLists('create', {
      name: name.value.trim(),
      private: privateList.value,
    });
    emit('created', id);
  } catch (cause) {
    error.value = (cause as Error).message;
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <section class="playlist-create">
    <h2>新建歌单</h2>
    <p class="muted">歌单与当前酷狗账号同步。</p>
    <button
      v-if="!profile.loggedIn"
      class="primary full-width"
      @click="emit('account')"
    >
      登录后创建歌单
    </button>
    <form v-else @submit.prevent="create">
      <input
        v-model="name"
        class="text-input"
        autofocus
        maxlength="40"
        placeholder="给歌单起个名字"
        aria-label="歌单名称"
        :disabled="busy"
      />
      <label class="setting-row">
        <span>设为私密歌单</span>
        <input v-model="privateList" type="checkbox" :disabled="busy" />
      </label>
      <button class="primary full-width" :disabled="busy || !name.trim()">
        {{ busy ? '正在创建…' : '创建歌单' }}
      </button>
    </form>
    <p v-if="error" class="inline-error" role="status">{{ error }}</p>
  </section>
</template>
<style scoped>
.segmented {
  margin-bottom: 14px;
}
.segmented button {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 6px;
}
</style>
