package cn.ting.music.tests;

import android.app.*;
import android.content.*;
import android.os.*;
import java.lang.reflect.*;
import org.json.*;

/** 仅修改本次创建的临时云歌单，最终清理；日志不输出账号、歌单名或歌曲内容。 */
public final class CloudPlaylistChecks extends Instrumentation {
  private Object repository;
  private Method execute;
  private JSONObject lists;
  private boolean inspect;
  private boolean cleanupOnly;
  private final String prefix = "TvT联调" + System.currentTimeMillis();

  @Override public void onCreate(Bundle args) {
    super.onCreate(args);
    inspect = args != null && args.getBoolean("inspect", false);
    cleanupOnly = args != null && "cleanup".equals(args.getString("mode"));
    start();
  }

  private void report(String text) {
    Bundle status = new Bundle();
    status.putString("stream", text + "\n");
    sendStatus(0, status);
  }

  private void check(String text, boolean passed) {
    report(text + ": " + (passed ? "PASS" : "FAIL"));
    if (!passed) throw new AssertionError(text);
  }

  private JSONObject call(String operation, JSONObject input) throws Exception {
    if (lists != null) input.put("accountKey", lists.getString("accountKey"))
        .put("totalVersion", lists.getLong("totalVersion"));
    try { return (JSONObject) execute.invoke(repository, operation, input); }
    catch (InvocationTargetException error) {
      Throwable cause = error.getCause();
      if (cause instanceof Exception) throw (Exception) cause;
      throw error;
    }
  }

  @Override public void onStart() {
    Thread watchdog = new Thread(() -> {
      try {
        Thread.sleep(20000);
        for (java.util.Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet())
          for (StackTraceElement frame : entry.getValue())
            if (frame.getClassName().startsWith("cn.ting.music.")) report("Cloud wait frame: " + entry.getKey().getName() + " " + frame);
      } catch (InterruptedException done) {}
    });
    watchdog.start();
    Bundle result = new Bundle();
    int code = Activity.RESULT_OK;
    try {
      Context context = getTargetContext();
      Class<?> apiType = context.getClassLoader().loadClass("cn.ting.music.KugouApi");
      Object api = apiType.getConstructor(Context.class).newInstance(context);
      JSONObject raw = (JSONObject) apiType.getMethod("cloudPlaylists").invoke(api);
      JSONObject data = raw.getJSONObject("data");
      report("Cloud list data keys=" + data.names());
      JSONArray rows = data.optJSONArray("info");
      if (rows != null && rows.length() > 0) {
        report("Cloud list row keys=" + rows.getJSONObject(0).names());
        report("Cloud list type=" + rows.getJSONObject(0).optInt("type") + " count=" + rows.length());
        for (int i = 0; i < rows.length(); i++) {
          JSONObject row = rows.getJSONObject(i);
          report("Raw list flag=" + row.optInt("is_def") + " officialLikesName=" + row.optString("name").equals("我喜欢")
              + " type=" + row.optInt("type") + " deleted=" + row.optInt("is_del"));
        }
      }
      Class<?> repositoryType = context.getClassLoader().loadClass("cn.ting.music.CloudPlaylistRepository");
      Constructor<?> constructor = repositoryType.getDeclaredConstructor(apiType);
      constructor.setAccessible(true);
      repository = constructor.newInstance(api);
      execute = repositoryType.getDeclaredMethod("execute", String.class, JSONObject.class);
      execute.setAccessible(true);
      lists = call("list", new JSONObject());
      check("Native cloud list whitelist and account scope", lists.has("accountKey") && lists.has("totalVersion")
          && !lists.toString().contains("\"token\""));
      for (int index = 0; index < lists.getJSONArray("playlists").length(); index++) {
        JSONObject list = lists.getJSONArray("playlists").getJSONObject(index);
        if (list.optInt("count") > 0) {
          JSONObject cover = call("cover", new JSONObject().put("id", list.getString("id")));
          check("Cloud card artwork resolves without opening full tracks", cover.optString("cover").startsWith("https://"));
          break;
        }
      }
      if (!inspect && !cleanupOnly) {
        lists = call("create", new JSONObject().put("name", prefix + "A").put("private", true));
        String id = lists.getString("createdId");
        check("Real private cloud playlist creation confirmed", !id.isEmpty());
        JSONObject tracks = call("tracks", new JSONObject().put("id", id));
        check("New cloud playlist is empty", tracks.getJSONArray("tracks").length() == 0);
        JSONObject search = (JSONObject) apiType.getMethod("search", String.class, int.class).invoke(api, "纯音乐", 1);
        JSONArray songs = search.getJSONObject("data").getJSONArray("lists");
        JSONArray add = new JSONArray();
        for (int index = 0; index < 2; index++) {
          JSONObject song = songs.getJSONObject(index);
          add.put(new JSONObject().put("hash", song.getString("FileHash"))
              .put("title", song.optString("SongName")).put("artist", song.optString("SingerName"))
              .put("albumId", song.optString("AlbumID")).put("audioId", song.optString("MixSongID")));
        }
        tracks = call("addTracks", new JSONObject().put("id", id)
            .put("listVersion", tracks.getLong("listVersion")).put("tracks", add));
        check("Real song addition confirmed by reread", tracks.getJSONArray("tracks").length() == 2);
        JSONArray order = new JSONArray().put(tracks.getJSONArray("tracks").getJSONObject(1).getString("fileId"))
            .put(tracks.getJSONArray("tracks").getJSONObject(0).getString("fileId"));
        tracks = call("sortTracks", new JSONObject().put("id", id).put("listVersion", tracks.getLong("listVersion")).put("fileIds", order));
        check("Real encrypted song order confirmed", tracks.getJSONArray("tracks").getJSONObject(0).getString("fileId").equals(order.getString(0)));
        lists = call("list", new JSONObject());
        lists = call("update", new JSONObject().put("id", id).put("name", prefix + "改名").put("description", "本次临时联调后自动清理"));
        check("Real encrypted name and description update confirmed", lists.has("playlists"));
        lists = call("create", new JSONObject().put("name", prefix + "B").put("private", true));
        String other = lists.getString("createdId");
        JSONArray listOrder = new JSONArray();
        for (int index = 0; index < lists.getJSONArray("playlists").length(); index++) {
          JSONObject list = lists.getJSONArray("playlists").getJSONObject(index);
          if (!list.optBoolean("isDefault") && !list.getString("id").equals(id) && !list.getString("id").equals(other)) listOrder.put(list.getString("id"));
        }
        listOrder.put(other).put(id);
        lists = call("sortLists", new JSONObject().put("ids", listOrder));
        check("Real encrypted playlist order confirmed", lists.has("playlists"));
        tracks = call("tracks", new JSONObject().put("id", id));
        tracks = call("removeTracks", new JSONObject().put("id", id).put("listVersion", tracks.getLong("listVersion"))
            .put("fileIds", new JSONArray().put(tracks.getJSONArray("tracks").getJSONObject(0).getString("fileId"))));
        check("Real song removal confirmed", tracks.getJSONArray("tracks").length() == 1);
      }
      result.putString("stream", inspect ? "CLOUD_PLAYLIST_INSPECT_PASS\n" : "CLOUD_PLAYLIST_DEVICE_PASS\n");
    } catch (Throwable error) {
      code = Activity.RESULT_CANCELED;
      Throwable cause = error instanceof InvocationTargetException ? error.getCause() : error;
      report("Cloud operation failure: " + cause.getClass().getSimpleName()
          + (cause.getMessage() == null ? "" : " " + cause.getMessage()));
      if (repository != null) try {
        Field field = repository.getClass().getDeclaredField("lastReceiptResponse");
        field.setAccessible(true);
        JSONObject response = (JSONObject) field.get(repository);
        JSONObject data = response == null ? null : response.optJSONObject("data");
        if (data != null) {
          report("Last write data keys=" + data.names() + " code=" + data.optInt("code") + " status=" + data.optInt("status"));
          Object info = data.opt("info");
          JSONArray items = info instanceof JSONArray ? (JSONArray) info : new JSONArray().put(info);
          for (int i = 0; i < items.length(); i++) {
            JSONObject row = items.optJSONObject(i);
            if (row != null) report("Last write row keys=" + row.names() + " code=" + row.optInt("code") + " sort=" + row.optInt("sort"));
          }
        }
      } catch (Exception ignored) {}
      if (repository != null) try {
        JSONObject current = call("list", new JSONObject());
        JSONArray currentRows = current.getJSONArray("playlists");
        for (int i = 0; i < currentRows.length(); i++) {
          JSONObject row = currentRows.getJSONObject(i);
          report("Current sort row " + i + " type=" + row.optInt("type") + " sort=" + row.optInt("sort")
              + " default=" + row.optBoolean("isDefault") + " test=" + row.optString("name").startsWith(prefix));
        }
      } catch (Exception ignored) {}
      if (repository != null) try {
        Field response = repository.getClass().getDeclaredField("lastCreateResponse");
        response.setAccessible(true);
        JSONObject rawCreate = (JSONObject) response.get(repository);
        if (rawCreate != null) {
          report("Create receipt fields=" + rawCreate.names() + " status=" + rawCreate.opt("status"));
          JSONObject detail = rawCreate.optJSONObject("data");
          if (detail != null) {
            report("Create data fields=" + detail.names());
            Object info = detail.opt("info");
            report("Create info shape=" + (info == null ? "missing" : info.getClass().getSimpleName()));
            if (info instanceof JSONObject) {
              JSONObject row = (JSONObject) info;
              report("Create info keys=" + row.names() + " code=" + row.optInt("code") + " status=" + row.optInt("status")
                  + " number=" + row.optInt("number") + " idPositive=" + (row.optLong("listid") > 0));
            }
            if (info instanceof JSONArray) {
              JSONArray receipt = (JSONArray) info;
              report("Create receipt rows=" + receipt.length());
              for (int i = 0; i < receipt.length(); i++) {
                JSONObject row = receipt.getJSONObject(i);
                report("Create receipt row fields=" + row.names() + " type=" + row.optInt("type")
                    + " deleted=" + row.optInt("is_del") + " testName=" + row.optString("name").startsWith(prefix));
              }
            }
          }
        }
      } catch (Exception ignored) {}
      if (repository != null) try {
        Field field = repository.getClass().getDeclaredField("lastTracksResponse");
        field.setAccessible(true);
        JSONObject rawTracks = (JSONObject) field.get(repository);
        if (rawTracks != null) {
          report("Track response fields=" + rawTracks.names());
          JSONObject detail = rawTracks.optJSONObject("data");
          if (detail != null) {
            report("Track data fields=" + detail.names());
            JSONArray songs = detail.optJSONArray("info");
            if (songs != null && songs.length() > 0) {
              report("Track row fields=" + songs.getJSONObject(0).names());
              for (int i = 0; i < Math.min(3, songs.length()); i++)
                report("Track row sort=" + songs.getJSONObject(i).opt("sort"));
            }
          }
        }
      } catch (Exception ignored) {}
      result.putString("stream", "CLOUD_PLAYLIST_FAIL " + cause.getClass().getSimpleName()
          + (cause.getMessage() == null ? "" : " " + cause.getMessage()) + "\n");
    } finally {
      if (!inspect && repository != null) try {
        lists = call("list", new JSONObject());
        JSONArray temporary = new JSONArray();
        for (int index = 0; index < lists.getJSONArray("playlists").length(); index++) {
          JSONObject list = lists.getJSONArray("playlists").getJSONObject(index);
          if (list.getString("name").matches("TvT联调[0-9]{13}(A|B|改名)")) temporary.put(list.getString("id"));
        }
        for (int index = 0; index < temporary.length(); index++) {
          lists = call("list", new JSONObject());
          lists = call("delete", new JSONObject().put("id", temporary.getString(index)));
          report("Temporary cloud playlist deleted and absence confirmed: PASS");
        }
      } catch (Throwable cleanup) {
        report("Cleanup failure: " + cleanup.getMessage());
        try {
          Field field = repository.getClass().getDeclaredField("lastReceiptResponse");
          field.setAccessible(true);
          JSONObject response = (JSONObject) field.get(repository);
          JSONObject data = response.optJSONObject("data");
          report("Cleanup receipt keys=" + response.names() + " data keys=" + (data == null ? "missing" : data.names()));
          if (data != null) {
            Object info = data.opt("info");
            report("Cleanup info shape=" + (info == null ? "missing" : info.getClass().getSimpleName()));
            if (info instanceof JSONObject) report("Cleanup info keys=" + ((JSONObject) info).names());
            JSONArray rows = info instanceof JSONArray ? (JSONArray) info : new JSONArray().put(info);
            for (int i = 0; i < rows.length(); i++) {
              JSONObject row = rows.optJSONObject(i);
              if (row != null) report("Cleanup row keys=" + row.names() + " code=" + row.optInt("code") + " status=" + row.optInt("status") + " type=" + row.optInt("type") + " deleted=" + row.optInt("is_del")
                  + " idPositive=" + (row.optLong("listid") > 0));
            }
          }
        } catch (Exception ignored) {}
        code = Activity.RESULT_CANCELED;
        result.putString("stream", "CLOUD_PLAYLIST_CLEANUP_FAILED: " + cleanup.getClass().getSimpleName() + "\n");
      }
    }
    watchdog.interrupt();
    finish(code, result);
  }
}
