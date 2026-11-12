<script setup lang="ts">
import { ref } from 'vue';
defineProps<{ title: string }>();
const expanded = ref(false);
const rendered = ref(false);
function toggle() {
  if (!expanded.value) rendered.value = true;
  expanded.value = !expanded.value;
}
function finishClose() {
  if (!expanded.value) rendered.value = false;
}
</script>
<template>
  <details :open="rendered" :data-expanded="expanded">
    <summary :aria-expanded="expanded" @click.prevent="toggle">
      {{ title }}
    </summary>
    <Transition name="fold" @after-leave="finishClose">
      <div v-show="expanded" class="disclosure-body" :inert="!expanded">
        <div class="disclosure-inner"><slot /></div>
      </div>
    </Transition>
  </details>
</template>
<style scoped>
.disclosure-body {
  display: grid;
  grid-template-rows: 1fr;
}
.disclosure-inner {
  overflow: hidden;
  min-height: 0;
}
.fold-enter-active,
.fold-leave-active {
  transition:
    grid-template-rows var(--motion-duration) var(--motion-ease),
    opacity var(--motion-duration) ease;
}
.fold-enter-from,
.fold-leave-to {
  grid-template-rows: 0fr;
  opacity: 0;
}
</style>
