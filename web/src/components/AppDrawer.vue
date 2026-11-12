<script setup lang="ts">
import { X } from 'lucide-vue-next';
defineProps<{ name: string }>();
const emit = defineEmits<{ close: [] }>();
function retire(element: Element) {
  (element as HTMLElement).inert = true;
  element.setAttribute('aria-hidden', 'true');
}
function restore(element: Element) {
  (element as HTMLElement).inert = false;
  element.removeAttribute('aria-hidden');
}
</script>

<template>
  <Transition name="backdrop">
    <div v-if="name" class="modal-backdrop" @click.self="emit('close')">
      <Transition
        name="sheet"
        mode="out-in"
        appear
        @before-leave="retire"
        @before-enter="restore"
        @leave-cancelled="restore"
      >
        <section
          :key="name"
          class="sheet-panel"
          role="dialog"
          aria-modal="true"
          :aria-label="name"
        >
          <div class="sheet-handle" />
          <button
            v-if="name !== 'star'"
            class="icon close-sheet"
            aria-label="关闭"
            @click="emit('close')"
          >
            <X :size="23" />
          </button>
          <slot />
        </section>
      </Transition>
    </div>
  </Transition>
</template>
