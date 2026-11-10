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

  static final long MAX_FILE_BYTES = 48L * 1024 * 1024;
  static final long MAX_TOTAL_BYTES = MAX_FILE_BYTES * 3;
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
  private volatile HttpURLConnection connection;
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
    if (!track.optString("uri").isEmpty()
        || !hash.matches("[a-f0-9]{32}")
        || !Arrays.asList(QualityCatalog.KEYS).contains(quality)) return "";
    return "u" + Long.toHexString(owner.getAsLong()) + "-" + hash + "-" + quality;
  }

  JSONObject playback(JSONObject track, String quality) {
    String key = key(track, quality);
    if (key.isEmpty()) return null;
    synchronized (this) {
      try {
        File audio = new File(root, key + ".audio"), meta = new File(root, key + ".json");
        if (!meta.isFile() || !audio.isFile()) return null;
        JSONObject record =
            new JSONObject(new String(Files.readAllBytes(meta.toPath()), StandardCharsets.UTF_8));
        if (audio.length() == 0 || audio.length() != record.optLong("bytes")) return null;
        // 自动预缓存短期使用，避免长期沿用过去的账号授权。
        if (System.currentTimeMillis() - record.optLong("createdAt") > 24L * 60 * 60 * 1000)
          return null;
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

  private synchronized void trim(Set<String> keep) throws IOException {
    if (!root.isDirectory() && !root.mkdirs()) throw new IOException("预缓存目录不可用");
    File[] files = root.listFiles();
    if (files == null) return;
    for (File file : files) {
      String name = file.getName();
      int suffix = name.lastIndexOf('.');
      if (suffix < 0 || !keep.contains(name.substring(0, suffix)) || name.endsWith(".part"))
        file.delete();
    }
  }

  void fetch(JSONObject item, String quality, int token) throws Exception {
    String key = key(item, quality);
    if (key.isEmpty() || token != generation) return;
    JSONObject resolved = resolver.resolve(item, quality);
    if (token != generation) return;
    URL url = new URL(resolved.getString("url"));
    boolean officialLegacyAudio =
        url.getProtocol().equals("http")
            && url.getHost().equalsIgnoreCase("fs.youthandroid2.kugou.com");
    if (!url.getProtocol().equals("https") && !officialLegacyAudio)
      throw new IOException("预缓存地址无效");
    File partial = new File(root, key + ".part");
    HttpURLConnection request = null;
    try {
      long available;
      synchronized (this) {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("预缓存目录不可用");
        new File(root, key + ".audio").delete();
        new File(root, key + ".json").delete();
        long used = 0;
        File[] files = root.listFiles();
        if (files != null)
          for (File file : files) if (file.getName().endsWith(".audio")) used += file.length();
        available = Math.min(MAX_FILE_BYTES, MAX_TOTAL_BYTES - used);
        if (available <= 0) throw new IOException("预缓存空间已满");
      }
      request = (HttpURLConnection) url.openConnection();
      request.setConnectTimeout(8000);
      request.setReadTimeout(8000);
      request.setInstanceFollowRedirects(false);
      connection = request;
      if (token != generation) return;
      if (request.getResponseCode() != 200) throw new IOException("预缓存请求未完成");
      long total = request.getContentLengthLong();
      if (total > available) throw new IOException("预缓存超过空间上限");
      long bytes = 0;
      try (InputStream input = request.getInputStream();
          OutputStream output = new FileOutputStream(partial)) {
        byte[] buffer = new byte[32768];
        int length;
        while ((length = input.read(buffer)) != -1) {
          if (token != generation || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException();
          bytes += length;
          if (bytes > available) throw new IOException("预缓存超过空间上限");
          output.write(buffer, 0, length);
        }
      }
      if (bytes == 0 || (total >= 0 && bytes != total)) throw new IOException("预缓存音频不完整");
      MediaMetadataRetriever metadata = new MediaMetadataRetriever();
      try {
        metadata.setDataSource(partial.getAbsolutePath());
        String duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        if (duration == null || Long.parseLong(duration) <= 0) throw new IOException("预缓存音频不可用");
      } finally {
        metadata.release();
      }
      synchronized (this) {
        if (token != generation || !key.equals(key(item, quality))) return;
        File audio = new File(root, key + ".audio"), meta = new File(root, key + ".json");
        if (!partial.renameTo(audio)) throw new IOException("预缓存保存失败");
        try {
          JSONObject record =
              new JSONObject()
                  .put("bytes", bytes)
                  .put("quality", resolved.getString("quality"))
                  .put("createdAt", System.currentTimeMillis());
          Files.write(meta.toPath(), record.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
          audio.delete();
          meta.delete();
          throw error;
        }
      }
    } finally {
      if (request != null) request.disconnect();
      if (connection == request) connection = null;
      partial.delete();
    }
  }

  void stop() {
    generation++;
    signature = "";
    worker.getQueue().clear();
    HttpURLConnection active = connection;
    if (active != null) active.disconnect();
  }

  @Override
  public void close() {
    stop();
    worker.shutdownNow();
  }
}
