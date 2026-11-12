<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue';
import {
  UserRound,
  AudioLines,
  Compass,
  Search,
  Library,
  Settings2,
  Heart,
  FolderDown,
  Plus,
  Play,
  Pause,
  SkipForward,
  ListMusic,
  Download,
  Moon,
  Check,
} from 'lucide-vue-next';
import Artwork from './components/Artwork.vue';
import AppDrawer from './components/AppDrawer.vue';
import PlayerView from './components/PlayerView.vue';
import LibraryView from './components/LibraryView.vue';
import AccountPanel from './components/AccountPanel.vue';
import PersonalHome from './components/PersonalHome.vue';
import ExploreView from './components/ExploreView.vue';
import SearchView from './components/SearchView.vue';
import CatalogDetail from './components/CatalogDetail.vue';
import VipPanel from './components/VipPanel.vue';
import QualityPanel from './components/QualityPanel.vue';
import FloatingLyricsPanel from './components/FloatingLyricsPanel.vue';
import QueuePanel from './components/QueuePanel.vue';
import NotificationPanel from './components/NotificationPanel.vue';
import {
  notifications,
  startNotifications,
  stopNotifications,
  checkNotifications,
} from './notifications';
import UpdatePanel from './components/UpdatePanel.vue';
import { updates, startUpdates } from './updates';
import LicensePanel from './components/LicensePanel.vue';
import StarPrompt from './components/StarPrompt.vue';
import { star, startStarPrompt, stopStarPrompt, claimStarPrompt } from './star';
import { native } from './bridge';
import {
  library,
  player,
  current,
  command,
  notify,
  toast,
  toggleFavorite,
  profile,
  preferences,
  refreshAccount,
  syncFavorites,
  updatePreference,
  isLiked,
  cacheSong,
  cacheState,
  refreshMusicCache,
  removeCache,
} from './store';
import type { Track, MusicEntity } from './types';

const tab = ref('discover');
const visited = ref(new Set(['discover']));
const details = ref<MusicEntity[]>([]);
const fullPlayer = ref(false);
const sheet = ref('');
const starReady = ref(false);
const pageVisible = ref(!document.hidden);
let starClaiming = false;
const searchFocused = ref(false);
const dock = ref<HTMLElement>();
const dockHeight = ref(165);
let dockObserver: ResizeObserver | undefined;
function measureDock() {
  if (dock.value) dockHeight.value = dock.value.offsetHeight;
}
function observeDock() {
  if (!dock.value || document.hidden) return;
  dockObserver?.observe(dock.value);
  measureDock();
}
watch(
  [
    () => updates.prompt,
    () => notifications.prompt,
    sheet,
    fullPlayer,
    () => star.pending,
    starReady,
    pageVisible,
  ],
  async ([
    prompt,
    permissionPrompt,
    dialog,
    playingPage,
    starPending,
    ready,
    visible,
  ]) => {
    if (permissionPrompt && !dialog && !playingPage) {
      notifications.prompt = false;
      sheet.value = 'notifications';
      return;
    }
    if (prompt && !dialog && !playingPage) {
      updates.prompt = false;
      sheet.value = 'updates';
      return;
    }
    if (!visible || dialog || permissionPrompt || prompt) return;
    if (ready) {
      starReady.value = false;
      sheet.value = 'star';
    } else if (starPending && !starClaiming) {
      starClaiming = true;
      try {
        starReady.value = await claimStarPrompt();
      } finally {
        starClaiming = false;
      }
    }
  },
);
const selected = ref<Track>();
const collection = ref('');
const playlistName = ref('');
const equalizer = ref(false);
const speed = ref('1');
const themeOptions = [
  { id: 'system', name: '跟随系统' },
  { id: 'light', name: '浅色' },
  { id: 'dark', name: '深色' },
];
const navigation = [
  { id: 'discover', name: '现在听', icon: AudioLines },
  { id: 'explore', name: '发现', icon: Compass },
  { id: 'search', name: '搜索', icon: Search },
  { id: 'library', name: '资料库', icon: Library },
];
function navigate(id: string) {
  tab.value = id;
  visited.value.add(id);
  collection.value = '';
  details.value = [];
}
function openCollection(id: string) {
  navigate('library');
  collection.value = id;
}
function openEntity(entity: MusicEntity) {
  details.value.push(entity);
}
function menu(track: Track) {
  selected.value = track;
  sheet.value = 'track';
}
function back() {
  if (sheet.value) {
    sheet.value = '';
    return true;
  }
  if (fullPlayer.value) {
    fullPlayer.value = false;
    return true;
  }
  if (details.value.length) {
    details.value.pop();
    return true;
  }
  if (collection.value) {
    collection.value = '';
    return true;
  }
  if (tab.value !== 'discover') {
    navigate('discover');
    return true;
  }
  return false;
}
async function refresh() {
  await Promise.allSettled([
    refreshMusicCache(),
    refreshAccount().then(() => syncFavorites()),
    native<typeof preferences>('settings').then((value) =>
      Object.assign(preferences, value),
    ),
  ]);
}
function createPlaylist() {
  const name = playlistName.value.trim();
  if (!name) return;
  const id = crypto.randomUUID();
  library.playlists.push({ id, name, tracks: [] });
  playlistName.value = '';
  sheet.value = '';
  openCollection(id);
}
function addToPlaylist(id: string) {
  const playlist = library.playlists.find((p) => p.id === id);
  if (
    playlist &&
    selected.value &&
    !playlist.tracks.some((t) => t.id === selected.value!.id)
  )
    playlist.tracks.push(selected.value);
  notify('已加入歌单');
  sheet.value = '';
}
async function removeFromCollection() {
  if (!selected.value) return;
  if (collection.value === 'favorites') void toggleFavorite(selected.value);
  else if (collection.value === 'local') {
    if (selected.value.cached) {
      try {
        await removeCache(selected.value);
      } catch (error) {
        notify((error as Error).message);
        return;
      }
    } else
      library.local = library.local.filter((t) => t.id !== selected.value!.id);
  } else {
    const playlist = library.playlists.find((p) => p.id === collection.value);
    if (playlist)
      playlist.tracks = playlist.tracks.filter(
        (t) => t.id !== selected.value!.id,
      );
  }
  sheet.value = '';
}
const systemTheme = matchMedia('(prefers-color-scheme: dark)');
const systemDark = ref(window.tingSystemDark ?? systemTheme.matches);
function systemAppearance(event: Event) {
  systemDark.value = (event as CustomEvent<{ dark: boolean }>).detail.dark;
  updateAppearance();
}
function browserAppearance() {
  if (!window.Native) systemDark.value = systemTheme.matches;
  updateAppearance();
}
function updateAppearance() {
  const dark =
    library.theme === 'dark' ||
    (library.theme === 'system' && systemDark.value);
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  if (window.Native)
    void command('appearance', {
      dark,
    });
}
watch(() => library.theme, updateAppearance, { immediate: true });
function visibility() {
  pageVisible.value = !document.hidden;
  if (document.hidden) {
    dockObserver?.disconnect();
    for (const animation of document.getAnimations()) {
      if (animation.effect?.getComputedTiming().iterations === Infinity)
        animation.cancel();
      else animation.finish();
    }
  }
  document.body.classList.toggle('backgrounded', document.hidden);
  if (!document.hidden) {
    observeDock();
    void command('state');
    void refresh();
  }
}
function resumed() {
  pageVisible.value = !document.hidden;
  observeDock();
  void refresh();
  void command('state');
  if (window.tingSystemDark !== undefined)
    systemDark.value = window.tingSystemDark;
  updateAppearance();
}
function changePreference(event: Event, key: keyof typeof preferences) {
  const input = event.target as HTMLInputElement;
  const enabled = input.checked;
  input.checked = preferences[key];
  void updatePreference(key, enabled);
}
function keydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    back();
  }
}
function cacheSelected() {
  if (selected.value) void cacheSong(selected.value);
  sheet.value = '';
}
function cacheChanged() {
  void refreshMusicCache().catch((error: Error) => notify(error.message));
}
function openCache() {
  sheet.value = '';
  openCollection('local');
}
onMounted(() => {
  window.addEventListener('ting:cache-changed', cacheChanged);
  dockObserver = new ResizeObserver(measureDock);
  observeDock();
  systemTheme.addEventListener('change', browserAppearance);
  window.addEventListener('ting:appearance', systemAppearance);
  window.addEventListener('ting:resume', resumed);
  void refresh();
  startUpdates();
  startNotifications();
  startStarPrompt();
  window.tingHandleBack = back;
  void command('state');
  document.addEventListener('visibilitychange', visibility);
  window.addEventListener('keydown', keydown);
});
onUnmounted(() => {
  window.removeEventListener('ting:cache-changed', cacheChanged);
  dockObserver?.disconnect();
  stopNotifications();
  stopStarPrompt();
  delete window.tingHandleBack;
  systemTheme.removeEventListener('change', browserAppearance);
  window.removeEventListener('ting:appearance', systemAppearance);
  window.removeEventListener('ting:resume', resumed);
  document.removeEventListener('visibilitychange', visibility);
  window.removeEventListener('keydown', keydown);
});
</script>

<template>
  <div
    class="app-shell"
    :class="{ 'search-editing': tab === 'search' && searchFocused }"
    :style="{ '--dock-height': `${dockHeight}px` }"
  >
    <aside class="sidebar">
      <div class="brand">
        <img class="brand-logo" src="/brand/tvt.svg" alt="" />
        <span>TvT</span>
      </div>

      <nav>
        <button
          v-for="item in navigation"
          :key="item.id"
          :class="{ selected: tab === item.id }"
          @click="navigate(item.id)"
        >
          <component :is="item.icon" :size="21" />
          {{ item.name }}
        </button>
      </nav>
      <div class="sidebar-bottom">
        <button @click="sheet = 'settings'">
          <Settings2 :size="19" />
          偏好设置
        </button>
      </div>
    </aside>
    <main>
      <header class="topbar">
        <button
          class="icon round"
          aria-label="账号与设置"
          @click="sheet = 'account'"
        >
          <img
            v-if="profile.avatar"
            :src="profile.avatar"
            class="user-avatar"
            alt="用户头像"
          />
          <UserRound v-else :size="21" />
        </button>
      </header>
      <div class="page-stage">
        <Transition name="page" mode="out-in">
          <CatalogDetail
            v-if="details.length"
            :key="`${details.at(-1)!.kind}:${details.at(-1)!.id}`"
            :item="details.at(-1)!"
            @back="details.pop()"
            @open="openEntity"
            @menu="menu"
          />
        </Transition>
        <Transition name="page">
          <div v-show="!details.length" class="tab-stage">
            <Transition name="page">
              <PersonalHome
                :key="String(profile.loggedIn)"
                v-show="tab === 'discover'"
                @open="openEntity"
                @menu="menu"
                @explore="navigate('explore')"
                @account="sheet = 'account'"
              />
            </Transition>
            <Transition name="page">
              <ExploreView
                v-if="visited.has('explore')"
                v-show="tab === 'explore'"
                @open="openEntity"
                @menu="menu"
              />
            </Transition>
            <Transition name="page">
              <SearchView
                :active="tab === 'search'"
                v-if="visited.has('search')"
                v-show="tab === 'search'"
                @open="openEntity"
                @menu="menu"
                @focus="searchFocused = $event"
              />
            </Transition>
            <Transition name="page">
              <LibraryView
                v-if="visited.has('library')"
                v-show="tab === 'library'"
                v-model:collection="collection"
                @menu="menu"
                @sheet="sheet = $event"
              />
            </Transition>
          </div>
        </Transition>
      </div>
    </main>
    <div
      class="bottom-dock"
      ref="dock"
      :inert="tab === 'search' && searchFocused"
      :aria-hidden="tab === 'search' && searchFocused"
    >
      <div v-if="current" class="mini-player">
        <button class="mini-info" @click="fullPlayer = true">
          <Artwork :src="current.cover" :seed="current.title" />
          <span>
            <strong>{{ current.title }}</strong>
            <small>{{ current.artist }}</small>
          </span>
        </button>
        <button
          class="icon"
          :aria-label="player.playing ? '暂停' : '播放'"
          @click="command('toggle')"
        >
          <component
            :is="player.playing ? Pause : Play"
            :size="23"
            fill="currentColor"
          />
        </button>
        <button class="icon" aria-label="下一首" @click="command('next')">
          <SkipForward :size="22" />
        </button>
        <button class="icon" aria-label="播放队列" @click="sheet = 'queue'">
          <ListMusic :size="22" />
        </button>
        <div
          class="mini-progress"
          :style="{
            width: `${player.duration ? (player.position / player.duration) * 100 : 0}%`,
          }"
        />
      </div>
      <nav class="bottom-nav">
        <button
          v-for="item in navigation"
          :key="item.id"
          :class="{ selected: tab === item.id }"
          @click="navigate(item.id)"
        >
          <component :is="item.icon" :size="23" />
          <span>{{ item.name }}</span>
        </button>
      </nav>
    </div>
    <PlayerView
      :open="fullPlayer"
      @close="fullPlayer = false"
      @menu="menu"
      @sheet="sheet = $event"
    />
    <AppDrawer :name="sheet" @close="sheet = ''">
      <template v-if="sheet === 'track' && selected">
        <div class="sheet-track">
          <Artwork :src="selected.cover" :seed="selected.title" />
          <div>
            <h2>{{ selected.title }}</h2>
            <p>{{ selected.artist }}</p>
          </div>
        </div>
        <button
          class="menu-item"
          @click="
            toggleFavorite(selected);
            sheet = '';
          "
        >
          <Heart :size="21" />
          {{ isLiked(selected!) ? '取消喜欢' : '加入我喜欢' }}
        </button>
        <button
          class="menu-item"
          @click="
            command('enqueue', { track: selected });
            sheet = '';
            notify('已加入播放队列');
          "
        >
          <ListMusic :size="21" />
          加入播放队列
        </button>
        <button class="menu-item" @click="sheet = 'add'">
          <Plus :size="21" />
          添加到歌单
        </button>
        <button
          v-if="selected.hash && !selected.uri"
          class="menu-item"
          :disabled="cacheState(selected) !== ''"
          @click="cacheSelected"
        >
          <Download :size="21" />
          {{
            cacheState(selected) === 'ready'
              ? '已缓存 · 可离线播放'
              : cacheState(selected) === 'pending'
                ? '正在缓存'
                : '缓存歌曲'
          }}
        </button>
        <button
          v-if="collection && collection !== 'history'"
          class="menu-item danger"
          @click="removeFromCollection"
        >
          {{
            collection === 'local' && selected.cached
              ? '删除缓存'
              : '从当前列表移除'
          }}
        </button>
      </template>
      <template v-if="sheet === 'create'">
        <h2>新建歌单</h2>
        <form @submit.prevent="createPlaylist">
          <input
            v-model="playlistName"
            class="text-input"
            autofocus
            maxlength="40"
            placeholder="给歌单起个名字"
            aria-label="歌单名称"
          />
          <button class="primary full-width" :disabled="!playlistName.trim()">
            创建歌单
          </button>
        </form>
      </template>
      <template v-if="sheet === 'add'">
        <h2>添加到歌单</h2>
        <button
          v-for="list in library.playlists"
          :key="list.id"
          class="menu-item"
          @click="addToPlaylist(list.id)"
        >
          <ListMusic :size="21" />
          {{ list.name }}
        </button>
        <p v-if="!library.playlists.length" class="muted">
          还没有歌单，先在资料库创建一张吧。
        </p>
      </template>
      <template v-if="sheet === 'delete'">
        <h2>删除这张歌单？</h2>
        <p class="muted">歌单中的音乐文件不会被删除。</p>
        <button
          class="primary danger-bg"
          @click="
            library.playlists = library.playlists.filter(
              (p) => p.id !== collection,
            );
            collection = '';
            sheet = '';
          "
        >
          确认删除
        </button>
      </template>
      <QueuePanel v-if="sheet === 'queue'" />
      <template v-if="sheet === 'sleep'">
        <h2>让音乐陪你入睡</h2>
        <p class="muted">到时自动暂停播放。</p>
        <button
          v-for="minutes in [15, 30, 45, 60, 90]"
          :key="minutes"
          class="menu-item"
          @click="
            command('sleep', { minutes });
            sheet = '';
            notify(`将在 ${minutes} 分钟后暂停`);
          "
        >
          <Moon :size="20" />
          {{ minutes }} 分钟
        </button>
        <button
          class="menu-item"
          @click="
            command('sleep', { minutes: 0 });
            sheet = '';
          "
        >
          关闭定时
        </button>
      </template>
      <template v-if="sheet === 'sound'">
        <QualityPanel />
        <label class="setting-row">
          <span>播放速度</span>
          <div class="speed-options">
            <button
              v-for="value in ['0.75', '1', '1.25', '1.5', '2']"
              :key="value"
              :class="{ selected: speed === value }"
              @click="
                speed = value;
                command('speed', { speed: Number(value) });
              "
            >
              {{ value }}×
            </button>
          </div>
        </label>
        <label class="setting-row">
          <span>轻柔低音增强</span>
          <input
            v-model="equalizer"
            type="checkbox"
            @change="command('bass', { enabled: equalizer })"
          />
        </label>
        <p class="muted">音效取决于设备支持情况。</p>
      </template>
      <template v-if="sheet === 'settings'">
        <h2>偏好设置</h2>
        <h3>外观</h3>
        <div class="theme-options">
          <button
            v-for="option in themeOptions"
            :key="option.id"
            :class="{ selected: library.theme === option.id }"
            @click="library.theme = option.id"
          >
            {{ option.name }}
            <Check v-if="library.theme === option.id" :size="15" />
          </button>
        </div>

        <h3>播放与后台</h3>
        <label
          v-for="option in [
            {
              key: 'resumeOnLaunch' as const,
              name: '重新打开时恢复上次播放',
            },
            { key: 'autoVip' as const, name: '打开应用时自动签到' },
            { key: 'floatingLyrics' as const, name: '桌面歌词' },
            { key: 'preloadAudio' as const, name: '预缓存后续歌曲' },
          ]"
          :key="option.key"
          class="setting-row"
        >
          <span>{{ option.name }}</span>
          <input
            type="checkbox"
            :checked="preferences[option.key]"
            @change="changePreference($event, option.key)"
          />
        </label>
        <p class="muted">桌面歌词支持大部分自定义内容。</p>
        <button class="menu-item" @click="sheet = 'lyricsSettings'">
          桌面歌词设置
        </button>
        <button
          class="menu-item"
          @click="
            checkNotifications();
            sheet = 'notifications';
          "
        >
          锁屏与悬浮通知
        </button>
        <h3>音乐与存储</h3>
        <button
          class="menu-item"
          @click="
            command('exportLibrary', {
              version: 1,
              favorites: library.favorites,
              playlists: library.playlists,
            })
          "
        >
          备份资料库
        </button>
        <button class="menu-item" @click="command('importLibrary')">
          导入资料库备份
        </button>
        <button class="menu-item" @click="openCache">管理歌曲缓存</button>
        <button
          class="menu-item"
          @click="
            command('import');
            sheet = '';
          "
        >
          <FolderDown :size="20" />
          导入本地音乐
        </button>
        <h3>关于TvT</h3>
        <button class="menu-item" @click="sheet = 'updates'">应用更新</button>
        <button class="text-button" @click="sheet = 'licenses'">
          开源许可
        </button>
        <p class="muted">
          {{ updates.currentVersion }} · 安卓版
          <br />
          无广告 SDK，无用户行为分析。
          <br />
          GPL-2.0-only · 非酷狗官方应用。
        </p>
      </template>
      <template v-if="sheet === 'account'">
        <AccountPanel />
        <button
          v-if="profile.loggedIn"
          class="menu-item"
          @click="sheet = 'vip'"
        >
          概念版 VIP 与领取记录
        </button>
        <button class="menu-item" @click="sheet = 'settings'">
          <Settings2 :size="20" />
          偏好设置
        </button>
      </template>
      <FloatingLyricsPanel v-if="sheet === 'lyricsSettings'" />
      <VipPanel v-if="sheet === 'vip'" />
      <NotificationPanel v-if="sheet === 'notifications'" @close="sheet = ''" />
      <UpdatePanel v-if="sheet === 'updates'" />
      <LicensePanel v-if="sheet === 'licenses'" />
      <StarPrompt v-if="sheet === 'star'" @close="sheet = ''" />
    </AppDrawer>
    <Transition name="toast">
      <div v-if="toast.text" class="toast" role="status">
        {{ toast.text }}
      </div>
    </Transition>
  </div>
</template>
