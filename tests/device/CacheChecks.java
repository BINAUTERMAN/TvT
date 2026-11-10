package cn.ting.music.tests;

import android.app.*;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.*;
import java.lang.reflect.*;
import org.json.*;

/** 使用真实系统缓存和本地解码，不切换整机网络，不导出音频或登录态。 */
public final class CacheChecks extends Instrumentation {
  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  private void check(String name, boolean passed) throws Exception {
    Bundle message = new Bundle();
    message.putString("stream", name + ": " + (passed ? "PASS" : "FAIL") + "\n");
    sendStatus(0, message);
    if (!passed) throw new Exception(name);
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    Object cache = null;
    Method remove = null;
    String key = "";
    boolean created = false;
    int resultCode = Activity.RESULT_OK;
    try {
      ClassLoader loader = getTargetContext().getClassLoader();
      Class<?> apiType = loader.loadClass("cn.ting.music.KugouApi"),
          cacheType = loader.loadClass("cn.ting.music.MusicCache");
      Object api =
          apiType.getConstructor(android.content.Context.class).newInstance(getTargetContext());
      Constructor<?> constructor = cacheType.getDeclaredConstructor(android.content.Context.class);
      constructor.setAccessible(true);
      cache = constructor.newInstance(getTargetContext());
      Method list = cacheType.getDeclaredMethod("list"),
          start = cacheType.getDeclaredMethod("start", apiType, JSONObject.class, String.class),
          playback = cacheType.getDeclaredMethod("playback", JSONObject.class);
      remove = cacheType.getDeclaredMethod("remove", String.class);
      for (Method method : new Method[] {list, start, playback, remove}) method.setAccessible(true);
      JSONObject search =
          (JSONObject)
              apiType.getMethod("search", String.class, int.class).invoke(api, "纯音乐 铃声", 1);
      JSONArray songs = search.getJSONObject("data").getJSONArray("lists");
      JSONObject song = null;
      for (int index = 0; index < songs.length(); index++) {
        JSONObject candidate = songs.getJSONObject(index);
        if (candidate.optInt("Duration") >= 3
            && candidate.optInt("Duration") <= 90
            && candidate.optLong("FileSize") < 2000000
            && candidate.optString("FileHash").matches("[a-fA-F0-9]{32}")) {
          song = candidate;
          break;
        }
      }
      check("Small authorized online cache sample found", song != null);
      key = song.getString("FileHash").toLowerCase(java.util.Locale.ROOT);
      JSONObject track =
          new JSONObject()
              .put("id", key)
              .put("hash", key)
              .put("title", song.optString("SongName"))
              .put("artist", song.optString("SingerName"))
              .put("album", song.optString("AlbumName"))
              .put("cover", song.optString("Image"))
              .put("albumId", song.optString("AlbumID"))
              .put("audioId", song.optString("MixSongID"))
              .put("duration", song.optInt("Duration"));
      JSONArray before = ((JSONObject) list.invoke(cache)).getJSONArray("tracks");
      created = true;
      for (int index = 0; index < before.length(); index++)
        if (before.getJSONObject(index).optString("hash").equals(key)) created = false;
      JSONObject pending = (JSONObject) start.invoke(cache, api, track, "128");
      check(
          "System music cache request accepted",
          pending.optString("state").equals("pending")
              || pending.optString("state").equals("ready"));
      JSONObject cached = null;
      for (int attempt = 0; attempt < 180 && cached == null; attempt++) {
        JSONObject state = (JSONObject) list.invoke(cache);
        JSONArray tracks = state.getJSONArray("tracks");
        for (int index = 0; index < tracks.length(); index++)
          if (tracks.getJSONObject(index).optString("hash").equals(key))
            cached = tracks.getJSONObject(index);
        JSONArray jobs = state.getJSONArray("jobs");
        for (int index = 0; index < jobs.length(); index++) {
          JSONObject job = jobs.getJSONObject(index);
          if (job.optString("hash").equals(key) && job.optString("state").equals("failed"))
            throw new Exception(job.optString("message"));
        }
        if (cached == null) SystemClock.sleep(500);
      }
      check(
          "Completed audio appears in native local library",
          cached != null && cached.optBoolean("cached"));
      JSONObject local = (JSONObject) playback.invoke(cache, cached);
      check(
          "Offline handle resolves to owned local file",
          local.getString("uri").startsWith("file://"));
      MediaPlayer player = new MediaPlayer();
      try {
        player.setDataSource(getTargetContext(), Uri.parse(local.getString("uri")));
        player.prepare();
        check(
            "Native player prepares cached audio from file without online URL",
            player.getDuration() > 0);
      } finally {
        player.release();
      }
      Object reopened = constructor.newInstance(getTargetContext());
      check(
          "Cache record survives repository recreation",
          ((JSONObject) playback.invoke(reopened, cached))
              .getString("uri")
              .equals(local.getString("uri")));
      if (created) {
        java.io.File file = new java.io.File(Uri.parse(local.getString("uri")).getPath());
        String root =
            new java.io.File(
                        getTargetContext()
                            .getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC),
                        "offline")
                    .getCanonicalPath()
                + java.io.File.separator;
        check(
            "Test cleanup stays inside cache directory", file.getCanonicalPath().startsWith(root));
        check("Test cache file can be removed", file.delete());
        boolean rejected = false;
        try {
          playback.invoke(cache, cached);
        } catch (InvocationTargetException expected) {
          rejected = true;
        }
        check("Missing cache cannot be reported as playable", rejected);
      }
      result.putString("stream", "CACHE_CHECKS_PASSED\n");
    } catch (Exception error) {
      Throwable failure =
          error instanceof InvocationTargetException
              ? ((InvocationTargetException) error).getCause()
              : error;
      result.putString(
          "stream",
          "FAIL " + failure.getClass().getSimpleName() + ": " + failure.getMessage() + "\n");
      resultCode = Activity.RESULT_CANCELED;
    } finally {
      if (created && cache != null && remove != null && !key.isEmpty())
        try {
          remove.invoke(cache, key);
        } catch (Exception ignored) {
        }
    }
    finish(resultCode, result);
  }
}
