<script setup lang="ts">
import { ref } from 'vue';
import { Download, RefreshCw, ArrowUpRight } from 'lucide-vue-next';
import {
  updates,
  checkUpdates,
  downloadUpdate,
  downloadStatus,
  installUpdate,
  changeUpdateChoice,
  enableUpdates,
} from '../updates';
import { command } from '../store';
const confirmation = ref<'disable' | 'skip' | ''>('');
const dontWarn = ref(false);
function toggle(event: Event) {
  (event.target as HTMLInputElement).checked = updates.enabled;
  if (updates.enabled) void choose('disable');
  else void enableUpdates();
}
async function choose(action: 'disable' | 'skip') {
  if (updates.dontWarn) {
    await changeUpdateChoice(action, true);
    return;
  }
  dontWarn.value = false;
  confirmation.value = action;
}
async function confirm() {
  if (!confirmation.value) return;
  await changeUpdateChoice(confirmation.value, dontWarn.value);
  confirmation.value = '';
}
</script>
<template>
  <div class="update-panel">
    <h2>应用更新</h2>
    <p class="muted">当前版本 {{ updates.currentVersion }}</p>
    <label class="setting-row">
      <span>自动检查更新</span>
      <input type="checkbox" :checked="updates.enabled" @change="toggle" />
    </label>
    <p class="muted">
      打开应用时检查 GitHub 稳定发行版，发现新版后提示，由你确认下载和安装。
    </p>
    <button
      class="menu-item"
      :disabled="updates.checking"
      @click="checkUpdates(true)"
    >
      <RefreshCw :size="20" />
      {{ updates.checking ? '正在检查…' : '检查更新' }}
    </button>
    <p v-if="!updates.configured" class="muted">
      此构建尚未配置更新仓库，发布到 GitHub 后可启用。
    </p>
    <section v-if="updates.release.available" class="release-notes">
      <h3>
        {{ updates.release.rollback ? '回退修复版' : '发现新版本' }}
        {{ updates.release.version }}
      </h3>
      <p v-if="updates.release.name" class="muted">
        {{ updates.release.name }}
      </p>
      <p class="release-body">
        {{ updates.release.notes || '作者暂未填写更新说明。' }}
      </p>
      <button
        v-if="updates.state === 'ready'"
        class="primary full-width"
        @click="installUpdate"
      >
        安装更新
      </button>
      <button
        v-else-if="updates.state === 'downloading'"
        class="secondary-button full-width"
        @click="downloadStatus"
      >
        正在下载 · 刷新进度
      </button>
      <button
        v-else
        class="primary full-width"
        :disabled="updates.downloading"
        @click="downloadUpdate"
      >
        <Download :size="18" />
        {{ updates.state === 'failed' ? '重新下载' : '下载更新' }}
      </button>
      <button class="menu-item" @click="choose('skip')">跳过此版本</button>
      <p v-if="updates.state === 'ready'" class="muted">
        首次安装更新需按系统提示允许安装，然后再次点击“安装更新”。
      </p>
    </section>
    <p v-if="updates.error" class="login-error" role="alert">
      {{ updates.error }}
    </p>
    <button
      v-if="updates.configured"
      class="menu-item"
      @click="command('updateReleases')"
    >
      历史版本与发行说明
      <ArrowUpRight :size="18" />
    </button>
    <div
      v-if="confirmation"
      class="choice-confirmation"
      role="alertdialog"
      aria-label="确认更新选择"
    >
      <h3>你确定要这么做吗，可能会影响某些功能</h3>
      <p class="muted">
        {{
          confirmation === 'disable'
            ? '关闭后不会自动提示新版本，你仍可手动检查。'
            : '只跳过此版本，之后的新版本仍会提示。'
        }}
      </p>
      <label class="setting-row">
        <span>再也不提醒</span>
        <input v-model="dontWarn" type="checkbox" />
      </label>
      <div class="confirmation-actions">
        <button class="secondary-button" @click="confirmation = ''">
          取消
        </button>
        <button class="primary" @click="confirm">确定</button>
      </div>
    </div>
  </div>
</template>
