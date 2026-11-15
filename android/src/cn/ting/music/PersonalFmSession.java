package cn.ting.music;

import java.util.LinkedHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** 请求代次隔离暂停、切换队列与账号；有限去重，避免无限续曲累积内存。 */
final class PersonalFmSession {
  boolean active;
  boolean starting;
  boolean pending;
  boolean waiting;
  boolean disliking;
  boolean exhausted;
  long owner;
  String error = "";
  private volatile int epoch;
  private final LinkedHashMap<String, Object> recent = new LinkedHashMap<>();

  void start(long user) {
    stop();
    owner = user;
    starting = true;
  }

  void restore(long user, JSONArray queue) throws org.json.JSONException {
    stop();
    owner = user;
    active = true;
    filter(queue);
  }

  int begin() {
    pending = true;
    error = "";
    return ++epoch;
  }

  boolean accepts(int ticket, long user) {
    return ticket == epoch && owner == user && (active || starting);
  }

  void cancel() {
    epoch++;
    pending = false;
    starting = false;
    waiting = false;
    disliking = false;
  }

  void stop() {
    cancel();
    active = false;
    owner = 0;
    error = "";
    exhausted = false;
    recent.clear();
  }

  JSONArray filter(JSONArray songs) throws org.json.JSONException {
    JSONArray accepted = new JSONArray();
    for (int index = 0; index < songs.length(); index++) {
      JSONObject raw = songs.optJSONObject(index);
      if (raw == null) continue;
      String hash = raw.optString("hash").toLowerCase(java.util.Locale.ROOT);
      if (!hash.matches("[a-f0-9]{32}") || recent.containsKey(hash)) continue;
      JSONObject track = new JSONObject(raw.toString());
      Object mark = track.remove("fmMark");
      recent.put(hash, mark == null ? JSONObject.NULL : mark);
      while (recent.size() > 100) recent.remove(recent.keySet().iterator().next());
      accepted.put(track);
    }
    return accepted;
  }

  JSONObject requestTrack(JSONObject track) throws org.json.JSONException {
    if (track == null) return null;
    JSONObject result = new JSONObject(track.toString());
    Object mark = recent.get(track.optString("hash"));
    if (mark != null && mark != JSONObject.NULL) result.put("fmMark", mark);
    return result;
  }
}
