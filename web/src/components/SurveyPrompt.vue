<script setup lang="ts">
const emit = defineEmits<{ close: []; accept: [] }>();
</script>

<template>
  <div class="survey-invitation">
    <h2>听了这么久，聊聊吧？</h2>
    <p class="muted">
      看来你已经和 TvT 相处一段时间了，愿意花 5
      分钟填写问卷，帮我们把它变得更好吗？(｡･ω･｡)
    </p>
    <div class="invitation-actions">
      <button class="secondary-button" @click="emit('close')">不要</button>
      <button class="primary" @click="emit('accept')">好！</button>
    </div>
    <small>自愿参与，拒绝后不会再自动提醒。</small>
  </div>
</template>

<style scoped>
.survey-invitation h2 {
  margin: 0 0 10px;
  font-size: 22px;
  line-height: 1.4;
}
.survey-invitation .muted {
  margin-bottom: 0;
  font-size: 14px;
  line-height: 1.65;
}
.invitation-actions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin: 18px 0 10px;
}
.invitation-actions button {
  min-height: 44px;
  border-radius: 999px;
  justify-content: center;
}
.survey-invitation > small {
  display: block;
  text-align: center;
  font-size: 12px;
  line-height: 1.5;
}
</style>
