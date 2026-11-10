<script setup lang="ts">
import { ref } from 'vue';
import { Star } from 'lucide-vue-next';
import { native } from '../bridge';

const emit = defineEmits<{ close: [] }>();
const busy = ref(false);
const error = ref('');
async function openRepository() {
  busy.value = true;
  try {
    await native('openRepository');
    emit('close');
  } catch (cause) {
    error.value = (cause as Error).message;
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="star-prompt">
    <Star :size="34" :stroke-width="1.5" />
    <h2>喜欢 TvT 吗？</h2>
    <p>你已经使用本软件播放5首歌了，如果喜欢TvT点颗Star ⭐怎么样？</p>
    <p v-if="error" class="login-error" role="alert">{{ error }}</p>
    <div class="star-actions">
      <button class="secondary-button" :disabled="busy" @click="emit('close')">
        下次一定
      </button>
      <button class="primary" :disabled="busy" @click="openRepository">
        这就去！
      </button>
    </div>
  </div>
</template>

<style scoped>
.star-prompt > svg {
  color: #8b6ad6;
  margin-bottom: 12px;
}
.star-prompt > p {
  color: var(--muted);
  line-height: 1.8;
}
.star-actions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-top: 24px;
}
.star-actions button {
  min-height: 48px;
  border-radius: 999px;
  justify-content: center;
}
</style>
