package cn.ting.music;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.URI;
import java.util.*;
import org.json.*;

/** 只在用户提交、刷新或查看结果时请求；任务记录按账号隔离，不保存图片和令牌。 */
final class PlaylistImporter {
  private static final Object LOCK = new Object();
  private final KugouApi api;
  private final SharedPreferences preferences;
  private long owner;
  private String ownerToken;
  private JSONObject lastResponse;
  private final java.util.function.BooleanSupplier active;

  PlaylistImporter(Context context, KugouApi api) {
    this(context, api, () -> true);
  }

  PlaylistImporter(Context context, KugouApi api, java.util.function.BooleanSupplier active) {
    this.api = api;
    this.active = active;
    preferences = context.getSharedPreferences("playlist_imports", Context.MODE_PRIVATE);
  }

  JSONObject execute(String operation, JSONObject input) throws Exception {
    synchronized (LOCK) {
      requireAccount();
      if (operation.equals("pending")) {
        JSONArray saved = history();
        return new JSONObject().put("pending", saved.length() > 0 && !saved.getJSONObject(0).optBoolean("confirmed")
            && saved.getJSONObject(0).optInt("state") < 10);
      }
      if (operation.equals("import")) {
        JSONObject submitted = execute("submit", input);
        return complete(submitted.getString("submittedId"));
      }
      if (operation.equals("resume")) {
        JSONArray saved = history();
        if (saved.length() == 0) throw new Exception("没有需要继续的导入，请重新提交链接");
        return complete(saved.getJSONObject(0).getString("id"));
      }
      if (operation.equals("history")) return new JSONObject().put("tasks", history());
      if (operation.equals("submit")) {
        String url = link(input.optString("url"));
        String fingerprint = KugouApi.md5(url);
        JSONArray existing = history();
        for (int index = 0; index < existing.length(); index++) {
          JSONObject task = existing.getJSONObject(index);
          if (task.optString("fingerprint").equals(fingerprint) && task.optInt("state") < 10)
            return new JSONObject().put("tasks", existing).put("submittedId", task.getString("id"));
        }
        JSONObject response = request("/assetservice/import/v1/add_task", new JSONObject()
            .put("source", 3).put("task_type", 0).put("url", url));
        return remember(response, "分享链接", fingerprint);
      }
      if (operation.equals("refresh")) return refresh();
      if (operation.equals("result")) return result(input.optString("id"), input.optInt("page", 1));
      throw new Exception("歌单导入操作无效");
    }
  }

  JSONObject submitImage(String target, String base64) throws Exception {
    synchronized (LOCK) {
      requireAccount();
      JSONObject lists = new CloudPlaylistRepository(api).execute("list", new JSONObject());
      JSONObject destination = null;
      JSONArray rows = lists.getJSONArray("playlists");
      for (int index = 0; index < rows.length(); index++) {
        JSONObject row = rows.getJSONObject(index);
        if (row.getString("id").equals(target) && row.getInt("type") == 0 && !row.optBoolean("isDefault")) destination = row;
      }
      if (destination == null) throw new Exception("目标云歌单已变化，请重新选择");
      if (base64.length() > 3 * 1024 * 1024) throw new Exception("截图过大，请选择较小的图片");
      String serial = String.valueOf(api.accountUserId()) + System.currentTimeMillis();
      request("/assetservice/import/v1/submit_img", new JSONObject().put("task_sn", serial).put("img_base64", base64));
      JSONObject response = request("/assetservice/import/v1/add_task", new JSONObject()
          .put("source", 3).put("task_type", 1).put("task_sn", serial)
          .put("listid", destination.getLong("listId")).put("list_name", destination.getString("name")));
      JSONObject remembered = remember(response, "歌单截图", "");
      JSONArray saved = remembered.getJSONArray("tasks");
      saved.getJSONObject(0).put("cloudId", target);
      save(saved);
      return complete(remembered.getString("submittedId"));
    }
  }

  private JSONObject remember(JSONObject response, String source, String fingerprint) throws Exception {
    JSONObject data = response.optJSONObject("data");
    if (data == null) data = response;
    String id = first(data, "id", "task_id", "taskid");
    if (!id.matches("[0-9]{1,20}")) throw new Exception("导入失败：平台未返回有效的任务编号");
    JSONArray history = history();
    JSONArray saved = new JSONArray().put(new JSONObject().put("id", id).put("source", source)
        .put("state", 0).put("createdAt", System.currentTimeMillis()).put("message", "正在导入")
        .put("fingerprint", fingerprint));
    for (int index = 0; index < Math.min(19, history.length()); index++)
      if (!history.getJSONObject(index).getString("id").equals(id)) saved.put(history.getJSONObject(index));
    save(saved);
    return new JSONObject().put("tasks", saved).put("submittedId", id);
  }

  private JSONObject refresh() throws Exception {
    JSONArray tasks = history();
    JSONArray ids = new JSONArray();
    for (int index = 0; index < tasks.length(); index++)
      ids.put((Object) new java.math.BigInteger(tasks.getJSONObject(index).getString("id")));
    if (ids.length() == 0) return new JSONObject().put("tasks", tasks);
    JSONObject response = request("/assetservice/import/v1/query_task_status", new JSONObject().put("ids", ids));
    JSONArray rows = rows(response.opt("data"));
    if (rows.length() == 0) throw new Exception("平台暂未返回这些任务的进度，请稍后手动刷新");
    JSONObject cloud = new CloudPlaylistRepository(api).execute("list", new JSONObject());
    JSONArray cloudLists = cloud.getJSONArray("playlists");
    for (int index = 0; index < tasks.length(); index++) {
      JSONObject task = tasks.getJSONObject(index);
      for (int position = 0; position < rows.length(); position++) {
        JSONObject row = rows.optJSONObject(position);
        if (row == null || !first(row, "id", "task_id", "taskid").equals(task.getString("id"))) continue;
        int state = row.optInt("status", row.optInt("task_status", -1));
        if (state < 0) continue;
        task.put("state", state).put("listId", first(row, "listid", "list_id"))
            .put("name", first(row, "list_name", "name"))
            .put("message", first(row, "error_msg", "err_msg", "msg", "message"));
        task.put("confirmed", false);
        if (state == 3) {
          for (int cloudIndex = 0; cloudIndex < cloudLists.length(); cloudIndex++) {
            JSONObject list = cloudLists.getJSONObject(cloudIndex);
            if ((list.getString("id").equals(task.optString("cloudId"))
                || list.getString("listId").equals(task.optString("listId"))
                || list.optString("globalId").equals(task.optString("listId"))) && list.optInt("count") > 0) {
              task.put("confirmed", true).put("cloudId", list.getString("id")).put("imported", list.getInt("count"));
              break;
            }
          }
          if (!task.optBoolean("confirmed")) task.put("message", "正在确认歌曲保存结果");
        }
      }
    }
    save(tasks);
    return new JSONObject().put("tasks", tasks);
  }

  private JSONObject result(String id, int page) throws Exception {
    JSONObject task = null;
    JSONArray tasks = history();
    for (int index = 0; index < tasks.length(); index++)
      if (tasks.getJSONObject(index).getString("id").equals(id)) task = tasks.getJSONObject(index);
    if (task == null || task.optInt("state") != 3 || task.optString("listId").isEmpty())
      throw new Exception("导入尚未完成或平台未返回目标歌单，请先刷新进度");
    JSONObject response = request("/pubsongs/v1/query_task", new JSONObject().put("listid", task.getString("listId"))
        .put("page", Math.max(1, Math.min(100, page))).put("pagesize", 30).put("show_missed", 0));
    JSONObject data = response.optJSONObject("data");
    if (data == null) data = response;
    JSONArray raw = rows(data), items = new JSONArray();
    for (int index = 0; index < raw.length(); index++) {
      JSONObject row = raw.optJSONObject(index);
      if (row == null) continue;
      String hash = first(row, "hash", "filehash", "FileHash");
      String matched = hash.matches("[a-fA-F0-9]{32}") ? "matched"
          : row.optInt("is_missed", -1) == 1 ? "missed" : "unknown";
      items.put(new JSONObject().put("name", first(row, "songname", "song_name", "name", "filename"))
          .put("artist", first(row, "singername", "singer_name", "author_name"))
          .put("match", matched).put("reason", first(row, "reason", "error_msg", "msg")));
    }
    return new JSONObject().put("items", items).put("page", page).put("more", raw.length() >= 30)
        .put("matched", data.optInt("imported_num", data.optInt("match_count", data.optInt("matched_count", -1))))
        .put("missed", data.optInt("missed_num", data.optInt("missed_count", data.optInt("miss_count", -1))))
        .put("total", data.optInt("songs_num", -1));
  }

  private JSONObject request(String path, JSONObject body) throws Exception {
    String token = ownerToken;
    JSONObject response = api.importRequest(path, body, token);
    lastResponse = response;
    api.checkSuccess(response, "平台暂不支持此导入请求，请检查链接、权限或验证状态");
    if (!api.accountToken().equals(token)) throw new Exception("账号已变化，请重新打开导入记录");
    return response;
  }

  private JSONObject complete(String id) throws Exception {
    long deadline = System.currentTimeMillis() + 60000;
    try {
      for (int attempt = 0; attempt < 25 && System.currentTimeMillis() < deadline; attempt++) {
        if (!active.getAsBoolean()) return new JSONObject().put("paused", true);
        JSONArray saved = refresh().getJSONArray("tasks");
        JSONObject task = null;
        for (int index = 0; index < saved.length(); index++)
          if (saved.getJSONObject(index).getString("id").equals(id)) task = saved.getJSONObject(index);
        if (task == null) throw new Exception("导入会话已失效，请重新提交");
        if (task.optInt("state") >= 10) throw new Exception(task.optString("message", "平台未能解析这个歌单链接"));
        if (task.optInt("state") == 3) return materialize(task);
        Thread.sleep(1200);
      }
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
      return new JSONObject().put("paused", true);
    }
    throw new Exception("平台处理超时，本次未能完成导入");
  }

  private JSONObject materialize(JSONObject task) throws Exception {
    if (!active.getAsBoolean()) return new JSONObject().put("paused", true);
    JSONObject response = request("/pubsongs/v1/query_task", new JSONObject().put("listid", task.getString("listId"))
        .put("page", 1).put("pagesize", 30).put("show_missed", 0));
    JSONObject detail = response.getJSONObject("data");
    int expected = detail.optInt("imported_num", -1);
    if (expected <= 0) throw new Exception("没有匹配到可导入的歌曲，请检查歌单是否公开、来源是否受支持");
    if (expected > 3000) throw new Exception("歌单超过 3000 首，请拆分后导入");
    JSONArray source = new JSONArray();
    for (int page = 1; page <= 10; page++) {
      if (!active.getAsBoolean()) return new JSONObject().put("paused", true);
      JSONObject tracksResponse = api.ownedCatalogRequest("https://gateway.kugou.com/v4/get_list_all_file_v3",
          "cloudlist.service.kugou.com", new TreeMap<>(), new JSONObject().put("listid", task.getString("listId"))
              .put("type", 0).put("userid", owner).put("token", ownerToken).put("page", page).put("pagesize", 300)
              .put("area_code", 1).put("allplatform", 1).put("show_cover", 1), ownerToken);
      api.checkSuccess(tracksResponse, "未能读取平台匹配后的歌曲，导入失败");
      JSONArray rows = rows(tracksResponse.opt("data"));
      for (int index = 0; index < rows.length(); index++) source.put(CloudPlaylistRepository.track(rows.getJSONObject(index)));
      if (rows.length() < 300 || source.length() >= expected) break;
    }
    if (source.length() != expected) throw new Exception("平台返回的歌曲不完整，导入未完成");
    Set<String> sourceKeys = new HashSet<>();
    for (int index = 0; index < source.length(); index++) {
      String hash = source.getJSONObject(index).optString("hash");
      if (!hash.matches("[a-f0-9]{32}")) throw new Exception("部分歌曲缺少可用的资源标识，导入失败");
      sourceKeys.add(trackKey(source.getJSONObject(index)));
    }
    CloudPlaylistRepository repository = new CloudPlaylistRepository(api);
    JSONObject lists = repository.execute("list", new JSONObject());
    String target = task.optString("cloudId");
    boolean exists = false;
    for (int index = 0; index < lists.getJSONArray("playlists").length(); index++)
      if (lists.getJSONArray("playlists").getJSONObject(index).getString("id").equals(target)) exists = true;
    if (!exists) {
      String name = first(detail, "name");
      if (name.isEmpty()) name = "导入的歌单";
      if (name.length() > 40) name = name.substring(0, 40);
      lists = repository.execute("create", new JSONObject().put("accountKey", lists.getString("accountKey"))
          .put("totalVersion", lists.getLong("totalVersion")).put("name", name).put("private", true));
      target = lists.getString("createdId");
      task.put("cloudId", target).put("confirmed", false);
      saveTask(task);
    }
    JSONObject tracks = repository.execute("tracks", new JSONObject().put("accountKey", lists.getString("accountKey")).put("id", target));
    Set<String> existing = new HashSet<>();
    for (int index = 0; index < tracks.getJSONArray("tracks").length(); index++)
      addIdentity(existing, tracks.getJSONArray("tracks").getJSONObject(index));
    JSONArray batch = new JSONArray();
    for (int index = 0; index < source.length(); index++) {
      if (!active.getAsBoolean()) return new JSONObject().put("paused", true);
      JSONObject song = source.getJSONObject(index);
      if (!containsIdentity(existing, song)) {
        addIdentity(existing, song);
        batch.put(song);
      }
      if (batch.length() == 100 || (index == source.length() - 1 && batch.length() > 0)) {
        tracks = repository.execute("addTracks", new JSONObject().put("accountKey", lists.getString("accountKey"))
            .put("id", target).put("listVersion", tracks.getLong("listVersion")).put("tracks", batch));
        batch = new JSONArray();
      }
    }
    tracks = repository.execute("tracks", new JSONObject().put("accountKey", lists.getString("accountKey")).put("id", target));
    Set<String> confirmed = new HashSet<>();
    for (int index = 0; index < tracks.getJSONArray("tracks").length(); index++)
      addIdentity(confirmed, tracks.getJSONArray("tracks").getJSONObject(index));
    for (int index = 0; index < source.length(); index++)
      if (!containsIdentity(confirmed, source.getJSONObject(index))) throw new Exception("歌曲未能完整保存，导入失败");
    task.put("confirmed", true).put("cloudId", target).put("state", 3);
    saveTask(task);
    return new JSONObject().put("success", true).put("cloudId", target).put("name", detail.optString("name", "导入的歌单"))
        .put("count", sourceKeys.size()).put("missed", detail.optInt("missed_num"));
  }

  private static String trackKey(JSONObject track) {
    String audioId = track.optString("audioId");
    // 平台保存时可能规范化文件 hash，同一音频以 mixsongid 核对。
    return audioId.matches("[1-9][0-9]*") ? "audio-" + audioId : "hash-" + track.optString("hash");
  }

  static void addIdentity(Set<String> keys, JSONObject track) {
    keys.add(trackKey(track));
    String hash = track.optString("hash");
    if (hash.matches("[a-f0-9]{32}")) keys.add("hash-" + hash);
  }

  static boolean containsIdentity(Set<String> keys, JSONObject track) {
    return keys.contains(trackKey(track)) || keys.contains("hash-" + track.optString("hash"));
  }

  private void saveTask(JSONObject task) throws Exception {
    JSONArray saved = history();
    for (int index = 0; index < saved.length(); index++)
      if (saved.getJSONObject(index).getString("id").equals(task.getString("id"))) { saved.put(index, task); break; }
    save(saved);
  }

  private void requireAccount() throws Exception {
    JSONObject identity = api.accountIdentity();
    owner = identity.optLong("userid");
    ownerToken = identity.optString("token");
    if (owner <= 0 || ownerToken.isEmpty()) throw new Exception("请先登录酷狗账号，再导入歌单");
  }

  private JSONArray history() throws Exception { return new JSONArray(preferences.getString(key(), "[]")); }
  private String key() { return "tasks-" + owner; }
  private void save(JSONArray tasks) { preferences.edit().putString(key(), tasks.toString()).apply(); }

  static String link(String input) throws Exception {
    input = input.replace("https\\://", "https://").replace("http\\://", "http://");
    java.util.regex.Matcher match = java.util.regex.Pattern.compile("https?://[^\\s<>\\\"]+").matcher(input.trim());
    if (!match.find()) throw new Exception("请粘贴完整的公开歌单分享链接");
    String link = match.group().replaceAll("[，。；！）】]+$", "");
    URI uri = new URI(link);
    String host = uri.getHost();
    if (link.length() > 2000 || host == null || uri.getUserInfo() != null || !host.contains(".")
        || host.matches("[0-9.]+") || host.endsWith(".local") || host.endsWith(".localhost"))
      throw new Exception("请输入音乐平台的公开歌单分享链接");
    return link;
  }

  private static String first(JSONObject row, String... keys) {
    for (String key : keys) {
      Object value = row.opt(key);
      if (value instanceof String || value instanceof Number) return value.toString().replaceAll("<[^>]*>", "").trim();
    }
    return "";
  }

  private static JSONArray rows(Object value) {
    if (value instanceof JSONArray) return (JSONArray) value;
    if (value instanceof JSONObject) for (String key : new String[] {"list", "info", "tasks", "task_list", "songs", "data"}) {
      JSONArray found = rows(((JSONObject) value).opt(key));
      if (found.length() > 0) return found;
    }
    return new JSONArray();
  }
}
