import { createApp } from 'vue';
import App from './App.vue';
import './design.css';
import { notify, library } from './store';
import type { Track, Playlist } from './types';
window.addEventListener('ting:backup', (event) => {
  const data = (event as CustomEvent).detail;
  const validTrack = (track: Track) =>
    track &&
    typeof track.id === 'string' &&
    typeof track.title === 'string' &&
    typeof track.artist === 'string' &&
    (!track.uri || track.uri.startsWith('content://'));
  if (
    data?.version !== 1 ||
    !Array.isArray(data.favorites) ||
    !Array.isArray(data.playlists) ||
    !data.favorites.every(validTrack) ||
    !data.playlists.every(
      (list: Playlist) =>
        typeof list.id === 'string' &&
        typeof list.name === 'string' &&
        Array.isArray(list.tracks) &&
        list.tracks.every(validTrack),
    )
  ) {
    notify('这不是有效的TvT资料库文件');
    return;
  }
  library.favorites = [
    ...new Map(
      [...library.favorites, ...data.favorites].map((track) => [
        track.id,
        track,
      ]),
    ).values(),
  ];
  for (const list of data.playlists as Playlist[]) {
    const existing = library.playlists.find((item) => item.id === list.id);
    if (existing)
      existing.tracks = [
        ...new Map(
          [...existing.tracks, ...list.tracks].map((track) => [
            track.id,
            track,
          ]),
        ).values(),
      ];
    else library.playlists.push(list);
  }
  notify('资料库已合并导入');
});
window.addEventListener('ting:message', (event) =>
  notify((event as CustomEvent<string>).detail),
);
createApp(App).mount('#app');
