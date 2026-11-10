import { reactive } from 'vue';
import { native } from './bridge';

type AccessState = 'disabled' | 'unknown';
export interface NotificationStatus {
  enabled: boolean;
  permission: boolean;
  lockscreen: AccessState;
  floating: AccessState;
  needsAttention: boolean;
  dontRemind: boolean;
}
export const notifications = reactive<NotificationStatus & { prompt: boolean }>(
  {
    enabled: true,
    permission: true,
    lockscreen: 'unknown',
    floating: 'unknown',
    needsAttention: false,
    dontRemind: false,
    prompt: false,
  },
);
let shown = false;
export async function checkNotifications() {
  try {
    const status = await native<NotificationStatus>('notificationStatus');
    Object.assign(notifications, status);
    if (
      status.needsAttention === true &&
      !status.dontRemind &&
      !shown &&
      !document.hidden
    ) {
      shown = true;
      notifications.prompt = true;
    }
  } catch {
    /* 权限查询失败不阻止使用播放器。 */
  }
}
function permissionResult(event: Event) {
  Object.assign(
    notifications,
    (event as CustomEvent<NotificationStatus>).detail,
  );
}
export function startNotifications() {
  void checkNotifications();
  window.addEventListener('ting:resume', checkNotifications);
  window.addEventListener('ting:notification-result', permissionResult);
}
export function stopNotifications() {
  window.removeEventListener('ting:resume', checkNotifications);
  window.removeEventListener('ting:notification-result', permissionResult);
}
