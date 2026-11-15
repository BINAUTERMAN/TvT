package cn.ting.music.tests;

import android.app.*;
import android.content.*;
import android.os.*;
import java.lang.reflect.*;
import org.json.*;

/** 使用用户提供的链接，记录真实导入任务，不输出链接或歌单私人内容。 */
public final class PlaylistImportChecks extends Instrumentation {
  private String link;
  private Object importer;
  private Method execute;
  @Override public void onCreate(Bundle args) {
    super.onCreate(args);
    String encoded = args == null ? "" : args.getString("linkBase64", "");
    link = encoded.isEmpty() ? "" : new String(java.util.Base64.getDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8);
    start();
  }
  private void report(String text) { Bundle status = new Bundle(); status.putString("stream", text + "\n"); sendStatus(0, status); }
  private JSONObject call(String operation, JSONObject input) throws Exception {
    try { return (JSONObject) execute.invoke(importer, operation, input); }
    catch (InvocationTargetException error) { throw (Exception) error.getCause(); }
  }
  @Override public void onStart() {
    report("Import final-result validation started");
    Thread watchdog = new Thread(() -> {
      try {
        Thread.sleep(20000);
        for (java.util.Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
          for (StackTraceElement frame : entry.getValue()) {
            if (frame.getClassName().startsWith("cn.ting.music.")) report("Import wait frame: " + entry.getKey().getName() + " " + frame);
          }
        }
      } catch (InterruptedException done) { }
    });
    watchdog.start();
    Bundle result = new Bundle();
    int code = Activity.RESULT_OK;
    try {
      Context context = getTargetContext();
      ClassLoader loader = context.getClassLoader();
      Class<?> apiType = loader.loadClass("cn.ting.music.KugouApi");
      Object api = apiType.getConstructor(Context.class).newInstance(context);
      Class<?> type = loader.loadClass("cn.ting.music.PlaylistImporter");
      Constructor<?> constructor = type.getDeclaredConstructor(Context.class, apiType);
      constructor.setAccessible(true);
      importer = constructor.newInstance(context, api);
      execute = type.getDeclaredMethod("execute", String.class, JSONObject.class);
      execute.setAccessible(true);
      JSONObject history = call("history", new JSONObject());
      if (history.getJSONArray("tasks").length() > 0 && link.isEmpty()) {
        JSONObject completed = call("resume", new JSONObject());
        if (!completed.optBoolean("success") || completed.optInt("count") < 1 || completed.optString("cloudId").isEmpty())
          throw new Exception("Import did not confirm visible cloud songs");
        report("Import finalizes a visible cloud playlist with reread songs: PASS; count=" + completed.getInt("count"));
      }
      if (!link.isEmpty()) {
        history = call("submit", new JSONObject().put("url", link));
        report("Real external playlist import accepted with task identifier: " + history.has("submittedId"));
        JSONObject duplicate = call("submit", new JSONObject().put("url", link));
        if (!history.getString("submittedId").equals(duplicate.getString("submittedId"))) throw new Exception("Duplicate task was created");
        report("Duplicate submission reuses the recorded task: PASS");
      }
      JSONObject progress = call("refresh", new JSONObject());
      JSONArray tasks = progress.getJSONArray("tasks");
      for (int index = 0; index < tasks.length(); index++) {
        JSONObject task = tasks.getJSONObject(index);
        report("Real import task state=" + task.optInt("state") + " has target=" + !task.optString("listId").isEmpty());
        if (task.optInt("state") == 3 && !task.optString("listId").isEmpty()) {
          JSONObject matched = call("result", new JSONObject().put("id", task.getString("id")));
          report("Real match result count=" + matched.getJSONArray("items").length()
              + " matched=" + matched.optInt("matched") + " missed=" + matched.optInt("missed"));
          Field responseField = type.getDeclaredField("lastResponse");
          responseField.setAccessible(true);
          JSONObject wire = (JSONObject) responseField.get(importer);
          report("Match response fields=" + wire.names());
          JSONObject detail = wire.optJSONObject("data");
          if (detail != null) {
            report("Match data fields=" + detail.names());
            report("Match counts: imported=" + detail.optInt("imported_num", -1)
                + " missed=" + detail.optInt("missed_num", -1)
                + " total=" + detail.optInt("songs_num", -1)
                + " status=" + detail.optInt("status", -1)
                + " positive target=" + (detail.optLong("listid") > 0));
            JSONObject cloud = (JSONObject) apiType.getMethod("cloudPlaylists").invoke(api);
            JSONArray cloudRows = cloud.getJSONObject("data").getJSONArray("info");
            boolean localMatch = false, globalMatch = false, nameMatch = false;
            for (int i = 0; i < cloudRows.length(); i++) {
              JSONObject row = cloudRows.getJSONObject(i);
              localMatch |= row.optString("listid").equals(detail.optString("listid"));
              globalMatch |= row.optString("global_collection_id").equals(detail.optString("listid"));
              nameMatch |= row.optString("name").equals(detail.optString("name"));
            }
            report("Imported target in cloud list: local=" + localMatch + " global=" + globalMatch + " name=" + nameMatch);
            JSONObject publicSongs = (JSONObject) apiType.getMethod("playlist", String.class, int.class)
                .invoke(api, detail.optString("listid"), 1);
            report("Parsed target playlist response fields=" + publicSongs.names());
            JSONObject publicData = publicSongs.optJSONObject("data");
            if (publicData != null) {
              report("Parsed target data fields=" + publicData.names());
              for (String key : new String[] {"songs", "info", "list"}) {
                JSONArray array = publicData.optJSONArray(key);
                if (array != null) {
                  report("Parsed target songs=" + array.length());
                  if (array.optJSONObject(0) != null) report("Parsed target song fields=" + array.getJSONObject(0).names());
                }
              }
            }
            Method userMethod = apiType.getDeclaredMethod("accountUserId");
            userMethod.setAccessible(true);
            Method tokenMethod = apiType.getDeclaredMethod("accountToken");
            tokenMethod.setAccessible(true);
            Method catalogRequest = apiType.getDeclaredMethod("catalogRequest", String.class, String.class, java.util.TreeMap.class, Object.class);
            catalogRequest.setAccessible(true);
            JSONObject directTracks = (JSONObject) catalogRequest.invoke(api,
                "https://gateway.kugou.com/v4/get_list_all_file_v3", "cloudlist.service.kugou.com", new java.util.TreeMap<String,String>(),
                new JSONObject().put("listid", detail.optString("listid")).put("userid", userMethod.invoke(api))
                    .put("token", tokenMethod.invoke(api)).put("type", 0).put("page", 1).put("pagesize", 300)
                    .put("area_code", 1).put("allplatform", 1).put("show_cover", 1));
            report("Parsed target cloud track response: status=" + directTracks.optInt("status") + " code=" + directTracks.optInt("error_code"));
            JSONObject directData = directTracks.optJSONObject("data");
            if (directData != null) report("Parsed target cloud track count=" + directData.optInt("count", -1)
                + " rows=" + (directData.optJSONArray("info") == null ? -1 : directData.getJSONArray("info").length()));
            for (java.util.Iterator<String> iterator = detail.keys(); iterator.hasNext();) {
              String key = iterator.next();
              Object value = detail.opt(key);
              if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                report("Match array=" + key + " count=" + array.length());
                if (array.optJSONObject(0) != null) report("Match row fields=" + array.getJSONObject(0).names());
              }
            }
          }
        }
      }
      result.putString("stream", "PLAYLIST_IMPORT_DEVICE_PASS\n");
    } catch (Throwable error) {
      code = Activity.RESULT_CANCELED;
      report("Import error=" + error.getClass().getSimpleName() + " " + error.getMessage());
      if (importer != null) try {
        Field response = importer.getClass().getDeclaredField("lastResponse");
        response.setAccessible(true);
        JSONObject raw = (JSONObject) response.get(importer);
        if (raw != null) {
          report("Import response fields=" + raw.names());
          JSONObject data = raw.optJSONObject("data");
          if (data != null) report("Import data fields=" + data.names());
        }
      } catch (Exception ignored) {}
      result.putString("stream", "PLAYLIST_IMPORT_DEVICE_FAIL\n");
    }
    watchdog.interrupt();
    finish(code, result);
  }
}
