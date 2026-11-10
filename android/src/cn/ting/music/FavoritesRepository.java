package cn.ting.music;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** 云端喜欢歌单是唯一在线收藏来源，缓存只减少重复读取。 */
final class FavoritesRepository {
  private static String token = "";
  private static long updated;
  private static long listId;
  private static final Map<String, JSONObject> songs = new LinkedHashMap<>();
  private static final Map<String, Long> fileIds = new LinkedHashMap<>();
  private final KugouApi api;

  FavoritesRepository(KugouApi api) {
    this.api = api;
  }

  private JSONArray list(JSONObject response) {
    JSONObject data = response.optJSONObject("data");
    if (data == null) return new JSONArray();
    JSONArray result = data.optJSONArray("info");
    if (result == null) result = data.optJSONArray("songs");
    if (result == null) result = data.optJSONArray("list");
    return result == null ? new JSONArray() : result;
  }

  synchronized JSONArray read(boolean refresh) throws Exception {
    synchronized (FavoritesRepository.class) {
      String currentToken = api.accountToken();
      if (currentToken.isEmpty()) throw new Exception("请先登录酷狗账号以同步喜欢的音乐");
      if (!refresh && token.equals(currentToken) && System.currentTimeMillis() - updated < 300000)
        return new JSONArray(songs.values());
      JSONArray lists = list(api.cloudPlaylists());
      listId = 0;
      for (int index = 0; index < lists.length(); index++) {
        JSONObject item = lists.getJSONObject(index);
        if (item.optString("name").equals("我喜欢")) {
          listId = item.optLong("listid");
          break;
        }
      }
      if (listId == 0)
        for (int index = 0; index < lists.length(); index++) {
          JSONObject item = lists.getJSONObject(index);
          if (item.optInt("is_def") == 1) {
            listId = item.optLong("listid");
            break;
          }
        }
      if (listId == 0) throw new Exception("未找到酷狗账号的我喜欢歌单");
      Map<String, JSONObject> loaded = new LinkedHashMap<>();
      Map<String, Long> ids = new LinkedHashMap<>();
      for (int page = 1; page <= 20; page++) {
        JSONObject body =
            new JSONObject()
                .put("listid", listId)
                .put("userid", api.accountUserId())
                .put("type", 0)
                .put("page", page)
                .put("pagesize", 300)
                .put("area_code", 1)
                .put("allplatform", 1)
                .put("show_cover", 1)
                .put("token", currentToken);
        JSONObject response =
            api.catalogRequest(
                "https://gateway.kugou.com/v4/get_list_all_file_v3",
                "cloudlist.service.kugou.com",
                new TreeMap<>(),
                body);
        api.checkSuccess(response, "喜欢歌单同步失败");
        JSONArray rows = list(response);
        for (int index = 0; index < rows.length(); index++) {
          JSONObject raw = rows.getJSONObject(index);
          JSONObject audio = raw.optJSONObject("audio_info");
          if (audio == null) audio = raw;
          String hash =
              audio
                  .optString("hash", raw.optString("hash", raw.optString("filehash")))
                  .toLowerCase(java.util.Locale.ROOT);
          if (!hash.isEmpty()) {
            loaded.put(hash, raw);
            ids.put(hash, raw.optLong("fileid", raw.optLong("file_id")));
          }
        }
        if (rows.length() < 300) break;
      }
      songs.clear();
      songs.putAll(loaded);
      fileIds.clear();
      fileIds.putAll(ids);
      token = currentToken;
      updated = System.currentTimeMillis();
      return new JSONArray(songs.values());
    }
  }

  boolean contains(String hash) throws Exception {
    read(false);
    synchronized (FavoritesRepository.class) {
      return songs.containsKey(hash.toLowerCase(java.util.Locale.ROOT));
    }
  }

  private String matchingKey(JSONObject track) {
    String hash = track.optString("hash").toLowerCase(java.util.Locale.ROOT);
    if (songs.containsKey(hash)) return hash;
    String audioId = track.optString("audioId");
    if (audioId.isEmpty() || audioId.equals("0")) return "";
    for (Map.Entry<String, JSONObject> entry : songs.entrySet()) {
      JSONObject song = entry.getValue();
      if (audioId.equals(song.optString("mixsongid", song.optString("album_audio_id"))))
        return entry.getKey();
    }
    return "";
  }

  boolean containsTrack(JSONObject track) throws Exception {
    synchronized (FavoritesRepository.class) {
      read(false);
      return !matchingKey(track).isEmpty();
    }
  }

  boolean set(JSONObject track, boolean liked) throws Exception {
    synchronized (FavoritesRepository.class) {
      read(false);
      String hash = track.optString("hash").toLowerCase(java.util.Locale.ROOT);
      if (!hash.matches("[a-f0-9]{32}")) throw new Exception("本地音乐暂不能同步到酷狗喜欢歌单");
      if ((!matchingKey(track).isEmpty()) == liked) return liked;
      JSONObject body =
          new JSONObject()
              .put("userid", api.accountUserId())
              .put("token", api.accountToken())
              .put("listid", listId)
              .put("list_ver", 0)
              .put("type", 0);
      JSONObject response;
      if (liked) {
        JSONObject item =
            new JSONObject()
                .put("number", 1)
                .put("name", track.optString("artist") + " - " + track.optString("title"))
                .put("hash", hash)
                .put("size", 0)
                .put("sort", 0)
                .put("timelen", 0)
                .put("bitrate", 0)
                .put("album_id", parse(track.optString("albumId")))
                .put("mixsongid", parse(track.optString("audioId")));
        body.put("slow_upload", 1)
            .put("scene", "false;null")
            .put("data", new JSONArray().put(item));
        TreeMap<String, String> query = new TreeMap<>();
        query.put("last_time", String.valueOf(System.currentTimeMillis() / 1000));
        query.put("last_area", "gztx");
        response =
            api.catalogRequest(
                "https://gateway.kugou.com/cloudlist.service/v6/add_song", "", query, body);
      } else {
        long fileId = fileIds.getOrDefault(matchingKey(track), 0L);
        if (fileId == 0) {
          read(true);
          fileId = fileIds.getOrDefault(matchingKey(track), 0L);
        }
        if (fileId == 0) throw new Exception("暂未获取到云端收藏编号，请刷新喜欢歌单后重试");
        body.put("data", new JSONArray().put(new JSONObject().put("fileid", fileId)));
        response =
            api.catalogRequest(
                "https://gateway.kugou.com/v4/delete_songs",
                "cloudlist.service.kugou.com",
                new TreeMap<>(),
                body);
      }
      api.checkSuccess(response, "喜欢状态同步失败");
      updated = 0;
      for (int attempt = 0; attempt < 3; attempt++) {
        if (attempt > 0) Thread.sleep(attempt * 500L);
        read(true);
        if ((!matchingKey(track).isEmpty()) == liked) return liked;
      }
      throw new Exception("收藏请求已提交，云端同步暂未完成，请刷新我喜欢确认");
    }
  }

  private long parse(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException error) {
      return 0;
    }
  }
}
