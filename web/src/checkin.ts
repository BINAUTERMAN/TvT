import { reactive } from 'vue';
import { native } from './bridge';
import { vip } from './vip';
import type { VipStatus } from './vip';

export interface CheckinProbe {
  checked: boolean;
  ok: boolean;
  kind?: string;
  code: number;
  message: string;
  checkedAt: number;
}
export interface CheckinState {
  loggedIn: boolean;
  auto: boolean;
  today: string;
  mode: 'reward' | 'listen' | 'off';
  claims: number;
  maxClaims: number;
  done: boolean;
  nextInMs: number;
  message: string;
  error: string;
  probe: CheckinProbe;
  claimed?: boolean;
  status?: VipStatus;
}
/** 签到奖励进度来自原生持久化，VIP 权益来自平台接口，两者分开维护。 */
export const checkin = reactive<CheckinState>({
  loggedIn: false,
  auto: true,
  today: '',
  mode: 'off',
  claims: 0,
  maxClaims: 8,
  done: false,
  nextInMs: 0,
  message: '',
  error: '',
  probe: { checked: false, ok: false, code: 0, message: '', checkedAt: 0 },
});
export function resetCheckin() {
  Object.assign(checkin, {
    loggedIn: false,
    auto: true,
    today: '',
    mode: 'off' as const,
    claims: 0,
    maxClaims: 8,
    done: false,
    nextInMs: 0,
    message: '',
    error: '',
    probe: { checked: false, ok: false, code: 0, message: '', checkedAt: 0 },
  });
}
let pending: Promise<void> | undefined;
let generation = 0;
/** 只读本地签到进度，不发起网络请求。 */
export function refreshCheckin(): Promise<void> {
  if (pending) return pending;
  const request = generation;
  pending = native<CheckinState>('checkinStatus')
    .then((value) => {
      if (request === generation) applyCheckin(value);
    })
    .finally(() => {
      if (request === generation) pending = undefined;
    });
  return pending;
}
/** 手动重新探活，用于接口恢复后当天继续签到。 */
export async function probeCheckin(): Promise<void> {
  applyCheckin(await native<CheckinState>('checkinProbe'));
}
/** 手动推进一次签到，返回平台实际结果。 */
export async function stepCheckin(): Promise<CheckinState> {
  const request = generation;
  const state = await native<CheckinState>('checkinStep', { manual: true });
  if (request === generation) applyCheckin(state);
  return state;
}
function applyCheckin(state: Partial<CheckinState> | null | undefined) {
  if (!state || typeof state !== 'object') return;
  const { status, probe, ...rest } = state;
  Object.assign(checkin, rest);
  if (probe) checkin.probe = { ...checkin.probe, ...probe };
  if (status) Object.assign(vip, status);
}
window.addEventListener('ting:checkin', (event) => {
  applyCheckin((event as CustomEvent<CheckinState>).detail);
});
