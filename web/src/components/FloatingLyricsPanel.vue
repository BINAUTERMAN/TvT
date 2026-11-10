<script setup lang="ts">
import { onMounted, reactive } from 'vue';
import { native } from '../bridge';
import { preferences, updatePreference, notify } from '../store';
const settings = reactive({
  size: 20,
  locked: false,
  enabled: false,
  permission: false,
  width: 88,
  accent: 'gold',
});
async function load() {
  try {
    Object.assign(settings, await native('floatingSettings'));
  } catch (error) {
    notify((error as Error).message);
  }
}
async function update(data: Partial<typeof settings>) {
  try {
    Object.assign(settings, await native('floatingSettings', data));
  } catch (error) {
    notify((error as Error).message);
  }
}
async function enable(event: Event) {
  const input = event.target as HTMLInputElement;
  const value = input.checked;
  input.checked = preferences.floatingLyrics;
  await updatePreference('floatingLyrics', value);
  await load();
}
async function resetPosition() {
  try {
    await native('floatingSettings', { resetPosition: true });
    await load();
  } catch (error) {
    notify((error as Error).message);
  }
}
onMounted(() => void load());
</script>
<template>
  <section>
    <h2>桌面歌词</h2>
    <div class="floating-preview" :data-accent="settings.accent">
      <strong>
        <span>让音乐</span>
        在此刻流动
      </strong>
      <small>原文与译文，留在你喜欢的位置</small>
    </div>
    <label class="setting-row">
      <span>显示桌面歌词</span>
      <input
        type="checkbox"
        :checked="preferences.floatingLyrics"
        @change="enable"
      />
    </label>
    <label class="setting-row">
      <span>宽度 {{ settings.width }}%</span>
      <input
        class="lyrics-size"
        type="range"
        min="45"
        max="100"
        :value="settings.width"
        aria-label="歌词宽度"
        @input="
          settings.width = Number(($event.target as HTMLInputElement).value)
        "
        @change="update({ width: settings.width })"
      />
    </label>
    <div class="theme-options" aria-label="歌词高亮颜色">
      <button
        v-for="option in [
          { id: 'white', name: '月白' },
          { id: 'gold', name: '暖金' },
          { id: 'mint', name: '薄荷' },
        ]"
        :key="option.id"
        :aria-pressed="settings.accent === option.id"
        :class="{ selected: settings.accent === option.id }"
        @click="update({ accent: option.id })"
      >
        {{ option.name }}
      </button>
    </div>
    <label class="setting-row">
      <span>字号 {{ settings.size }}</span>
      <input
        class="lyrics-size"
        type="range"
        min="12"
        max="42"
        step="1"
        :value="settings.size"
        aria-label="歌词字号"
        @input="
          settings.size = Number(($event.target as HTMLInputElement).value)
        "
        @change="update({ size: settings.size })"
      />
    </label>
    <label class="setting-row">
      <span>锁定位置，背景透明</span>
      <input
        type="checkbox"
        :checked="settings.locked"
        @change="
          update({ locked: ($event.target as HTMLInputElement).checked })
        "
      />
    </label>
    <p class="muted">
      拖动歌词移动位置，轻点显示编辑按钮。锁定后背景和按钮完全透明、触摸穿透，通知中的歌词按钮可解锁。字体跟随系统；有逐字时间信息时按字高亮，没有时保持逐行显示。
    </p>
    <button class="text-button" @click="resetPosition">重置位置</button>
    <p v-if="!settings.permission" class="muted">
      首次使用需要允许悬浮窗权限。
    </p>
  </section>
</template>
