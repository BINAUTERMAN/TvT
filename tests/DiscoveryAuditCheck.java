package cn.ting.music;

import java.nio.file.*;
import java.util.*;
import org.json.*;

/** 审计公开分类，不读取账号或保存歌词、音频。 */
public final class DiscoveryAuditCheck {
  public static void main(String[] args) throws Exception {
    KugouApi api = new KugouApi(UUID.randomUUID().toString().replace("-", ""));
    if (args.length > 0 && args[0].equals("playlists")) {
      JSONArray groups = api.catalog("tags", new JSONObject()).getJSONArray("data");
      JSONArray audit = new JSONArray();
      for (int index = 0; index < groups.length(); index++) {
        JSONArray tags = groups.getJSONObject(index).optJSONArray("son");
        if (tags == null) continue;
        for (int child = 0; child < tags.length(); child++) {
          JSONObject tag = tags.getJSONObject(child);
          int id = tag.getInt("tag_id");
          JSONObject response = api.catalog("playlists", new JSONObject().put("category", id));
          JSONObject data = response.optJSONObject("data");
          JSONArray list = data == null ? null : data.optJSONArray("special_list");
          JSONObject entry =
              new JSONObject()
                  .put("id", id)
                  .put("name", tag.getString("tag_name"))
                  .put("count", list == null ? 0 : list.length());
          System.out.println(entry);
          audit.put(entry);
        }
      }
      Files.write(Paths.get("build/test/playlist-audit.json"), audit.toString().getBytes("UTF-8"));
      return;
    }
    JSONObject scenes = api.catalog("scenes", new JSONObject());
    Files.write(Paths.get("build/test/scenes.json"), scenes.toString().getBytes("UTF-8"));
    JSONArray list = scenes.getJSONObject("data").getJSONArray("list");
    JSONArray audit = new JSONArray();
    for (int index = 0; index < list.length(); index++) {
      JSONObject scene = list.getJSONObject(index);
      int id = scene.getInt("scene_id");
      JSONObject result = api.catalog("sceneSongs", new JSONObject().put("id", String.valueOf(id)));
      Files.write(
          Paths.get("build/test/scene-songs-" + id + ".json"), result.toString().getBytes("UTF-8"));
      JSONObject data = result.optJSONObject("data");
      JSONArray songs = data == null ? null : data.optJSONArray("list");
      JSONObject entry =
          new JSONObject()
              .put("id", id)
              .put("name", scene.optString("title"))
              .put("count", songs == null ? 0 : songs.length());
      System.out.println(entry);
      audit.put(entry);
      if (songs == null || songs.length() == 0)
        Files.write(
            Paths.get("build/test/empty-scene-" + id + ".json"),
            result.toString().getBytes("UTF-8"));
    }
    Files.write(Paths.get("build/test/scene-audit.json"), audit.toString().getBytes("UTF-8"));
  }
}
