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
  public static volatile PlaybackService instance;

  public interface Observer {
    void changed(JSONObject state);
  }

  public Observer observer;
  public interface FmLevelObserver { void changed(float[] levels); }
  public FmLevelObserver fmLevelObserver;
  private FmVisualizer fmVisualizer;
  private boolean fmVisualizerWanted;

  public final class LocalBinder extends Binder {
    public PlaybackService service() {
      return PlaybackService.this;
    }
  }

  private final LocalBinder binder = new LocalBinder();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService network = Executors.newSingleThreadExecutor();
  private final ExecutorService lyricNetwork = Executors.newSingleThreadExecutor();
  private final ExecutorService favoriteWork = Executors.newSingleThreadExecutor();
  private final ExecutorService fmNetwork = new ThreadPoolExecutor(
      0, 1, 15, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
  private final PersonalFmSession fm = new PersonalFmSession();
  private MediaPlayer media;
  private NetworkMediaSource mediaSource;
  private VolumeShaper envelope;
  private boolean fadingOut;
  private final Runnable finishFade = () -> completeFade();
  private MediaSession session;
  private final ArtworkLoader artworkLoader = new ArtworkLoader();
  private android.graphics.Bitmap artwork;
  private android.graphics.drawable.Icon notificationIcon;
  private AudioManager audio;
  private AudioFocusRequest focus;
  private BassBoost bass;
  private JSONArray queue = new JSONArray();
  private int index;
  private int mode;
  private volatile int generation;
  private int resumePosition;
  private boolean seekPending;
  private boolean seekResume;
  private boolean seekRestoring;
  private int seekTarget;
  private int seekActive;
  private int seekPrevious;
  private boolean loadAutoPlay;
  private volatile long loadStartedAt, loadResolvedAt, loadSourceOpenedAt, loadPreparedAt, loadReadyAt;
  private int durationMs;
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
  private float appliedSpeed = 1f;
  private final PlaybackClock playbackClock = new PlaybackClock();
  private long sleepAt;
  private String error = "";
  private boolean favorite;
  private boolean favoritesReady;
  private boolean favoritePending;
  private String quality = "128";
  private String resolvedQuality = "128";
  private FloatingLyrics floatingLyrics;
  private StatusBarLyrics statusBarLyrics;
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
    AppHttp.initialize(this);
    instance = this;
    notificationIcon = NotificationIcons.status(this);
    api = new KugouApi(this);
    fmVisualizer = new FmVisualizer(this, levels -> handler.post(() -> {
      if (fmLevelObserver != null && fmVisualizerWanted && fm.active && isPlaying())
        fmLevelObserver.changed(levels);
    }));
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
    statusBarLyrics = new StatusBarLyrics(this);
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
      long user = api.accountUserId();
      if (saved.getBoolean("personalFm", false) && user > 0
          && user == saved.getLong("personalFmOwner", 0) && queue.length() > 0) {
        fm.restore(user, queue);
        mode = 0;
      }
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
              ? (seekPending ? seekPrevious : playbackPosition()) / 1000.0
              : resumePosition / 1000.0);
      state.put("duration", prepared ? durationMs / 1000.0 : 0);
      state.put("mode", mode).put("sleepAt", sleepAt).put("error", error);
      state
          .put("liked", favorite)
          .put("favoritesReady", favoritesReady)
          .put("favoritePending", favoritePending)
          .put("quality", resolvedQuality)
          .put("interrupted", resumeAfterFocus);
      state.put("personalFm", fm.active).put("fmStarting", fm.starting)
          .put("fmBusy", fm.pending).put("fmWaiting", fm.waiting)
          .put("fmError", fm.error);
    } catch (Exception ignored) {
    }
    return state;
  }

  private boolean isPlaying() {
    return media != null && prepared && !fadingOut && media.isPlaying();
  }

  private int playbackPosition() {
    if (!prepared || media == null) return resumePosition;
    return (int) Math.min(durationMs, playbackClock.position(media.getCurrentPosition(), SystemClock.elapsedRealtime()));
  }

  private JSONObject track() {
    return queue.optJSONObject(index);
  }

  public void execute(String action, JSONObject data) throws Exception {
    switch (action) {
      case "fmStart":
        startPersonalFm();
        break;
      case "fmVisualize":
        fmVisualizerWanted = data.optBoolean("enabled");
        updateFmVisualizer();
        break;
      case "fmRetry":
        if (!fm.active) startPersonalFm();
        else {
          requireFmAccount();
          fm.waiting = fm.exhausted;
          requestPersonalFm("play");
        }
        break;
      case "fmDislike":
        requireFmAccount();
        if (!fm.active || track() == null || !track().optString("id").equals(data.optString("id")))
          throw new Exception("歌曲已变化，请重试");
        if (fm.pending) throw new Exception("正在获取推荐，请稍候");
        fm.disliking = true;
        requestPersonalFm("garbage");
        break;
      case "fmStop":
        fm.stop();
        emit();
        break;
      case "accountChanged":
        if ((fm.active || fm.starting) && fm.owner != api.accountUserId()) {
          pause();
          fm.stop();
          queue = new JSONArray();
          index = 0;
          releaseMedia();
          showNotification();
          emit();
        }
        break;
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
        if (floatingLyrics.enabled() && lyricLines.isEmpty()) loadFloatingLyrics();
        showNotification();
        emit();
        break;
      case "quality":
        String selected = data.optString("quality", "128");
        if (!java.util.Arrays.asList("128", "320", "flac", "high").contains(selected))
          throw new Exception("音质选项无效");
        quality = selected;
        getSharedPreferences("playback", MODE_PRIVATE).edit().putString("quality", quality).apply();
        resumePosition = seekPending ? seekTarget : prepared ? playbackPosition() : resumePosition;
        load(isPlaying() || loading, true, true);
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
        fm.stop();
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
        if (fm.active) throw new Exception("随心听按推荐顺序播放，退出后可切换模式");
        mode = Math.max(0, Math.min(2, data.optInt("mode")));
        emit();
        break;
      case "jump":
        fm.cancel();
        fm.exhausted = false;
        index = Math.max(0, Math.min(data.optInt("index"), queue.length() - 1));
        newPlaybackSession();
        load();
        break;
      case "enqueue":
        if (fm.starting) fm.cancel();
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
        if (prepared) playbackPosition();
        speed = Math.max(.5f, Math.min(2f, (float) data.optDouble("speed", 1)));
        playbackClock.speed(speed, SystemClock.elapsedRealtime());
        if (isPlaying()) applyPlaybackSpeed(media);
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
        .putBoolean("personalFm", fm.active)
        .putLong("personalFmOwner", fm.owner)
        .apply();
    refreshBuffer();
  }

  private void remove(int target) {
    if (target < 0 || target >= queue.length()) return;
    fm.cancel();
    fm.exhausted = false;
    queue.remove(target);
    if (queue.length() == 0) {
      pause();
      releaseMedia();
      index = 0;
      fm.stop();
    } else if (target == index) {
      index = Math.min(index, queue.length() - 1);
      newPlaybackSession();
      load();
    } else if (target < index) index--;
    emit();
  }

  private void requireFmAccount() throws Exception {
    long user = api.accountUserId();
    if (user <= 0) throw new Exception("请先登录酷狗账号，再开启随心听");
    if ((fm.active || fm.starting) && fm.owner != user) {
      pause();
      fm.stop();
      persistQueue();
      emit();
      throw new Exception("账号已变化，请重新开启随心听");
    }
  }

  private void startPersonalFm() throws Exception {
    requireFmAccount();
    if (fm.pending) return;
    if (fm.active && queue.length() > 0) {
      if (!isPlaying()) toggleToPlay();
      return;
    }
    pause();
    fm.start(api.accountUserId());
    requestPersonalFm("login");
  }

  private void persistQueue() {
    getSharedPreferences("playback", MODE_PRIVATE).edit()
        .putString("queue", queue.toString()).putInt("index", index).putInt("mode", mode)
        .putBoolean("personalFm", fm.active).putLong("personalFmOwner", fm.owner).apply();
  }

  private void replenishPersonalFm() {
    if (fm.active && isPlaying() && queue.length() - index - 1 <= 2
        && !fm.pending && fm.error.isEmpty()) requestPersonalFm("play");
  }

  private void requestPersonalFm(String action) {
    if (destroyed || fm.pending || (!fm.active && !fm.starting)) return;
    if (api.accountUserId() != fm.owner) {
      fm.stop();
      persistQueue();
      emit();
      return;
    }
    final boolean initial = fm.starting;
    final long owner = fm.owner;
    final int ticket = fm.begin();
    final JSONObject source;
    try {
      source = initial ? null : fm.requestTrack(track());
    } catch (Exception exception) {
      fm.pending = false;
      fm.starting = false;
      fm.disliking = false;
      fm.error = "随心听歌曲信息异常，请重新开启";
      emit();
      return;
    }
    final int remaining = initial ? 0 : Math.max(0, queue.length() - index - 1);
    if (fm.waiting) {
      playbackBuffer.stop();
      releaseMedia();
      audio.abandonAudioFocusRequest(focus);
      loading = false;
      handler.removeCallbacks(periodicCheckpoint);
      handler.removeCallbacks(lyricTick);
      showNotification();
    }
    emit();
    fmNetwork.execute(() -> {
      if (!fm.accepts(ticket, owner) || api.accountUserId() != owner) return;
      try {
        PersonalFmApi client = new PersonalFmApi(api);
        JSONArray songs = client.songs(client.request(action, source, remaining, owner), action.equals("garbage"));
        handler.post(() -> {
          if (destroyed || !fm.accepts(ticket, owner)) return;
          if (api.accountUserId() != owner) {
            fm.stop();
            persistQueue();
            emit();
            return;
          }
          boolean advance = fm.waiting || fm.disliking;
          fm.pending = false;
          fm.disliking = false;
          try {
            JSONArray accepted = fm.filter(songs);
            if (initial) {
              if (accepted.length() == 0) throw new Exception("平台暂未推荐新歌曲，请稍后重试");
              fm.starting = false;
              fm.active = true;
              queue = accepted;
              index = 0;
              mode = 0;
              resumePosition = 0;
              newPlaybackSession();
              persistQueue();
              load();
            } else {
              // 仅保留最近二十首和待播放歌曲，后台续曲不累积整晚的队列。
              while (index > 20) { queue.remove(0); index--; }
              for (int position = 0; position < accepted.length(); position++)
                queue.put(accepted.getJSONObject(position));
              if (accepted.length() == 0 && (!action.equals("garbage") || index >= queue.length() - 1))
                throw new Exception("平台暂未推荐新歌曲，请稍后重试");
              fm.waiting = false;
              fm.exhausted = false;
              persistQueue();
              if (advance) next(false);
              else emit();
              refreshBuffer();
            }
          } catch (Exception exception) {
            personalFmFailed(exception);
          }
        });
      } catch (Exception exception) {
        handler.post(() -> {
          if (!destroyed && fm.accepts(ticket, owner)) personalFmFailed(exception);
        });
      }
    });
  }

  private void personalFmFailed(Exception exception) {
    fm.pending = false;
    fm.starting = false;
    fm.disliking = false;
    if (exception instanceof java.net.UnknownHostException || exception instanceof java.net.ConnectException)
      fm.error = "网络连接失败，请检查网络后重试";
    else if (exception instanceof java.net.SocketTimeoutException)
      fm.error = "随心听响应超时，请稍后重试";
    else if (exception instanceof org.json.JSONException)
      fm.error = "平台返回的随心听数据异常，请稍后重试";
    else if (exception instanceof javax.net.ssl.SSLException)
      fm.error = "随心听安全连接失败，请稍后重试";
    else fm.error = exception.getMessage() == null ? "随心听暂不可用，请稍后重试" : exception.getMessage();
    if (fm.waiting) {
      releaseMedia();
      audio.abandonAudioFocusRequest(focus);
      showNotification();
    }
    emit();
  }

  private void load() {
    load(true);
  }

  private void load(boolean autoPlay) {
    load(autoPlay, true);
  }

  private void load(boolean autoPlay, boolean preferCache) {
    load(autoPlay, preferCache, false);
  }

  private void load(boolean autoPlay, boolean preferCache, boolean exactQuality) {
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
    releaseMedia();
    loading = true;
    loadAutoPlay = autoPlay;
    loadStartedAt = SystemClock.elapsedRealtime();
    loadResolvedAt = loadSourceOpenedAt = loadPreparedAt = loadReadyAt = 0;
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
            if (cached != null && (exactQuality || uri.isEmpty())
                && !requestedQuality.equals(cached.optString("quality"))) {
              cached = null;
              if (uri.startsWith("ting-cache://")) uri = "";
            }
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
            if (token != generation) return;
            loadResolvedAt = SystemClock.elapsedRealtime();
            playbackBuffer.protectCurrent(item, resolvedQuality);
            final NetworkMediaSource source =
                address.startsWith("https://") || address.startsWith("http://")
                    ? playbackBuffer.source(item, resolvedQuality, address) : null;
            if (source != null) source.onComplete(() -> handler.post(() -> {
              if (token == generation && !destroyed) refreshBuffer();
            }));
            if (token == generation) loadSourceOpenedAt = SystemClock.elapsedRealtime();
            handler.post(
                () -> {
                  if (token != generation || destroyed) {
                    if (source != null) source.close();
                    return;
                  }
                  try {
                    mediaSource = source;
                    media = new MediaPlayer();
                    media.setAudioAttributes(
                        new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build());
                    media.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
                    media.setOnInfoListener(
                        (player, what, extra) -> {
                          if (!ownsPlayer(player, token)) return true;
                          if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                            playbackClock.pause(player.getCurrentPosition(), SystemClock.elapsedRealtime());
                            fmVisualizer.close();
                            playbackBuffer.stop();
                            lyricBuffering = true;
                            floatingLyrics.stopAnimation();
                          } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                            if (player.isPlaying() && !seekPending) playbackClock.start(SystemClock.elapsedRealtime());
                            lyricBuffering = false;
                            updateFloatingLyrics();
                            refreshBuffer();
                            updateFmVisualizer();
                          }
                          return false;
                        });
                    if (source == null) media.setDataSource(this, Uri.parse(address));
                    else media.setDataSource(source);
                    media.setOnSeekCompleteListener(player -> {
                      if (ownsPlayer(player, token)) completeSeek(player);
                    });
                    media.setOnPreparedListener(
                        player -> {
                          if (!ownsPlayer(player, token)) return;
                          prepared = true;
                          durationMs = player.getDuration();
                          playbackClock.reset(startPosition, SystemClock.elapsedRealtime());
                          playbackClock.speed(speed, SystemClock.elapsedRealtime());
                          loadPreparedAt = SystemClock.elapsedRealtime();
                          try {
                            if (startPosition > 0) beginSeek(startPosition, autoPlay, true);
                            else finishReady(player, autoPlay, true);
                          } catch (RuntimeException failed) { fail("无法恢复播放位置，请重试"); }
                        });
                    media.setOnCompletionListener(
                        player -> {
                          if (!ownsPlayer(player, token)) return;
                          if (fm.active) next(false);
                          else if (mode == 1) {
                            beginSeek(0, true, false);
                          } else if (mode == 0 && index == queue.length() - 1) {
                            pause();
                          } else next(false);
                        });
                    media.setOnErrorListener(
                        (player, what, extra) -> {
                          if (!ownsPlayer(player, token)) return true;
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
    fm.cancel();
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

  private boolean ownsPlayer(MediaPlayer player, int token) {
    return !destroyed && token == generation && player != null && media == player;
  }

  private void applyPlaybackSpeed(MediaPlayer player) {
    // 新播放器默认为正常速度，避免每次播放和恢复都同步查询系统播放参数。
    if (Float.compare(appliedSpeed, speed) == 0) return;
    player.setPlaybackParams(new PlaybackParams().allowDefaults().setSpeed(speed));
    appliedSpeed = speed;
  }

  private void toggleToPlay() {
    cancelInterruption();
    if (fm.active && fm.exhausted) {
      fm.waiting = true;
      requestPersonalFm("play");
      return;
    }
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
      applyPlaybackSpeed(media);
      media.setVolume(1, 1);
      fadeVolume(continuingFade ? currentEnvelope() : 0, 1);
      media.start();
      playbackClock.start(SystemClock.elapsedRealtime());
      new StarPrompt(this).started(playbackSession);
      checkpoint();
      showNotification();
      emit();
      refreshBuffer();
      replenishPersonalFm();
    }
  }

  private void pause() {
    fm.cancel();
    playbackBuffer.stop();
    cancelInterruption();
    if (loading) {
      if (seekPending) resumePosition = seekTarget;
      generation++;
      loading = false;
      loadAutoPlay = false;
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
    if (!prepared || media == null || !Double.isFinite(seconds)) return;
    int target = (int) Math.max(0, Math.min(durationMs, Math.round(seconds * 1000)));
    if (seekPending) {
      seekTarget = target;
      checkpoint();
      return;
    }
    beginSeek(target, isPlaying(), false);
  }

  private void beginSeek(int position, boolean resume, boolean restoring) {
    seekPrevious = restoring ? position : playbackPosition();
    playbackClock.pause(media.getCurrentPosition(), SystemClock.elapsedRealtime());
    seekTarget = seekActive = Math.max(0, Math.min(position, durationMs));
    seekResume = resume;
    seekRestoring = restoring;
    seekPending = true;
    loading = true;
    handler.removeCallbacks(finishFade);
    fadingOut = false;
    if (media.isPlaying()) media.pause();
    handler.removeCallbacks(lyricTick);
    floatingLyrics.stopAnimation();
    // 定位异步完成前不能开始播放，否则旧音频缓冲与新歌词进度会同时出现。
    media.seekTo((long) seekActive, MediaPlayer.SEEK_CLOSEST);
    checkpoint();
    showNotification();
    emit();
  }

  private void completeSeek(MediaPlayer player) {
    if (!seekPending) return;
    try {
      if (seekTarget != seekActive) {
        seekActive = seekTarget;
        player.seekTo((long) seekActive, MediaPlayer.SEEK_CLOSEST);
        return;
      }
      boolean resume = seekResume, restoring = seekRestoring;
      seekPending = false;
      resumePosition = seekTarget;
      playbackClock.reset(seekTarget, SystemClock.elapsedRealtime());
      playbackClock.speed(speed, SystemClock.elapsedRealtime());
      finishReady(player, resume, restoring);
    } catch (RuntimeException failed) { fail("音频定位失败，请重试"); }
  }

  private void finishReady(MediaPlayer player, boolean resume, boolean initial) {
    loading = false;
    if (resume && audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
      applyPlaybackSpeed(player);
      fadeVolume(0, 1);
      player.start();
      playbackClock.start(SystemClock.elapsedRealtime());
      new StarPrompt(this).started(playbackSession);
      applyBass();
    }
    if (initial) {
      loadReadyAt = SystemClock.elapsedRealtime();
      refreshFavoriteState();
      loadFloatingLyrics();
    }
    checkpoint();
    updateMetadata();
    updateFloatingLyrics();
    showNotification();
    emit();
    refreshBuffer();
    replenishPersonalFm();
  }

  private void next(boolean previous) {
    if (fm.disliking) fm.cancel();
    resumePosition = 0;
    if (queue.length() == 0) return;
    if (previous && prepared && playbackPosition() > 3000) {
      seek(0);
      return;
    }
    if (fm.active && !previous) {
      if (index >= queue.length() - 1) {
        fm.waiting = true;
        fm.exhausted = true;
        playbackBuffer.stop();
        generation++;
        releaseMedia();
        loading = false;
        audio.abandonAudioFocusRequest(focus);
        handler.removeCallbacks(periodicCheckpoint);
        handler.removeCallbacks(lyricTick);
        if (fm.error.isEmpty()) requestPersonalFm("play");
        showNotification();
        emit();
        return;
      }
      fm.exhausted = false;
      fm.waiting = false;
      index++;
      newPlaybackSession();
      load();
      persistQueue();
      return;
    }
    if (fm.active && previous && index == 0) { seek(0); return; }
    fm.exhausted = false;
    fm.waiting = false;
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
    resumePosition = 0;
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
      fm.cancel();
      if (loading) {
        generation++;
        loading = false;
        releaseMedia();
      }
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
      playbackClock.pause(media.getCurrentPosition(), SystemClock.elapsedRealtime());
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
            .putLong(MediaMetadata.METADATA_KEY_DURATION, prepared ? durationMs : 0)
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
    long position = prepared && media != null ? playbackPosition() : resumePosition;
    boolean lyricsEnabled = floatingLyrics.enabled();
    boolean lyricsLocked = lyricsEnabled && floatingLyrics.locked();
    int lyricsIcon =
        lyricsLocked
            ? R.drawable.ic_lyrics_unlock
            : lyricsEnabled ? R.drawable.ic_lyrics_active : R.drawable.ic_lyrics;
    String lyricsLabel = lyricsLocked ? "解锁桌面歌词" : lyricsEnabled ? "关闭桌面歌词" : "开启桌面歌词";
    session.setPlaybackState(
        new PlaybackState.Builder()
            .addCustomAction(
                new PlaybackState.CustomAction.Builder(
                        "favorite",
                        favoritePending ? "正在同步喜欢" : favorite ? "取消喜欢" : "喜欢并同步酷狗",
                        favorite ? R.drawable.ic_heart_filled : R.drawable.ic_heart)
                    .build())
            .addCustomAction(
                new PlaybackState.CustomAction.Builder("lyrics", lyricsLabel, lyricsIcon).build())
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
            .setSmallIcon(notificationIcon)
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
                new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this, lyricsIcon)
                            .setTint(lyricsEnabled ? 0xff35baff : 0xff888888),
                        lyricsLabel,
                        lyricsControl())
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
    updateFmVisualizer();
    if (observer != null) observer.changed(snapshot());
  }

  public void checkpoint() {
    if (track() != null) {
      int position = seekPending ? seekTarget : prepared && media != null ? playbackPosition() : resumePosition;
      resumePosition = position;
      getSharedPreferences("playback", MODE_PRIVATE)
          .edit()
          .putString("queue", queue.toString())
          .putInt("position", position)
          .putInt("index", index)
          .putBoolean("wasPlaying", seekPending ? seekResume : loading ? loadAutoPlay : isPlaying() || resumeAfterFocus)
          .apply();
    }
  }

  public void loadFloatingLyrics() {
    if (destroyed) return;
    if (!((PowerManager) getSystemService(POWER_SERVICE)).isInteractive()) {
      handler.removeCallbacks(lyricTick);
      if (floatingLyrics != null) floatingLyrics.hide();
      if (statusBarLyrics != null) statusBarLyrics.hide();
      return;
    }
    if (floatingLyrics == null || (!floatingLyrics.enabled() && !statusBarLyrics.enabled())) {
      if (floatingLyrics != null) floatingLyrics.hide();
      if (statusBarLyrics != null) statusBarLyrics.hide();
      handler.removeCallbacks(lyricTick);
      return;
    }
    JSONObject item = track();
    if (item == null) return;
    floatingLyrics.show(item.optString("title"));
    String id = item.optString("id");
    int token = generation;
    lyricNetwork.execute(
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
    if (!floatingLyrics.applyLockChange()) floatingLyrics.hide();
    updateFloatingLyrics();
    showNotification();
  }

  public void configureFloatingLyrics(JSONObject updates) {
    if (!floatingLyrics.applySettings(updates)) floatingLyrics.hide();
    updateFloatingLyrics();
    showNotification();
  }

  public void configureStatusBarLyrics() {
    configureStatusBarLyrics(true);
  }

  public void configureStatusBarLyrics(boolean requestLyrics) {
    if (!statusBarLyrics.enabled()) {
      statusBarLyrics.hide();
      updateFloatingLyrics();
      return;
    }
    boolean preview = statusBarLyrics.isPreviewing();
    statusBarLyrics.refresh();
    if (preview && statusBarLyrics.enabled()) return;
    if (requestLyrics
        && (floatingLyrics.enabled() || statusBarLyrics.enabled())
        && lyricLines.isEmpty()) loadFloatingLyrics();
    else updateFloatingLyrics();
  }

  public void previewStatusBarLyrics() {
    statusBarLyrics.preview();
  }

  private void updateFloatingLyrics() {
    handler.removeCallbacks(lyricTick);
    if (destroyed) return;
    if (floatingLyrics == null || (!floatingLyrics.enabled() && !statusBarLyrics.enabled())) {
      if (floatingLyrics != null) floatingLyrics.hide();
      if (statusBarLyrics != null) statusBarLyrics.hide();
      return;
    }
    if (!((PowerManager) getSystemService(POWER_SERVICE)).isInteractive()) {
      floatingLyrics.hide();
      statusBarLyrics.hide();
      return;
    }
    JSONObject item = track();
    if (item == null) {
      floatingLyrics.hide();
      if (!statusBarLyrics.isPreviewing()) statusBarLyrics.hide();
      return;
    }
    long position = prepared && media != null ? playbackPosition() : resumePosition;
    int active = -1;
    for (int index = 0; index < lyricTimes.size(); index++) {
      if (lyricTimes.get(index) <= position) active = index;
      else break;
    }
    long next = active + 1 < lyricTimes.size() ? lyricTimes.get(active + 1) : Long.MAX_VALUE;
    if (active >= 0 && active < lyricLines.size()) {
      JSONObject line = lyricLines.get(active);
      JSONObject upcoming = active + 1 < lyricLines.size() ? lyricLines.get(active + 1) : null;
      floatingLyrics.showLine(
          line, upcoming, active, position, isPlaying() && !lyricBuffering, speed);
      statusBarLyrics.show(
          line, upcoming, position, isPlaying() && !lyricBuffering, speed, item.optString("title"));
      next = Math.min(next, AirLyricsProgress.nextBoundary(line, position));
    } else {
      floatingLyrics.show(item.optString("title"));
      statusBarLyrics.show(
          null, null, position, isPlaying() && !lyricBuffering, speed, item.optString("title"));
    }
    if (isPlaying() && next < Long.MAX_VALUE)
      handler.postDelayed(lyricTick, Math.max(80, (long) ((next - position) / speed)));
  }

  private void releaseMedia() {
    seekPending = false;
    // 先撤销所属关系，释放期间到达的旧播放器回调不能再操作当前状态。
    MediaPlayer retiring = media;
    NetworkMediaSource source = mediaSource;
    media = null;
    mediaSource = null;
    if (fmVisualizer != null) fmVisualizer.close();
    if (statusBarLyrics != null) statusBarLyrics.hide();
    lyricBuffering = false;
    if (floatingLyrics != null) floatingLyrics.stopAnimation();
    handler.removeCallbacks(finishFade);
    fadingOut = false;
    if (envelope != null) {
      try { envelope.close(); } catch (IllegalStateException ignored) {}
      envelope = null;
    }
    prepared = false;
    durationMs = 0;
    appliedSpeed = 1f;
    if (bass != null) {
      bass.release();
      bass = null;
    }
    // 音量和音效控制仍依赖活跃播放器，先释放它们，再中止读取并释放播放器。
    if (source != null) source.close();
    if (retiring != null) retiring.release();
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
    fm.stop();
    generation++;
    observer = null;
    fmLevelObserver = null;
    fmVisualizer.close();
    handler.removeCallbacksAndMessages(null);
    network.shutdownNow();
    lyricNetwork.shutdownNow();
    favoriteWork.shutdownNow();
    fmNetwork.shutdownNow();
    playbackBuffer.close();
    artworkLoader.close();
    floatingLyrics.hide();
    statusBarLyrics.hide();
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
      // 当前音频仍需联网时，后续预载让出带宽；缓存完整后由完成事件恢复。
      playbackBuffer.update(queue, index, mode, quality,
          isPlaying() && !lyricBuffering && (mediaSource == null || mediaSource.fullyCached()));
  }

  private void updateFmVisualizer() {
    if (fmVisualizer == null) return;
    if (fmVisualizerWanted && fmLevelObserver != null && fm.active && isPlaying() && !lyricBuffering)
      fmVisualizer.start(media.getAudioSessionId());
    else fmVisualizer.close();
  }

  public void detachFmVisualizer() {
    fmVisualizerWanted = false;
    fmLevelObserver = null;
    if (fmVisualizer != null) fmVisualizer.close();
  }

  @Override
  public void onConfigurationChanged(android.content.res.Configuration configuration) {
    super.onConfigurationChanged(configuration);
    if (floatingLyrics != null) {
      floatingLyrics.onDisplayChanged();
      statusBarLyrics.onDisplayChanged();
      updateFloatingLyrics();
    }
  }
}
