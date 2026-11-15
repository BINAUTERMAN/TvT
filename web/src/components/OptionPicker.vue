<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, useId } from 'vue';
import { Check, ChevronDown } from 'lucide-vue-next';
const props = defineProps<{
  modelValue: string;
  label: string;
  options: readonly { value: string; label: string }[];
  disabled?: boolean;
  fullWidth?: boolean;
}>();
const emit = defineEmits<{ 'update:modelValue': [value: string] }>();
const expanded = ref(false);
const root = ref<HTMLElement>();
const id = useId();
const selected = computed(
  () =>
    props.options.find((option) => option.value === props.modelValue)?.label ??
    '选择',
);
function choose(value: string) {
  expanded.value = false;
  root.value
    ?.querySelector<HTMLButtonElement>('[role=combobox]')
    ?.focus({ preventScroll: true });
  emit('update:modelValue', value);
}
function retire(element: Element) {
  (element as HTMLElement).inert = true;
  element.setAttribute('aria-hidden', 'true');
}
function restore(element: Element) {
  (element as HTMLElement).inert = false;
  element.removeAttribute('aria-hidden');
}
function close(event: Event) {
  if (
    document.hidden ||
    (event.type === 'pointerdown' &&
      !root.value?.contains(event.target as Node))
  )
    expanded.value = false;
}
async function keydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && expanded.value) {
    event.stopPropagation();
    expanded.value = false;
  } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault();
    expanded.value = true;
    await nextTick();
    const options =
      root.value?.querySelectorAll<HTMLButtonElement>('[role=option]');
    if (!options?.length) return;
    const current = Array.from(options).indexOf(
      document.activeElement as HTMLButtonElement,
    );
    const next =
      current < 0
        ? event.key === 'ArrowDown'
          ? 0
          : options.length - 1
        : (current + (event.key === 'ArrowDown' ? 1 : -1) + options.length) %
          options.length;
    options[next]?.focus();
  }
}
onMounted(() => {
  document.addEventListener('pointerdown', close);
  document.addEventListener('visibilitychange', close);
});
onUnmounted(() => {
  document.removeEventListener('pointerdown', close);
  document.removeEventListener('visibilitychange', close);
});
</script>
<template>
  <div
    ref="root"
    class="option-picker"
    :class="{ 'option-picker-wide': fullWidth }"
    @keydown="keydown"
  >
    <button
      type="button"
      role="combobox"
      :disabled="disabled"
      :aria-label="label"
      :aria-expanded="expanded"
      :aria-controls="id"
      :data-value="modelValue"
      @click="expanded = !expanded"
    >
      <span>{{ selected }}</span>
      <ChevronDown :size="16" :class="{ expanded }" />
    </button>
    <Transition
      name="choice"
      @before-leave="retire"
      @before-enter="restore"
      @leave-cancelled="restore"
    >
      <div
        v-if="expanded"
        :id="id"
        role="listbox"
        :aria-label="label"
        class="option-list"
      >
        <button
          v-for="option in options"
          :key="option.value"
          type="button"
          role="option"
          :aria-selected="modelValue === option.value"
          @click="choose(option.value)"
        >
          <span>{{ option.label }}</span>
          <Check v-if="modelValue === option.value" :size="17" />
        </button>
      </div>
    </Transition>
  </div>
</template>
<style scoped>
.option-picker {
  position: relative;
  max-width: 68%;
  min-width: 130px;
}
.option-picker > button {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  width: 100%;
  min-height: 42px;
  font-size: 13px;
  color: var(--muted);
}
.option-picker > button svg {
  transition: transform var(--motion-duration) var(--motion-ease);
}
.option-picker-wide {
  max-width: 100%;
  width: 100%;
}
.option-picker-wide > button {
  justify-content: space-between;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--surface);
  color: var(--ink);
  font-size: 15px;
  min-height: 50px;
}
.option-picker > button > span,
.option-list > button > span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.option-picker-wide .option-list {
  width: 100%;
  min-width: 0;
  max-height: 260px;
  overflow-y: auto;
}
.expanded {
  transform: rotate(180deg);
}
.option-list {
  position: absolute;
  top: calc(100% + 4px);
  right: 0;
  z-index: 6;
  width: max-content;
  min-width: 180px;
  padding: 6px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--bg);
  box-shadow: 0 10px 32px #0002;
  transform-origin: top right;
}
.option-list > button {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  width: 100%;
  min-height: 44px;
  padding: 10px 12px;
  border-radius: 10px;
  font-size: 14px;
  text-align: left;
}
.option-list > button[aria-selected='true'] {
  background: var(--surface);
  color: #007aff;
}
.choice-enter-active,
.choice-leave-active {
  transition:
    opacity 180ms ease,
    transform 180ms var(--motion-ease);
}
.choice-enter-from,
.choice-leave-to {
  opacity: 0;
  transform: translateY(-5px) scale(0.97);
}
</style>
