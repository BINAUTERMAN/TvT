package cn.ting.music.tests;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.URI;
import java.util.*;
import org.json.*;

/** 复用设备上刚播放的真实音频，检查缓存与实际音质，不导出音频或地址。 */
public final class PlaybackCacheChecks extends Instrumentation {
  private int checks;
  private Object service;
  private Method snapshot;

  private void check(boolean passed, String name) throws Exception {
    Bundle value = new Bundle();
    value.putString("stream", name + ": " + (passed ? "PASS" : "FAIL") + "\n");
    sendStatus(0, value);
    if (!passed) throw new Exception(name);
    checks++;
  }

  private Object field(Object target, String name) throws Exception {
    Field value = target.getClass().getDeclaredField(name);
    value.setAccessible(true);
    return value.get(target);
  }

  private Method method(Class<?> type, String name, Class<?>... args) throws Exception {
    Method value = type.getDeclaredMethod(name, args);
    value.setAccessible(true);
    return value;
  }

  private JSONObject state() {
    JSONObject[] value = new JSONObject[1];
    runOnMainSync(
        () -> {
          try {
            value[0] = (JSONObject) snapshot.invoke(service);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    return value[0];
  }

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    int code = Activity.RESULT_OK;
    Object reopened = null;
    try {
      Context target = getTargetContext();
      DeviceLaunch.start(this, target);
      Class<?> type = target.getClassLoader().loadClass("cn.ting.music.PlaybackService");
      for (int i = 0; i < 100; i++) {
        service = type.getField("instance").get(null);
        if (service != null) break;
        SystemClock.sleep(100);
      }
      if (service == null) throw new Exception("Playback service unavailable");
      snapshot = type.getMethod("snapshot");
      for (int i = 0; i < 100 && state().optBoolean("loading"); i++) SystemClock.sleep(100);
      Object buffer = field(service, "playbackBuffer");
      Class<?> bufferType = buffer.getClass();
      SharedPreferences saved = target.getSharedPreferences("playback", 0);
      JSONObject track =
          new JSONArray(saved.getString("queue", "[]")).getJSONObject(saved.getInt("index", 0));
      String quality = saved.getString("quality", "128");
      Method playback = method(bufferType, "playback", JSONObject.class, String.class);
      JSONObject cached = (JSONObject) playback.invoke(buffer, track, quality);
      check(
          cached != null && quality.equals(cached.optString("quality")),
          "Real selected-quality stream cache is complete");
      check(
          cached.getString("uri").endsWith(".stream"),
          "Online playback persisted the complete disk cache");
      File local = new File(new URI(cached.getString("uri")));
      long length = local.length();
      reopened =
          method(bufferType, "source", JSONObject.class, String.class, String.class)
              .invoke(buffer, track, quality, "https://invalid.invalid/never-requested");
      Method read =
          method(reopened.getClass(), "readAt", long.class, byte[].class, int.class, int.class);
      byte[] actual = new byte[1024], expected = new byte[1024];
      try (RandomAccessFile file = new RandomAccessFile(local, "r")) {
        for (long offset : new long[] {0, length / 2, length - 1024}) {
          int count = (Integer) read.invoke(reopened, offset, actual, 0, actual.length);
          file.seek(offset);
          file.readFully(expected, 0, count);
          check(
              count > 0
                  && Arrays.equals(Arrays.copyOf(actual, count), Arrays.copyOf(expected, count)),
              "Reopened cache returns exact audio bytes");
        }
      }
      check(
          (Long) field(reopened, "networkBytes") == 0 && (Long) field(reopened, "openCount") == 0,
          "Recreated streaming source reuses disk with zero HTTP requests");
      for (String other : new String[] {"128", "320", "flac", "high"}) {
        JSONObject candidate = (JSONObject) playback.invoke(buffer, track, other);
        check(
            candidate == null || other.equals(candidate.optString("quality")),
            "Cache never substitutes another quality for " + other);
      }
      double before = state().optDouble("position");
      long started = SystemClock.elapsedRealtime();
      runOnMainSync(
          () -> {
            try {
              type.getMethod("execute", String.class, JSONObject.class)
                  .invoke(service, "quality", new JSONObject().put("quality", quality));
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      for (int i = 0; i < 100 && state().optBoolean("loading"); i++) SystemClock.sleep(100);
      check(
          !(Boolean) field(service, "loading") && field(service, "mediaSource") == null,
          "Manual quality switch reuses complete local cache");
      check(
          quality.equals(field(service, "resolvedQuality")),
          "Manual switch keeps the selected actual quality");
      check(
          Math.abs(state().optDouble("position") - before) < 3,
          "Cached quality switch preserves playback position");
      long complete = 0;
      int count = 0;
      File root = (File) field(buffer, "root");
      File[] files = root.listFiles();
      if (files != null)
        for (File file : files)
          if (file.getName().endsWith(".stream")) {
            count++;
            complete += file.length();
          }
      result.putString(
          "stream",
          "PLAYBACK_CACHE_PASS checks="
              + checks
              + " switchReadyMs="
              + (SystemClock.elapsedRealtime() - started)
              + " streamFiles="
              + count
              + " reservedStreamBytes="
              + complete
              + "\n");
    } catch (Throwable error) {
      code = Activity.RESULT_CANCELED;
      result.putString("stream", "PLAYBACK_CACHE_ERROR " + error.getClass().getSimpleName() + "\n");
    } finally {
      if (reopened != null)
        try {
          method(reopened.getClass(), "close").invoke(reopened);
        } catch (Exception ignored) {
        }
    }
    finish(code, result);
  }
}
