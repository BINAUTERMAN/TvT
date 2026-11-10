package cn.ting.music.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import java.lang.reflect.InvocationTargetException;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

/** 只输出状态、数量和音频可读性，不输出账号内容、令牌及播放地址。 */
public final class NetworkDiagnostics extends Instrumentation {
  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    start();
  }

  private void report(String name, Object value) {
    Bundle bundle = new Bundle();
    bundle.putString("stream", name + ": " + value + "\n");
    sendStatus(0, bundle);
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    try {
      Class<?> type = getTargetContext().getClassLoader().loadClass("cn.ting.music.KugouApi");
      Object api =
          type.getConstructor(android.content.Context.class).newInstance(getTargetContext());
      type.getMethod("ensureRegistered").invoke(api);
      report("deviceRegistration", "PASS");
      JSONObject account = (JSONObject) type.getMethod("account").invoke(api);
      report("loggedIn", account.optBoolean("loggedIn"));
      JSONObject cloud = (JSONObject) type.getMethod("cloudPlaylists").invoke(api);
      report("cloud.status", cloud.optInt("status", -1));
      JSONArray lists = cloud.getJSONObject("data").optJSONArray("info");
      report("cloud.count", lists == null ? 0 : lists.length());
      if (lists != null)
        for (int row = 0; row < lists.length(); row++) {
          JSONObject item = lists.getJSONObject(row);
          if (item.optString("name").equals("我喜欢")) {
            report("favorites.declaredCount", item.optInt("count", item.optInt("song_count", -1)));
            java.lang.reflect.Method token = type.getDeclaredMethod("accountToken");
            token.setAccessible(true);
            java.lang.reflect.Method user = type.getDeclaredMethod("accountUserId");
            user.setAccessible(true);
            java.lang.reflect.Method request =
                type.getDeclaredMethod(
                    "catalogRequest",
                    String.class,
                    String.class,
                    java.util.TreeMap.class,
                    Object.class);
            request.setAccessible(true);
            JSONObject body =
                new JSONObject()
                    .put("listid", item.optLong("listid"))
                    .put("userid", user.invoke(api))
                    .put("token", token.invoke(api))
                    .put("type", 0)
                    .put("page", 1)
                    .put("pagesize", 300)
                    .put("area_code", 1)
                    .put("allplatform", 1)
                    .put("show_cover", 1);
            JSONObject response =
                (JSONObject)
                    request.invoke(
                        api,
                        "https://gateway.kugou.com/v4/get_list_all_file_v3",
                        "cloudlist.service.kugou.com",
                        new java.util.TreeMap<>(),
                        body);
            JSONObject detail = response.optJSONObject("data");
            report("favorites.responseFields", detail == null ? "none" : detail.names());
            if (detail != null && detail.optJSONArray("info") != null) {
              JSONArray rows = detail.getJSONArray("info");
              report("favorites.rawCount", rows.length());
              if (rows.length() > 0) report("favorites.firstFields", rows.getJSONObject(0).names());
            }
          }
        }
      JSONArray favorites =
          (JSONArray) type.getMethod("favorites", boolean.class).invoke(api, true);
      report("cloudFavorites.count", favorites.length());
      JSONObject search =
          (JSONObject) type.getMethod("search", String.class, int.class).invoke(api, "纯音乐", 1);
      JSONArray songs = search.getJSONObject("data").getJSONArray("lists");
      boolean playable = false;
      for (int index = 0; index < Math.min(3, songs.length()); index++) {
        JSONObject song = songs.getJSONObject(index);
        JSONObject track =
            new JSONObject()
                .put("hash", song.optString("FileHash"))
                .put("albumId", song.optString("AlbumID", "0"))
                .put("audioId", song.optString("MixSongID", "0"));
        try {
          String url = (String) type.getMethod("songUrl", JSONObject.class).invoke(api, track);
          report("audio.host", new URL(url).getHost());
          HttpURLConnection audio = (HttpURLConnection) new URL(url).openConnection();
          audio.setConnectTimeout(12000);
          audio.setReadTimeout(12000);
          audio.setRequestProperty("Range", "bytes=0-255");
          try {
            report("audio.http", audio.getResponseCode());
            try (java.io.InputStream input = audio.getInputStream()) {
              playable = input.read() >= 0;
            }
            report("audio.readable", playable);
          } catch (javax.net.ssl.SSLException exception) {
            report("audio.tls", exception.getClass().getSimpleName());
          } finally {
            audio.disconnect();
          }
          if (playable) break;
        } catch (InvocationTargetException error) {
          report("playback.error", error.getCause().getMessage());
        }
      }
      result.putString(
          "stream", playable ? "NETWORK_CHECKS_PASS\n" : "NETWORK_CHECKS_PLAYBACK_FAILED\n");
      finish(playable ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    } catch (Throwable error) {
      Throwable cause = error instanceof InvocationTargetException ? error.getCause() : error;
      result.putString(
          "stream", "NETWORK_CHECKS_FAILED: " + cause.getClass().getSimpleName() + "\n");
      finish(Activity.RESULT_CANCELED, result);
    }
  }
}
