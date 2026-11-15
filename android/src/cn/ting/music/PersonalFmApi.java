package cn.ting.music;

import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** 私人 FM 协议与响应白名单，账号和推荐标记只保留在原生层。 */
final class PersonalFmApi {
  private final KugouApi api;

  PersonalFmApi(KugouApi api) {
    this.api = api;
  }

  JSONObject request(String action, JSONObject track, int remaining) throws Exception {
    return request(action, track, remaining, api.accountUserId());
  }

  JSONObject request(String action, JSONObject track, int remaining, long expectedUser) throws Exception {
    JSONObject identity = api.accountIdentity();
    long user = identity.optLong("userid");
    if (user != expectedUser) throw new Exception("账号已变化，请重新开启随心听");
    if (!action.equals("login") && !action.equals("play") && !action.equals("garbage"))
      throw new Exception("随心听操作无效");
    long time = System.currentTimeMillis();
    JSONObject body = new JSONObject()
        .put("appid", 3116).put("clientver", 11440).put("clienttime", time)
        .put("mid", api.deviceMid()).put("action", action)
        .put("recommend_source_locked", 0).put("song_pool_id", 0)
        .put("callerid", 0).put("m_type", 1).put("platform", "ios")
        .put("area_code", 1).put("remain_songcnt", Math.max(0, Math.min(3000, remaining)))
        .put("is_overplay", 0).put("mode", "normal")
        .put("fakem", "ca981cfc583a4c37f28d2d49000013c16a0a")
        .put("key", KugouApi.md5("3116LnT6xpN3khm36zse0QzvmgTZ3waWdRSA11440" + time));
    if (user > 0) body.put("userid", user).put("kguid", user).put("token", identity.optString("token"));
    if (track != null) {
      body.put("hash", track.optString("hash"));
      String songId = track.optString("audioId");
      if (songId.matches("[0-9]{1,18}")) body.put("songid", songId);
      if (track.has("fmMark")) body.put("cur_mark", track.get("fmMark"));
    }
    if (user > 0) return api.ownedCatalogRequest("https://gateway.kugou.com/v2/personal_recommend",
        "persnfm.service.kugou.com", new TreeMap<>(), body, identity.getString("token"));
    return api.catalogRequest("https://gateway.kugou.com/v2/personal_recommend", "persnfm.service.kugou.com", new TreeMap<>(), body);
  }

  JSONArray songs(JSONObject response) throws Exception {
    return songs(response, false);
  }

  JSONArray songs(JSONObject response, boolean allowEmpty) throws Exception {
    api.checkSuccess(response, "随心听暂不可用，请稍后重试");
    Object data = response.opt("data");
    JSONArray rows = data instanceof JSONArray ? (JSONArray) data : null;
    if (data instanceof JSONObject) {
      JSONObject object = (JSONObject) data;
      for (String key : new String[] {"song_list", "songlist", "songs", "list"})
        if (object.optJSONArray(key) != null) { rows = object.optJSONArray(key); break; }
    }
    if (rows == null) {
      if (allowEmpty) return new JSONArray();
      throw new Exception("平台未返回随心听歌曲，请稍后重试");
    }
    JSONArray songs = new JSONArray();
    for (int index = 0; index < Math.min(rows.length(), 30); index++) {
      JSONObject raw = rows.optJSONObject(index);
      if (raw == null) continue;
      JSONObject song = normalize(raw);
      if (song != null) {
        if (!song.has("fmMark") && data instanceof JSONObject
            && ((JSONObject) data).has("cur_mark"))
          song.put("fmMark", ((JSONObject) data).get("cur_mark"));
        songs.put(song);
      }
    }
    return songs;
  }

  static JSONObject normalize(JSONObject raw) throws Exception {
    String hash = first(raw, "hash", "FileHash", "filehash").toLowerCase(java.util.Locale.ROOT);
    if (!hash.matches("[a-f0-9]{32}")) return null;
    String filename = first(raw, "filename", "FileName", "file_name");
    int split = filename.indexOf(" - ");
    String title = first(raw, "songname", "song_name", "SongName", "audio_name");
    if (title.isEmpty()) title = split >= 0 ? filename.substring(split + 3) : filename;
    String artist = first(raw, "singername", "singer_name", "SingerName", "author_name");
    if (artist.isEmpty()) artist = split >= 0 ? filename.substring(0, split) : "未知歌手";
    String cover = first(raw, "imgurl", "sizable_cover", "album_cover", "cover");
    JSONObject trans = raw.optJSONObject("trans_param");
    if (cover.isEmpty() && trans != null) cover = first(trans, "union_cover");
    if (cover.startsWith("http://")) cover = "https://" + cover.substring(7);
    if (!cover.startsWith("https://")) cover = "";
    JSONObject song = new JSONObject().put("id", hash).put("hash", hash)
        .put("title", title.replaceAll("(?i)\\.(mp3|flac|m4a|ogg|wav|aac|ape)$", "").trim())
        .put("artist", artist).put("album", first(raw, "album_name", "albumname", "AlbumName"))
        .put("cover", cover.replace("{size}", "400"))
        .put("albumId", first(raw, "album_id", "albumid", "AlbumID"))
        .put("audioId", first(raw, "album_audio_id", "mixsongid", "songid", "MixSongID"))
        .put("duration", raw.optDouble("duration", raw.optDouble("time_length", raw.optDouble("timelen", 0))));
    if (raw.has("cur_mark")) song.put("fmMark", raw.get("cur_mark"));
    return song;
  }

  private static String first(JSONObject raw, String... keys) {
    for (String key : keys) {
      Object value = raw.opt(key);
      if (value instanceof String || value instanceof Number) {
        String text = value.toString().replaceAll("<[^>]*>", "").trim();
        if (!text.isEmpty()) return text;
      }
    }
    return "";
  }
}
