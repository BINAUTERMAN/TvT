package cn.ting.music;

import java.util.UUID;
import org.json.*;

public final class PersonalFmApiCheck {
  public static void main(String[] args) throws Exception {
    PersonalFmApi fm = new PersonalFmApi(new KugouApi(UUID.randomUUID().toString().replace("-", "")));
    JSONObject response = fm.request("login", null, 0);
    System.out.println("FM response status=" + response.opt("status")
        + " code=" + response.optInt("error_code", response.optInt("errcode")));
    Object data = response.opt("data");
    System.out.println("FM data keys=" + (data instanceof JSONObject ? ((JSONObject) data).keySet()
        : data instanceof JSONArray ? "array" : "none"));
    if (data instanceof JSONObject) {
      JSONObject object = (JSONObject) data;
      for (String key : object.keySet()) {
        JSONArray rows = object.optJSONArray(key);
        if (rows != null) System.out.println("FM array=" + key + " count=" + rows.length()
            + " first keys=" + (rows.optJSONObject(0) == null ? "none" : rows.getJSONObject(0).keySet()));
      }
    }
    JSONArray songs = fm.songs(response);
    if (songs.length() == 0) throw new AssertionError("No FM songs");
    System.out.println("PASS real anonymous FM response and normalized songs=" + songs.length());
    PersonalFmSession session = new PersonalFmSession();
    JSONArray clean = session.filter(songs);
    if (clean.toString().contains("fmMark") || clean.toString().contains("token"))
      throw new AssertionError("Private data leaked into queue");
    JSONObject next = fm.request("play", session.requestTrack(clean.getJSONObject(0)), 2);
    JSONArray following = session.filter(fm.songs(next));
    if (following.length() == 0) throw new AssertionError("No new FM songs after refill");
    System.out.println("PASS real anonymous FM refill, deduplication and private marker isolation: new=" + following.length());
  }
}
