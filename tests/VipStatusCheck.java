package cn.ting.music;

import org.json.*;

public final class VipStatusCheck {
  public static void main(String[] args) throws Exception {
    long now = VipRepository.expiry("2026-11-10 11:00:00");
    JSONObject concept =
        new JSONObject()
            .put("is_vip", 1)
            .put("busi_type", "concept")
            .put("product_type", "svip")
            .put("vip_end_time", "2026-11-11 10:59:35");
    JSONObject regular =
        new JSONObject()
            .put(
                "data",
                new JSONObject()
                    .put("is_vip", 0)
                    .put("vip_end_time", "")
                    .put("busi_vip", new JSONArray().put(concept)));
    JSONObject history =
        new JSONObject()
            .put(
                "data",
                new JSONObject()
                    .put("server_time", now / 1000)
                    .put(
                        "list",
                        new JSONArray()
                            .put(new JSONObject().put("day", "2026-11-11").put("receive_vip", 1))));
    JSONObject status = VipRepository.normalize(regular, history, now);
    if (!status.getBoolean("active")
        || !status.getString("label").equals("概念版 VIP")
        || status.getBoolean("todayClaimed"))
      throw new AssertionError("Concept or future record mismatch");
    history
        .getJSONObject("data")
        .getJSONArray("list")
        .put(new JSONObject().put("day", "2026-11-10").put("receive_vip", 1));
    if (!VipRepository.normalize(regular, history, now).getBoolean("todayClaimed"))
      throw new AssertionError("Confirmed claim missing");
    JSONObject unknown =
        VipRepository.normalize(regular, history, now)
            .getJSONArray("todayReceipts")
            .getJSONObject(0);
    if (!unknown.getString("label").contains("未说明"))
      throw new AssertionError("Unknown receipt must not imply concept VIP");
    history.getJSONObject("data").getJSONArray("list").getJSONObject(1).put("vip_type", "tvip");
    JSONObject before = VipRepository.normalize(regular, history, now);
    if (!VipRepository.protectsConcept(before))
      throw new AssertionError("Active concept membership must block listening claim");
    if (!before.getJSONArray("todayReceipts").getJSONObject(0).getString("label").equals("畅听 VIP"))
      throw new AssertionError("Listening receipt must remain separate from concept rights");
    if (VipRepository.extensions(before, before).length() != 0)
      throw new AssertionError("Existing record is not a new extension");
    concept.put("vip_end_time", "2026-11-12 10:59:35");
    JSONObject after = VipRepository.normalize(regular, history, now);
    if (VipRepository.extensions(before, after).getJSONObject(0).getLong("addedSeconds") != 86400)
      throw new AssertionError("Same product expiry extension missing");
    after.getJSONArray("rights").getJSONObject(0).put("type", "tvip");
    if (VipRepository.protectsConcept(after))
      throw new AssertionError("Listening membership must not be treated as concept");
    if (VipRepository.extensions(before, after).length() != 0)
      throw new AssertionError("Different products must not be compared");
    concept.put("vip_end_time", "2026-11-09 10:59:35");
    if (VipRepository.protectsConcept(VipRepository.normalize(regular, null, now)))
      throw new AssertionError("Expired concept membership does not block daily claim");
    if (VipRepository.normalize(regular, null, now).getBoolean("active"))
      throw new AssertionError("Expired VIP must not remain active");
    if (VipRepository.normalize(regular, null, now).getBoolean("claimKnown"))
      throw new AssertionError("Unavailable record must not be confirmed");
    if (VipRepository.expiry("1794283200") != 1794283200000L
        || VipRepository.expiry("1794283200000") != 1794283200000L
        || VipRepository.expiry("invalid") != 0) throw new AssertionError("Expiry conversion");
    System.out.println("PASS concept rights, expiry, server date, future record and unknown claim");
  }
}
