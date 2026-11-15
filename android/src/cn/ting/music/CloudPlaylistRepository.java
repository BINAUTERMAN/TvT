package cn.ting.music;

import java.util.*;
import org.json.*;

/** 云歌单写入前校验账号、归属与版本，写入后重新读取确认。 */
final class CloudPlaylistRepository {
  private static final Object LOCK = new Object();
  private static String sessionToken = "";
  private static String accountKey = "";
  private static JSONObject confirmedLists;
  private static long confirmedUntil;
  private static JSONObject latestLists;
  private static long latestListsAt;
  private static final LinkedHashMap<String, String> artwork = new LinkedHashMap<>();
  private final KugouApi api;
  private String requestToken;
  private JSONObject lastCreateResponse;
  private JSONObject lastTracksResponse;
  private JSONObject lastReceiptResponse;

  CloudPlaylistRepository(KugouApi api) { this.api = api; }

  JSONObject execute(String operation, JSONObject input) throws Exception {
    synchronized (LOCK) {
      String token = api.accountToken();
      requestToken = token;
      if (token.isEmpty() || api.accountUserId() <= 0) throw new Exception("请先登录酷狗账号");
      if (!sessionToken.equals(token)) {
        sessionToken = token;
        accountKey = UUID.randomUUID().toString();
        confirmedLists = null;
        latestLists = null;
        artwork.clear();
      }
      if (!operation.equals("list") && !accountKey.equals(input.optString("accountKey")))
        throw new Exception("账号已变化，请刷新云端歌单后重试");
      JSONObject lists = operation.equals("cover") && latestLists != null && System.currentTimeMillis() - latestListsAt < 30000
          ? new JSONObject(latestLists.toString()) : readLists();
      if (operation.equals("list")) return lists;
      checkAccount(token);
      if (operation.equals("create") || operation.equals("collect")) return create(operation, input, lists, token);
      if (operation.equals("sortLists")) return sortLists(input, lists, token);
      JSONObject list = find(lists.getJSONArray("playlists"), input.optString("id"));
      if (operation.equals("cover")) return cover(list);
      if (operation.equals("tracks")) {
        JSONObject tracks = readTracks(list);
        checkAccount(token);
        return tracks;
      }
      if (list.optBoolean("isDefault")) throw new Exception("请通过“我喜欢”管理默认歌单");
      if (list.optBoolean("isSystem") && (operation.equals("delete") || operation.equals("update")))
        throw new Exception("操作失败：这是酷狗系统歌单，不支持删除或修改歌单信息");
      if (operation.equals("delete")) {
        requireVersion(input, "totalVersion", lists.optLong("totalVersion"));
        JSONObject response = cloudRequest("/v2/delete_list", new JSONObject()
            .put("listid", list.getLong("listId")).put("type", list.getInt("type"))
            .put("total_ver", lists.getLong("totalVersion")));
        api.checkSuccess(response, "云歌单删除失败");
        rememberReceipt(response, lists, list.getString("id"));
        checkAccount(token);
        return confirmLists(after -> {
          for (int index = 0; index < after.getJSONArray("playlists").length(); index++)
            if (after.getJSONArray("playlists").getJSONObject(index).getString("id").equals(list.getString("id"))) return false;
          return true;
        }, "删除失败：平台同步超时，未能确认歌单已删除");
      }
      if (list.getInt("type") != 0) throw new Exception("收藏歌单只能播放、排序或取消收藏，不能修改原歌单");
      if (operation.equals("update")) {
        requireVersion(input, "totalVersion", lists.optLong("totalVersion"));
        return update(input, lists, list, token);
      }
      if (operation.equals("addTracks") || operation.equals("removeTracks") || operation.equals("sortTracks"))
        return modifyTracks(operation, input, list, token);
      throw new Exception("云歌单操作无效");
    }
  }

  private JSONObject readLists() throws Exception {
    checkAccount(requestToken);
    List<JSONObject> lists = new ArrayList<>();
    long version = 0;
    for (int page = 1; page <= 30; page++) {
      JSONObject data = api.cloudPlaylists(page).getJSONObject("data");
      version = data.optLong("total_ver", version);
      JSONArray rows = rows(data);
      for (int index = 0; index < rows.length(); index++) {
        JSONObject raw = rows.getJSONObject(index);
        if (raw.optInt("is_del") == 1) continue;
        long listId = raw.optLong("listid");
        int type = raw.optInt("type");
        if (listId <= 0 || (type != 0 && type != 1)) continue;
        String image = text(raw, "imgurl", "flexible_cover", "cover", "pic");
        lists.add(new JSONObject().put("id", "remote-" + type + "-" + listId)
            .put("listId", String.valueOf(listId)).put("type", type)
            .put("name", text(raw, "name", "listname"))
            .put("description", text(raw, "intro", "description"))
            .put("tags", raw.optString("tags"))
            .put("cover", image.replace("http:", "https:").replace("{size}", "400"))
            .put("count", raw.optInt("count", raw.optInt("songcount", raw.optInt("song_count"))))
            .put("isDefault", raw.optInt("is_def") == 2)
            .put("isSystem", raw.optInt("is_def") == 1 || raw.optInt("is_def") == 2)
            .put("sort", raw.optInt("sort")).put("version", raw.optLong("list_ver"))
            .put("globalId", text(raw, "global_collection_id", "list_create_gid"))
            .put("sourceId", raw.optString("list_create_gid")));
      }
      if (rows.length() < 100) break;
      if (page == 30) throw new Exception("读取失败：歌单数量超过当前支持的 3000 张上限");
    }
    lists.sort(Comparator.comparingInt((JSONObject item) -> item.optInt("type"))
        .thenComparingInt(item -> item.optInt("sort")));
    checkAccount(requestToken);
    JSONObject result = new JSONObject().put("accountKey", accountKey).put("totalVersion", version)
        .put("playlists", new JSONArray(lists));
    if (confirmedLists != null && System.currentTimeMillis() < confirmedUntil
        && version <= confirmedLists.optLong("totalVersion")) result = new JSONObject(confirmedLists.toString());
    latestLists = new JSONObject(result.toString());
    latestListsAt = System.currentTimeMillis();
    return result;
  }

  private JSONObject readTracks(JSONObject list) throws Exception {
    JSONArray tracks = new JSONArray();
    long version = 0;
    for (int page = 1; page <= 10; page++) {
      JSONObject response = catalogRequest("https://gateway.kugou.com/v4/get_list_all_file_v3",
          "cloudlist.service.kugou.com", new TreeMap<>(), new JSONObject()
              .put("listid", list.getLong("listId")).put("type", list.getInt("type"))
              .put("userid", api.accountUserId()).put("token", api.accountToken())
              .put("page", page).put("pagesize", 300).put("area_code", 1)
              .put("allplatform", 1).put("show_cover", 1));
      lastTracksResponse = response;
      // 新建的空歌单尚无歌曲记录，使用平台已确认的初始版本。
      if (page == 1 && response.optInt("error_code") == 30203
          && list.optInt("count") == 0 && list.optLong("version") == 0)
        return new JSONObject().put("accountKey", accountKey).put("id", list.getString("id"))
            .put("listVersion", 0).put("tracks", new JSONArray()).put("count", 0);
      api.checkSuccess(response, "云歌单歌曲读取失败");
      JSONObject data = response.getJSONObject("data");
      long nextVersion = data.optLong("list_ver");
      if (page > 1 && version != nextVersion) throw new Exception("读取失败：读取期间歌单版本发生变化");
      version = nextVersion;
      int total = data.optInt("count", data.optInt("total", 0));
      JSONArray rows = rows(data);
      for (int index = 0; index < rows.length(); index++) tracks.put(track(rows.getJSONObject(index)));
      if (rows.length() < 300 || (total > 0 && tracks.length() >= total)) break;
      if (page == 10) throw new Exception("读取失败：歌单超过当前支持的 3000 首上限");
    }
    List<JSONObject> ordered = new ArrayList<>();
    for (int index = 0; index < tracks.length(); index++) ordered.add(tracks.getJSONObject(index));
    ordered.sort(Comparator.comparingLong((JSONObject item) -> item.optLong("sort")).reversed());
    if (confirmedLists != null) {
      JSONArray cached = confirmedLists.getJSONArray("playlists");
      for (int position = 0; position < cached.length(); position++) {
        JSONObject row = cached.getJSONObject(position);
        if (row.getString("id").equals(list.getString("id"))) {
          row.put("count", tracks.length());
          if (row.optString("cover").isEmpty() && !ordered.isEmpty()) row.put("cover", ordered.get(0).optString("cover"));
        }
      }
    }
    return new JSONObject().put("accountKey", accountKey).put("id", list.getString("id"))
        .put("listVersion", version).put("tracks", new JSONArray(ordered)).put("count", tracks.length());
  }

  private JSONObject cover(JSONObject list) throws Exception {
    String id = list.getString("id");
    String key = id + ":" + list.optLong("version") + ":" + list.optInt("count");
    String image = list.optString("cover");
    if (image.isEmpty()) image = artwork.get(key);
    if (image == null || image.isEmpty()) {
      JSONObject response = catalogRequest("https://gateway.kugou.com/v4/get_list_all_file_v3",
          "cloudlist.service.kugou.com", new TreeMap<>(), new JSONObject()
              .put("listid", list.getLong("listId")).put("type", list.getInt("type"))
              .put("page", 1).put("pagesize", 1).put("area_code", 1).put("allplatform", 1).put("show_cover", 1));
      api.checkSuccess(response, "歌单封面读取失败");
      JSONArray songs = rows(response.getJSONObject("data"));
      image = songs.length() == 0 ? "" : track(songs.getJSONObject(0)).optString("cover");
      artwork.put(key, image);
      while (artwork.size() > 64) artwork.remove(artwork.keySet().iterator().next());
    }
    checkAccount(requestToken);
    return new JSONObject().put("id", id).put("cover", image == null ? "" : image);
  }

  private JSONObject create(String operation, JSONObject input, JSONObject lists, String token) throws Exception {
    String name = name(input.optString("name"));
    boolean collecting = operation.equals("collect");
    JSONObject source = input.optJSONObject("source");
    if (collecting && (source == null || !source.optString("globalId").matches("[a-zA-Z0-9_-]{1,100}")))
      throw new Exception("尚未取得完整的原歌单信息，请重新打开详情后收藏");
    if (collecting) {
      JSONArray existing = lists.getJSONArray("playlists");
      for (int index = 0; index < existing.length(); index++) {
        JSONObject row = existing.getJSONObject(index);
        if (row.optInt("type") == 1 && row.optString("sourceId").equals(source.getString("globalId")))
          return lists.put("createdId", row.getString("id"));
      }
    }
    if (collecting) source = publicSource(source.getString("globalId"));
    JSONObject body = new JSONObject().put("userid", api.accountUserId()).put("token", token)
        .put("total_ver", lists.optLong("totalVersion")).put("name", name)
        .put("type", collecting ? 1 : 0).put("source", collecting ? 1 : 0)
        .put("is_pri", collecting ? 0 : input.optBoolean("private") ? 1 : 0)
        .put("list_create_userid", collecting ? source.optLong("userId") : 0)
        .put("list_create_listid", collecting ? source.optLong("listId") : 0)
        .put("list_create_gid", collecting ? source.optString("globalId") : "")
        .put("from_shupinmv", 0);
    TreeMap<String, String> query = new TreeMap<>();
    query.put("last_time", String.valueOf(System.currentTimeMillis() / 1000));
    query.put("last_area", "gztx");
    lastCreateResponse = catalogRequest("https://gateway.kugou.com/cloudlist.service/v5/add_list", "", query, body);
    api.checkSuccess(lastCreateResponse, "云歌单创建失败");
    rememberReceipt(lastCreateResponse, lists);
    checkAccount(token);
    Set<String> oldIds = new HashSet<>();
    for (int index = 0; index < lists.getJSONArray("playlists").length(); index++)
      oldIds.add(lists.getJSONArray("playlists").getJSONObject(index).getString("id"));
    return confirmLists(after -> {
      for (int index = 0; index < after.getJSONArray("playlists").length(); index++) {
        JSONObject row = after.getJSONArray("playlists").getJSONObject(index);
        if (!oldIds.contains(row.getString("id")) && row.getString("name").equals(name)) {
          after.put("createdId", row.getString("id"));
          return true;
        }
      }
      return false;
    }, "创建失败：平台同步超时，未能确认新增歌单");
  }

  private JSONObject update(JSONObject input, JSONObject lists, JSONObject list, String token) throws Exception {
    String name = name(input.optString("name"));
    String intro = input.optString("description").trim();
    if (intro.length() > 500) throw new Exception("歌单简介最多 500 字");
    JSONObject response = cloudRequest("/v1/modify_list", new JSONObject()
        .put("total_ver", lists.optLong("totalVersion")).put("listid", list.getLong("listId"))
        .put("type", 0).put("name", name).put("sort", list.optInt("sort"))
        .put("tags", list.optString("tags")).put("intro", intro));
    api.checkSuccess(response, "云歌单修改失败");
    checkAccount(token);
    confirmedUntil = 0;
    rememberReceipt(response, lists);
    return confirmLists(after -> {
      JSONObject confirmed = find(after.getJSONArray("playlists"), list.getString("id"));
      return confirmed.getString("name").equals(name) && confirmed.optString("description").equals(intro);
    }, "修改失败：平台返回的歌单信息与修改内容不一致");
  }

  private JSONObject sortLists(JSONObject input, JSONObject lists, String token) throws Exception {
    requireVersion(input, "totalVersion", lists.optLong("totalVersion"));
    JSONArray requested = input.getJSONArray("ids");
    JSONArray actual = lists.getJSONArray("playlists");
    Map<String, JSONObject> available = new LinkedHashMap<>();
    JSONArray resource = new JSONArray();
    for (int index = 0; index < actual.length(); index++) {
      JSONObject row = actual.getJSONObject(index);
      if (row.optBoolean("isDefault")) resource.put(new JSONObject().put("listid", row.getLong("listId"))
          .put("type", row.getInt("type")).put("sort", row.optInt("sort")));
      else available.put(row.getString("id"), row);
    }
    Set<String> unique = new HashSet<>();
    int[] sort = {1, 1};
    for (int index = 0; index < requested.length(); index++) {
      String id = requested.getString(index);
      JSONObject row = available.get(id);
      if (row == null || !unique.add(id)) throw new Exception("排序失败：歌单列表已发生变化");
      int type = row.getInt("type");
      resource.put(new JSONObject().put("listid", row.getLong("listId")).put("type", type).put("sort", sort[type]++));
    }
    if (unique.size() != available.size()) throw new Exception("排序失败：提交的歌单列表不完整");
    JSONObject response = cloudRequest("/v1/modify_list_sort", new JSONObject()
        .put("total_ver", lists.optLong("totalVersion")).put("data", resource));
    api.checkSuccess(response, "云歌单排序失败");
    checkAccount(token);
    confirmedUntil = 0;
    rememberReceipt(response, lists);
    return confirmLists(after -> {
    for (int type = 0; type <= 1; type++) {
      List<String> expected = new ArrayList<>(), confirmed = new ArrayList<>();
      for (int index = 0; index < requested.length(); index++)
        if (available.get(requested.getString(index)).optInt("type") == type) expected.add(requested.getString(index));
      for (int index = 0; index < after.getJSONArray("playlists").length(); index++) {
        JSONObject row = after.getJSONArray("playlists").getJSONObject(index);
        if (row.optInt("type") == type && !row.optBoolean("isDefault")) confirmed.add(row.getString("id"));
      }
      if (!expected.equals(confirmed)) return false;
    }
    return true;
    }, "平台未能确认云歌单顺序，排序失败");
  }

  private JSONObject modifyTracks(String operation, JSONObject input, JSONObject list, String token) throws Exception {
    JSONObject before = readTracks(list);
    requireVersion(input, "listVersion", before.optLong("listVersion"));
    JSONObject body = new JSONObject().put("listid", list.getLong("listId"))
        .put("type", 0).put("list_ver", before.getLong("listVersion"));
    JSONArray resource = new JSONArray();
    Set<String> requested = new LinkedHashSet<>();
    Map<String, String> requestedAudio = new HashMap<>();
    if (operation.equals("addTracks")) {
      JSONArray tracks = input.getJSONArray("tracks");
      if (tracks.length() < 1 || tracks.length() > 100) throw new Exception("一次添加 1 至 100 首歌曲");
      for (int index = 0; index < tracks.length(); index++) {
        JSONObject song = tracks.getJSONObject(index);
        String hash = song.optString("hash").toLowerCase(Locale.ROOT);
        if (!hash.matches("[a-f0-9]{32}") || !song.optString("uri").isEmpty())
          throw new Exception("添加失败：歌单仅支持酷狗曲库歌曲");
        requested.add(hash);
        requestedAudio.put(hash, song.optString("audioId"));
        resource.put(new JSONObject().put("number", 1).put("name", song.optString("artist") + " - " + song.optString("title"))
            .put("hash", hash).put("size", 0).put("sort", 0).put("timelen", 0).put("bitrate", 0)
            .put("album_id", number(song.optString("albumId"))).put("mixsongid", number(song.optString("audioId"))));
      }
      body.put("userid", api.accountUserId()).put("token", token)
          .put("slow_upload", 1).put("scene", "false;null").put("data", resource);
      api.checkSuccess(catalogRequest("https://gateway.kugou.com/cloudlist.service/v6/add_song", "", new TreeMap<>(), body), "歌曲添加失败");
    } else {
      JSONArray ids = input.getJSONArray("fileIds");
      Set<String> available = new HashSet<>();
      for (int index = 0; index < before.getJSONArray("tracks").length(); index++)
        available.add(before.getJSONArray("tracks").getJSONObject(index).optString("fileId"));
      for (int index = 0; index < ids.length(); index++) {
        String id = ids.getString(index);
        if (!available.contains(id) || !requested.add(id) || number(id) <= 0)
          throw new Exception("修改失败：歌曲列表已发生变化");
        resource.put(new JSONObject().put("fileid", number(id)).put("sort", ids.length() - index));
      }
      if (requested.isEmpty() || (operation.equals("sortTracks") && requested.size() != available.size()))
        throw new Exception("排序失败：提交的歌曲顺序不完整");
      body.put("data", resource);
      JSONObject response;
      if (operation.equals("sortTracks")) response = cloudRequest("/v1/modify_song_sort", body);
      else response = catalogRequest("https://gateway.kugou.com/v4/delete_songs", "cloudlist.service.kugou.com",
          new TreeMap<>(), body.put("userid", api.accountUserId()).put("token", token));
      api.checkSuccess(response, "歌曲修改失败");
      lastReceiptResponse = response;
    }
    checkAccount(token);
    JSONObject after = null;
    boolean confirmed = false;
    for (int attempt = 0; attempt < 3; attempt++) {
      if (attempt > 0) Thread.sleep(attempt * 700L);
      after = readTracks(list);
    Set<String> hashes = new HashSet<>(), files = new HashSet<>(), audioIds = new HashSet<>();
    List<String> order = new ArrayList<>();
    for (int index = 0; index < after.getJSONArray("tracks").length(); index++) {
      JSONObject song = after.getJSONArray("tracks").getJSONObject(index);
      hashes.add(song.optString("hash"));
      if (number(song.optString("audioId")) > 0) audioIds.add(song.optString("audioId"));
      files.add(song.optString("fileId"));
      order.add(song.optString("fileId"));
    }
    confirmed = operation.equals("addTracks") ? requested.stream().allMatch(hash -> hashes.contains(hash)
        || (number(requestedAudio.get(hash)) > 0 && audioIds.contains(requestedAudio.get(hash))))
        : operation.equals("removeTracks") ? Collections.disjoint(files, requested)
        : order.equals(new ArrayList<>(requested));
      if (confirmed) break;
    }
    if (!confirmed) throw new Exception("修改失败：平台返回的歌曲列表与操作结果不一致");
    return after.put("totalVersion", readLists().getLong("totalVersion"));
  }

  static JSONObject track(JSONObject raw) throws Exception {
    JSONObject audio = raw.optJSONObject("audio_info");
    if (audio == null) audio = raw;
    JSONObject base = raw.optJSONObject("base");
    if (base == null) base = raw;
    JSONObject album = raw.optJSONObject("album_info");
    if (album == null) album = raw.optJSONObject("albuminfo");
    if (album == null) album = new JSONObject();
    String hash = text(audio, "hash", "filehash", "FileHash", "hash_128").toLowerCase(Locale.ROOT);
    String filename = text(base, "filename", "file_name", "FileName", "audio_name", "name");
    int split = filename.indexOf(" - ");
    String title = text(base, "songname", "song_name", "SongName", "audio_name");
    if (title.isEmpty()) title = split >= 0 ? filename.substring(split + 3) : filename;
    String artist = text(base, "singername", "author_name", "SingerName");
    JSONArray authors = raw.optJSONArray("authors");
    if (artist.isEmpty() && authors != null && authors.optJSONObject(0) != null)
      artist = text(authors.getJSONObject(0), "author_name", "name");
    if (artist.isEmpty()) artist = split >= 0 ? filename.substring(0, split) : "未知歌手";
    String fileId = text(raw, "fileid", "file_id");
    String cover = text(album, "sizable_cover", "cover");
    if (cover.isEmpty()) cover = text(raw, "sizable_cover", "cover", "imgurl");
    return new JSONObject().put("id", hash.isEmpty() ? "cloud-file-" + fileId : hash)
        .put("hash", hash).put("fileId", fileId).put("sort", raw.optLong("sort")).put("title", title)
        .put("artist", artist).put("album", text(album, "album_name", "name"))
        .put("cover", cover.replace("http:", "https:").replace("{size}", "400"))
        .put("albumId", text(base, "album_id", "AlbumID"))
        .put("audioId", text(base, "album_audio_id", "mixsongid", "MixSongID"))
        .put("duration", audio.optDouble("duration", audio.optDouble("timelength", 0)));
  }

  private static JSONObject find(JSONArray rows, String id) throws Exception {
    for (int index = 0; index < rows.length(); index++)
      if (rows.getJSONObject(index).optString("id").equals(id)) return rows.getJSONObject(index);
    throw new Exception("操作失败：歌单已被删除或不属于当前账号");
  }

  private static JSONArray rows(JSONObject data) {
    for (String key : new String[] {"info", "songs", "list", "lists"})
      if (data.optJSONArray(key) != null) return data.optJSONArray(key);
    return new JSONArray();
  }

  private void checkAccount(String token) throws Exception {
    if (!api.accountToken().equals(token)) throw new Exception("账号已变化，请重新加载云端歌单");
  }

  private JSONObject catalogRequest(String endpoint, String router, TreeMap<String, String> query, JSONObject body) throws Exception {
    return api.ownedCatalogRequest(endpoint, router, query, body, requestToken);
  }

  private JSONObject cloudRequest(String path, JSONObject body) throws Exception {
    return api.cloudRequest(path, body, requestToken);
  }

  private static void requireVersion(JSONObject input, String key, long actual) throws Exception {
    if (!input.has(key) || input.optLong(key, -1) != actual)
      throw new Exception("操作失败：歌单版本冲突，其他设备已修改歌单");
  }

  private static String name(String value) throws Exception {
    String name = value.trim();
    if (name.isEmpty() || name.length() > 40) throw new Exception("歌单名称需要 1 至 40 字");
    return name;
  }

  private interface Confirmation { boolean matches(JSONObject response) throws Exception; }

  private void rememberReceipt(JSONObject response, JSONObject before) throws Exception {
    rememberReceipt(response, before, "");
  }

  private void rememberReceipt(JSONObject response, JSONObject before, String deletedId) throws Exception {
    lastReceiptResponse = response;
    JSONObject data = response.optJSONObject("data");
    if (data == null || !data.has("total_ver")) return;
    Object info = data.opt("info");
    JSONArray receipt = info instanceof JSONArray ? (JSONArray) info
        : info instanceof JSONObject ? new JSONArray().put(info) : null;
    if (receipt == null) return;
    Map<String, JSONObject> records = new LinkedHashMap<>();
    JSONArray previous = before.getJSONArray("playlists");
    for (int index = 0; index < previous.length(); index++) {
      JSONObject row = previous.getJSONObject(index);
      records.put(row.getString("id"), row);
    }
    for (int index = 0; index < receipt.length(); index++) {
      JSONObject raw = receipt.getJSONObject(index);
      long listId = raw.optLong("listid");
      int type = raw.optInt("type");
      if (listId <= 0 || (type != 0 && type != 1)) continue;
      String id = "remote-" + type + "-" + listId;
      if (raw.optInt("is_del") == 1 || (id.equals(deletedId) && raw.optInt("code") == 1)) { records.remove(id); continue; }
      JSONObject row = records.containsKey(id) ? new JSONObject(records.get(id).toString()) : new JSONObject();
      row.put("id", id).put("listId", String.valueOf(listId)).put("type", type);
      if (raw.has("name")) row.put("name", raw.getString("name"));
      if (raw.has("intro")) row.put("description", raw.optString("intro"));
      if (raw.has("tags")) row.put("tags", raw.optString("tags"));
      if (raw.has("count")) row.put("count", raw.optInt("count"));
      if (raw.has("sort")) row.put("sort", raw.optInt("sort"));
      if (raw.has("is_def")) row.put("isDefault", raw.optInt("is_def") == 2)
          .put("isSystem", raw.optInt("is_def") == 1 || raw.optInt("is_def") == 2);
      if (raw.has("global_collection_id")) row.put("globalId", raw.optString("global_collection_id"));
      if (raw.has("list_create_gid")) row.put("sourceId", raw.optString("list_create_gid"));
      String cover = text(raw, "imgurl", "flexible_cover", "cover", "pic");
      if (!cover.isEmpty()) row.put("cover", cover.replace("http:", "https:").replace("{size}", "400"));
      records.put(id, row);
    }
    List<JSONObject> ordered = new ArrayList<>(records.values());
    ordered.sort(Comparator.comparingInt((JSONObject item) -> item.optInt("type"))
        .thenComparingInt(item -> item.optInt("sort")));
    confirmedLists = new JSONObject().put("accountKey", accountKey)
        .put("totalVersion", data.getLong("total_ver")).put("playlists", new JSONArray(ordered));
    confirmedUntil = System.currentTimeMillis() + 15000;
  }

  private JSONObject publicSource(String globalId) throws Exception {
    JSONObject response = api.catalog("playlistDetail", new JSONObject().put("id", globalId));
    Object data = response.opt("data");
    JSONArray candidates = data instanceof JSONArray ? (JSONArray) data
        : data instanceof JSONObject ? rows((JSONObject) data) : new JSONArray();
    if (candidates.length() == 0) throw new Exception("平台未返回原歌单详情，请稍后重试");
    JSONObject row = candidates.getJSONObject(0);
    if (row.optJSONObject("listinfo") != null) row = row.getJSONObject("listinfo");
    return new JSONObject().put("globalId", globalId)
        .put("userId", number(text(row, "list_create_userid", "create_userid", "userid")))
        .put("listId", number(text(row, "list_create_listid", "listid", "specialid")));
  }

  private JSONObject confirmLists(Confirmation confirmation, String message) throws Exception {
    // 只在这次写入后做三次有界确认，不重复提交，也不创建后台轮询。
    for (int attempt = 0; attempt < 3; attempt++) {
      if (attempt > 0) Thread.sleep(attempt * 700L);
      JSONObject response = readLists();
      if (confirmation.matches(response)) return response;
    }
    throw new Exception(message);
  }

  private static long number(String value) {
    try { return Long.parseLong(value); } catch (NumberFormatException exception) { return 0; }
  }

  private static String text(JSONObject value, String... keys) {
    for (String key : keys) {
      Object item = value.opt(key);
      if (item instanceof String || item instanceof Number) {
        String result = item.toString().replaceAll("<[^>]*>", "").trim();
        if (!result.isEmpty()) return result;
      }
    }
    return "";
  }
}
