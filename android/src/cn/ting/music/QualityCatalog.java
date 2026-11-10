package cn.ting.music;

import org.json.JSONArray;
import org.json.JSONObject;

/** 平台的音质哈希位于权益条目本身，info 只描述文件属性。 */
final class QualityCatalog {
  static final String[] KEYS = {"128", "320", "flac", "high"};
  static final String[] LABELS = {"标准", "高品", "无损 FLAC", "Hi-Res"};

  static java.util.List<String> candidates(JSONObject resource, String requested) {
    java.util.List<String> result = new java.util.ArrayList<>();
    int start = java.util.Arrays.asList(KEYS).indexOf(requested);
    if (start < 0) throw new IllegalArgumentException("音质选项无效");
    for (int index = start; index >= 0; index--)
      if (index == 0 || hash(resource, KEYS[index]).matches("[a-f0-9]{32}"))
        result.add(KEYS[index]);
    return result;
  }

  static JSONObject entry(JSONObject resource, String quality) {
    JSONArray related = resource.optJSONArray("relate_goods");
    JSONObject fallback = null;
    if (related != null)
      for (int index = 0; index < related.length(); index++) {
        JSONObject item = related.optJSONObject(index);
        if (item == null || !quality.equals(item.optString("quality"))) continue;
        int expected =
            quality.equals("128") ? 1 : quality.equals("320") ? 4 : quality.equals("flac") ? 5 : 6;
        if (item.optInt("level") == expected) return item;
        fallback = item;
      }
    return fallback;
  }

  static String hash(JSONObject resource, String quality) {
    JSONObject trans = resource.optJSONObject("trans_param");
    if (trans != null && (quality.equals("128") || quality.equals("320"))) {
      String ogg = trans.optString("ogg_" + quality + "_hash");
      if (!ogg.isEmpty()) return ogg.toLowerCase(java.util.Locale.ROOT);
    }
    JSONObject item = entry(resource, quality);
    if (item == null) return "";
    String hash = item.optString("hash");
    if (hash.isEmpty() && item.optJSONObject("info") != null)
      hash = item.optJSONObject("info").optString("hash");
    return hash.toLowerCase(java.util.Locale.ROOT);
  }

  static JSONArray options(JSONObject resource) throws Exception {
    JSONArray result = new JSONArray();
    for (int index = 0; index < KEYS.length; index++) {
      String quality = KEYS[index];
      JSONObject item = entry(resource, quality);
      boolean exists = !hash(resource, quality).isEmpty();
      JSONObject info = item == null ? null : item.optJSONObject("info");
      JSONObject trans = resource.optJSONObject("trans_param");
      long size = info == null ? 0 : info.optLong("filesize");
      if (trans != null && (quality.equals("128") || quality.equals("320")))
        size = trans.optLong("ogg_" + quality + "_filesize", size);
      result.put(
          new JSONObject()
              .put("quality", quality)
              .put("label", LABELS[index])
              .put("exists", exists)
              .put("restricted", item != null && item.optInt("privilege") != 0)
              .put("status", item == null ? 0 : item.optInt("status"))
              .put("size", size)
              .put("bitrate", info == null ? 0 : info.optInt("bitrate")));
    }
    return result;
  }
}
