import { reactive } from 'vue';
import { native } from './bridge';

export interface VipStatus {
  label: string;
  active: boolean;
  expiresAt: number;
  rights: {
    type?: string;
    label: string;
    active: boolean;
    expiresAt: number;
  }[];
  todayReceipts: { type: string; label: string }[];
  today: string;
  todayClaimed: boolean;
  claimKnown: boolean;
  checkedAt: number;
  recordError?: string;
}
export const vip = reactive<VipStatus>({
  label: '正在读取 VIP 状态',
  active: false,
  expiresAt: 0,
  rights: [],
  todayReceipts: [],
  today: '',
  todayClaimed: false,
  claimKnown: false,
  checkedAt: 0,
});
let pending: Promise<void> | undefined;
let generation = 0;
export function resetVip() {
  generation++;
  pending = undefined;
  Object.assign(vip, {
    label: '正在读取 VIP 状态',
    active: false,
    expiresAt: 0,
    rights: [],
    todayReceipts: [],
    today: '',
    todayClaimed: false,
    claimKnown: false,
    checkedAt: 0,
    recordError: '',
  });
}
export function refreshVip(refresh = false): Promise<void> {
  if (pending) return pending;
  const request = generation;
  pending = native<VipStatus>('vipStatus', { refresh })
    .then((value) => {
      if (request === generation) Object.assign(vip, value);
    })
    .catch((error: Error) => {
      if (request === generation) vip.label = 'VIP 状态暂不可用';
      throw error;
    })
    .finally(() => {
      if (request === generation) pending = undefined;
    });
  return pending;
}
export function formatVipExpiry(time: number): string {
  if (!time) return '平台未返回到期时间';
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(time);
}
window.addEventListener('ting:vip', (event) => {
  Object.assign(vip, (event as CustomEvent<VipStatus>).detail);
});
