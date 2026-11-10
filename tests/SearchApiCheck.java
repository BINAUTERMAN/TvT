package cn.ting.music;

import java.nio.file.*;
import java.util.UUID;
import org.json.*;

public final class SearchApiCheck {
  public static void main(String[] args) throws Exception {
    KugouApi api = new KugouApi(UUID.randomUUID().toString().replace("-", ""));
    for (String route : new String[] {"searchSuggest", "searchHot", "search"}) {
      JSONObject data =
          new JSONObject()
              .put("keyword", route.equals("search") ? "故事的小黄花" : "周杰")
              .put("type", "lyric");
      JSONObject result = api.catalog(route, data);
      Files.write(Paths.get("build/test/" + route + ".json"), result.toString().getBytes("UTF-8"));
      System.out.println(route + " fields=" + result.names());
    }
  }
}
