<script setup lang="ts">
import { ref } from 'vue';
import { ChevronUp, ChevronDown, GripVertical } from 'lucide-vue-next';
const props = defineProps<{
  items: { id: string; name: string; subtitle?: string; group?: number }[];
  busy: boolean;
}>();
const emit = defineEmits<{ save: [ids: string[]]; cancel: [] }>();
const draft = ref([...props.items]);
const dragging = ref(-1);
const destination = ref(-1);
function reorder(from: number, target: number) {
  if (
    props.busy ||
    from < 0 ||
    target < 0 ||
    from >= draft.value.length ||
    target >= draft.value.length ||
    draft.value[from].group !== draft.value[target].group
  )
    return;
  const [item] = draft.value.splice(from, 1);
  draft.value.splice(target, 0, item);
}
function move(index: number, direction: number) {
  const target = index + direction;
  reorder(index, target);
}
function start(event: PointerEvent, index: number) {
  if (props.busy) return;
  dragging.value = index;
  destination.value = index;
  (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
}
function drag(event: PointerEvent) {
  if (dragging.value < 0) return;
  const list = (event.currentTarget as HTMLElement).closest(
    '.playlist-order-list',
  );
  if (list) {
    const box = list.getBoundingClientRect();
    if (event.clientY < box.top + 45) list.scrollTop -= 24;
    else if (event.clientY > box.bottom - 45) list.scrollTop += 24;
  }
  const row = document
    .elementsFromPoint(event.clientX, event.clientY)
    .map((element) => element.closest<HTMLElement>('[data-order-index]'))
    .find(Boolean);
  if (row) {
    const target = Number(row.dataset.orderIndex);
    if (draft.value[target]?.group === draft.value[dragging.value]?.group)
      destination.value = target;
  }
}
function cancel() {
  dragging.value = -1;
  destination.value = -1;
}
function drop() {
  const from = dragging.value,
    to = destination.value;
  cancel();
  if (from !== to) reorder(from, to);
}
</script>
<template>
  <section class="playlist-order">
    <p class="muted">拖动右侧手柄排序，保存后同步云端。</p>
    <TransitionGroup
      name="playlist-order"
      tag="div"
      class="playlist-order-list"
    >
      <div
        v-for="(item, index) in draft"
        :key="item.id"
        class="playlist-order-row"
        :data-order-index="index"
        :class="{
          dragging: index === dragging,
          'drop-target': index === destination && dragging >= 0,
        }"
      >
        <span>
          <strong>{{ item.name }}</strong>
          <small v-if="item.subtitle">{{ item.subtitle }}</small>
        </span>
        <div class="queue-accessible-order">
          <button
            class="icon"
            :aria-label="`上移${item.name}`"
            :disabled="
              busy || index === 0 || draft[index - 1]?.group !== item.group
            "
            @click="move(index, -1)"
          >
            <ChevronUp :size="20" />
          </button>
          <button
            class="icon"
            :aria-label="`下移${item.name}`"
            :disabled="
              busy ||
              index === draft.length - 1 ||
              draft[index + 1]?.group !== item.group
            "
            @click="move(index, 1)"
          >
            <ChevronDown :size="20" />
          </button>
        </div>
        <button
          class="icon queue-grip"
          :aria-label="`拖动排序${item.name}`"
          :disabled="busy"
          @pointerdown="start($event, index)"
          @pointermove="drag"
          @pointerup="drop"
          @pointercancel="cancel"
        >
          <GripVertical :size="21" />
        </button>
      </div>
    </TransitionGroup>
    <div class="collection-actions">
      <button
        class="primary"
        :disabled="busy"
        @click="
          emit(
            'save',
            draft.map((item) => item.id),
          )
        "
      >
        {{ busy ? '正在保存…' : '保存顺序' }}
      </button>
      <button class="text-button" :disabled="busy" @click="emit('cancel')">
        取消排序
      </button>
    </div>
  </section>
</template>
<style scoped>
.playlist-order-list {
  max-height: 55vh;
  overflow: auto;
}
.playlist-order-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 1px solid var(--line);
}
.playlist-order-row > span {
  flex: 1;
  min-width: 0;
}
.playlist-order-row strong,
.playlist-order-row small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.playlist-order-row strong {
  font-size: 15px;
  font-weight: 550;
}
.playlist-order-row small {
  margin-top: 4px;
  font-size: 12px;
}
.playlist-order-move {
  transition: transform var(--motion-duration) var(--motion-ease);
}
.playlist-order-row.dragging {
  opacity: 0.45;
}
.playlist-order-row.drop-target {
  box-shadow: inset 0 2px var(--accent);
}
.playlist-order-row:focus-within .queue-accessible-order {
  display: flex;
}
</style>
