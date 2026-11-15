package cn.ting.music.tests;

import android.app.*;
import android.content.*;
import android.os.*;
import java.lang.reflect.*;
import org.json.*;

/** 仅核对已有导入记录和云歌单，不提交导入、增删歌曲或删除歌单。 */
public final class PlaylistOriginChecks extends Instrumentation {
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
  @Override public void onStart() {
    Bundle result = new Bundle();
    int code = Activity.RESULT_OK;
    try {
      Context target = getTargetContext();
      ClassLoader loader = target.getClassLoader();
      Class<?> apiType = loader.loadClass("cn.ting.music.KugouApi");
      Object api = apiType.getConstructor(Context.class).newInstance(target);
      Method user = apiType.getDeclaredMethod("accountUserId");
      user.setAccessible(true);
      String owner = String.valueOf(user.invoke(api));
      JSONArray tasks = new JSONArray(target.getSharedPreferences("playlist_imports", Context.MODE_PRIVATE)
          .getString("tasks-" + owner, "[]"));
      Class<?> repositoryType = loader.loadClass("cn.ting.music.CloudPlaylistRepository");
      Constructor<?> constructor = repositoryType.getDeclaredConstructor(apiType);
      constructor.setAccessible(true);
      Object repository = constructor.newInstance(api);
      Method execute = repositoryType.getDeclaredMethod("execute", String.class, JSONObject.class);
      execute.setAccessible(true);
      JSONObject lists = (JSONObject) execute.invoke(repository, "list", new JSONObject());
      JSONArray rows = lists.getJSONArray("playlists");
      StringBuilder report = new StringBuilder();
      int matching = 0;
      for (int i = 0; i < rows.length(); i++) {
        JSONObject row = rows.getJSONObject(i);
        if (!row.optString("name").equals("我喜欢的音乐") || row.optBoolean("isSystem")) continue;
        matching++;
        boolean fromImport = false, confirmed = false;
        for (int t = 0; t < tasks.length(); t++) {
          JSONObject task = tasks.getJSONObject(t);
          if (task.optString("cloudId").equals(row.getString("id"))) {
            fromImport = true;
            confirmed = task.optBoolean("confirmed");
          }
        }
        JSONObject tracks = (JSONObject) execute.invoke(repository, "tracks", new JSONObject()
            .put("accountKey", lists.getString("accountKey")).put("id", row.getString("id")));
        report.append("NAMED_PLAYLIST_ORIGIN recordedImport=").append(fromImport)
            .append(" importConfirmed=").append(confirmed).append(" actualSongs=").append(tracks.optInt("count"))
            .append(" metadataSongs=").append(row.optInt("count")).append('\n');
      }
      report.append("PLAYLIST_ORIGIN_READ_ONLY_PASS candidates=").append(matching).append('\n');
      result.putString("stream", report.toString());
    } catch (Throwable error) {
      code = Activity.RESULT_CANCELED;
      Throwable cause = error instanceof InvocationTargetException ? error.getCause() : error;
      result.putString("stream", "PLAYLIST_ORIGIN_READ_ONLY_FAIL: " + cause.getClass().getSimpleName() + "\n");
    }
    finish(code, result);
  }
}
