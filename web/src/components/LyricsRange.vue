<script setup lang="ts">
import { computed, ref } from 'vue';
const props = withDefaults(
  defineProps<{
    modelValue: number;
    min: number;
    max: number;
    label: string;
    step?: number;
    disabled?: boolean;
  }>(),
  { step: 1, disabled: false },
);
const emit = defineEmits<{
  'update:modelValue': [value: number];
  change: [value: number];
}>();
const input = ref<HTMLInputElement>();
const fill = computed(
  () => `${((props.modelValue - props.min) / (props.max - props.min)) * 100}%`,
);
let gesture:
  | { id: number; x: number; value: number; changed: boolean }
  | undefined;
function down(event: PointerEvent) {
  event.preventDefault();
  if (gesture || props.disabled || event.button !== 0 || !input.value) return;
  const box = input.value.getBoundingClientRect();
  const thumb = 20;
  const center =
    box.left +
    thumb / 2 +
    ((box.width - thumb) * (props.modelValue - props.min)) /
      (props.max - props.min);
  if (Math.abs(event.clientX - center) > 20) return;
  gesture = {
    id: event.pointerId,
    x: event.clientX,
    value: props.modelValue,
    changed: false,
  };
  input.value.focus({ preventScroll: true });
  input.value.setPointerCapture(event.pointerId);
}
function move(event: PointerEvent) {
  if (!gesture || gesture.id !== event.pointerId || !input.value) return;
  const delta = event.clientX - gesture.x;
  if (!gesture.changed && Math.abs(delta) < 6) return;
  gesture.changed = true;
  const width = Math.max(1, input.value.getBoundingClientRect().width - 20);
  const value = Math.max(
    props.min,
    Math.min(
      props.max,
      Math.round(
        (gesture.value + (delta / width) * (props.max - props.min)) /
          props.step,
      ) * props.step,
    ),
  );
  emit('update:modelValue', value);
}
function end(event: PointerEvent, cancelled = false) {
  if (!gesture || gesture.id !== event.pointerId) return;
  const previous = gesture;
  gesture = undefined;
  if (input.value?.hasPointerCapture(event.pointerId))
    input.value.releasePointerCapture(event.pointerId);
  if (cancelled) emit('update:modelValue', previous.value);
  else if (previous.changed && props.modelValue !== previous.value)
    emit('change', props.modelValue);
}
function keyboardInput(event: Event) {
  if (!gesture)
    emit('update:modelValue', Number((event.target as HTMLInputElement).value));
}
function keyboardChange(event: Event) {
  if (!gesture)
    emit('change', Number((event.target as HTMLInputElement).value));
}
function adjust(direction: number) {
  const value = Math.max(
    props.min,
    Math.min(props.max, props.modelValue + direction * props.step),
  );
  if (value === props.modelValue) return;
  emit('update:modelValue', value);
  emit('change', value);
}
</script>
<template>
  <div class="lyrics-range-control">
    <button
      type="button"
      :aria-label="`减小${label}`"
      :disabled="disabled || modelValue <= min"
      @click="adjust(-1)"
    >
      −
    </button>
    <input
      ref="input"
      type="range"
      :aria-label="label"
      :min="min"
      :max="max"
      :step="step"
      :value="modelValue"
      :disabled="disabled"
      :style="{ '--range-fill': fill }"
      @pointerdown="down"
      @pointermove="move"
      @pointerup="end($event)"
      @pointercancel="end($event, true)"
      @lostpointercapture="end($event, true)"
      @click.prevent
      @input="keyboardInput"
      @change="keyboardChange"
    />
    <button
      type="button"
      :aria-label="`增大${label}`"
      :disabled="disabled || modelValue >= max"
      @click="adjust(1)"
    >
      +
    </button>
  </div>
</template>
<style scoped>
.lyrics-range-control {
  display: flex;
  align-items: center;
  gap: 4px;
  width: 100%;
  min-width: 0;
}
button {
  flex: 0 0 44px;
  height: 44px;
  border-radius: 50%;
  background: transparent;
  color: var(--muted);
  font-size: 20px;
}
input {
  appearance: none;
  width: 0;
  flex: 1;
  min-width: 0;
  height: 44px;
  margin: 0;
  padding: 0;
  background: transparent;
  cursor: ew-resize;
  touch-action: pan-y;
}
input::-webkit-slider-runnable-track {
  height: 5px;
  border-radius: 99px;
  background: linear-gradient(
    to right,
    #007aff 0 var(--range-fill),
    var(--line) var(--range-fill) 100%
  );
}
input::-webkit-slider-thumb {
  appearance: none;
  width: 20px;
  height: 20px;
  margin-top: -7.5px;
  border-radius: 50%;
  background: #fff;
  border: 1px solid #00000008;
  box-shadow: 0 1px 5px #00000026;
}
input:focus-visible {
  outline: 2px solid #007aff;
  outline-offset: 2px;
  border-radius: 8px;
}
button:active:not(:disabled) {
  background: var(--line);
}
button:disabled,
input:disabled {
  opacity: 0.35;
  cursor: default;
}
</style>
