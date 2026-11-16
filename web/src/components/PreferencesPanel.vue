<script setup lang="ts">
import { Check, FolderDown } from 'lucide-vue-next';
import { library, preferences, updatePreference } from '../store';
import { updates } from '../updates';

const emit = defineEmits<{
  open: [name: 'lyricsSettings' | 'notifications' | 'updates' | 'licenses'];
  cache: [];
  import: [];
}>();
const themes = [
  { id: 'system', name: '跟随系统' },
  { id: 'light', name: '浅色' },
  { id: 'dark', name: '深色' },
];
const options = [
  { key: 'resumeOnLaunch', name: '重新打开时恢复上次播放' },
  { key: 'autoVip', name: '打开应用时自动签到' },
  { key: 'floatingLyrics', name: '桌面歌词' },
  { key: 'preloadAudio', name: '预缓存后续歌曲' },
] as const;
function changePreference(event: Event, key: keyof typeof preferences) {
  const input = event.target as HTMLInputElement;
  const enabled = input.checked;
  input.checked = preferences[key];
  void updatePreference(key, enabled);
}
</script>

<template>
  <div class="preferences-panel">
    <h2>偏好设置</h2>
    <div class="preferences-grid">
      <section class="preference-section">
        <h3>外观</h3>
        <div class="theme-options">
          <button
            v-for="option in themes"
            :key="option.id"
            :class="{ selected: library.theme === option.id }"
            @click="library.theme = option.id"
          >
            {{ option.name }}
            <Check v-if="library.theme === option.id" :size="15" />
          </button>
        </div>
      </section>
      <section class="preference-section playback-preferences">
        <h3>播放与后台</h3>
        <label v-for="option in options" :key="option.key" class="setting-row">
          <span>{{ option.name }}</span>
          <input
            type="checkbox"
            :checked="preferences[option.key]"
            @change="changePreference($event, option.key)"
          />
        </label>
        <p class="muted">桌面歌词支持大部分自定义内容。</p>
        <button class="menu-item" @click="emit('open', 'lyricsSettings')">
          桌面歌词设置
        </button>
        <button class="menu-item" @click="emit('open', 'notifications')">
          锁屏与悬浮通知
        </button>
      </section>
      <section class="preference-section storage-preferences">
        <h3>音乐与存储</h3>
        <button class="menu-item" @click="emit('cache')">管理歌曲缓存</button>
        <button class="menu-item" @click="emit('import')">
          <FolderDown :size="20" />
          导入本地音乐
        </button>
      </section>
      <section class="preference-section about-preferences">
        <h3>关于TvT</h3>
        <button class="menu-item" @click="emit('open', 'updates')">
          应用更新
        </button>
        <button class="text-button" @click="emit('open', 'licenses')">
          开源许可
        </button>
        <p class="muted">
          {{ updates.currentVersion }} · 安卓版 · Android 8.0 及以上
          <br />
          无广告 SDK，无用户行为分析。
          <br />
          GPL-2.0-only · 非酷狗官方应用。
        </p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.preferences-grid {
  display: grid;
  gap: 24px;
}
.preference-section {
  min-width: 0;
}
.preference-section h3 {
  margin-top: 0;
}
.setting-row span {
  min-width: 0;
  overflow-wrap: break-word;
}
.setting-row input {
  flex-shrink: 0;
}
.theme-options {
  flex-wrap: wrap;
}
@media (min-width: 700px) {
  .preferences-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 28px 32px;
    align-items: start;
  }
  .playback-preferences {
    grid-column: 2;
    grid-row: 1 / span 3;
  }
  .storage-preferences,
  .about-preferences {
    grid-column: 1;
  }
}
</style>
