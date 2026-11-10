package cn.ting.music;

import java.nio.file.*;
import java.util.UUID;
import org.json.*;

/** 只保存公开目录响应，避免以空白界面掩盖字段或分类映射错误。 */
public final class DiscoveryApiCheck {
  static void save(String name, JSONObject value) throws Exception {
    Files.write(Paths.get("build/test/" + name + ".json"), value.toString().getBytes("UTF-8"));
  }

  static void shape(String label, JSONObject value) {
    JSONObject data = value.optJSONObject("data");
    System.out.println(label + " keys=" + (data == null ? value.names() : data.names()));
    if (data != null)
      for (String key :
          new String[] {"songs", "songlist", "audio_list", "list", "info", "special_list"}) {
        JSONArray rows = data.optJSONArray(key);
        if (rows != null)
          System.out.println(
              label
                  + " "
                  + key
                  + " count="
                  + rows.length()
                  + " firstKeys="
                  + (rows.length() > 0 ? rows.optJSONObject(0).names() : "empty"));
      }
  }

  public static void main(String[] args) throws Exception {
    KugouApi api = new KugouApi(UUID.randomUUID().toString().replace("-", ""));
    JSONObject ranks = api.catalog("ranks", new JSONObject());
    save("rank-list", ranks);
    JSONArray items = ranks.getJSONObject("data").getJSONArray("info");
    for (int index = 0; index < Math.min(3, items.length()); index++) {
      JSONObject rank = items.getJSONObject(index);
      JSONObject songs =
          api.catalog("rankSongs", new JSONObject().put("id", rank.optString("rankid")));
      shape("rank " + rank.optString("rankid"), songs);
      if (songs.getJSONObject("data").getJSONArray("songlist").length() == 0)
        throw new AssertionError("Rank source returned no songs");
      save("rank-songs-" + index, songs);
    }
    JSONObject tags = api.catalog("tags", new JSONObject());
    save("playlist-tags", tags);
    JSONArray groups = tags.optJSONArray("data");
    if (groups != null)
      for (int index = 0; index < groups.length(); index++) {
        JSONObject group = groups.getJSONObject(index);
        JSONArray children = group.optJSONArray("son");
        System.out.println(
            "tag group="
                + group.optString("tag_name")
                + " id="
                + group.optString("tag_id")
                + " childCount="
                + (children == null ? 0 : children.length()));
        if (children == null) continue;
        for (int child = 0; child < Math.min(2, children.length()); child++) {
          JSONObject tag = children.getJSONObject(child);
          JSONObject response =
              api.catalog("playlists", new JSONObject().put("category", tag.optInt("tag_id")));
          shape("tag " + tag.optString("tag_name") + " " + tag.optString("tag_id"), response);
          save("tag-songs-" + index + "-" + child, response);
        }
      }
  }
}
