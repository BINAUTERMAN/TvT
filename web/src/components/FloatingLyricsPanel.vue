<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
import { native } from '../bridge';
import { preferences, updatePreference, notify } from '../store';

const settings = reactive({
  size: 20,
  width: 88,
  locked: false,
  enabled: false,
  permission: false,
  highlightColor: '#00d9e9',
  textColor: '#8fffad',
  secondaryColor: '#dedee5',
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
    name: '清透',
    highlightColor: '#00d9e9',
    textColor: '#8fffad',
    secondaryColor: '#dedee5',
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
const previewStyle = computed(() => ({
  width: `${settings.width}%`,
  color: settings.textColor,
  backgroundColor: `${settings.backgroundColor}${Math.round(
    settings.backgroundOpacity * 2.55,
  )
    .toString(16)
    .padStart(2, '0')}`,
  opacity: settings.opacity / 100,
  fontSize: `${settings.size}px`,
  fontWeight: settings.bold ? 700 : 400,
  textAlign: settings.align as 'left' | 'center' | 'right',
  gap: `${settings.lineSpacing}px`,
  textShadow: settings.shadow ? `0 1px ${settings.shadow}px #000` : 'none',
  WebkitTextStroke: `${settings.outline / 2}px #141418bd`,
}));
const highlightStyle = computed(() => ({
  color: settings.highlightColor,
  backgroundImage: settings.gradient
    ? `linear-gradient(#ffffff88, #ffffff00), linear-gradient(${settings.highlightColor}, ${settings.highlightColor})`
    : 'none',
  backgroundClip: settings.gradient ? 'text' : 'border-box',
  WebkitTextFillColor: settings.gradient
    ? 'transparent'
    : settings.highlightColor,
}));
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
async function update(data: Partial<Settings> & { resetPosition?: boolean }) {
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
async function enable(event: Event) {
  const input = event.target as HTMLInputElement;
  const value = input.checked;
  input.checked = preferences.floatingLyrics;
  busy.value = true;
  await updatePreference('floatingLyrics', value);
  busy.value = false;
  await load();
}
function changeRange(event: Event, key: (typeof ranges)[number]['key']) {
  settings[key] = Number((event.target as HTMLInputElement).value);
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
function changeSelect(event: Event, key: 'mode' | 'align') {
  void update({ [key]: (event.target as HTMLSelectElement).value });
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
    <h2>桌面歌词</h2>
    <p class="muted">把喜欢的字色和位置，留在桌面上。</p>
    <div class="lyrics-preview-stage" aria-label="桌面歌词外观预览">
      <div class="floating-preview" :style="previewStyle">
        <strong>
          <span :style="highlightStyle">让音乐</span>
          在此刻流动
        </strong>
        <small
          v-if="settings.mode !== 'single'"
          :style="{
            color: settings.secondaryColor,
            fontSize: `${settings.mode === 'next' ? 100 : settings.secondarySize}%`,
          }"
        >
          {{
            settings.mode === 'next'
              ? '下一句，陪你走过每个日常'
              : 'Let the music flow in this moment'
          }}
        </small>
      </div>
    </div>
    <p class="muted">
      静态外观预览；实际字体随系统缩放，锁定后系统可能降低不透明度以允许触摸穿透。
    </p>
    <button v-if="failed" class="text-button" @click="load">
      重新读取设置
    </button>
    <fieldset :disabled="!ready || busy" :aria-busy="busy">
      <label class="setting-row">
        <span>显示桌面歌词</span>
        <input
          type="checkbox"
          :checked="preferences.floatingLyrics"
          @change="enable"
        />
      </label>
      <p v-if="ready && !settings.permission" class="muted" role="status">
        尚未获得悬浮窗权限，开启时会进入系统授权页面。
      </p>
      <h3>配色</h3>
      <div class="theme-options lyrics-presets" aria-label="歌词配色预设">
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
      <h3>文字与布局</h3>
      <label class="setting-row">
        <span>显示模式</span>
        <select
          aria-label="歌词显示模式"
          :value="settings.mode"
          @change="changeSelect($event, 'mode')"
        >
          <option value="single">单行原文</option>
          <option value="translation">原文与译文 / 下一句</option>
          <option value="next">双行交替（原文与下一句）</option>
        </select>
      </label>
      <label class="setting-row">
        <span>文字对齐</span>
        <select
          aria-label="歌词文字对齐"
          :value="settings.align"
          @change="changeSelect($event, 'align')"
        >
          <option value="left">居左</option>
          <option value="center">居中</option>
          <option value="right">居右</option>
        </select>
      </label>
      <label class="setting-row">
        <span>加粗文字</span>
        <input
          type="checkbox"
          :checked="settings.bold"
          @change="changeToggle($event, 'bold')"
        />
      </label>
      <label class="setting-row">
        <span>字色渐变</span>
        <input
          type="checkbox"
          :checked="settings.gradient"
          @change="changeToggle($event, 'gradient')"
        />
      </label>
      <label class="setting-row">
        <span>平滑逐字与长句移动</span>
        <input
          type="checkbox"
          :checked="settings.smooth"
          @change="changeToggle($event, 'smooth')"
        />
      </label>
      <p class="muted">
        有真实逐字时间时连续扫色，长句随演唱进度横移；双行上下交替接唱。无逐字时间时整句高亮。暂停和灭屏时停止动画。
      </p>
      <label
        v-for="range in ranges"
        :key="range.key"
        class="setting-row lyrics-range"
        :class="{
          'lyrics-range-disabled':
            (range.key === 'snapDistance' && !settings.snap) ||
            (range.key === 'secondarySize' && settings.mode !== 'translation'),
        }"
      >
        <span>
          {{ range.label }}
          <small>{{ settings[range.key] }}{{ range.unit }}</small>
        </span>
        <input
          class="lyrics-size"
          type="range"
          :aria-label="`歌词${range.label}`"
          :min="range.min"
          :max="range.max"
          :value="settings[range.key]"
          :disabled="
            (range.key === 'snapDistance' && !settings.snap) ||
            (range.key === 'secondarySize' && settings.mode !== 'translation')
          "
          @input="changeRange($event, range.key)"
          @change="update({ [range.key]: settings[range.key] })"
        />
      </label>
      <h3>位置与操作</h3>
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
      <p class="muted">
        拖动歌词调整位置，松手靠近四边时自动吸附。横竖屏分别记忆位置，避让系统栏与刘海；宽度按可用屏幕比例适配手机和平板。
      </p>
      <p class="muted">
        轻点歌词展开控制面板，再点收起；背景设为 0%
        时收起后只保留文字。锁定后可从播放通知中的歌词按钮解锁，也可回到这里关闭锁定。
      </p>
      <button class="text-button" @click="update({ resetPosition: true })">
        重置横竖屏位置
      </button>
    </fieldset>
  </section>
</template>

<style scoped>
.floating-settings fieldset {
  border: 0;
  margin: 0;
  padding: 0;
  min-width: 0;
}
.floating-settings h3 {
  margin: 24px 0 8px;
  font-size: 15px;
}
.lyrics-preview-stage {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 156px;
  margin-top: 16px;
  padding: 16px 0;
  border-radius: 20px;
  background: repeating-linear-gradient(
    135deg,
    var(--surface) 0 12px,
    var(--soft) 12px 24px
  );
  overflow: hidden;
}
.floating-preview {
  min-width: 0;
  margin: 0;
  padding: 12px;
  box-sizing: border-box;
  border-radius: 18px;
  box-shadow: none;
  overflow-wrap: anywhere;
}
.floating-preview strong {
  font-size: inherit;
  font-weight: inherit;
  line-height: 1.35;
}
.floating-preview small {
  line-height: 1.4;
}
.lyrics-presets {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(58px, 1fr));
}
.lyrics-presets button {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-width: 0;
  padding: 10px 4px;
}
.lyrics-presets i {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  border: 1px solid #0002;
  flex-shrink: 0;
}
.lyrics-colors {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}
.lyrics-colors input {
  width: 44px;
  height: 36px;
  border: 0;
  padding: 2px;
  background: transparent;
}
.setting-row select {
  max-width: 62%;
  min-height: 40px;
  background: var(--surface);
  color: var(--ink);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 4px;
}
.lyrics-range {
  gap: 10px;
}
.lyrics-range > span {
  display: grid;
  gap: 4px;
}
.lyrics-range small {
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}
.lyrics-range-disabled {
  opacity: 0.5;
}
@media (max-width: 360px) {
  .lyrics-colors {
    grid-template-columns: 1fr;
  }
}
</style>
