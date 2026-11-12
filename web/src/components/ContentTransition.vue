<script setup lang="ts">
import { ref } from 'vue';

defineProps<{ identity: string | number }>();
const stage = ref<HTMLElement>();
function freezeHeight(element: Element) {
  if (!stage.value) return;
  (element as HTMLElement).inert = true;
  stage.value.style.height = `${stage.value.getBoundingClientRect().height}px`;
  stage.value.classList.add('content-changing');
}
function resize(element: Element) {
  if (stage.value?.style.height)
    stage.value.style.height = `${(element as HTMLElement).offsetHeight}px`;
}
function releaseHeight() {
  stage.value?.style.removeProperty('height');
  stage.value?.classList.remove('content-changing');
}
function restore(element: Element) {
  (element as HTMLElement).inert = false;
  releaseHeight();
}
</script>

<template>
  <div ref="stage" class="content-stage">
    <Transition
      name="content"
      mode="out-in"
      @before-leave="freezeHeight"
      @enter="resize"
      @after-enter="releaseHeight"
      @enter-cancelled="releaseHeight"
      @leave-cancelled="restore"
    >
      <div :key="identity" class="content-pane"><slot /></div>
    </Transition>
  </div>
</template>
