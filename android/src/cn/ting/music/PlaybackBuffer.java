package cn.ting.music;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.*;
import android.os.PowerManager;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** 播放服务按队列事件预取，不启动常驻任务；自动缓存不混入手动离线资料库。 */
final class PlaybackBuffer implements AutoCloseable {
  interface Resolver {
    JSONObject resolve(JSONObject track, String quality) throws Exception;
  }

  static final long MAX_FILE_BYTES = AudioBlockCache.MAX_FILE_BYTES;
  static final long MAX_TOTAL_BYTES = AudioBlockCache.MAX_TOTAL_BYTES;
  private final Context context;
  private final File root;
  private final Resolver resolver;
  private final java.util.function.LongSupplier owner;
  private final ThreadPoolExecutor worker =
      new ThreadPoolExecutor(
          1,
          1,
          15,
          TimeUnit.SECONDS,
          new LinkedBlockingQueue<>(1),
          runnable -> {
            Thread thread = new Thread(runnable, "audio-ahead");
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
          },
          new ThreadPoolExecutor.DiscardOldestPolicy());
  private volatile int generation;
  private volatile NetworkMediaSource fetchingSource;
  private volatile String protectedKey = "";
  private String signature = "";
  private String queueSignature = "";
  private int[] upcoming = new int[0];

  PlaybackBuffer(Context context, KugouApi api) {
    this(
        context,
        (track, quality) ->
            api.bestSongUrl(new JSONObject(track.toString()).put("quality", quality)),
        api::accountUserId);
  }

  PlaybackBuffer(Context context, Resolver resolver) {
    this(context, resolver, () -> 0L);
  }

  private PlaybackBuffer(
      Context context, Resolver resolver, java.util.function.LongSupplier owner) {
    this.context = context.getApplicationContext();
    this.resolver = resolver;
    this.owner = owner;
    worker.allowCoreThreadTimeOut(true);
    root = new File(context.getCacheDir(), "audio-ahead");
  }

  static int[] plan(int length, int index, int mode, int count, Random random) {
    List<Integer> values = new ArrayList<>();
    if (length <= 1 || mode == 1) return new int[0];
    for (int offset = 1; offset < length; offset++) {
      int candidate = (index + offset) % length;
      if (mode == 0 && candidate <= index) break;
      values.add(candidate);
    }
    if (mode == 2) Collections.shuffle(values, random);
    int[] result = new int[Math.min(count, values.size())];
    for (int position = 0; position < result.length; position++)
      result[position] = values.get(position);
    return result;
  }

  private String key(JSONObject track, String quality) {
    String hash = track.optString("hash").toLowerCase(Locale.ROOT);
    if ((!track.optString("uri").isEmpty() && !track.optString("uri").startsWith("ting-cache://"))
        || !hash.matches("[a-f0-9]{32}")
        || !Arrays.asList(QualityCatalog.KEYS).contains(quality)) return "";
    return "u" + Long.toHexString(owner.getAsLong()) + "-" + hash + "-" + quality;
  }

  JSONObject playback(JSONObject track, String quality) {
    String key = key(track, quality);
    if (key.isEmpty()) return null;
    synchronized (this) {
      try (AudioBlockCache streamed = new AudioBlockCache(root, key, -1)) {
        if (streamed.complete())
          return new JSONObject()
              .put("uri", Uri.fromFile(streamed.file()).toString())
              .put("quality", quality);
      } catch (IOException | JSONException ignored) {
      }
      try {
        File audio = new File(root, key + ".audio"), meta = new File(root, key + ".json");
        if (!meta.isFile() || !audio.isFile()) return null;
        JSONObject record =
            new JSONObject(new String(Files.readAllBytes(meta.toPath()), StandardCharsets.UTF_8));
        if (audio.length() == 0
            || audio.length() != record.optLong("bytes")
            || !quality.equals(record.optString("quality"))) return null;
        // 自动预缓存短期使用，避免长期沿用过去的账号授权。
        if (System.currentTimeMillis() - record.optLong("createdAt") > 24L * 60 * 60 * 1000)
          return null;
        audio.setLastModified(System.currentTimeMillis());
        return new JSONObject()
            .put("uri", Uri.fromFile(audio).toString())
            .put("quality", record.getString("quality"));
      } catch (Exception ignored) {
        return null;
      }
    }
  }

  private int allowance() {
    if (!context
        .getSharedPreferences("preferences", Context.MODE_PRIVATE)
        .getBoolean("preloadAudio", true)) return 0;
    PowerManager power = context.getSystemService(PowerManager.class);
    ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
    if (power.isPowerSaveMode()
        || connectivity.getRestrictBackgroundStatus()
            == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) return 0;
    NetworkCapabilities caps = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
    if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return 0;
    return connectivity.isActiveNetworkMetered() ? 1 : 2;
  }

  int nextIndex(JSONArray queue, int current, int mode) {
    String expected = queue.toString() + ":" + current + ":" + mode;
    return expected.equals(queueSignature) && upcoming.length > 0 ? upcoming[0] : -1;
  }

  void update(JSONArray queue, int index, int mode, String quality, boolean playing) {
    if (!playing) {
      stop();
      return;
    }
    String identity = queue.toString() + ":" + index + ":" + mode;
    if (!identity.equals(queueSignature)) {
      queueSignature = identity;
      upcoming = plan(queue.length(), index, mode, 2, new Random());
    }
    int count = Math.min(allowance(), mode == 2 ? 1 : 2);
    String nextSignature = identity + ":" + quality + ":" + count + ":" + owner.getAsLong();
    if (signature.equals(nextSignature)) return;
    stop();
    signature = nextSignature;
    int token = generation;
    List<JSONObject> candidates = new ArrayList<>();
    Set<String> keep = new HashSet<>();
    JSONObject current = queue.optJSONObject(index);
    if (current != null) keep.add(key(current, quality));
    for (int position = 0; position < Math.min(count, upcoming.length); position++) {
      JSONObject item = queue.optJSONObject(upcoming[position]);
      if (item != null && !key(item, quality).isEmpty() && keep.add(key(item, quality)))
        candidates.add(item);
    }
    worker.execute(
        () -> {
          try {
            if (token != generation) return;
            trim(keep);
            for (JSONObject item : candidates) {
              if (token != generation || allowance() == 0) break;
              if (playback(item, quality) != null || new MusicCache(context).playback(item) != null)
                continue;
              try {
                fetch(item, quality, token);
              } catch (Exception ignored) {
              }
            }
          } catch (Exception ignored) {
          }
        });
  }

  void protectCurrent(JSONObject item, String quality) {
    protectedKey = key(item, quality);
  }

  NetworkMediaSource source(JSONObject item, String quality, String address) throws IOException {
    return source(item, quality, address, MAX_TOTAL_BYTES);
  }

  private NetworkMediaSource source(JSONObject item, String quality, String address, long fileLimit)
      throws IOException {
    String identity = key(item, quality);
    return new NetworkMediaSource(
        address,
        identity.isEmpty()
            ? null
            : length -> length > fileLimit ? null : streamCache(identity, length));
  }

  private synchronized AudioBlockCache streamCache(String identity, long length)
      throws IOException {
    if (length <= 0) {
      try {
        return new AudioBlockCache(root, identity, -1);
      } catch (IOException missing) {
        return null;
      }
    }
    if (length > MAX_TOTAL_BYTES) return null;
    long previous = new File(root, identity + ".stream").length();
    makeRoom(Math.max(0, length - previous), Collections.singleton(identity));
    return new AudioBlockCache(root, identity, length);
  }

  private synchronized void trim(Set<String> keep) throws IOException {
    makeRoom(0, keep);
  }

  private void makeRoom(long incoming, Set<String> keep) throws IOException {
    if (!root.isDirectory() && !root.mkdirs()) throw new IOException("预缓存目录不可用");
    File[] files = root.listFiles();
    if (files == null) return;
    long used = 0;
    List<File> audioFiles = new ArrayList<>();
    for (File file : files) {
      String name = file.getName();
      int suffix = name.lastIndexOf('.');
      if (suffix < 0) continue;
      String identity = name.substring(0, suffix);
      if (name.endsWith(".part")) {
        file.delete();
        continue;
      }
      if (!name.endsWith(".audio") && !name.endsWith(".stream")) continue;
      if (!identity.equals(protectedKey)
          && !keep.contains(identity)
          && System.currentTimeMillis() - file.lastModified() > AudioBlockCache.MAX_AGE_MS) {
        remove(identity);
        if (!file.exists()) continue;
      }
      used += file.length();
      audioFiles.add(file);
    }
    audioFiles.sort(Comparator.comparingLong(File::lastModified));
    for (File file : audioFiles) {
      if (used + incoming <= MAX_TOTAL_BYTES) break;
      String name = file.getName(), identity = name.substring(0, name.lastIndexOf('.'));
      if (identity.equals(protectedKey) || keep.contains(identity)) continue;
      long size = groupBytes(identity);
      remove(identity);
      used -= size - groupBytes(identity);
    }
    if (used + incoming > MAX_TOTAL_BYTES) throw new IOException("预缓存空间已满");
  }

  private void remove(String identity) {
    for (String suffix : new String[] {".audio", ".json", ".stream", ".ranges", ".ranges.tmp"})
      new File(root, identity + suffix).delete();
  }

  private long groupBytes(String identity) {
    return new File(root, identity + ".audio").length()
        + new File(root, identity + ".stream").length();
  }

  void fetch(JSONObject item, String quality, int token) throws Exception {
    String key = key(item, quality);
    if (key.isEmpty() || token != generation) return;
    JSONObject resolved = resolver.resolve(item, quality);
    if (token != generation) return;
    String actual = resolved.getString("quality");
    NetworkMediaSource source = source(item, actual, resolved.getString("url"), MAX_FILE_BYTES);
    fetchingSource = source;
    try {
      if (token != generation) return;
      long total = source.getSize();
      if (total <= 0 || total > MAX_FILE_BYTES || !source.cacheEnabled())
        throw new IOException("预缓存超过空间上限");
      byte[] buffer = new byte[AudioBlockCache.BLOCK_BYTES];
      long offset = 0;
      while (offset < total) {
        if (token != generation || Thread.currentThread().isInterrupted())
          throw new InterruptedIOException();
        int count = source.readAt(offset, buffer, 0, buffer.length);
        if (count <= 0) throw new IOException("预缓存音频不完整");
        offset += count;
      }
      JSONObject local = playback(item, actual);
      if (local == null) throw new IOException("预缓存音频不完整");
      MediaMetadataRetriever metadata = new MediaMetadataRetriever();
      boolean valid = false;
      try {
        metadata.setDataSource(new File(new URI(local.getString("uri"))).getAbsolutePath());
        String duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        if (duration == null || Long.parseLong(duration) <= 0) throw new IOException("预缓存音频不可用");
        valid = true;
      } finally {
        metadata.release();
        if (!valid)
          synchronized (this) {
            remove(key(item, actual));
          }
      }
    } finally {
      source.close();
      if (fetchingSource == source) fetchingSource = null;
    }
  }

  void stop() {
    generation++;
    signature = "";
    worker.getQueue().clear();
    NetworkMediaSource active = fetchingSource;
    if (active != null) active.close();
  }

  @Override
  public void close() {
    stop();
    worker.shutdownNow();
  }
}
