package cn.ting.music.tests;

import android.app.*;
import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import org.json.*;

/** 真实授权音频仅存手机缓存目录；取消边界使用可控解析器，不代替线上播放验收。 */
public final class BufferChecks extends Instrumentation {
  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  private void check(String name, boolean passed) throws Exception {
    Bundle status = new Bundle();
    status.putString("stream", name + ": " + (passed ? "PASS" : "FAIL") + "\n");
    sendStatus(0, status);
    if (!passed) throw new Exception(name);
  }

  private Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
    Method method = type.getDeclaredMethod(name, parameters);
    method.setAccessible(true);
    return method;
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    int code = Activity.RESULT_OK;
    Object cache = null, controlled = null;
    Class<?> bufferType = null;
    JSONObject track = null;
    boolean created = false;
    Thread pending = null;
    CountDownLatch release = new CountDownLatch(1);
    try {
      Context context = getTargetContext();
      ClassLoader loader = context.getClassLoader();
      Class<?> apiType = loader.loadClass("cn.ting.music.KugouApi");
      bufferType = loader.loadClass("cn.ting.music.PlaybackBuffer");
      Object api = apiType.getConstructor(Context.class).newInstance(context);
      Constructor<?> constructor = bufferType.getDeclaredConstructor(Context.class, apiType);
      constructor.setAccessible(true);
      cache = constructor.newInstance(context, api);
      JSONObject response =
          (JSONObject)
              apiType.getMethod("search", String.class, int.class).invoke(api, "纯音乐 铃声", 1);
      JSONArray songs = response.getJSONObject("data").getJSONArray("lists");
      for (int index = 0; index < songs.length(); index++) {
        JSONObject song = songs.getJSONObject(index);
        if (song.optInt("Duration") >= 3
            && song.optInt("Duration") <= 90
            && song.optLong("FileSize") < 2000000
            && song.optString("FileHash").matches("[a-fA-F0-9]{32}")) {
          track =
              new JSONObject()
                  .put("hash", song.getString("FileHash"))
                  .put("albumId", song.optString("AlbumID"))
                  .put("audioId", song.optString("MixSongID"));
          break;
        }
      }
      check("Small authorized audio sample available", track != null);
      Method playback = method(bufferType, "playback", JSONObject.class, String.class);
      created = playback.invoke(cache, track, "128") == null;
      if (created)
        method(bufferType, "fetch", JSONObject.class, String.class, int.class)
            .invoke(cache, track, "128", 0);
      JSONObject local = (JSONObject) playback.invoke(cache, track, "128");
      check(
          "Real ahead-cache download verified and resolved locally",
          local != null && local.getString("uri").startsWith("file://"));
      MediaPlayer player = new MediaPlayer();
      try {
        player.setDataSource(context, Uri.parse(local.getString("uri")));
        player.prepare();
        check("Native MediaPlayer prepares ahead-cache audio from file", player.getDuration() > 0);
      } finally {
        player.release();
      }
      check(
          "Requested quality partitions automatic cache",
          playback.invoke(cache, track, "high") == null);
      Object reopened = constructor.newInstance(context, api);
      try {
        check(
            "Completed cache survives repository recreation",
            playback.invoke(reopened, track, "128") != null);
      } finally {
        method(bufferType, "close").invoke(reopened);
      }

      Class<?> resolver = loader.loadClass("cn.ting.music.PlaybackBuffer$Resolver");
      CountDownLatch entered = new CountDownLatch(1);
      Object slow =
          Proxy.newProxyInstance(
              loader,
              new Class<?>[] {resolver},
              (proxy, action, arguments) -> {
                entered.countDown();
                release.await(8, TimeUnit.SECONDS);
                return new JSONObject()
                    .put("url", "https://invalid.invalid/unused")
                    .put("quality", "128");
              });
      Constructor<?> controlledConstructor =
          bufferType.getDeclaredConstructor(Context.class, resolver);
      controlledConstructor.setAccessible(true);
      controlled = controlledConstructor.newInstance(context, slow);
      Object target = controlled;
      JSONObject sample = track;
      Method fetch = method(bufferType, "fetch", JSONObject.class, String.class, int.class);
      pending =
          new Thread(
              () -> {
                try {
                  fetch.invoke(target, sample, "128", 0);
                } catch (Exception ignored) {
                }
              });
      pending.start();
      check("Controlled resolver reached before cancellation", entered.await(8, TimeUnit.SECONDS));
      method(bufferType, "stop").invoke(controlled);
      release.countDown();
      pending.join(3000);
      check(
          "Cancelled generation does not publish a playable cache",
          !pending.isAlive() && playback.invoke(controlled, track, "128") == null);
      Method key = method(bufferType, "key", JSONObject.class, String.class);
      String name = (String) key.invoke(cache, track, "128");
      check(
          "Automatic cache is isolated by account",
          !name.equals(key.invoke(controlled, track, "128")));
      result.putString("stream", "AHEAD_CACHE_CHECKS_PASS\n");
    } catch (Exception error) {
      code = Activity.RESULT_CANCELED;
      Throwable cause = error instanceof InvocationTargetException ? error.getCause() : error;
      String detail = cause.getMessage();
      result.putString(
          "stream",
          "AHEAD_CACHE_ERROR "
              + cause.getClass().getSimpleName()
              + (detail != null && detail.startsWith("预缓存") ? " " + detail : "")
              + "\n");
    } finally {
      release.countDown();
      if (pending != null)
        try {
          pending.join(3000);
        } catch (InterruptedException ignored) {
        }
      if (bufferType != null)
        for (Object value : new Object[] {cache, controlled}) {
          if (value == null) continue;
          try {
            method(bufferType, "close").invoke(value);
            if (created && track != null && value == cache) {
              String key =
                  (String)
                      method(bufferType, "key", JSONObject.class, String.class)
                          .invoke(value, track, "128");
              File root =
                  new File(getTargetContext().getCacheDir(), "audio-ahead").getCanonicalFile();
              for (String suffix : new String[] {".audio", ".json", ".part"}) {
                File file = new File(root, key + suffix).getCanonicalFile();
                if (root.equals(file.getParentFile())) file.delete();
              }
            }
          } catch (Exception ignored) {
          }
        }
    }
    finish(code, result);
  }
}
