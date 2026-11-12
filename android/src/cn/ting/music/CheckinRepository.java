package cn.ting.music;

import android.content.Context;
import android.content.SharedPreferences;
import java.time.LocalDate;
import java.time.ZoneId;
import org.json.JSONObject;

/**
 * 概念版签到奖励：通过广告播放上报接口领取畅听时长，每次约 3 小时、每天最多 8 次。
 *
 * <p>与畅听 VIP 领取互斥——畅听领取会把时长改写成一天口径，同日再调用会让已领到的奖励回退，
 * 因此两种方式在当天只能使用其中一种，跨天再重新选择。领取进度按账号持久化，应用被清后台后
 * 重新打开会从上次进度继续，不创建常驻后台任务。
 */
final class CheckinRepository {
  static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  static final int MAX_CLAIMS = 8;
  static final long CLAIM_INTERVAL = 60000L;
  /** 匿名探活的基线：接口正常时，未带登录态的请求应被登录校验拒绝。 */
  static final int CODE_LOGGED_OUT = 20002;
  /** 平台返回的"今日次数已用光"。 */
  static final int CODE_EXHAUSTED = 30002;
  private static final long MANUAL_GAP = 5000L;
  private static final long PROBE_RETRY_GAP = 15 * 60 * 1000L;
  private static final long AD_ID = 12307537187L;
  private static final long AD_PLAY_MS = 30000L;
  private static final Object LOCK = new Object();

  private final KugouApi api;
  private final Context context;
  private final SharedPreferences preferences;

  CheckinRepository(KugouApi api, Context context) {
    if (context == null) throw new IllegalStateException("签到需要应用环境");
    this.api = api;
    this.context = context.getApplicationContext();
    this.preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE);
  }

  /** 探活判定：只有与基线一致的未登录错误码才算接口仍按预期拒绝请求。 */
  static boolean probePassed(JSONObject response) {
    return response != null
        && response.optInt("error_code", response.optInt("errcode", -1)) == CODE_LOGGED_OUT;
  }

  static boolean shouldRetryProbe(boolean checked, boolean ok, long checkedAt, long now) {
    return checked && !ok && now - checkedAt >= PROBE_RETRY_GAP;
  }

  /** 领取响应分类：0 成功，1 今日次数用光，2 登录失效，3 其他失败。 */
  static int claimVerdict(JSONObject response) {
    if (response == null) return 3;
    if (response.optInt("status") == 1) return 0;
    int code = response.optInt("error_code", response.optInt("errcode", 0));
    if (code == CODE_EXHAUSTED) return 1;
    if (code == CODE_LOGGED_OUT) return 2;
    return 3;
  }

  /** 距离下一次领取还有多久；0 表示可以立即领取。 */
  static long nextDelayMs(int claims, long lastAt, long now, boolean manual) {
    if (claims >= MAX_CLAIMS) return 0;
    long gap = manual ? MANUAL_GAP : CLAIM_INTERVAL;
    long wait = lastAt + gap - now;
    return wait > 0 ? wait : 0;
  }

  /** 今日是否已由奖励流程占用，畅听领取需让路。 */
  static boolean rewardUsedToday(String day, String mode, String today, int claims) {
    return today.equals(day) && "reward".equals(mode) && claims > 0;
  }

  /** 网络层失败不应被当成接口改版。 */
  static boolean transportFailure(Throwable error) {
    Throwable cause = error;
    for (int depth = 0; cause != null && depth < 6; depth++) {
      if (cause instanceof java.io.IOException) return true;
      String text = cause.getMessage();
      if (text != null) {
        if (text.contains("音乐服务暂不可用")
            || text.contains("未返回内容")
            || text.contains("内容过大")
            || text.contains("timed out")
            || text.contains("Unable to resolve host")
            || text.contains("连接")) return true;
      }
      cause = cause.getCause();
    }
    return false;
  }

  /** 只读状态，供界面展示；不发起网络请求。 */
  JSONObject status() throws Exception {
    String today = LocalDate.now(ZONE).toString();
    try {
      if (!api.account().optBoolean("loggedIn"))
        return blank(new JSONObject().put("loggedIn", false), "off", today);
      return describe(owner(), "reward", today);
    } catch (Exception error) {
      return blank(new JSONObject().put("loggedIn", false), "off", today)
          .put("error", "账号信息读取失败：" + message(error));
    }
  }

  /** 强制执行一次探活，结果按当天缓存；接口恢复且当天尚未到账时回到奖励领取。 */
  JSONObject probeNow() throws Exception {
    synchronized (LOCK) {
      JSONObject probe = probe(true);
      if (probe.optBoolean("ok") && api.account().optBoolean("loggedIn")) {
        String today = LocalDate.now(ZONE).toString();
        String owner = owner();
        if ("listen".equals(preferences.getString(key(owner, "mode"), ""))
            && preferences.getInt(key(owner, "count"), 0) == 0
            && !today.equals(preferences.getString("vipConfirmedDay:" + owner, "")))
          preferences.edit().putString(key(owner, "mode"), "").apply();
      }
      return status();
    }
  }

  /**
   * 推进一次签到：探活失败走畅听领取，正常时按每分钟一次领取签到奖励。
   *
   * @param manual 手动触发时忽略自动签到开关和一分钟节奏，仍保留最小间隔防重复提交
   */
  JSONObject step(boolean manual) throws Exception {
    synchronized (LOCK) {
      String today = LocalDate.now(ZONE).toString();
      JSONObject state = status();
      if (!state.optBoolean("loggedIn")) return state;
      String owner = owner();
      resetDay(owner, today);
      state = describe(owner, "reward", today);
      if (!manual && !state.optBoolean("auto"))
        return state.put("message", "已在设置中关闭启动自动签到");
      JSONObject previousProbe = state.optJSONObject("probe");
      // 仅随前台签到入口重试，不另设轮询；已有到账记录时保持当日领取口径。
      if ("listen".equals(state.optString("mode"))
          && state.optInt("claims") == 0
          && !today.equals(preferences.getString("vipConfirmedDay:" + owner, ""))
          && previousProbe != null
          && shouldRetryProbe(previousProbe.optBoolean("checked"), previousProbe.optBoolean("ok"),
              previousProbe.optLong("checkedAt"), System.currentTimeMillis())) {
        probeNow();
        state = describe(owner, "reward", today);
      }
      if ("listen".equals(state.optString("mode"))) return listenClaim(owner, today, manual);
      int claims = state.optInt("claims");
      if (claims == 0 && today.equals(preferences.getString("vipConfirmedDay:" + owner, ""))) {
        // 平台已有今日畅听记录：先到账的一方决定当天口径，不再叠加奖励领取。
        preferences.edit().putString(key(owner, "mode"), "listen").apply();
        return describe(owner, "listen", today)
            .put("message", "今日已有畅听领取记录，未启动签到奖励以避免时长回退");
      }
      if (claims >= MAX_CLAIMS) return state.put("message", "今日签到奖励已领满");
      JSONObject probe = probe(false);
      state.put("probe", probe);
      if (!probe.optBoolean("ok")) {
        if ("network".equals(probe.optString("kind")))
          return state
              .put("error", "接口探活未完成：" + probe.optString("message"))
              .put("nextInMs", 0);
        if (claims > 0)
          return state
              .put(
                  "error",
                  "签到奖励接口异常，今日已领 " + claims + " 次；未提交畅听领取以避免时长回退")
              .put("nextInMs", 0);
        return fallbackToListen(owner, today, probe);
      }
      long now = System.currentTimeMillis();
      long wait = nextDelayMs(claims, preferences.getLong(key(owner, "last"), 0), now, manual);
      if (wait > 0) return state.put("nextInMs", wait);
      preferences
          .edit()
          .putLong(key(owner, "last"), now)
          .putString(key(owner, "error"), "")
          .apply();
      JSONObject response;
      try {
        response = api.checkinReward(AD_ID, now - AD_PLAY_MS, now);
      } catch (Exception error) {
        boolean transport = transportFailure(error);
        if (!transport) markProbeChanged(message(error));
        return describe(owner, "reward", today)
            .put("error", "签到奖励请求失败：" + message(error))
            .put("nextInMs", transport ? CLAIM_INTERVAL : 0);
      }
      int verdict = claimVerdict(response);
      if (verdict == 0) {
        int next = claims + 1;
        preferences.edit().putInt(key(owner, "count"), next).apply();
        JSONObject result =
            describe(owner, "reward", today)
                .put("claimed", true)
                .put(
                    "message",
                    next >= MAX_CLAIMS
                        ? "今日签到奖励已领满 " + MAX_CLAIMS + " 次"
                        : "第 " + next + " 次签到奖励已领取");
        if (next == 1 || next >= MAX_CLAIMS) attachStatus(result);
        return result;
      }
      if (verdict == 1) {
        preferences.edit().putInt(key(owner, "count"), MAX_CLAIMS).apply();
        JSONObject result =
            describe(owner, "reward", today).put("message", "平台返回今日次数已用光");
        attachStatus(result);
        return result;
      }
      if (verdict == 2) return fail(owner, today, "登录已失效，请重新登录酷狗账号");
      String detail = detail(response);
      markProbeChanged(detail);
      return fail(owner, today, "签到奖励接口返回 " + detail);
    }
  }

  private JSONObject fallbackToListen(String owner, String today, JSONObject probe)
      throws Exception {
    preferences.edit().putString(key(owner, "mode"), "listen").apply();
    return listenClaim(owner, today, false).put("probe", probe);
  }

  /** 当天走畅听领取时推进一次：手动触发忽略自动开关与十分钟保护，到账仍以平台记录为准。 */
  private JSONObject listenClaim(String owner, String today, boolean manual) throws Exception {
    JSONObject result = describe(owner, "listen", today);
    try {
      JSONObject listen = new VipRepository(api, context).claim(manual);
      if (listen.optJSONObject("status") != null)
        result.put("status", listen.getJSONObject("status"));
      String note = listen.optString("message");
      boolean ok = listen.optBoolean("claimed") || listen.optBoolean("skipped");
      return result.put("message", note).put("error", ok ? "" : note);
    } catch (Exception error) {
      return result.put("error", "畅听领取失败：" + message(error));
    }
  }

  private JSONObject fail(String owner, String today, String text) throws Exception {
    preferences.edit().putString(key(owner, "error"), text).apply();
    return describe(owner, "reward", today).put("nextInMs", 0).put("error", text);
  }

  private void attachStatus(JSONObject result) {
    try {
      result.put("status", new VipRepository(api, context).read(true));
    } catch (Exception ignored) {
    }
  }

  /** 探活：与领取同一路径、同一签名的匿名请求，服务端在登录校验处拒绝，不产生业务副作用。 */
  private JSONObject probe(boolean force) throws Exception {
    String today = LocalDate.now(ZONE).toString();
    if (!force && today.equals(preferences.getString("checkinProbeDay", "")))
      return probeState(today);
    long now = System.currentTimeMillis();
    JSONObject verdict;
    try {
      JSONObject response = api.probeAdReward(AD_ID, now - AD_PLAY_MS, now);
      boolean ok = probePassed(response);
      verdict =
          new JSONObject()
              .put("checked", true)
              .put("ok", ok)
              .put("kind", ok ? "ok" : "changed")
              .put("code", response.optInt("error_code", response.optInt("errcode", 0)))
              .put("message", ok ? "" : detail(response))
              .put("checkedAt", now);
    } catch (Exception error) {
      // 断网或超时不写入当日结论，避免把网络失败误判为接口改版。
      return new JSONObject()
          .put("checked", false)
          .put("ok", false)
          .put("kind", transportFailure(error) ? "network" : "changed")
          .put("code", 0)
          .put("message", message(error))
          .put("checkedAt", now);
    }
    preferences
        .edit()
        .putString("checkinProbeDay", today)
        .putBoolean("checkinProbeOk", verdict.optBoolean("ok"))
        .putString("checkinProbeKind", verdict.optString("kind"))
        .putInt("checkinProbeCode", verdict.optInt("code"))
        .putString("checkinProbeMessage", verdict.optString("message"))
        .putLong("checkinProbeAt", verdict.optLong("checkedAt"))
        .apply();
    return verdict;
  }

  private void markProbeChanged(String text) {
    preferences
        .edit()
        .putString("checkinProbeDay", LocalDate.now(ZONE).toString())
        .putBoolean("checkinProbeOk", false)
        .putString("checkinProbeKind", "changed")
        .putInt("checkinProbeCode", 0)
        .putString("checkinProbeMessage", text)
        .putLong("checkinProbeAt", System.currentTimeMillis())
        .apply();
  }

  private JSONObject probeState(String today) throws Exception {
    if (!today.equals(preferences.getString("checkinProbeDay", "")))
      return new JSONObject()
          .put("checked", false)
          .put("ok", false)
          .put("code", 0)
          .put("message", "");
    return new JSONObject()
        .put("checked", true)
        .put("ok", preferences.getBoolean("checkinProbeOk", false))
        .put("kind", preferences.getString("checkinProbeKind", ""))
        .put("code", preferences.getInt("checkinProbeCode", 0))
        .put("message", preferences.getString("checkinProbeMessage", ""))
        .put("checkedAt", preferences.getLong("checkinProbeAt", 0));
  }

  private JSONObject describe(String owner, String fallback, String today) throws Exception {
    boolean fresh = today.equals(preferences.getString(key(owner, "day"), ""));
    String mode = fresh ? preferences.getString(key(owner, "mode"), "") : "";
    int claims = fresh ? preferences.getInt(key(owner, "count"), 0) : 0;
    if (mode.isEmpty()) mode = fallback;
    return blank(new JSONObject(), mode, today)
        .put("loggedIn", true)
        .put("claims", claims)
        .put("done", claims >= MAX_CLAIMS)
        .put(
            "nextInMs",
            nextDelayMs(
                claims,
                fresh ? preferences.getLong(key(owner, "last"), 0) : 0,
                System.currentTimeMillis(),
                false))
        .put("error", fresh ? preferences.getString(key(owner, "error"), "") : "");
  }

  private JSONObject blank(JSONObject target, String mode, String today) throws Exception {
    return target
        .put("today", today)
        .put("mode", mode)
        .put("auto", preferences.getBoolean("autoVip", true))
        .put("claims", 0)
        .put("maxClaims", MAX_CLAIMS)
        .put("done", false)
        .put("nextInMs", 0)
        .put("message", "")
        .put("error", "")
        .put("probe", probeState(today));
  }

  private void resetDay(String owner, String today) {
    if (today.equals(preferences.getString(key(owner, "day"), ""))) return;
    preferences
        .edit()
        .putString(key(owner, "day"), today)
        .putInt(key(owner, "count"), 0)
        .putString(key(owner, "mode"), "")
        .putLong(key(owner, "last"), 0L)
        .putString(key(owner, "error"), "")
        .apply();
  }

  private String owner() {
    return String.valueOf(api.accountUserId());
  }

  private static String key(String owner, String name) {
    return "checkin:" + name + ":" + owner;
  }

  private static String detail(JSONObject response) {
    int code = response.optInt("error_code", response.optInt("errcode", 0));
    String text =
        response.optString("error_msg", response.optString("msg", response.optString("error", "")));
    return (text.isEmpty() ? "错误码 " + code : text + "（" + code + "）");
  }

  private static String message(Throwable error) {
    if (error == null) return "未知错误";
    String text = error.getMessage();
    return text == null || text.isEmpty() ? error.getClass().getSimpleName() : text;
  }
}
