<script setup lang="ts">
import { computed, ref } from 'vue';
import { Bell } from 'lucide-vue-next';
import { native } from '../bridge';
import { notifications, checkNotifications } from '../notifications';
import type { NotificationStatus } from '../notifications';
const emit = defineEmits<{ close: [] }>();
const dontRemind = ref(notifications.dontRemind);
const busy = ref(false);
const error = ref('');
const message = computed(() => {
  if (!notifications.enabled) return '你未开启锁屏通知和悬浮通知，这会影响体验';
  if (
    notifications.lockscreen === 'disabled' &&
    notifications.floating === 'disabled'
  )
    return '你未开启锁屏通知和悬浮通知，这会影响体验';
  if (notifications.lockscreen === 'disabled')
    return '你未开启锁屏通知，这会影响体验';
  if (notifications.floating === 'disabled')
    return '你未开启悬浮通知，这会影响体验';
  return '请在系统设置中确认锁屏通知和悬浮通知已开启';
});
async function cancel() {
  try {
    Object.assign(
      notifications,
      await native<NotificationStatus>('notificationStatus', {
        dontRemind: dontRemind.value,
      }),
    );
    emit('close');
  } catch (cause) {
    error.value = (cause as Error).message;
  }
}
async function enable() {
  busy.value = true;
  error.value = '';
  try {
    await native('notificationOpen');
  } catch (cause) {
    error.value = (cause as Error).message;
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div class="notification-panel">
    <Bell :size="30" :stroke-width="1.5" />
    <h2>播放通知</h2>
    <p role="status">{{ message }}</p>
    <div class="setting-row">
      <span>应用通知</span>
      <small>{{ notifications.enabled ? '已允许' : '未开启' }}</small>
    </div>
    <div class="setting-row">
      <span>锁屏通知</span>
      <small>
        {{
          notifications.lockscreen === 'disabled' ? '未开启' : '需在系统中确认'
        }}
      </small>
    </div>
    <div class="setting-row">
      <span>悬浮通知</span>
      <small>
        {{
          notifications.floating === 'disabled' ? '未开启' : '需在系统中确认'
        }}
      </small>
    </div>
    <p class="muted">
      在系统通知设置中允许锁屏显示，并开启悬浮通知（部分手机称为横幅或浮动通知）。系统未公开的独立开关无法准确读取，不会标成已开启。此权限与桌面歌词的悬浮窗权限相互独立。
    </p>
    <label class="setting-row">
      <span>不再提醒</span>
      <input v-model="dontRemind" type="checkbox" />
    </label>
    <p v-if="error" class="login-error" role="alert">{{ error }}</p>
    <div class="confirmation-actions">
      <button class="secondary-button" :disabled="busy" @click="checkNotifications">重新检查</button>
      <button class="secondary-button" :disabled="busy" @click="cancel">
        取消
      </button>
      <button class="primary" :disabled="busy" @click="enable">开启</button>
    </div>
  </div>
</template>
