package cn.ting.music;

import java.util.UUID;
import org.json.*;

public final class CategoryVariantsCheck {
  public static void main(String[] args) throws Exception {
    KugouApi api = new KugouApi(UUID.randomUUID().toString().replace("-", ""));
    for (int category :
        args.length > 0 ? new int[] {Integer.parseInt(args[0])} : new int[] {84, 20, 9, 1084, 14})
      for (int sort : new int[] {1, 2, 3}) {
        JSONObject value =
            api.catalog(
                "playlists",
                new JSONObject().put("category", category).put("withsong", 0).put("sort", sort));
        JSONObject data = value.optJSONObject("data");
        JSONArray lists = data == null ? null : data.optJSONArray("special_list");
        System.out.println(
            "category="
                + category
                + " sort="
                + sort
                + " count="
                + (lists == null ? 0 : lists.length()));
      }
  }
}
