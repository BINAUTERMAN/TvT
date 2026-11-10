import { reactive } from 'vue';
import { native } from './bridge';

export const star = reactive({ pending: false });
let request: Promise<void> | undefined;
let active = false;

function refresh() {
  if (!active || document.hidden || request || !window.Native) return;
  request = native<{ eligible: boolean }>('starStatus')
    .then((status) => {
      if (active) star.pending = status?.eligible === true;
    })
    .catch(() => {})
    .finally(() => {
      request = undefined;
    });
}

export async function claimStarPrompt(): Promise<boolean> {
  star.pending = false;
  try {
    return (await native<boolean>('starConsume')) === true;
  } catch {
    return false;
  }
}

export function startStarPrompt() {
  active = true;
  window.addEventListener('ting:star-ready', refresh);
  window.addEventListener('ting:resume', refresh);
  document.addEventListener('visibilitychange', refresh);
  refresh();
}

export function stopStarPrompt() {
  active = false;
  window.removeEventListener('ting:star-ready', refresh);
  window.removeEventListener('ting:resume', refresh);
  document.removeEventListener('visibilitychange', refresh);
}
