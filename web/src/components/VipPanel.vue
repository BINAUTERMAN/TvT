<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Crown, RefreshCw, Gift } from 'lucide-vue-next';
import { preferences, updatePreference } from '../store';
import { vip, refreshVip, formatVipExpiry } from '../vip';
import { checkin, refreshCheckin, stepCheckin } from '../checkin';

const busy = ref(false);
const claiming = ref(false);
const error = ref('');
const message = ref('');
const activeRights = computed(() => vip.rights.filter((item) => item.active));
const receivedLabels = computed(() =>
  [...new Set(vip.todayReceipts.map((item) => item.label))].join('、'),
);
const conceptActive = computed(() =>
  activeRights.value.some(
    (item) => item.type === 'svip' || item.label === '概念版 VIP',
  ),
);
const rewardMode = computed(() => checkin.mode === 'reward');
const fallback = computed(() => checkin.probe.checked && !checkin.probe.ok);
const claimLabel = computed(() => {
  if (claiming.value) return '正在确认';
  if (rewardMode.value) {
    if (checkin.done) return '今日签到已完成';
    if (checkin.claims > 0)
      return `继续签到（${checkin.claims}/${checkin.maxClaims}）`;
    return '签到领 VIP';
  }
  if (vip.todayClaimed) return '今日已领取';
  if (conceptActive.value) return '概念版 VIP 生效中';
  return checkin.mode === 'listen' ? '领取一天畅听 VIP' : '签到领 VIP';
});
const blocked = computed(
  () =>
    busy.value ||
    claiming.value ||
    vip.todayClaimed ||
    conceptActive.value ||
    (rewardMode.value && checkin.done) ||
    (checkin.mode === 'off' && checkin.probe.checked && !checkin.loggedIn),
);
function changeAutoVip(event: Event) {
  void updatePreference('autoVip', (event.target as HTMLInputElement).checked);
}
async function load() {
  busy.value = true;
  error.value = '';
  try {
    // 权益查询与签到进度互不影响，各自取结果。
    await Promise.allSettled([refreshVip(true), refreshCheckin()]);
    error.value = vip.recordError || '';
  } catch (cause) {
    error.value = (cause as Error).message;
  } finally {
    busy.value = false;
  }
}
async function claim() {
  claiming.value = true;
  error.value = '';
  message.value = '';
  try {
    const state = await stepCheckin();
    if (state.message) message.value = state.message;
    if (state.error) error.value = state.error;
  } catch (cause) {
    error.value = (cause as Error).message;
  } finally {
    claiming.value = false;
  }
}
onMounted(() => void load());
</script>
<template>
  <div class="vip-panel">
    <Crown :size="32" :stroke-width="1.5" />
    <h2>会员权益</h2>
    <div class="vip-status">
      <span>{{ busy ? '正在查询账号权益' : vip.label }}</span>
      <button
        class="icon"
        aria-label="刷新VIP状态"
        :disabled="busy || claiming"
        @click="load"
      >
        <RefreshCw :size="18" />
      </button>
    </div>
    <div v-for="right in activeRights" :key="right.label" class="vip-right">
      <strong>{{ right.label }}</strong>
      <span class="muted">{{ formatVipExpiry(right.expiresAt) }} 到期</span>
    </div>
    <p v-if="!activeRights.length && vip.active && vip.expiresAt" class="muted">
      {{ formatVipExpiry(vip.expiresAt) }} 到期
    </p>
    <div v-if="fallback" class="checkin-alert">
      <strong>
        概念版VIP接口测试失败，无法享受高音质服务，推荐自行前往官方软件领取
      </strong>
      <span v-if="checkin.probe.message" class="muted">
        平台返回：{{ checkin.probe.message }}
      </span>
    </div>
    <div v-else-if="rewardMode" class="checkin-progress">
      <strong>今日签到奖励 {{ checkin.claims }}/{{ checkin.maxClaims }}</strong>
    </div>
    <button class="primary full-width" :disabled="blocked" @click="claim">
      <Gift :size="19" />
      {{ claimLabel }}
    </button>
    <p
      v-if="vip.todayClaimed && receivedLabels"
      class="login-message"
      role="status"
    >
      今日已领取：{{ receivedLabels }}
    </p>
    <p
      v-if="!busy && !fallback && !conceptActive && vip.checkedAt"
      class="muted"
    >
      高音质权益可前往官方 App 领取。
    </p>
    <p v-if="checkin.message || message" class="login-message" role="status">
      {{ checkin.message || message }}
    </p>
    <p v-if="error || checkin.error" class="login-error" role="alert">
      {{ error || checkin.error }}
    </p>
    <label class="setting-row">
      <span>启动时自动签到</span>
      <input
        type="checkbox"
        :checked="preferences.autoVip"
        @change="changeAutoVip"
      />
    </label>
    <p v-if="conceptActive" class="muted">
      概念版 VIP 有效期间，跳过畅听领取。
    </p>
  </div>
</template>
