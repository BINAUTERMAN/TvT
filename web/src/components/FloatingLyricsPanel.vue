<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue';
import { native } from '../bridge';
import LyricsRange from './LyricsRange.vue';
import StatusBarLyricsPanel from './StatusBarLyricsPanel.vue';
import LyricsPreview from './LyricsPreview.vue';
import AnimatedDisclosure from './AnimatedDisclosure.vue';
import OptionPicker from './OptionPicker.vue';
import { preferences, updatePreference, notify } from '../store';

const settings = reactive({
  size: 20,
  width: 88,
  locked: false,
  enabled: false,
  permission: false,
  highlightColor: '#35baff',
  textColor: '#c5ebff',
  secondaryColor: '#b1d9f0',
  backgroundColor: '#1c1c20',
  opacity: 100,
  backgroundOpacity: 0,
  bold: true,
  lineSpacing: 5,
  secondarySize: 70,
  shadow: 1,
  outline: 1,
  gradient: true,
  smooth: true,
  align: 'center',
  mode: 'next',
  snap: true,
  snapDistance: 24,
});
type Settings = typeof settings;
const ready = ref(false);
const busy = ref(false);
const failed = ref(false);
const resetConfirm = ref(false);
let disposed = false;
const ranges = [
  { key: 'size', label: '字号', min: 12, max: 48, unit: 'sp' },
  { key: 'width', label: '窗口宽度', min: 30, max: 100, unit: '%' },
  { key: 'opacity', label: '整体不透明度', min: 30, max: 100, unit: '%' },
  {
    key: 'backgroundOpacity',
    label: '背景不透明度',
    min: 0,
    max: 90,
    unit: '%',
  },
  { key: 'lineSpacing', label: '行距', min: 0, max: 20, unit: 'dp' },
  { key: 'secondarySize', label: '副行字号比例', min: 50, max: 100, unit: '%' },
  { key: 'shadow', label: '文字阴影', min: 0, max: 6, unit: 'dp' },
  { key: 'outline', label: '文字描边', min: 0, max: 4, unit: 'dp' },
  { key: 'snapDistance', label: '边缘吸附距离', min: 8, max: 64, unit: 'dp' },
] as const;
const colors = [
  { key: 'highlightColor', label: '已唱颜色' },
  { key: 'textColor', label: '未唱颜色' },
  { key: 'secondaryColor', label: '副行颜色' },
  { key: 'backgroundColor', label: '背景颜色' },
] as const;
const presets = [
  {
    name: '经典蓝',
    highlightColor: '#35baff',
    textColor: '#c5ebff',
    secondaryColor: '#b1d9f0',
  },
  {
    name: '暖金',
    highlightColor: '#ffe4ac',
    textColor: '#ffffff',
    secondaryColor: '#dedee5',
  },
  {
    name: '薄荷',
    highlightColor: '#c0f4dc',
    textColor: '#ffffff',
    secondaryColor: '#d4e9e0',
  },
  {
    name: '晴空',
    highlightColor: '#83d9ff',
    textColor: '#ffffff',
    secondaryColor: '#c7dcea',
  },
  {
    name: '樱粉',
    highlightColor: '#ffacd3',
    textColor: '#ffffff',
    secondaryColor: '#ead2df',
  },
] as const;
function accept(value: Settings) {
  Object.assign(settings, value);
  preferences.floatingLyrics = value.enabled;
}
async function load() {
  if (document.hidden || busy.value || disposed) return;
  busy.value = true;
  failed.value = false;
  try {
    const value = await native<Settings>('floatingSettings');
    if (!disposed && !document.hidden) {
      accept(value);
      ready.value = true;
    }
  } catch (error) {
    if (!disposed) {
      failed.value = true;
      notify((error as Error).message);
    }
  } finally {
    busy.value = false;
  }
}
async function update(
  data: Partial<Settings> & {
    resetPosition?: boolean;
    resetDefaults?: boolean;
  },
) {
  if (busy.value || !ready.value || document.hidden) return;
  busy.value = true;
  let reload = false;
  try {
    const value = await native<Settings>('floatingSettings', data);
    if (!disposed && !document.hidden) accept(value);
  } catch (error) {
    reload = true;
    if (!disposed) notify((error as Error).message);
  } finally {
    busy.value = false;
    // 写入失败时读取原生值，避免预览与实际效果不一致。
    if (reload && !disposed) await load();
  }
}
async function resetDefaults() {
  resetConfirm.value = false;
  await update({ resetDefaults: true });
}
async function enable(event: Event) {
  const input = event.target as HTMLInputElement;
  const value = input.checked;
  input.checked = preferences.floatingLyrics;
  busy.value = true;
  await updatePreference('floatingLyrics', value);
  busy.value = false;
  await load();
}
function changeColor(event: Event, key: (typeof colors)[number]['key']) {
  settings[key] = (event.target as HTMLInputElement).value;
}
function changeToggle(
  event: Event,
  key: 'bold' | 'snap' | 'locked' | 'gradient' | 'smooth',
) {
  void update({ [key]: (event.target as HTMLInputElement).checked });
}
onMounted(() => {
  void load();
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
  <section class="floating-settings">
    <header class="lyrics-settings-header">
      <h2>桌面歌词</h2>
    </header>
    <LyricsPreview v-if="ready" :settings="settings" />
    <button v-if="failed" class="text-button" @click="load">
      重新读取设置
    </button>
    <fieldset :disabled="!ready || busy" :aria-busy="busy">
      <div class="lyrics-group">
        <label class="setting-row">
          <span>显示桌面歌词</span>
          <input
            type="checkbox"
            :checked="preferences.floatingLyrics"
            @change="enable"
          />
        </label>
        <label class="setting-row">
          <span>显示模式</span>
          <OptionPicker
            label="歌词显示模式"
            :model-value="settings.mode"
            :options="[
              { value: 'single', label: '单行原文' },
              { value: 'translation', label: '原文与译文' },
              { value: 'next', label: '双行交替' },
            ]"
            @update:model-value="update({ mode: $event })"
          />
        </label>
      </div>
      <p
        v-if="ready && !settings.permission"
        class="lyrics-footnote"
        role="status"
      >
        开启时需要授予悬浮窗权限。
      </p>
      <h3>常用调整</h3>
      <div class="lyrics-group">
        <div
          v-for="range in ranges.slice(0, 3)"
          :key="range.key"
          class="lyrics-slider-row"
        >
          <div class="lyrics-slider-title">
            <span>{{ range.label }}</span>
            <output>
              {{ settings[range.key] }}
              <small>{{ range.unit }}</small>
            </output>
          </div>
          <LyricsRange
            v-model="settings[range.key]"
            :label="`歌词${range.label}`"
            :min="range.min"
            :max="range.max"
            @change="update({ [range.key]: $event })"
          />
        </div>
      </div>
      <h3>配色</h3>
      <div class="lyrics-presets" aria-label="歌词配色预设">
        <button
          v-for="preset in presets"
          :key="preset.name"
          :aria-pressed="
            settings.highlightColor === preset.highlightColor &&
            settings.textColor === preset.textColor &&
            settings.secondaryColor === preset.secondaryColor
          "
          @click="
            update({
              highlightColor: preset.highlightColor,
              textColor: preset.textColor,
              secondaryColor: preset.secondaryColor,
            })
          "
        >
          <i :style="{ background: preset.highlightColor }" />
          {{ preset.name }}
        </button>
      </div>
      <AnimatedDisclosure class="lyrics-details" title="文字与配色">
        <div class="lyrics-group">
          <div class="lyrics-colors">
            <label v-for="color in colors" :key="color.key" class="setting-row">
              <span>{{ color.label }}</span>
              <input
                type="color"
                :aria-label="color.label"
                :value="settings[color.key]"
                @input="changeColor($event, color.key)"
                @change="update({ [color.key]: settings[color.key] })"
              />
            </label>
          </div>
          <label class="setting-row">
            <span>文字对齐</span>
            <OptionPicker
              label="歌词文字对齐"
              :model-value="settings.align"
              :options="[
                { value: 'left', label: '居左' },
                { value: 'center', label: '居中' },
                { value: 'right', label: '居右' },
              ]"
              @update:model-value="update({ align: $event })"
            />
          </label>
          <label
            v-for="toggle in [
              { key: 'bold' as const, label: '加粗文字' },
              { key: 'gradient' as const, label: '字色渐变' },
              { key: 'smooth' as const, label: '平滑逐字与长句移动' },
            ]"
            :key="toggle.key"
            class="setting-row"
          >
            <span>{{ toggle.label }}</span>
            <input
              type="checkbox"
              :checked="settings[toggle.key]"
              @change="changeToggle($event, toggle.key)"
            />
          </label>
          <div
            v-for="range in ranges.slice(3, 8)"
            :key="range.key"
            class="lyrics-slider-row"
            :class="{
              'lyrics-range-disabled':
                range.key === 'secondarySize' &&
                settings.mode !== 'translation',
            }"
          >
            <div class="lyrics-slider-title">
              <span>{{ range.label }}</span>
              <output>
                {{ settings[range.key] }}
                <small>{{ range.unit }}</small>
              </output>
            </div>
            <LyricsRange
              v-model="settings[range.key]"
              :label="`歌词${range.label}`"
              :min="range.min"
              :max="range.max"
              :disabled="
                range.key === 'secondarySize' && settings.mode !== 'translation'
              "
              @change="update({ [range.key]: $event })"
            />
          </div>
        </div>
      </AnimatedDisclosure>
      <h3>位置与操作</h3>
      <div class="lyrics-group">
        <label class="setting-row">
          <span>靠近屏幕边缘自动吸附</span>
          <input
            type="checkbox"
            :checked="settings.snap"
            @change="changeToggle($event, 'snap')"
          />
        </label>
        <label class="setting-row">
          <span>锁定位置，触摸穿透</span>
          <input
            type="checkbox"
            :checked="settings.locked"
            @change="changeToggle($event, 'locked')"
          />
        </label>
        <div
          class="lyrics-slider-row"
          :class="{ 'lyrics-range-disabled': !settings.snap }"
        >
          <div class="lyrics-slider-title">
            <span>边缘吸附距离</span>
            <output>
              {{ settings.snapDistance }}
              <small>dp</small>
            </output>
          </div>
          <LyricsRange
            v-model="settings.snapDistance"
            label="歌词边缘吸附距离"
            :min="8"
            :max="64"
            :disabled="!settings.snap"
            @change="update({ snapDistance: $event })"
          />
        </div>
      </div>
      <div class="lyrics-actions">
        <button @click="update({ resetPosition: true })">重置横竖屏位置</button>
        <button @click="resetConfirm = true">恢复默认参数</button>
      </div>
      <div
        v-if="resetConfirm"
        class="lyrics-reset-confirm"
        role="group"
        aria-label="恢复默认参数确认"
      >
        <p>恢复默认外观、位置并解除锁定，保留开启状态。</p>
        <div class="lyrics-actions">
          <button @click="resetConfirm = false">取消恢复</button>
          <button class="confirm-reset" @click="resetDefaults">
            确认恢复默认
          </button>
        </div>
      </div>
    </fieldset>
    <AnimatedDisclosure
      class="lyrics-details status-details"
      title="状态栏歌词"
    >
      <StatusBarLyricsPanel />
    </AnimatedDisclosure>
  </section>
</template>
<style scoped>
.floating-settings {
  --lyrics-tint: #007aff;
}
.lyrics-settings-header h2 {
  margin-bottom: 6px;
}
.lyrics-settings-header p {
  margin: 0;
  font-size: 13px;
}
fieldset {
  border: 0;
  margin: 0;
  padding: 0;
  min-width: 0;
}
h3 {
  margin: 22px 4px 8px;
  font-size: 12px;
  font-weight: 500;
  color: var(--muted);
}
.lyrics-group {
  border-radius: 18px;
  background: var(--surface);
  padding: 0 16px;
}
.lyrics-group > :last-child {
  border-bottom: 0;
}
.setting-row {
  min-height: 54px;
  padding: 10px 0;
  gap: 12px;
  font-size: 14px;
}
.setting-row input[type='checkbox']:checked {
  background: var(--lyrics-tint);
}
.setting-row input[type='checkbox']:checked::after {
  background: #fff;
}
.lyrics-slider-row {
  padding: 14px 0 6px;
  border-bottom: 1px solid var(--line);
}
.lyrics-slider-title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  font-size: 13px;
}
.lyrics-slider-title output {
  font-size: 14px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.lyrics-slider-title small {
  margin-left: 3px;
  font-size: 10px;
  font-weight: 400;
  color: var(--muted);
}
.lyrics-range-disabled {
  opacity: 0.45;
}
.lyrics-presets {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 6px;
  margin-bottom: 14px;
}
.lyrics-presets button {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  min-height: 64px;
  border-radius: 14px;
  background: var(--surface);
  padding: 10px 2px;
  font-size: 11px;
  border: 1px solid transparent;
}
.lyrics-presets button[aria-pressed='true'] {
  border-color: var(--lyrics-tint);
  color: var(--lyrics-tint);
}
.lyrics-presets i {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  box-shadow: inset 0 0 0 1px #00000012;
}
.lyrics-details {
  margin: 16px 0;
  border-radius: 18px;
  background: var(--surface);
}
.lyrics-details :deep(summary) {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  min-height: 60px;
  padding: 14px 16px;
  cursor: pointer;
  font-size: 14px;
  list-style: none;
}
.lyrics-details :deep(summary::-webkit-details-marker) {
  display: none;
}
.lyrics-details :deep(summary::after) {
  content: '›';
  color: var(--muted);
  font-size: 22px;
  transform: rotate(0);
  margin-left: auto;
  transition: transform var(--motion-duration) var(--motion-ease);
}
.lyrics-details[data-expanded='true'] :deep(summary::after) {
  transform: rotate(90deg);
}
.lyrics-colors {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 14px;
}
.lyrics-colors .setting-row {
  font-size: 12px;
}
.lyrics-colors input {
  width: 32px;
  height: 32px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: transparent;
}
.lyrics-footnote {
  margin: 10px 4px 16px;
  font-size: 11px;
  line-height: 1.65;
  color: var(--muted);
}
.lyrics-actions {
  display: flex;
  gap: 10px;
  margin-top: 14px;
}
.lyrics-actions button {
  flex: 1;
  min-height: 44px;
  padding: 10px 6px;
  border-radius: 13px;
  font-size: 12px;
  background: var(--surface);
  color: var(--lyrics-tint);
}
.lyrics-reset-confirm {
  padding: 14px;
  margin-top: 12px;
  background: var(--surface);
  border-radius: 18px;
  font-size: 12px;
}
.lyrics-reset-confirm .confirm-reset {
  color: #fff;
  background: var(--lyrics-tint);
}
@media (max-width: 360px) {
  .lyrics-group {
    padding: 0 12px;
  }
  .lyrics-colors {
    grid-template-columns: 1fr;
  }
  .lyrics-details summary > span {
    display: none;
  }
}
</style>
