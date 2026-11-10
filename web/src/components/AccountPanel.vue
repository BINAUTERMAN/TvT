<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import QRCode from 'qrcode';
import { native } from '../bridge';
import { notify, library, refreshAccount, syncFavorites } from '../store';
import { normalize } from '../music';
import { vip, refreshVip } from '../vip';
const vipLabel = computed(() => vip.label);
const loggedIn = ref(false);
const name = ref('');
const qrImage = ref('');
const qrKey = ref('');
const status = ref('');
const busy = ref(false);
const mode = ref<'sms' | 'qr'>('sms');
const mobile = ref('');
const code = ref('');
const sending = ref(false);
const loginBusy = ref(false);
const message = ref('');
interface LoginChoice {
  key: string;
  name: string;
  avatar: string;
}
interface MobileLoginResult {
  loggedIn?: boolean;
  requiresAccount?: boolean;
  choices?: LoginChoice[];
}
const loginChoices = ref<LoginChoice[]>([]);
const failed = ref(false);
const cooldown = ref(0);
let cooldownEnd = 0;
let cooldownTimer = 0;
const validMobile = computed(() => /^1[3-9]\d{9}$/.test(mobile.value));
const validCode = computed(() => /^\d{4,8}$/.test(code.value));
const cloud = ref<{ id: string; name: string }[]>([]);
let timer = 0;
let disposed = false;
let expires = 0;
function refreshCooldown() {
  clearTimeout(cooldownTimer);
  cooldown.value = Math.max(0, Math.ceil((cooldownEnd - Date.now()) / 1000));
  if (cooldown.value && !document.hidden)
    cooldownTimer = window.setTimeout(refreshCooldown, 1000);
}
async function sendCode() {
  if (!validMobile.value || sending.value || cooldown.value) return;
  sending.value = true;
  failed.value = false;
  message.value = '';
  try {
    await native('sendCode', { mobile: mobile.value });
    cooldownEnd = Date.now() + 60000;
    refreshCooldown();
    message.value = '验证码已发送，请留意短信';
  } catch (error) {
    failed.value = true;
    message.value = (error as Error).message;
  } finally {
    sending.value = false;
  }
}
async function loginMobile() {
  if (!validMobile.value || !validCode.value || loginBusy.value) return;
  loginBusy.value = true;
  failed.value = false;
  message.value = '';
  try {
    const result = await native<MobileLoginResult>('loginMobile', {
      mobile: mobile.value,
      code: code.value,
    });
    mobile.value = '';
    code.value = '';
    await completeMobileLogin(result);
  } catch (error) {
    failed.value = true;
    message.value = (error as Error).message;
  } finally {
    loginBusy.value = false;
  }
}
async function completeMobileLogin(result: MobileLoginResult) {
  if (result.requiresAccount && result.choices?.length) {
    loginChoices.value = result.choices;
    message.value = '此手机号绑定了多个账号，请选择要登录的账号';
    return;
  }
  if (!result.loggedIn) throw new Error('平台尚未确认登录，请重新获取验证码');
  loginChoices.value = [];
  await account();
  notify('已登录酷狗账号');
}
async function chooseAccount(choice: LoginChoice) {
  if (loginBusy.value) return;
  loginBusy.value = true;
  failed.value = false;
  message.value = '';
  try {
    await completeMobileLogin(
      await native<MobileLoginResult>('chooseLoginAccount', {
        key: choice.key,
      }),
    );
  } catch (cause) {
    failed.value = true;
    message.value = (cause as Error).message;
  } finally {
    loginBusy.value = false;
  }
}
function resetChoices() {
  loginChoices.value = [];
  code.value = '';
  message.value = '';
  void native('cancelMobileLogin').catch(() => {});
}
function changeMode() {
  resetChoices();
  mode.value = mode.value === 'sms' ? 'qr' : 'sms';
  clearTimeout(timer);
  qrImage.value = '';
  status.value = '';
}
async function account() {
  try {
    const info = await refreshAccount();
    loggedIn.value = info.loggedIn;
    name.value = info.name;
    if (info.loggedIn) {
      try {
        await refreshVip(true);
      } catch {
        vip.label = 'VIP 状态暂不可用';
      }
    }
    if (info.loggedIn) void syncFavorites().catch(() => {});
  } catch (error) {
    status.value = (error as Error).message;
  }
}
async function createQr() {
  clearTimeout(timer);
  busy.value = true;
  status.value = '';
  qrImage.value = '';
  try {
    const response = await native<{ data?: { qrcode?: string; key?: string } }>(
      'qrKey',
    );
    qrKey.value = response.data?.qrcode || response.data?.key || '';
    if (!qrKey.value) throw new Error('登录二维码暂不可用');
    qrImage.value = await QRCode.toDataURL(
      `https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=${qrKey.value}`,
      { width: 240, margin: 2, color: { dark: '#252d29', light: '#ffffff' } },
    );
    status.value = '请用酷狗 App 扫码并确认登录';
    expires = Date.now() + 180000;
    timer = window.setTimeout(checkQr, 3000);
  } catch (error) {
    status.value = (error as Error).message;
  } finally {
    busy.value = false;
  }
}
async function checkQr() {
  if (disposed) return;
  if (Date.now() > expires) {
    status.value = '二维码已过期，请重新获取';
    qrImage.value = '';
    return;
  }
  if (document.hidden) return;
  try {
    const response = await native<{ data?: { status: number } }>('qrCheck', {
      key: qrKey.value,
    });
    if (disposed) return;
    const code = response.data?.status;
    if (code === 4) {
      qrImage.value = '';
      status.value = '登录成功';
      await account();
      return;
    }
    if (code === 0) {
      status.value = '二维码已过期，请重新获取';
      qrImage.value = '';
      return;
    }
    if (code === 2) status.value = '已扫码，请在酷狗 App 中确认';
    timer = window.setTimeout(checkQr, 3000);
  } catch {
    status.value = '登录状态获取失败，请重新获取二维码';
  }
}
function visibility() {
  clearTimeout(timer);
  refreshCooldown();
  if (!document.hidden && qrImage.value && !loggedIn.value)
    timer = window.setTimeout(checkQr, 500);
}
async function loadCloud() {
  busy.value = true;
  try {
    const response = await native<{
      data?: {
        info?: Record<string, unknown>[];
        lists?: Record<string, unknown>[];
      };
    }>('cloudPlaylists');
    cloud.value = (response.data?.info ?? response.data?.lists ?? [])
      .map((item) => ({
        id: String(item.global_collection_id ?? ''),
        name: String(item.name ?? item.listname ?? '未命名歌单'),
      }))
      .filter((item) => item.id);
    if (!cloud.value.length) notify('账号暂无可导入的歌单，或接口暂不可用');
  } catch (error) {
    notify((error as Error).message);
    await account();
  } finally {
    busy.value = false;
  }
}
async function importCloud(list: { id: string; name: string }) {
  busy.value = true;
  try {
    const tracks = [];
    for (let page = 1; page <= 30; page++) {
      const response = await native<{
        data?: {
          songs?: Record<string, unknown>[];
          info?: Record<string, unknown>[];
        };
      }>('playlist', { id: list.id, page });
      const rows = response.data?.songs ?? response.data?.info ?? [];
      tracks.push(...rows.map(normalize).filter((t) => t.hash));
      if (rows.length < 100) break;
    }
    if (!tracks.length) throw new Error('歌单没有返回可导入的歌曲');
    const existing = library.playlists.find((p) => p.id === `cloud-${list.id}`);
    const unique = [
      ...new Map(tracks.map((track) => [track.id, track])).values(),
    ];
    if (existing) existing.tracks = unique;
    else
      library.playlists.push({
        id: `cloud-${list.id}`,
        name: list.name,
        tracks: unique,
      });
    notify(`已导入 ${unique.length} 首歌曲`);
  } catch (error) {
    notify((error as Error).message);
  } finally {
    busy.value = false;
  }
}
async function logout() {
  await native('logout');
  await refreshAccount();
  library.favorites = library.favorites.filter((track) => !!track.uri);
  loggedIn.value = false;
  cloud.value = [];
  status.value = '';
}
onMounted(() => {
  void account();
  document.addEventListener('visibilitychange', visibility);
});
onUnmounted(() => {
  if (loginChoices.value.length)
    void native('cancelMobileLogin').catch(() => {});
  disposed = true;
  clearTimeout(timer);
  clearTimeout(cooldownTimer);
  document.removeEventListener('visibilitychange', visibility);
});
</script>
<template>
  <div class="account-panel">
    <h2>{{ loggedIn ? '我的账号' : '登录酷狗账号' }}</h2>
    <template v-if="loggedIn">
      <div class="account-summary">
        <strong>{{ name }}</strong>
        <span class="vip-badge">{{ vipLabel }}</span>
      </div>
      <button class="menu-item" :disabled="busy" @click="loadCloud">
        读取云端歌单
      </button>
      <button
        v-for="list in cloud"
        :key="list.id"
        class="menu-item"
        :disabled="busy"
        @click="importCloud(list)"
      >
        {{ list.name }}
        <span class="muted">导入</span>
      </button>
      <button class="text-button danger" @click="logout">退出登录</button>
    </template>
    <template v-else-if="loginChoices.length">
      <p class="muted">此手机号绑定了多个账号，选择你常用的账号继续登录。</p>
      <div class="login-choices" aria-label="选择酷狗账号">
        <button
          v-for="(choice, index) in loginChoices"
          :key="choice.key"
          class="login-choice"
          :disabled="loginBusy"
          @click="chooseAccount(choice)"
        >
          <img v-if="choice.avatar" :src="choice.avatar" alt="" />
          <span v-else class="choice-avatar">
            {{ choice.name.slice(0, 1) }}
          </span>
          <strong>{{ choice.name }}</strong>
          <span class="muted">账号 {{ index + 1 }}</span>
        </button>
      </div>
      <p
        class="login-message"
        :class="{ 'login-error': failed }"
        :role="failed ? 'alert' : 'status'"
      >
        {{ loginBusy ? '正在登录所选账号' : message }}
      </p>
      <button class="text-button" :disabled="loginBusy" @click="resetChoices">
        重新输入手机号
      </button>
    </template>
    <template v-else-if="mode === 'sms'">
      <p class="muted">同步你的歌单与账号播放权限。</p>
      <form class="login-form" @submit.prevent="loginMobile">
        <label class="form-field">
          手机号
          <div class="input-line">
            <span>+86</span>
            <input
              v-model="mobile"
              type="tel"
              inputmode="tel"
              autocomplete="tel-national"
              maxlength="11"
              placeholder="输入手机号码"
              aria-label="手机号码"
              required
            />
          </div>
        </label>
        <label class="form-field">
          短信验证码
          <div class="input-line">
            <input
              v-model="code"
              inputmode="numeric"
              autocomplete="one-time-code"
              maxlength="8"
              placeholder="输入验证码"
              aria-label="短信验证码"
              required
            />
            <button
              type="button"
              class="text-button"
              :disabled="!validMobile || sending || cooldown > 0"
              @click="sendCode"
            >
              {{
                sending
                  ? '发送中'
                  : cooldown
                    ? `${cooldown} 秒后重发`
                    : '获取验证码'
              }}
            </button>
          </div>
        </label>
        <p
          class="login-message"
          :class="{ 'login-error': failed }"
          :role="failed ? 'alert' : 'status'"
        >
          {{ message }}
        </p>
        <button
          class="primary full-width"
          :disabled="!validMobile || !validCode || loginBusy"
        >
          {{ loginBusy ? '正在登录' : '登录' }}
        </button>
      </form>
      <p class="muted">
        验证码由酷狗发送。手机号和验证码仅用于本次登录，不保存到资料库。
      </p>
    </template>
    <template v-else>
      <p class="muted">使用另一台设备上的酷狗 App 扫码。</p>
      <img
        v-if="qrImage"
        class="login-qr"
        :src="qrImage"
        alt="酷狗登录二维码"
      />
      <p class="muted" role="status">{{ status }}</p>
      <button class="primary" :disabled="busy" @click="createQr">
        {{ busy ? '正在获取…' : qrImage ? '刷新二维码' : '获取登录二维码' }}
      </button>
    </template>
    <div v-if="!loggedIn" class="login-alternative">
      <button class="text-button" @click="changeMode">
        {{ mode === 'sms' ? '使用扫码登录' : '使用手机验证码登录' }}
      </button>
    </div>
    <p v-if="busy && loggedIn" class="muted">正在读取，请稍候…</p>
  </div>
</template>
