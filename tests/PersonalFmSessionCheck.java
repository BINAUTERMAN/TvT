package cn.ting.music;

import org.json.*;

public final class PersonalFmSessionCheck {
  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
    System.out.println("PASS " + message);
  }

  public static void main(String[] args) throws Exception {
    PersonalFmSession fm = new PersonalFmSession();
    fm.start(11);
    int first = fm.begin();
    check(fm.accepts(first, 11) && !fm.accepts(first, 12), "request bound to its account");
    fm.cancel();
    check(!fm.accepts(first, 11) && !fm.pending && !fm.starting, "pause invalidates late startup results");
    fm.start(12);
    int second = fm.begin();
    fm.stop();
    check(!fm.accepts(second, 12), "ordinary playback invalidates FM requests");
    fm.start(11);
    JSONObject raw = new JSONObject().put("hash", "a".repeat(32))
        .put("title", "测试").put("fmMark", "native-only-mark");
    JSONArray accepted = fm.filter(new JSONArray().put(raw).put(raw));
    check(accepted.length() == 1 && !accepted.getJSONObject(0).has("fmMark"),
        "duplicate songs removed and native markers excluded from playback queue");
    check(fm.requestTrack(accepted.getJSONObject(0)).getString("fmMark").equals("native-only-mark"),
        "native feedback retains the platform recommendation mark");
    check(!accepted.getJSONObject(0).has("fmMark"), "feedback cloning never mutates UI track");
    for (int index = 0; index < 120; index++)
      fm.filter(new JSONArray().put(new JSONObject().put("hash", String.format("%032x", index))));
    java.lang.reflect.Field recent = PersonalFmSession.class.getDeclaredField("recent");
    recent.setAccessible(true);
    check(((java.util.Map<?, ?>) recent.get(fm)).size() == 100, "recommendation history remains bounded");
    fm.active = true;
    fm.exhausted = true;
    fm.cancel();
    check(fm.active && fm.exhausted, "pause at queue end retains explicit resume boundary");
    fm.begin();
    check(fm.exhausted, "retry does not reset the exhausted queue boundary");
    fm.stop();
    check(!fm.exhausted && ((java.util.Map<?, ?>) recent.get(fm)).isEmpty(), "exit clears account recommendation state");

    PersonalFmApi api = new PersonalFmApi(new KugouApi("test"));
    JSONObject response = new JSONObject().put("status", 1).put("data", new JSONObject()
        .put("cur_mark", "private-mark").put("token", "must-not-leak")
        .put("song_list", new JSONArray().put(new JSONObject().put("hash", "b".repeat(32))
            .put("filename", "歌手 - 歌曲.mp3").put("mixsongid", 123)
            .put("time_length", 200).put("token", "must-not-leak")
            .put("trans_param", new JSONObject().put("union_cover", "http://imge.kugou.com/{size}/cover.jpg")))));
    JSONObject song = api.songs(response).getJSONObject(0);
    check(song.getString("title").equals("歌曲") && song.getString("artist").equals("歌手")
        && song.getString("audioId").equals("123") && song.getDouble("duration") == 200,
        "real FM response field mapping");
    check(!song.has("token") && song.getString("cover").equals("https://imge.kugou.com/400/cover.jpg"),
        "response whitelist excludes credentials and normalizes artwork");
    check(api.songs(new JSONObject().put("status", 1), true).length() == 0,
        "confirmed feedback may return no recommendation songs");
    boolean rejected = false;
    try { api.songs(new JSONObject().put("status", 0).put("error_code", 20028)); }
    catch (Exception exception) { rejected = exception.getMessage().contains("验证"); }
    check(rejected, "platform verification failure is not treated as an empty success");
    rejected = false;
    try { api.songs(new JSONObject().put("status", 1)); }
    catch (Exception exception) { rejected = true; }
    check(rejected, "missing recommendation structure is an explicit error");
  }
}
