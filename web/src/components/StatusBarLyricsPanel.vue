<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue';
import { native } from '../bridge';
import { notify } from '../store';
import LyricsRange from './LyricsRange.vue';
const settings = reactive({
  enabled: false,
  permission: false,
  size: 12,
  width: 38,
  horizontal: 50,
  vertical: 0,
  side: 'auto',
  hideLandscape: true,
  color: '#ffffff',
  sungColor: '#00d9e9',
  error: '',
});
type Settings = typeof settings;
const busy = ref(false);
const ready = ref(false);
const confirmReset = ref(false);
let disposed = false;
const ranges = [
  { key: 'size', label: '字号', min: 8, max: 24, unit: 'sp' },
  { key: 'width', label: '区域宽度', min: 15, max: 80, unit: '%' },
  { key: 'horizontal', label: '水平位置', min: 0, max: 100, unit: '%' },
  { key: 'vertical', label: '垂直偏移', min: -12, max: 48, unit: 'dp' },
] as const;
async function request(
  updates: Partial<Settings> & { resetDefaults?: boolean } = {},
  preview = false,
) {
  if (busy.value || disposed || document.hidden) return;
  busy.value = true;
  try {
    const value = await native<Settings>(
      preview ? 'statusLyricsPreview' : 'statusLyricsSettings',
      updates,
    );
    if (!disposed && !document.hidden) {
      Object.assign(settings, value);
      ready.value = true;
    }
  } catch (error) {
    if (!disposed) notify((error as Error).message);
  } finally {
    busy.value = false;
  }
}
function load() {
  void request();
}
function toggle(event: Event, key: 'enabled' | 'hideLandscape') {
  const input = event.target as HTMLInputElement;
  const value = input.checked;
  input.checked = settings[key];
  void request({ [key]: value });
}
function color(event: Event, key: 'color' | 'sungColor') {
  void request({ [key]: (event.target as HTMLInputElement).value });
}
function side(event: Event) {
  void request({ side: (event.target as HTMLSelectElement).value });
}
function reset() {
  confirmReset.value = false;
  void request({ resetDefaults: true });
}
onMounted(() => {
  load();
  window.addEventListener('ting:resume', load);
  document.addEventListener('visibilitychange', load);
});
onUnmounted(() => {
  disposed = true;
  window.removeEventListener('ting:resume', load);
  document.removeEventListener('visibilitychange', load);
});
</script>
<template>
  <section class="status-lyrics-settings">
    <h3>状态栏歌词</h3>
    <p class="muted">
      在屏幕顶端显示独立的小号单行歌词，播放时显示，暂停或熄屏时隐藏。
    </p>
    <fieldset :disabled="busy || !ready" :aria-busy="busy">
      <label class="setting-row">
        <span>开启状态栏歌词</span>
        <input
          type="checkbox"
          :checked="settings.enabled"
          @change="toggle($event, 'enabled')"
        />
      </label>
      <p class="muted">
        需要悬浮窗权限。部分系统会遮挡状态栏区域，请用 5
        秒预览确认。默认在状态栏内按屏幕中线居中，可调节水平位置、垂直偏移，或手动选择左右侧。
      </p>
      <p v-if="settings.error" class="login-error" role="alert">
        {{ settings.error }}
      </p>
      <button
        class="secondary-button"
        :disabled="!settings.enabled"
        @click="request({}, true)"
      >
        预览状态栏位置（5秒）
      </button>
      <label class="setting-row">
        <span>显示位置</span>
        <select
          aria-label="状态栏歌词显示位置"
          :value="settings.side"
          @change="side"
        >
          <option value="auto">屏幕居中</option>
          <option value="left">状态栏左侧</option>
          <option value="right">状态栏右侧</option>
        </select>
      </label>
      <div
        v-for="range in ranges"
        :key="range.key"
        class="setting-row status-range"
      >
        <span>
          {{ range.label }}
          <small>{{ settings[range.key] }}{{ range.unit }}</small>
        </span>
        <LyricsRange
          v-model="settings[range.key]"
          :label="`状态栏歌词${range.label}`"
          :min="range.min"
          :max="range.max"
          @change="request({ [range.key]: $event })"
        />
      </div>
      <label class="setting-row">
        <span>状态栏未唱颜色</span>
        <input
          type="color"
          aria-label="状态栏未唱颜色"
          :value="settings.color"
          @change="color($event, 'color')"
        />
      </label>
      <label class="setting-row">
        <span>状态栏已唱颜色</span>
        <input
          type="color"
          aria-label="状态栏已唱颜色"
          :value="settings.sungColor"
          @change="color($event, 'sungColor')"
        />
      </label>
      <label class="setting-row">
        <span>横屏时隐藏状态栏歌词</span>
        <input
          type="checkbox"
          :checked="settings.hideLandscape"
          @change="toggle($event, 'hideLandscape')"
        />
      </label>
      <button class="text-button" @click="confirmReset = true">
        恢复状态栏默认参数
      </button>
      <div v-if="confirmReset" role="group" aria-label="恢复状态栏默认参数确认">
        <p class="muted">恢复状态栏字号、配色与位置，保留开启状态。</p>
        <button class="secondary-button" @click="confirmReset = false">
          取消恢复状态栏
        </button>
        <button class="primary" @click="reset">确认恢复状态栏默认</button>
      </div>
    </fieldset>
  </section>
</template>
<style scoped>
.status-lyrics-settings {
  padding: 0 16px 16px;
}
.status-lyrics-settings h3 {
  display: none;
}
.status-lyrics-settings > p {
  font-size: 12px;
}
.setting-row {
  min-height: 54px;
  padding: 10px 0;
  gap: 12px;
  font-size: 13px;
}
.setting-row input[type='checkbox']:checked {
  background: #007aff;
}
.setting-row input[type='checkbox']:checked::after {
  background: #fff;
}
fieldset {
  padding: 0;
  margin: 0;
  border: 0;
  min-width: 0;
}
.status-range {
  display: block;
  padding: 14px 0 6px;
}
.status-range > span {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}
.secondary-button {
  width: 100%;
  min-height: 44px;
  border-radius: 13px;
  color: #007aff;
  background: var(--bg);
  font-size: 12px;
}
.text-button {
  min-height: 44px;
  color: #007aff;
  font-size: 12px;
}
small {
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}
select {
  max-width: 64%;
  min-height: 40px;
  color: var(--ink);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 4px;
}
input[type='color'] {
  width: 44px;
  height: 36px;
  padding: 2px;
  border: 0;
  background: transparent;
}
[role='group'] button {
  margin-right: 10px;
}
</style>
