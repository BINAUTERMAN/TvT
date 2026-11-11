package cn.ting.music;

import android.app.*;
import android.content.*;
import android.media.*;
import android.media.audiofx.BassBoost;
import android.media.session.*;
import android.net.Uri;
import android.os.*;
import java.util.concurrent.*;
import org.json.*;

public final class PlaybackService extends Service {
  public static PlaybackService instance;

  public interface Observer {
    void changed(JSONObject state);
  }

  public Observer observer;

  public final class LocalBinder extends Binder {
    public PlaybackService service() {
      return PlaybackService.this;
    }
  }

  private final LocalBinder binder = new LocalBinder();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService network = Executors.newSingleThreadExecutor();
  private final ExecutorService favoriteWork = Executors.newSingleThreadExecutor();
  private MediaPlayer media;
  private VolumeShaper envelope;
  private boolean fadingOut;
  private final Runnable finishFade = () -> completeFade();
  private MediaSession session;
  private final ArtworkLoader artworkLoader = new ArtworkLoader();
  private android.graphics.Bitmap artwork;
  private AudioManager audio;
  private AudioFocusRequest focus;
  private BassBoost bass;
  private JSONArray queue = new JSONArray();
  private int index;
  private int mode;
  private volatile int generation;
  private int resumePosition;
  private boolean loading;
  private boolean prepared;
  private boolean resumeAfterFocus;
  private final Runnable periodicCheckpoint =
      new Runnable() {
        public void run() {
          if (isPlaying()) {
            checkpoint();
            handler.postDelayed(this, 30000);
          }
        }
      };
  private boolean bassEnabled;
  private boolean foreground;
  private boolean destroyed;
  private float speed = 1f;
  private long sleepAt;
  private String error = "";
  private boolean favorite;
  private boolean favoritesReady;
  private boolean favoritePending;
  private String quality = "128";
  private String resolvedQuality = "128";
  private FloatingLyrics floatingLyrics;
  private boolean lyricBuffering;
  private final java.util.List<Long> lyricTimes = new java.util.ArrayList<>();
  private final java.util.List<JSONObject> lyricLines = new java.util.ArrayList<>();
  private final BroadcastReceiver displayChanged =
      new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
          if (floatingLyrics == null) return;
          if (Intent.ACTION_SCREEN_ON.equals(intent.getAction()) && lyricLines.isEmpty())
            loadFloatingLyrics();
          else updateFloatingLyrics();
        }
      };
  private final Runnable lyricTick = () -> updateFloatingLyrics();
  private KugouApi api;
  private String playbackSession;
  private PlaybackBuffer playbackBuffer;
  private final BroadcastReceiver bufferPolicyChanged =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          refreshBuffer();
        }
      };
  private final Runnable sleepTask =
      () -> {
        sleepAt = 0;
        pause();
      };
  private final BroadcastReceiver noisy =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          resumeAfterFocus = false;
          pause();
        }
      };

  @Override
  public void onCreate() {
    super.onCreate();
    instance = this;
    api = new KugouApi(this);
    playbackSession = getSharedPreferences("playback", MODE_PRIVATE).getString("starSession", "");
    if (playbackSession.isEmpty()) newPlaybackSession();
    playbackBuffer = new PlaybackBuffer(this, api);
    IntentFilter bufferPolicy = new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
    bufferPolicy.addAction(android.net.ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED);
    bufferPolicy.addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION);
    if (Build.VERSION.SDK_INT >= 33)
      registerReceiver(bufferPolicyChanged, bufferPolicy, Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(bufferPolicyChanged, bufferPolicy);
    floatingLyrics = new FloatingLyrics(this);
    audio = (AudioManager) getSystemService(AUDIO_SERVICE);
    AudioAttributes attributes =
        new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();
    focus =
        new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(this::focusChanged, handler)
            .build();
    session = new MediaSession(this, "TingMusic");
    session.setFlags(
        MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
    session.setSessionActivity(
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    session.setMediaButtonReceiver(
        PendingIntent.getBroadcast(
            this,
            7,
            new Intent(this, MediaButtonReceiver.class).setAction(Intent.ACTION_MEDIA_BUTTON),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    session.setPlaybackToLocal(attributes);
    session.setCallback(
        new MediaSession.Callback() {
          @Override
          public void onPlay() {
            toggleToPlay();
          }

          @Override
          public void onPause() {
            pause();
          }

          @Override
          public void onCustomAction(String action, Bundle extras) {
            if (action.equals("favorite")) toggleCloudFavorite();
            else if (action.equals("lyrics"))
              try {
                lyricsControl().send();
              } catch (PendingIntent.CanceledException ignored) {
              }
          }

          @Override
          public void onSkipToNext() {
            next(false);
          }

          @Override
          public void onSkipToPrevious() {
            next(true);
          }

          @Override
          public void onSeekTo(long position) {
            seek(position / 1000.0);
          }

          @Override
          public void onStop() {
            pause();
          }
        });
    NotificationAccess.ensureChannel(this);
    IntentFilter display = new IntentFilter(Intent.ACTION_SCREEN_ON);
    display.addAction(Intent.ACTION_SCREEN_OFF);
    if (Build.VERSION.SDK_INT >= 33)
      registerReceiver(displayChanged, display, Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(displayChanged, display);
    if (Build.VERSION.SDK_INT >= 33)
      registerReceiver(
          noisy,
          new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
          Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    try {
      android.content.SharedPreferences saved = getSharedPreferences("playback", MODE_PRIVATE);
      queue = new JSONArray(saved.getString("queue", "[]"));
      index = Math.max(0, Math.min(saved.getInt("index", 0), queue.length() - 1));
      mode = saved.getInt("mode", 0);
      quality = saved.getString("quality", "128");
      resumePosition = saved.getInt("position", 0);
    } catch (JSONException ignored) {
      queue = new JSONArray();
    }
  }

  public JSONObject snapshot() {
    JSONObject state = new JSONObject();
    try {
      state
          .put("queue", queue)
          .put("index", index)
          .put("playing", isPlaying())
          .put("loading", loading);
      state.put(
          "position",
          prepared && media != null
              ? media.getCurrentPosition() / 1000.0
              : resumePosition / 1000.0);
      state.put("duration", prepared && media != null ? media.getDuration() / 1000.0 : 0);
      state.put("mode", mode).put("sleepAt", sleepAt).put("error", error);
      state
          .put("liked", favorite)
          .put("favoritesReady", favoritesReady)
          .put("favoritePending", favoritePending)
          .put("quality", resolvedQuality)
          .put("interrupted", resumeAfterFocus);
    } catch (Exception ignored) {
    }
    return state;
  }

  private boolean isPlaying() {
    return media != null && prepared && !fadingOut && media.isPlaying();
  }

  private JSONObject track() {
    return queue.optJSONObject(index);
  }

  public void execute(String action, JSONObject data) throws Exception {
    switch (action) {
      case "favorite":
        toggleCloudFavorite();
        break;
      case "lyrics":
        boolean lyricsEnabled =
            getSharedPreferences("preferences", MODE_PRIVATE).getBoolean("floatingLyrics", false);
        boolean locked =
            getSharedPreferences("preferences", MODE_PRIVATE).getBoolean("lyricsLocked", false);
        getSharedPreferences("preferences", MODE_PRIVATE)
            .edit()
            .putBoolean("floatingLyrics", !lyricsEnabled || locked)
            .putBoolean("lyricsLocked", false)
            .apply();
        floatingLyrics.refresh();
        loadFloatingLyrics();
        showNotification();
        emit();
        break;
      case "quality":
        String selected = data.optString("quality", "128");
        if (!java.util.Arrays.asList("128", "320", "flac", "high").contains(selected))
          throw new Exception("音质选项无效");
        quality = selected;
        getSharedPreferences("playback", MODE_PRIVATE).edit().putString("quality", quality).apply();
        resumePosition = prepared ? media.getCurrentPosition() : resumePosition;
        load(isPlaying() || loading, false);
        break;
      case "pause":
        pause();
        break;
      case "resume":
        toggleToPlay();
        break;
      case "play":
        resumePosition = 0;
        JSONArray tracks = data.getJSONArray("tracks");
        if (tracks.length() == 0 || tracks.length() > 3000) throw new Exception("播放队列为空或过大");
        queue = tracks;
        index = Math.max(0, Math.min(data.optInt("index"), queue.length() - 1));
        newPlaybackSession();
        load();
        break;
      case "toggle":
        if (isPlaying() || loading) pause();
        else toggleToPlay();
        break;
      case "next":
        next(false);
        break;
      case "previous":
        next(true);
        break;
      case "seek":
        seek(data.optDouble("position"));
        break;
      case "mode":
        mode = Math.max(0, Math.min(2, data.optInt("mode")));
        emit();
        break;
      case "jump":
        index = Math.max(0, Math.min(data.optInt("index"), queue.length() - 1));
        newPlaybackSession();
        load();
        break;
      case "enqueue":
        queue.put(data.getJSONObject("track"));
        emit();
        break;
      case "move":
        int from = data.optInt("from", -1), to = data.optInt("to", -1);
        if (from < 0 || to < 0 || from >= queue.length() || to >= queue.length())
          throw new Exception("播放队列已变化，请重新排序");
        if (data.has("id")
            && !data.optString("id").equals(queue.getJSONObject(from).optString("id")))
          throw new Exception("播放队列已变化，请重新排序");
        JSONObject moving = queue.getJSONObject(from);
        JSONArray reordered = new JSONArray();
        java.util.List<JSONObject> values = new java.util.ArrayList<>();
        for (int position = 0; position < queue.length(); position++)
          if (position != from) values.add(queue.getJSONObject(position));
        values.add(to, moving);
        for (JSONObject value : values) reordered.put(value);
        queue = reordered;
        if (index == from) index = to;
        else if (from < index && to >= index) index--;
        else if (from > index && to <= index) index++;
        checkpoint();
        emit();
        break;
      case "remove":
        remove(data.optInt("index"));
        break;
      case "sleep":
        handler.removeCallbacks(sleepTask);
        long delay = Math.max(0, Math.min(180, data.optInt("minutes"))) * 60000L;
        sleepAt = delay == 0 ? 0 : System.currentTimeMillis() + delay;
        if (delay > 0) handler.postDelayed(sleepTask, delay);
        emit();
        break;
      case "speed":
        speed = Math.max(.5f, Math.min(2f, (float) data.optDouble("speed", 1)));
        if (isPlaying()) media.setPlaybackParams(media.getPlaybackParams().setSpeed(speed));
        updateFloatingLyrics();
        emit();
        break;
      case "bass":
        bassEnabled = data.optBoolean("enabled");
        applyBass();
        break;
      default:
        emit();
    }
    getSharedPreferences("playback", MODE_PRIVATE)
        .edit()
        .putString("queue", queue.toString())
        .putInt("index", index)
        .putInt("mode", mode)
        .apply();
    refreshBuffer();
  }

  private void remove(int target) {
    if (target < 0 || target >= queue.length()) return;
    queue.remove(target);
    if (queue.length() == 0) {
      pause();
      releaseMedia();
      index = 0;
    } else if (target == index) {
      index = Math.min(index, queue.length() - 1);
      newPlaybackSession();
      load();
    } else if (target < index) index--;
    emit();
  }

  private void load() {
    load(true);
  }

  private void load(boolean autoPlay) {
    load(autoPlay, true);
  }

  private void load(boolean autoPlay, boolean preferCache) {
    cancelInterruption();
    playbackBuffer.stop();
    JSONObject item = track();
    if (item == null) return;
    int token = ++generation;
    String requestedQuality = quality;
    lyricTimes.clear();
    lyricLines.clear();
    handler.removeCallbacks(lyricTick);
    favoritesReady = false;
    favoritePending = false;
    favorite = false;
    artwork = null;
    updateMetadata();
    artworkLoader.load(
        item.optString("cover"),
        bitmap -> {
          if (token == generation) {
            artwork = bitmap;
            updateMetadata();
            showNotification();
          }
        });
    int startPosition = resumePosition;
    resumePosition = 0;
    releaseMedia();
    loading = true;
    error = "";
    showNotification();
    emit();
    network.execute(
        () -> {
          if (token != generation) return;
          try {
            String uri = item.optString("uri");
            JSONObject cached =
                (preferCache && uri.isEmpty()) || uri.startsWith("ting-cache://")
                    ? new MusicCache(this).playback(item)
                    : null;
            if (cached == null && preferCache && uri.isEmpty())
              cached = playbackBuffer.playback(item, requestedQuality);
            if (cached != null) {
              uri = cached.getString("uri");
              resolvedQuality = cached.optString("quality", "128");
            }
            if (uri.isEmpty()) {
              JSONObject requested =
                  new JSONObject(item.toString()).put("quality", requestedQuality);
              JSONObject resolved = api.bestSongUrl(requested);
              uri = resolved.getString("url");
              if (token != generation) return;
              resolvedQuality = resolved.getString("quality");
              if (!resolvedQuality.equals(quality)) {
                String label =
                    QualityCatalog.LABELS[
                        java.util.Arrays.asList(QualityCatalog.KEYS).indexOf(resolvedQuality)];
                handler.post(
                    () -> {
                      if (token != generation) return;
                      error = "所选音质暂不可用，已选择可播放的最高音质：" + label;
                      emit();
                      error = "";
                    });
              }
            }
            final String address = uri;
            handler.post(
                () -> {
                  if (token != generation) return;
                  try {
                    media = new MediaPlayer();
                    media.setAudioAttributes(
                        new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build());
                    media.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
                    media.setOnInfoListener(
                        (player, what, extra) -> {
                          if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                            playbackBuffer.stop();
                            lyricBuffering = true;
                            floatingLyrics.stopAnimation();
                          } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                            lyricBuffering = false;
                            updateFloatingLyrics();
                            refreshBuffer();
                          }
                          return false;
                        });
                    media.setDataSource(this, Uri.parse(address));
                    media.setOnSeekCompleteListener(player -> updateFloatingLyrics());
                    media.setOnPreparedListener(
                        player -> {
                          prepared = true;
                          loading = false;
                          if (startPosition > 0)
                            player.seekTo(Math.min(startPosition, player.getDuration()));
                          if (autoPlay
                              && audio.requestAudioFocus(focus)
                                  == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                            player.setPlaybackParams(new PlaybackParams().setSpeed(speed));
                            fadeVolume(0, 1);
                            player.start();
                            new StarPrompt(this).started(playbackSession);
                            applyBass();
                          }
                          updateMetadata();
                          refreshFavoriteState();
                          loadFloatingLyrics();
                          showNotification();
                          emit();
                          refreshBuffer();
                        });
                    media.setOnCompletionListener(
                        player -> {
                          if (mode == 1) {
                            player.seekTo(0);
                            player.start();
                            emit();
                          } else if (mode == 0 && index == queue.length() - 1) {
                            pause();
                          } else next(false);
                        });
                    media.setOnErrorListener(
                        (player, what, extra) -> {
                          fail("音频加载失败，请重试或切换歌曲");
                          return true;
                        });
                    media.prepareAsync();
                  } catch (Exception exception) {
                    fail("无法打开音频文件");
                  }
                });
          } catch (Exception exception) {
            handler.post(
                () -> {
                  if (token == generation) fail(exception.getMessage());
                });
          }
        });
  }

  private void fail(String message) {
    playbackBuffer.stop();
    cancelInterruption();
    loading = false;
    releaseMedia();
    audio.abandonAudioFocusRequest(focus);
    error = message == null ? "播放失败，请稍后重试" : message;
    showNotification();
    emit();
    error = "";
  }

  private void toggleToPlay() {
    cancelInterruption();
    if (loading) return;
    if (!prepared) {
      load();
      return;
    }
    int focusResult = audio.requestAudioFocus(focus);
    if (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
      boolean continuingFade = fadingOut;
      handler.removeCallbacks(finishFade);
      fadingOut = false;
      media.setPlaybackParams(new PlaybackParams().setSpeed(speed));
      media.setVolume(1, 1);
      fadeVolume(continuingFade ? currentEnvelope() : 0, 1);
      media.start();
      new StarPrompt(this).started(playbackSession);
      checkpoint();
      showNotification();
      emit();
      refreshBuffer();
    }
  }

  private void pause() {
    playbackBuffer.stop();
    cancelInterruption();
    if (loading) {
      generation++;
      loading = false;
      releaseMedia();
    }
    fadeToPause();
    checkpoint();
    handler.removeCallbacks(lyricTick);
    floatingLyrics.stopAnimation();
    handler.removeCallbacks(periodicCheckpoint);
    audio.abandonAudioFocusRequest(focus);
    showNotification();
    emit();
  }

  private void seek(double seconds) {
    if (prepared) media.seekTo((int) Math.max(0, Math.min(media.getDuration(), seconds * 1000)));
    checkpoint();
    updateFloatingLyrics();
    emit();
  }

  private void next(boolean previous) {
    resumePosition = 0;
    if (queue.length() == 0) return;
    if (previous && prepared && media.getCurrentPosition() > 3000) {
      seek(0);
      return;
    }
    if (mode == 2 && queue.length() > 1) {
      int planned = playbackBuffer.nextIndex(queue, index, mode);
      index =
          !previous && planned >= 0
              ? planned
              : (index + 1 + new java.util.Random().nextInt(queue.length() - 1)) % queue.length();
    } else index = (index + (previous ? -1 : 1) + queue.length()) % queue.length();
    newPlaybackSession();
    load();
    getSharedPreferences("playback", MODE_PRIVATE).edit().putInt("index", index).apply();
  }

  private void newPlaybackSession() {
    playbackSession = java.util.UUID.randomUUID().toString();
    getSharedPreferences("playback", MODE_PRIVATE)
        .edit()
        .putString("starSession", playbackSession)
        .apply();
  }

  private void focusChanged(int change) {
    if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
      if (prepared) fadeVolume(currentEnvelope(), .2f);
    } else if (change == AudioManager.AUDIOFOCUS_LOSS
        || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
      playbackBuffer.stop();
      boolean shouldResume = isPlaying() || fadingOut || resumeAfterFocus;
      resumeAfterFocus = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && shouldResume;
      fadeToPause();
      handler.removeCallbacks(lyricTick);
      checkpoint();
      floatingLyrics.stopAnimation();
      handler.removeCallbacks(periodicCheckpoint);
      showNotification();
      emit();
    } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
      if (prepared) fadeVolume(currentEnvelope(), 1);
      if (resumeAfterFocus) {
        resumeAfterFocus = false;
        toggleToPlay();
      }
    }
  }

  private float currentEnvelope() {
    try {
      return envelope == null ? 1 : envelope.getVolume();
    } catch (IllegalStateException error) {
      return 1;
    }
  }

  private void completeFade() {
    if (media != null && prepared && fadingOut) {
      media.pause();
      fadingOut = false;
      checkpoint();
      showNotification();
      emit();
    }
  }

  // 音量曲线由音频引擎执行，后台不运行逐帧音量计时器。
  private void fadeVolume(float from, float to) {
    if (media == null || !prepared) return;
    if (envelope != null) {
      envelope.close();
      envelope = null;
    }
    try {
      envelope =
          media.createVolumeShaper(
              new VolumeShaper.Configuration.Builder()
                  .setDuration(500)
                  .setInterpolatorType(VolumeShaper.Configuration.INTERPOLATOR_TYPE_CUBIC)
                  .setCurve(new float[] {0, 1}, new float[] {from, to})
                  .build());
      envelope.apply(VolumeShaper.Operation.PLAY);
    } catch (RuntimeException unsupported) {
      media.setVolume(to, to);
    }
  }

  private void fadeToPause() {
    if (media == null || !prepared || !media.isPlaying()) return;
    fadingOut = true;
    fadeVolume(currentEnvelope(), 0);
    handler.removeCallbacks(finishFade);
    handler.postDelayed(finishFade, 500);
  }

  public void cancelInterruption() {
    resumeAfterFocus = false;
  }

  private void applyBass() {
    if (!prepared) return;
    try {
      if (bass == null) bass = new BassBoost(0, media.getAudioSessionId());
      bass.setStrength((short) 350);
      bass.setEnabled(bassEnabled);
    } catch (RuntimeException ignored) {
      error = "此设备不支持低音增强";
      emit();
      error = "";
    }
  }

  private void updateMetadata() {
    JSONObject item = track();
    if (item == null) return;
    session.setMetadata(
        new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, item.optString("title"))
            .putString(MediaMetadata.METADATA_KEY_ARTIST, item.optString("artist"))
            .putString(MediaMetadata.METADATA_KEY_ALBUM, item.optString("album"))
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, item.optString("id"))
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, item.optString("title"))
            .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, item.optString("cover"))
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
            .putBitmap(MediaMetadata.METADATA_KEY_ART, artwork)
            .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, prepared ? media.getDuration() : 0)
            .build());
  }

  public void refreshFavoriteState() {
    JSONObject item = track();
    if (item == null || item.optString("hash").isEmpty()) return;
    String hash = item.optString("hash");
    int token = generation;
    favoriteWork.execute(
        () -> {
          try {
            boolean liked = api.isFavoriteTrack(item);
            handler.post(
                () -> {
                  if (token == generation
                      && !favoritePending
                      && track() != null
                      && track().optString("hash").equals(hash)) {
                    favorite = liked;
                    favoritesReady = true;
                    showNotification();
                    emit();
                  }
                });
          } catch (Exception ignored) {
          }
        });
  }

  private void toggleCloudFavorite() {
    JSONObject item = track();
    if (item == null || favoritePending) return;
    favoritePending = true;
    boolean previous = favorite;
    boolean wasReady = favoritesReady;
    String hash = item.optString("hash");
    int token = generation;
    boolean target = !favorite;
    if (wasReady) favorite = target;
    showNotification();
    emit();
    favoriteWork.execute(
        () -> {
          try {
            boolean confirmedTarget = wasReady ? target : !api.isFavoriteTrack(item);
            api.setFavorite(item, confirmedTarget);
            handler.post(
                () -> {
                  if (token != generation) return;
                  favoritePending = false;
                  if (track() != null && track().optString("hash").equals(hash)) {
                    favorite = confirmedTarget;
                    favoritesReady = true;
                  }
                  showNotification();
                  emit();
                });
          } catch (Exception failure) {
            handler.post(
                () -> {
                  if (token != generation) return;
                  favoritePending = false;
                  favorite = previous;
                  error = failure.getMessage();
                  if (observer == null)
                    android.widget.Toast.makeText(this, error, android.widget.Toast.LENGTH_SHORT)
                        .show();
                  showNotification();
                  emit();
                  error = "";
                });
          }
        });
  }

  private PendingIntent control(String action, int code) {
    return PendingIntent.getService(
        this,
        code,
        new Intent(this, PlaybackService.class).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  private PendingIntent lyricsControl() {
    if (android.provider.Settings.canDrawOverlays(this)) return control("lyrics", 8);
    return PendingIntent.getActivity(
        this,
        9,
        new Intent(this, LyricsPermissionActivity.class),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  private void showNotification() {
    if (destroyed) return;
    JSONObject item = track();
    if (item == null) return;
    updateFloatingLyrics();
    handler.removeCallbacks(periodicCheckpoint);
    if (isPlaying()) handler.postDelayed(periodicCheckpoint, 30000);
    session.setActive(true);
    long position = prepared && media != null ? media.getCurrentPosition() : 0;
    session.setPlaybackState(
        new PlaybackState.Builder()
            .addCustomAction(
                new PlaybackState.CustomAction.Builder("lyrics", "桌面歌词", R.drawable.ic_lyrics)
                    .build())
            .addCustomAction(
                new PlaybackState.CustomAction.Builder(
                        "favorite",
                        favoritePending ? "正在同步喜欢" : favorite ? "取消喜欢" : "喜欢并同步酷狗",
                        favorite ? R.drawable.ic_heart_filled : R.drawable.ic_heart)
                    .build())
            .setActions(
                PlaybackState.ACTION_PLAY
                    | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_SKIP_TO_NEXT
                    | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackState.ACTION_SEEK_TO)
            .setState(
                loading
                    ? PlaybackState.STATE_BUFFERING
                    : isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                position,
                isPlaying() ? speed : 0)
            .build());
    Notification notification =
        new Notification.Builder(this, "playback")
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setLargeIcon(artwork)
            .setShowWhen(false)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(item.optString("title"))
            .setContentText(item.optString("artist"))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    new Intent(this, MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying())
            .addAction(
                new Notification.Action.Builder(
                        R.drawable.ic_previous, "上一首", control("previous", 1))
                    .build())
            .addAction(
                new Notification.Action.Builder(
                        isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play,
                        isPlaying() ? "暂停" : "播放",
                        control("toggle", 2))
                    .build())
            .addAction(
                new Notification.Action.Builder(R.drawable.ic_next, "下一首", control("next", 3))
                    .build())
            .addAction(
                new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(
                                this, favorite ? R.drawable.ic_heart_filled : R.drawable.ic_heart)
                            .setTint(favorite ? 0xffff375f : 0xff888888),
                        favoritePending ? "同步中" : favorite ? "已喜欢" : "喜欢",
                        control("favorite", 4))
                    .build())
            .addAction(
                new Notification.Action.Builder(R.drawable.ic_lyrics, "桌面歌词", lyricsControl())
                    .build())
            .setStyle(
                new Notification.MediaStyle()
                    .setMediaSession(session.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2))
            .build();
    if (isPlaying() || loading || resumeAfterFocus) {
      startForeground(1, notification);
      foreground = true;
    } else {
      if (foreground) {
        stopForeground(STOP_FOREGROUND_DETACH);
        foreground = false;
      }
      ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1, notification);
    }
  }

  private void emit() {
    if (destroyed) return;
    if (observer != null) observer.changed(snapshot());
  }

  public void checkpoint() {
    if (prepared && media != null)
      getSharedPreferences("playback", MODE_PRIVATE)
          .edit()
          .putInt("position", media.getCurrentPosition())
          .putInt("index", index)
          .putBoolean("wasPlaying", isPlaying() || resumeAfterFocus)
          .apply();
  }

  public void loadFloatingLyrics() {
    if (destroyed) return;
    if (!((PowerManager) getSystemService(POWER_SERVICE)).isInteractive()) {
      handler.removeCallbacks(lyricTick);
      if (floatingLyrics != null) floatingLyrics.hide();
      return;
    }
    if (floatingLyrics == null || !floatingLyrics.enabled()) {
      if (floatingLyrics != null) floatingLyrics.hide();
      handler.removeCallbacks(lyricTick);
      return;
    }
    JSONObject item = track();
    if (item == null) return;
    floatingLyrics.show(item.optString("title"));
    String id = item.optString("id");
    int token = generation;
    network.execute(
        () -> {
          try {
            JSONObject document = api.lyricDocument(item);
            java.util.List<Long> times = new java.util.ArrayList<>();
            java.util.List<JSONObject> documents = new java.util.ArrayList<>();
            JSONArray lines = document.getJSONArray("lines");
            for (int index = 0; index < lines.length(); index++) {
              JSONObject line = lines.getJSONObject(index);
              documents.add(line);
              times.add(Math.round(line.optDouble("time") * 1000));
            }
            handler.post(
                () -> {
                  if (!destroyed
                      && token == generation
                      && track() != null
                      && track().optString("id").equals(id)) {
                    lyricTimes.clear();
                    lyricLines.clear();
                    for (int index = 0; index < times.size(); index++) {
                      int at = java.util.Collections.binarySearch(lyricTimes, times.get(index));
                      if (at < 0) at = -at - 1;
                      lyricTimes.add(at, times.get(index));
                      lyricLines.add(at, documents.get(index));
                    }
                    updateFloatingLyrics();
                  }
                });
          } catch (Exception ignored) {
          }
        });
  }

  public void refreshFloatingLyrics() {
    floatingLyrics.hide();
    updateFloatingLyrics();
    showNotification();
  }

  private void updateFloatingLyrics() {
    handler.removeCallbacks(lyricTick);
    if (destroyed) return;
    if (floatingLyrics == null || !floatingLyrics.enabled()) {
      if (floatingLyrics != null) floatingLyrics.hide();
      return;
    }
    if (!((PowerManager) getSystemService(POWER_SERVICE)).isInteractive()) {
      floatingLyrics.hide();
      return;
    }
    JSONObject item = track();
    if (item == null) return;
    long position = prepared && media != null ? media.getCurrentPosition() : resumePosition;
    int active = -1;
    for (int index = 0; index < lyricTimes.size(); index++) {
      if (lyricTimes.get(index) <= position) active = index;
      else break;
    }
    long next = active + 1 < lyricTimes.size() ? lyricTimes.get(active + 1) : Long.MAX_VALUE;
    if (active >= 0 && active < lyricLines.size()) {
      JSONObject line = lyricLines.get(active);
      JSONObject upcoming = active + 1 < lyricLines.size() ? lyricLines.get(active + 1) : null;
      floatingLyrics.showLine(line, upcoming, active, position, isPlaying() && !lyricBuffering, speed);
      next = Math.min(next, AirLyricsProgress.nextBoundary(line, position));
    } else floatingLyrics.show(item.optString("title"));
    if (isPlaying() && next < Long.MAX_VALUE)
      handler.postDelayed(lyricTick, Math.max(80, (long) ((next - position) / speed)));
  }

  private void releaseMedia() {
    lyricBuffering = false;
    if (floatingLyrics != null) floatingLyrics.stopAnimation();
    handler.removeCallbacks(finishFade);
    fadingOut = false;
    if (envelope != null) {
      envelope.close();
      envelope = null;
    }
    prepared = false;
    if (bass != null) {
      bass.release();
      bass = null;
    }
    if (media != null) {
      media.release();
      media = null;
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && intent.getAction() != null) {
      try {
        execute(intent.getAction(), new JSONObject());
      } catch (Exception ignored) {
      }
    }
    return START_NOT_STICKY;
  }

  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  @Override
  public void onDestroy() {
    checkpoint();
    destroyed = true;
    generation++;
    observer = null;
    handler.removeCallbacksAndMessages(null);
    network.shutdownNow();
    favoriteWork.shutdownNow();
    playbackBuffer.close();
    artworkLoader.close();
    floatingLyrics.hide();
    releaseMedia();
    audio.abandonAudioFocusRequest(focus);
    cancelInterruption();
    session.release();
    unregisterReceiver(noisy);
    unregisterReceiver(displayChanged);
    unregisterReceiver(bufferPolicyChanged);
    instance = null;
    super.onDestroy();
  }

  public void refreshBuffer() {
    if (!destroyed && playbackBuffer != null)
      playbackBuffer.update(queue, index, mode, quality, isPlaying());
  }

  @Override
  public void onConfigurationChanged(android.content.res.Configuration configuration) {
    super.onConfigurationChanged(configuration);
    if (floatingLyrics != null) {
      floatingLyrics.hide();
      updateFloatingLyrics();
    }
  }
}
