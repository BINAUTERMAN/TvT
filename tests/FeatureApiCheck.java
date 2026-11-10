package cn.ting.music;

import java.util.UUID;
import org.json.*;

/** 只读联调只输出资源存在性与结构，不输出播放地址和账号信息。 */
public final class FeatureApiCheck {
  public static void main(String[] args) throws Exception {
    String standard = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        lossless = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    JSONObject fixture =
        new JSONObject()
            .put(
                "relate_goods",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put("quality", "flac")
                            .put("level", 5)
                            .put("hash", lossless)
                            .put("info", new JSONObject().put("filesize", 50000000))));
    if (!lossless.equals(QualityCatalog.hash(fixture, "flac")))
      throw new AssertionError("Lossless hash must come from the root entry");
    System.out.println("PASS quality root hash mapping");
    if (!QualityCatalog.candidates(fixture, "high").equals(java.util.Arrays.asList("flac", "128")))
      throw new AssertionError("Missing Hi-Res must select FLAC before standard");
    if (!QualityCatalog.candidates(fixture, "320").equals(java.util.Arrays.asList("128")))
      throw new AssertionError("Fallback must not exceed requested quality");
    System.out.println("PASS quality descending fallback with missing intermediate resources");
    java.lang.reflect.Field cache = FavoritesRepository.class.getDeclaredField("songs");
    cache.setAccessible(true);
    @SuppressWarnings("unchecked")
    java.util.Map<String, JSONObject> favorites =
        (java.util.Map<String, JSONObject>) cache.get(null);
    favorites.put(lossless, new JSONObject().put("mixsongid", "12345"));
    FavoritesRepository repository = new FavoritesRepository(new KugouApi("test"));
    java.lang.reflect.Method match =
        FavoritesRepository.class.getDeclaredMethod("matchingKey", JSONObject.class);
    match.setAccessible(true);
    if (!lossless.equals(
        match.invoke(repository, new JSONObject().put("hash", standard).put("audioId", "12345"))))
      throw new AssertionError("Canonical favorite identity mismatch");
    if (!""
        .equals(
            match.invoke(repository, new JSONObject().put("hash", standard).put("audioId", "0"))))
      throw new AssertionError("Empty song ID must not match favorites");
    favorites.clear();
    System.out.println("PASS favorite canonical song identity mapping");

    KugouApi api = new KugouApi(UUID.randomUUID().toString().replace("-", ""));
    JSONArray songs = api.search("周杰伦", 1).getJSONObject("data").getJSONArray("lists");
    int checks = 0;
    for (int index = 0; index < Math.min(2, songs.length()); index++) {
      JSONObject raw = songs.getJSONObject(index);
      JSONObject track =
          new JSONObject()
              .put("hash", raw.optString("FileHash", standard))
              .put("albumId", raw.optString("AlbumID"))
              .put("audioId", raw.optString("MixSongID"));
      JSONArray options = api.qualityInfo(track);
      for (int position = 0; position < options.length(); position++) {
        JSONObject item = options.getJSONObject(position);
        System.out.println(
            "source "
                + index
                + " quality="
                + item.getString("quality")
                + " exists="
                + item.getBoolean("exists")
                + " restricted="
                + item.getBoolean("restricted"));
        checks++;
      }
    }
    if (checks == 0) throw new AssertionError("No quality responses");
    JSONObject scenes = api.catalog("scenes", new JSONObject());
    JSONObject structure = new JSONObject();
    java.util.Iterator<String> keys = scenes.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      Object value = scenes.get(key);
      structure.put(
          key,
          value instanceof JSONObject
              ? ((JSONObject) value).names()
              : value instanceof JSONArray ? "array" : "scalar");
    }
    System.out.println("scene structure=" + structure);
    JSONArray sceneRows = scenes.getJSONObject("data").getJSONArray("list");
    JSONObject sceneSongs =
        api.catalog(
            "sceneSongs",
            new JSONObject().put("id", sceneRows.getJSONObject(0).optString("scene_id")));
    Object sceneData = sceneSongs.opt("data");
    System.out.println(
        "scene songs structure="
            + (sceneData instanceof JSONObject
                ? ((JSONObject) sceneData).names()
                : sceneData instanceof JSONArray ? "array" : "none"));
    java.nio.file.Files.write(
        java.nio.file.Paths.get("build/test/scene-songs.json"),
        sceneSongs.toString().getBytes("UTF-8"));
    java.nio.file.Files.write(
        java.nio.file.Paths.get("build/test/scenes.json"), scenes.toString().getBytes("UTF-8"));
    System.out.println("READ_ONLY_SOURCE_CHECKS_PASS");
  }
}
