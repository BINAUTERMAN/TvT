<script setup lang="ts">
import { ref, watch } from 'vue';
import { Check, AudioLines } from 'lucide-vue-next';
import { native } from '../bridge';
import { current, player, command } from '../store';
import ContentTransition from './ContentTransition.vue';
interface QualityOption {
  quality: string;
  label: string;
  exists: boolean;
  restricted: boolean;
  size: number;
  bitrate: number;
}
const options = ref<QualityOption[]>([]);
const loading = ref(false),
  error = ref(''),
  changing = ref('');
let version = 0;
async function load() {
  const request = ++version;
  options.value = [];
  error.value = '';
  if (!current.value?.hash) {
    error.value = '本地音乐使用文件原有音质';
    return;
  }
  loading.value = true;
  try {
    const value = await native<QualityOption[]>('qualityInfo', current.value);
    if (request === version) options.value = value;
  } catch (cause) {
    if (request === version) error.value = (cause as Error).message;
  } finally {
    if (request === version) loading.value = false;
  }
}
async function choose(option: QualityOption) {
  if (!option.exists || changing.value) return;
  changing.value = option.quality;
  await command('quality', { quality: option.quality });
  changing.value = '';
}
function detail(option: QualityOption) {
  if (!option.exists) return '平台暂无此音质资源';
  const size =
    option.size >= 1048576
      ? `${(option.size / 1048576).toFixed(1)} MB`
      : option.size > 0
        ? `${(option.size / 1024).toFixed(1)} KB`
        : '资源已提供';
  return `${size}${option.restricted ? ' · 依账号权益播放' : ''}`;
}
watch(
  () => current.value?.id,
  () => void load(),
  { immediate: true },
);
</script>
<template>
  <section class="quality-panel">
    <h2>播放音质</h2>
    <p class="muted">根据当前歌曲的真实资源显示，播放权限由平台确认。</p>
    <ContentTransition
      :identity="`${current?.id}:${loading ? 'loading' : 'ready'}`"
    >
      <div v-if="loading" class="loading">正在查询音质…</div>
      <div class="quality-options" role="radiogroup" aria-label="播放音质">
        <button
          v-for="option in options"
          :key="option.quality"
          class="quality-option"
          role="radio"
          :aria-checked="player.quality === option.quality"
          :class="{ selected: player.quality === option.quality }"
          :disabled="!option.exists || !!changing || player.loading"
          @click="choose(option)"
        >
          <AudioLines :size="22" />
          <span>
            <strong>{{ option.label }}</strong>
            <small>{{ detail(option) }}</small>
          </span>
          <Check v-if="player.quality === option.quality" :size="20" />
        </button>
      </div>
      <div v-if="error" class="inline-error">
        {{ error }}
        <button v-if="current?.hash" class="text-button" @click="load">
          重试
        </button>
      </div>
    </ContentTransition>
  </section>
</template>
