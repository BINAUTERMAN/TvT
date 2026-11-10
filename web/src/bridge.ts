import type { Playback, Track } from './types';

declare global {
  interface Window {
    tingHandleBack?: () => boolean;
    tingSystemDark?: boolean;
    Native?: { request(id: string, action: string, payload: string): void };
    nativeResolve: (id: string, result: unknown, error?: string) => void;
    nativePlayback: (state: Partial<Playback>) => void;
    nativeImported: (tracks: Track[]) => void;
  }
}
const pending = new Map<
  string,
  {
    resolve: (data: unknown) => void;
    reject: (error: Error) => void;
    timer: number;
  }
>();
let sequence = 0;
window.nativeResolve = (id, result, error) => {
  const entry = pending.get(id);
  if (!entry) return;
  clearTimeout(entry.timer);
  pending.delete(id);
  if (error) entry.reject(new Error(error));
  else entry.resolve(result);
};
export function native<T = unknown>(
  action: string,
  payload: unknown = {},
): Promise<T> {
  if (!window.Native)
    return Promise.reject(new Error('请在安卓应用中使用此功能'));
  const id = String(++sequence);
  return new Promise((resolve, reject) => {
    const timer = window.setTimeout(() => {
      pending.delete(id);
      reject(new Error('请求超时，请检查网络后重试'));
    }, 35000);
    pending.set(id, {
      resolve: resolve as (data: unknown) => void,
      reject,
      timer,
    });
    window.Native!.request(id, action, JSON.stringify(payload));
  });
}
