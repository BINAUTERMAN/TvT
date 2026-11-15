export interface Track {
  id: string;
  title: string;
  artist: string;
  album: string;
  cover: string;
  hash: string;
  albumId: string;
  audioId: string;
  duration: number;
  uri?: string;
  artistId?: string;
  original?: boolean;
  heat?: number;
  cached?: boolean;
  cacheQuality?: string;
}
export interface Playback {
  queue: Track[];
  index: number;
  playing: boolean;
  loading: boolean;
  position: number;
  duration: number;
  mode: number;
  error?: string;
  sleepAt: number;
  color?: string;
  liked?: boolean;
  favoritesReady?: boolean;
  favoritePending?: boolean;
  quality?: string;
  interrupted?: boolean;
  personalFm?: boolean;
  fmStarting?: boolean;
  fmBusy?: boolean;
  fmWaiting?: boolean;
  fmError?: string;
}
export interface LyricLine {
  time: number;
  text: string;
  translation?: string;
  end?: number;
  words?: { text: string; time: number; end: number }[];
}
export interface LyricDocument {
  lines: LyricLine[];
  translationAvailable: boolean;
  translationSource?: string;
}
export type EntityKind = 'artist' | 'album' | 'playlist' | 'rank';
export interface MusicEntity {
  kind: EntityKind;
  id: string;
  name: string;
  subtitle: string;
  cover: string;
  coverAlternatives?: string[];
  description?: string;
  count?: number;
  presetTracks?: Track[];
}
