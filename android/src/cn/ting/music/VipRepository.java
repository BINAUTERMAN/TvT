package cn.ting.music;

import android.content.Context;
import android.content.SharedPreferences;
import java.time.*;
import java.time.format.DateTimeFormatter;
import org.json.*;

/** 区分概念版权益与普通 VIP；领取结果必须由云端当天记录确认。畅听领取与签到奖励互斥。 */
public final class VipRepository {
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final Object LOCK = new Object();
  private static long cachedUser, cachedAt;
  private static JSONObject cached;
  private final KugouApi api;
  private final SharedPreferences preferences;

  VipRepository(KugouApi api, Context context) {
    this.api = api;
    preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE);
  }

  static long expiry(Object value) {
    String text = String.valueOf(value).trim();
    if (text.isEmpty() || text.equals("null")) return 0;
    try {
      long numeric = Long.parseLong(text);
      return numeric < 100000000000L ? numeric * 1000 : numeric;
    } catch (Exception ignored) {
      try {
        return LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            .atZone(ZONE)
            .toInstant()
            .toEpochMilli();
      } catch (Exception invalid) {
        return 0;
      }
    }
  }

  static JSONObject normalize(JSONObject membership, JSONObject record, long now) throws Exception {
    JSONObject data = membership.optJSONObject("data");
    if (data == null) throw new Exception("平台未返回 VIP 权益信息");
    JSONObject history = record == null ? null : record.optJSONObject("data");
    long serverTime = history == null ? 0 : history.optLong("server_time") * 1000;
    long checkedAt = serverTime > 0 ? serverTime : now;
    String today = Instant.ofEpochMilli(checkedAt).atZone(ZONE).toLocalDate().toString();
    JSONArray rights = new JSONArray(), source = data.optJSONArray("busi_vip");
    String label = "普通账号";
    long expiresAt = 0;
    boolean active = false;
    int priority = 0;
    if (source != null)
      for (int index = 0; index < source.length(); index++) {
        JSONObject item = source.optJSONObject(index);
        if (item == null || !item.optString("busi_type").equals("concept")) continue;
        String product = item.optString("product_type");
        if (!product.equals("svip") && !product.equals("tvip")) continue;
        long end = expiry(item.opt("vip_end_time"));
        boolean valid = item.optInt("is_vip") > 0 && (end == 0 || end > checkedAt);
        String name = product.equals("svip") ? "概念版 VIP" : "畅听 VIP";
        rights.put(
            new JSONObject()
                .put("type", product)
                .put("label", name)
                .put("active", valid)
                .put("expiresAt", end));
        int rank = product.equals("svip") ? 2 : 1;
        if (valid && rank > priority) {
          label = name;
          expiresAt = end;
          active = true;
          priority = rank;
        }
      }
    if (!active && data.optInt("is_vip") > 0) {
      long end = expiry(data.opt("vip_end_time"));
      if (end == 0 || end > checkedAt) {
        label = "酷狗 VIP";
        active = true;
        expiresAt = end;
      }
    }
    JSONArray list = history == null ? null : history.optJSONArray("list");
    JSONArray receipts = new JSONArray();
    if (list != null)
      for (int index = 0; index < list.length(); index++) {
        JSONObject item = list.optJSONObject(index);
        if (item != null && today.equals(item.optString("day")) && item.optInt("receive_vip") > 0)
          receipts.put(
              new JSONObject()
                  .put("type", item.optString("vip_type"))
                  .put("label", receiptLabel(item.optString("vip_type"))));
      }
    return new JSONObject()
        .put("label", label)
        .put("active", active)
        .put("expiresAt", expiresAt)
        .put("rights", rights)
        .put("today", today)
        .put("todayClaimed", receipts.length() > 0)
        .put("todayReceipts", receipts)
        .put("claimKnown", list != null)
        .put("checkedAt", checkedAt);
  }

  private static String receiptLabel(String type) {
    if (type.equals("svip")) return "概念版 VIP";
    if (type.equals("tvip")) return "畅听 VIP";
    return "VIP（平台未说明类型）";
  }

  static JSONArray extensions(JSONObject before, JSONObject after) throws Exception {
    JSONArray result = new JSONArray();
    JSONArray previous = before.getJSONArray("rights"), current = after.getJSONArray("rights");
    for (int index = 0; index < current.length(); index++) {
      JSONObject right = current.getJSONObject(index);
      String type = right.optString("type");
      if (type.isEmpty()) continue;
      for (int oldIndex = 0; oldIndex < previous.length(); oldIndex++) {
        JSONObject old = previous.getJSONObject(oldIndex);
        long oldEnd = old.optLong("expiresAt"), end = right.optLong("expiresAt");
        // 只比较同一权益的已知到期时间，领取记录本身不能证明时长增加。
        if (type.equals(old.optString("type")) && oldEnd > 0 && end > oldEnd)
          result.put(
              new JSONObject()
                  .put("type", type)
                  .put("label", right.getString("label"))
                  .put("previousExpiresAt", oldEnd)
                  .put("expiresAt", end)
                  .put("addedSeconds", (end - oldEnd) / 1000));
      }
    }
    return result;
  }

  static boolean protectsConcept(JSONObject status) {
    JSONArray rights = status.optJSONArray("rights");
    if (rights == null) return false;
    for (int index = 0; index < rights.length(); index++) {
      JSONObject right = rights.optJSONObject(index);
      if (right != null && right.optString("type").equals("svip") && right.optBoolean("active"))
        return true;
    }
    return false;
  }

  JSONObject read(boolean refresh) throws Exception {
    synchronized (LOCK) {
      long user = api.accountUserId(), now = System.currentTimeMillis();
      if (!api.account().optBoolean("loggedIn")) throw new Exception("请先登录酷狗账号");
      if (!refresh && cached != null && cachedUser == user && now - cachedAt < 30000)
        return new JSONObject(cached.toString());
      JSONObject membership = api.catalog("vip", new JSONObject());
      JSONObject record = null;
      String error = "";
      try {
        record = api.catalog("vipRecord", new JSONObject());
      } catch (Exception failure) {
        error = "领取记录查询失败：" + failure.getMessage();
      }
      JSONObject result = normalize(membership, record, now).put("recordError", error);
      if (result.optBoolean("todayClaimed"))
        preferences.edit().putString("vipConfirmedDay:" + user, result.getString("today")).apply();
      cachedUser = user;
      cachedAt = now;
      cached = result;
      return new JSONObject(result.toString());
    }
  }

  JSONObject claim(boolean manual) throws Exception {
    synchronized (LOCK) {
      if (!api.account().optBoolean("loggedIn"))
        return new JSONObject().put("claimed", false).put("message", "请先登录酷狗账号");
      if (!manual && !preferences.getBoolean("autoVip", true))
        return new JSONObject().put("skipped", true);
      String owner = String.valueOf(api.accountUserId());
      String today = LocalDate.now(ZONE).toString();
      // 签到奖励与畅听领取互斥：当日已用奖励领取时不再提交，避免畅听时长被改回一天口径。
      if (CheckinRepository.rewardUsedToday(
          preferences.getString("checkin:day:" + owner, ""),
          preferences.getString("checkin:mode:" + owner, ""),
          today,
          preferences.getInt("checkin:count:" + owner, 0)))
        return new JSONObject()
            .put("claimed", false)
            .put("skipped", true)
            .put("message", "今日已用签到奖励，未提交畅听领取以避免畅听时长回退");
      if (!manual && today.equals(preferences.getString("vipConfirmedDay:" + owner, "")))
        return new JSONObject().put("skipped", true);
      String attemptKey = "vipAttempt:" + owner;
      long now = System.currentTimeMillis();
      if (!manual && now - preferences.getLong(attemptKey, 0) < 600000)
        return new JSONObject().put("skipped", true);
      preferences.edit().putLong(attemptKey, now).apply();
      JSONObject before = read(true);
      if (before.optBoolean("todayClaimed"))
        return new JSONObject()
            .put("claimed", true)
            .put("alreadyClaimed", true)
            .put("message", "今日已有领取记录，本次未重复领取；请分别查看各类 VIP 的到期时间")
            .put("status", before);
      if (!before.optBoolean("claimKnown"))
        return new JSONObject()
            .put("claimed", false)
            .put("status", before)
            .put("message", "领取记录暂不可用，请刷新后重试");
      if (protectsConcept(before))
        return new JSONObject()
            .put("claimed", false)
            .put("skipped", true)
            .put("status", before)
            .put("message", "概念版 VIP 有效，已跳过畅听领取");
      JSONObject response = api.catalog("claimVip", new JSONObject());
      api.checkSuccess(response, "平台未确认领取，请稍后重试");
      JSONObject after = before;
      // 仅在主动领取后的短暂到账窗口复查，不创建后台轮询。
      for (int attempt = 0; attempt < 3; attempt++) {
        if (attempt > 0) Thread.sleep(attempt * 500L);
        after = read(true);
        if (after.optBoolean("todayClaimed")) {
          JSONArray increased = extensions(before, after);
          return new JSONObject()
              .put("claimed", true)
              .put("status", after)
              .put("extensions", increased)
              .put(
                  "message",
                  increased.length() > 0 ? "已确认对应 VIP 的到期时间延长" : "平台已有今日领取记录，暂未确认到期时间增加，请稍后刷新");
        }
      }
      return new JSONObject()
          .put("claimed", false)
          .put("status", after)
          .put("message", "领取请求已提交，平台记录暂未确认到账，请稍后刷新状态");
    }
  }
}
