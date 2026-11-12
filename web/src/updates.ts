import { reactive } from 'vue';
import { native } from './bridge';
import { notify } from './store';

export interface ReleaseUpdate {
  available?: boolean;
  configured?: boolean;
  ignored?: boolean;
  versionCode?: number;
  version?: string;
  name?: string;
  notes?: string;
  publishedAt?: string;
  rollback?: boolean;
}
export const updates = reactive({
  enabled: true,
  dontWarn: false,
  skip: 0,
  configured: false,
  repository: '',
  currentVersion: '5.4',
  checking: false,
  downloading: false,
  state: 'idle',
  error: '',
  release: {} as ReleaseUpdate,
  prompt: false,
});
let initialized = false;
const prompted = new Set<number>();
export async function refreshUpdateSettings() {
  Object.assign(updates, await native('updateSettings'));
}
export async function checkUpdates(manual = false) {
  if (updates.checking) return;
  updates.checking = true;
  updates.error = '';
  try {
    await refreshUpdateSettings();
    if (!manual && !updates.enabled) return;
    const release = await native<ReleaseUpdate>('updateCheck', { manual });
    if (release.configured !== undefined)
      updates.configured = release.configured;
    if (release.available !== undefined) {
      updates.release = release;
      if (
        !manual &&
        release.available &&
        !release.ignored &&
        !prompted.has(release.versionCode ?? 0)
      ) {
        prompted.add(release.versionCode ?? 0);
        updates.prompt = true;
      }
    }
    if (manual && !release.available && !release.ignored)
      notify(
        release.configured === false
          ? '此构建尚未配置更新仓库'
          : '当前已经是最新版本',
      );
  } catch (error) {
    updates.error = (error as Error).message;
    if (manual) notify(updates.error);
  } finally {
    updates.checking = false;
  }
}
export async function downloadStatus() {
  try {
    const value = await native<{ state: string }>('updateStatus');
    updates.state = value.state;
  } catch {}
}
export async function downloadUpdate() {
  if (updates.downloading) return;
  updates.downloading = true;
  updates.error = '';
  try {
    const result = await native<{ state: string }>('updateDownload');
    updates.state = result.state;
    notify('正在下载更新，完成后返回这里安装');
  } catch (error) {
    updates.error = (error as Error).message;
  } finally {
    updates.downloading = false;
  }
}
export async function installUpdate() {
  try {
    await native('updateInstall');
  } catch (error) {
    updates.error = (error as Error).message;
  }
}
export async function changeUpdateChoice(
  action: 'disable' | 'skip',
  dontWarn: boolean,
) {
  const data =
    action === 'disable'
      ? { enabled: false, dontWarn }
      : { skip: updates.release.versionCode, dontWarn };
  await native('updateSettings', data);
  await refreshUpdateSettings();
  if (action === 'skip') updates.prompt = false;
}
export async function enableUpdates() {
  await native('updateSettings', { enabled: true });
  await refreshUpdateSettings();
}
export function startUpdates() {
  if (initialized) return;
  initialized = true;
  void checkUpdates();
  void downloadStatus();
  window.addEventListener('ting:update-download', () => void downloadStatus());
  window.addEventListener('ting:resume', () => {
    void checkUpdates();
    void downloadStatus();
  });
}
