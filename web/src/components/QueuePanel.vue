<script setup lang="ts">
import { ref } from 'vue';
import { GripVertical, X, ChevronUp, ChevronDown } from 'lucide-vue-next';
import { player, command } from '../store';
import Artwork from './Artwork.vue';
import { cleanTitle } from '../music';
const dragging = ref(-1),
  destination = ref(-1);
function start(event: PointerEvent, index: number) {
  dragging.value = index;
  destination.value = index;
  (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
}
function move(event: PointerEvent) {
  if (dragging.value < 0) return;
  const list = (event.currentTarget as HTMLElement).closest('.queue-list');
  if (list) {
    const box = list.getBoundingClientRect();
    if (event.clientY < box.top + 45) list.scrollTop -= 24;
    else if (event.clientY > box.bottom - 45) list.scrollTop += 24;
  }
  const target = document
    .elementsFromPoint(event.clientX, event.clientY)
    .map((element) => element.closest<HTMLElement>('[data-queue-index]'))
    .find(Boolean);
  if (target) destination.value = Number(target.dataset.queueIndex);
}
function cancel() {
  dragging.value = -1;
  destination.value = -1;
}
async function drop() {
  const from = dragging.value,
    to = destination.value;
  cancel();
  if (from >= 0 && to >= 0 && from !== to)
    await command('move', { from, to, id: player.queue[from]?.id });
}
function step(from: number, to: number) {
  if (to >= 0 && to < player.queue.length)
    void command('move', { from, to, id: player.queue[from]?.id });
}
</script>
<template>
  <section>
    <h2>
      接下来播放
      <small>{{ player.queue.length }}</small>
    </h2>
    <p class="muted queue-help">拖动右侧手柄排序</p>
    <div class="queue-list">
      <div
        v-for="(track, index) in player.queue"
        :key="`${track.id}-${index}`"
        :data-queue-index="index"
        class="queue-row"
        :class="{
          current: index === player.index,
          dragging: index === dragging,
          'drop-target': index === destination && dragging >= 0,
        }"
      >
        <button class="queue-track" @click="command('jump', { index })">
          <Artwork :src="track.cover" :seed="track.title" />
          <span>
            <strong>{{ cleanTitle(track.title) }}</strong>
            <small>{{ track.artist }}</small>
          </span>
        </button>
        <div class="queue-accessible-order">
          <button
            class="icon"
            aria-label="向上移动"
            :disabled="index === 0"
            @click="step(index, index - 1)"
          >
            <ChevronUp :size="17" />
          </button>
          <button
            class="icon"
            aria-label="向下移动"
            :disabled="index === player.queue.length - 1"
            @click="step(index, index + 1)"
          >
            <ChevronDown :size="17" />
          </button>
        </div>
        <button
          class="icon queue-grip"
          aria-label="拖动排序"
          @pointerdown="start($event, index)"
          @pointermove="move"
          @pointerup="drop"
          @pointercancel="cancel"
        >
          <GripVertical :size="21" />
        </button>
        <button
          class="icon"
          aria-label="从队列移除"
          @click="command('remove', { index })"
        >
          <X :size="18" />
        </button>
      </div>
      <p v-if="!player.queue.length" class="muted">播放队列还是空的。</p>
    </div>
  </section>
</template>
