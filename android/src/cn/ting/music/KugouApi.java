package cn.ting.music;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** 酷狗协议适配；参数与签名移植自 KuGouMusicApi。 */
public final class KugouApi {
  private static final String SALT = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA";
  private final String mid;
  private final String guid;
  private final String model;
  private volatile String dfid;
  private final android.content.SharedPreferences devicePreferences;
  private static final Object DEVICE_LOCK = new Object();
  private String userAuthorization = "";
  private String authorizationToken = "";
  private long authorizationExpires;
  private final AccountStore accounts;
  private final Context appContext;
  private volatile LoginChoices loginChoices;

  public KugouApi(Context context) {
    appContext = context.getApplicationContext();
    accounts = new AccountStore(context);
    android.content.SharedPreferences preferences =
        context.getSharedPreferences("device", Context.MODE_PRIVATE);
    devicePreferences = preferences;
    String stored = preferences.getString("mid", "");
    guid =
        preferences.getString(
            "guid",
            stored.matches("[a-f0-9]{32}")
                ? stored
                : UUID.randomUUID().toString().replace("-", ""));
    try {
      mid = new java.math.BigInteger(md5(guid), 16).toString();
    } catch (Exception exception) {
      throw new IllegalStateException("无法创建设备标识", exception);
    }
    model = android.os.Build.MODEL.toUpperCase(java.util.Locale.ROOT);
    dfid = preferences.getBoolean("registered", false) ? preferences.getString("dfid", "-") : "-";
    preferences.edit().putString("guid", guid).putString("mid", mid).apply();
  }

  KugouApi(String deviceMid) {
    appContext = null;
    mid = deviceMid;
    guid = deviceMid;
    model = "TEST";
    devicePreferences = null;
    dfid = "-";
    accounts = null;
  }

  private JSONObject savedAccount() {
    return accounts == null ? new JSONObject() : accounts.read();
  }

  String deviceMid() {
    return mid;
  }

  long accountUserId() {
    return savedAccount().optLong("userid");
  }

  String accountToken() {
    return savedAccount().optString("token");
  }

  JSONObject catalogRequest(
      String endpoint, String router, TreeMap<String, String> params, Object body)
      throws Exception {
    return signed(endpoint, router, params, body);
  }

  public JSONObject catalog(String route, JSONObject data) throws Exception {
    return new CatalogApi(this).execute(route, data);
  }

  void checkSuccess(JSONObject response, String fallback) throws Exception {
    requireSuccess(response, fallback);
  }

  public JSONArray favorites(boolean refresh) throws Exception {
    return new FavoritesRepository(this).read(refresh);
  }

  public boolean isFavorite(String hash) throws Exception {
    return new FavoritesRepository(this).contains(hash);
  }

  public boolean isFavoriteTrack(JSONObject track) throws Exception {
    return new FavoritesRepository(this).containsTrack(track);
  }

  public boolean setFavorite(JSONObject track, boolean liked) throws Exception {
    return new FavoritesRepository(this).set(track, liked);
  }

  public JSONObject settings(JSONObject updates) throws Exception {
    android.content.SharedPreferences preferences =
        appContext.getSharedPreferences("preferences", Context.MODE_PRIVATE);
    android.content.SharedPreferences.Editor editor = preferences.edit();
    for (String key :
        new String[] {
          "autoVip", "resumeOnLaunch", "floatingLyrics", "lyricsTranslation", "preloadAudio"
        }) if (updates.has(key)) editor.putBoolean(key, updates.optBoolean(key));
    editor.apply();
    return new JSONObject()
        .put("autoVip", preferences.getBoolean("autoVip", true))
        .put("resumeOnLaunch", preferences.getBoolean("resumeOnLaunch", true))
        .put("floatingLyrics", preferences.getBoolean("floatingLyrics", false))
        .put("lyricsTranslation", preferences.getBoolean("lyricsTranslation", true))
        .put("preloadAudio", preferences.getBoolean("preloadAudio", true));
  }

  public JSONObject autoVip(boolean force) throws Exception {
    return new VipRepository(this, appContext).claim(force);
  }

  public JSONObject vipStatus(boolean refresh) throws Exception {
    return new VipRepository(this, appContext).read(refresh);
  }

  public JSONObject checkinStatus() throws Exception {
    return checkin().status();
  }

  public JSONObject checkinStep(boolean manual) throws Exception {
    return checkin().step(manual);
  }

  private CheckinRepository checkin() {
    if (appContext == null) throw new IllegalStateException("签到需要应用环境");
    return new CheckinRepository(this, appContext);
  }

  /**
   * 签到奖励探活：与领取相同的路径、参数和签名，但不带账号令牌，
   * 服务端在登录校验处拒绝，因此不产生任何领取或广告记录。
   */
  JSONObject probeAdReward(long adId, long playStart, long playEnd) throws Exception {
    return signed(
        "https://gateway.kugou.com/youth/v1/ad/play_report",
        "",
        new TreeMap<>(),
        rewardBody(adId, playStart, playEnd),
        null,
        false);
  }

  /** 上报一次广告播放以领取签到奖励，每次约 3 小时，每天最多 8 次。 */
  JSONObject checkinReward(long adId, long playStart, long playEnd) throws Exception {
    ensureRegistered();
    return signed(
        "https://gateway.kugou.com/youth/v1/ad/play_report",
        "",
        new TreeMap<>(),
        rewardBody(adId, playStart, playEnd));
  }

  private static JSONObject rewardBody(long adId, long playStart, long playEnd) throws Exception {
    return new JSONObject().put("ad_id", adId).put("play_end", playEnd).put("play_start", playStart);
  }

  public static String md5(String value) throws Exception {
    byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
    StringBuilder result = new StringBuilder();
    for (byte item : digest) result.append(String.format("%02x", item & 255));
    return result.toString();
  }

  public JSONObject search(String keyword, int page) throws Exception {
    if (keyword.trim().isEmpty() || keyword.length() > 200) throw new Exception("请输入有效的搜索关键词");
    TreeMap<String, String> params = new TreeMap<>();
    params.put("keyword", keyword);
    params.put("page", String.valueOf(Math.max(1, page)));
    params.put("pagesize", "30");
    params.put("platform", "AndroidFilter");
    params.put("iscorrection", "1");
    params.put("privilegefilter", "0");
    params.put("area_code", "1");
    params.put("dopicfull", "1");
    params.put("clientver", "201");
    return signed("https://gateway.kugou.com/v2/search/song", "complexsearch.kugou.com", params);
  }

  public String songUrl(JSONObject track) throws Exception {
    return songUrl(track, null);
  }

  public JSONObject bestSongUrl(JSONObject track) throws Exception {
    String desired = track.optString("quality", "128");
    if (desired.equals("128"))
      return new JSONObject().put("url", songUrl(track)).put("quality", "128");
    JSONObject resource = qualityResource(track);
    Exception last = new Exception("平台暂无可用音质资源");
    for (String quality : QualityCatalog.candidates(resource, desired)) {
      try {
        String url = songUrl(new JSONObject(track.toString()).put("quality", quality), resource);
        return new JSONObject().put("url", url).put("quality", quality);
      } catch (Exception error) {
        if (error instanceof java.io.IOException || error.getMessage().contains("安全验证"))
          throw error;
        last = error;
      }
    }
    throw last;
  }

  private String songUrl(JSONObject track, JSONObject resource) throws Exception {
    ensureRegistered();
    String hash = track.optString("hash").toLowerCase();
    if (!hash.matches("[a-f0-9]{32}")) throw new Exception("歌曲标识无效");
    String quality = track.optString("quality", "128");
    if (!java.util.Arrays.asList("128", "320", "flac", "high").contains(quality))
      throw new Exception("音质选项无效");
    if (!quality.equals("128")) {
      hash = QualityCatalog.hash(resource == null ? qualityResource(track) : resource, quality);
      if (!hash.matches("[a-f0-9]{32}")) throw new Exception("平台未提供这首歌的所选音质资源");
    }
    TreeMap<String, String> params = new TreeMap<>();
    params.put("hash", hash);
    params.put("album_id", track.optString("albumId", "0"));
    params.put("album_audio_id", track.optString("audioId", "0"));
    params.put("area_code", "1");
    params.put("ssa_flag", "is_fromtrack");
    params.put("version", "11430");
    params.put("page_id", "967177915");
    params.put("quality", quality);
    params.put("behavior", "play");
    params.put("pid", "411");
    params.put("cmd", "26");
    params.put("pidversion", "3001");
    params.put("IsFreePart", "0");
    params.put("ppage_id", "356753938,823673182,967485191");
    params.put("cdnBackup", "1");
    params.put("module", "");
    params.put("clientver", "11430");
    params.put(
        "key",
        md5(
            hash
                + "185672dd44712f60bb1736df5a377e82"
                + "3116"
                + mid
                + savedAccount().optString("userid", "0")));
    JSONObject account = savedAccount();
    JSONObject result;
    if (!account.optString("token").isEmpty() && !account.optBoolean("invalid_session")) {
      JSONObject authorization = authorizeSong(hash, track.optString("audioId", "0"));
      params.remove("IsFreePart");
      params.put("need_m", "0");
      params.put("ptype", "0");
      params.put("mtype", "0");
      params.put("need_ogg", quality.equals("128") || quality.equals("320") ? "1" : "0");
      params.put("module_id", "51");
      params.put("clientver", "11561");
      params.put("auth", authorization.optString("auth"));
      params.put("open_time", authorization.optString("open_time", "0"));
      result = signed("https://gateway.kugou.com/tracker/v5/url", "", params);
    } else result = signed("https://gateway.kugou.com/v5/url", "trackercdn.kugou.com", params);
    JSONArray urls = result.optJSONArray("url");
    if (urls == null || urls.length() == 0) {
      if (result.optInt("errcode") == 20028 || result.optInt("error_code") == 20028)
        throw new Exception("平台要求安全验证（20028）。请先登录账号；若仍出现，请在官方客户端完成验证后重试");
      throw new Exception("这首歌暂不可播放，可能需要账号权限或受地区限制");
    }
    String url = urls.getString(0);
    URL address = new URL(url);
    boolean officialAudioHost = address.getHost().equalsIgnoreCase("fs.youthandroid2.kugou.com");
    if (address.getProtocol().equals("http") && !officialAudioHost)
      url = url.replaceFirst("^http:", "https:");
    if (!url.startsWith("https://") && !(url.startsWith("http://") && officialAudioHost))
      throw new Exception("平台返回的音频地址不受支持");
    return url;
  }

  public JSONArray qualityInfo(JSONObject track) throws Exception {
    return QualityCatalog.options(qualityResource(track));
  }

  private JSONObject qualityResource(JSONObject track) throws Exception {
    JSONObject resource =
        new JSONObject()
            .put("type", "audio")
            .put("page_id", 0)
            .put("hash", track.optString("hash"))
            .put("album_id", track.optString("albumId", "0"));
    JSONArray qualities = new JSONArray();
    for (String quality :
        new String[] {
          "128",
          "320",
          "flac",
          "high",
          "viper_atmos",
          "viper_tape",
          "viper_clear",
          "super",
          "multitrack"
        }) qualities.put(quality);
    JSONObject body =
        new JSONObject()
            .put("appid", 3116)
            .put("area_code", 1)
            .put("behavior", "play")
            .put("clientver", 11440)
            .put("need_hash_offset", 1)
            .put("relate", 1)
            .put("support_verify", 1)
            .put("resource", new JSONArray().put(resource))
            .put("qualities", qualities);
    JSONObject response =
        signed(
            "https://gateway.kugou.com/v2/get_res_privilege/lite",
            "media.store.kugou.com",
            new TreeMap<>(),
            body);
    requireSuccess(response, "未能获取歌曲音质信息");
    JSONArray data = response.optJSONArray("data");
    if (data == null || data.length() == 0) throw new Exception("平台未返回音质信息");
    return data.getJSONObject(0);
  }

  /** 概念版免费听先获取用户授权，再获取本首歌曲的播放授权。 */
  private synchronized JSONObject authorizeSong(String hash, String audioId) throws Exception {
    JSONObject account = savedAccount();
    String token = account.optString("token");
    if (userAuthorization.isEmpty()
        || !authorizationToken.equals(token)
        || System.currentTimeMillis() >= authorizationExpires) {
      TreeMap<String, String> params = new TreeMap<>();
      params.put("module_id", "51");
      JSONObject response =
          signed("https://gateway.kugou.com/v1/user_verify", "trackercdngz.kugou.com", params);
      requireSuccess(response, "未能获取账号播放授权，请重新登录后重试");
      JSONObject data = response.optJSONObject("data");
      if (data == null || data.optString("auth").isEmpty()) throw new Exception("账号暂未获得播放授权");
      userAuthorization = data.optString("auth");
      authorizationToken = token;
      authorizationExpires = System.currentTimeMillis() + 10 * 60000L;
    }
    TreeMap<String, String> params = new TreeMap<>();
    params.put("authorization", userAuthorization);
    params.put("module_id", "51");
    params.put("album_audio_id", audioId.isEmpty() ? "0" : audioId);
    params.put("hash", hash);
    params.put("clientver", "11561");
    JSONObject response =
        signed("https://gateway.kugou.com/v1/authorization", "trackercdngz.kugou.com", params);
    requireSuccess(response, "未能获取歌曲播放授权，请稍后重试");
    JSONObject data = response.optJSONObject("data");
    if (data == null || data.optString("auth").isEmpty()) throw new Exception("这首歌曲暂未获得播放授权");
    return data;
  }

  private static final Map<String, JSONObject> lyricCache =
      new java.util.LinkedHashMap<String, JSONObject>(12, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, JSONObject> entry) {
          return size() > 12;
        }
      };

  public String lyrics(JSONObject track) throws Exception {
    return LyricsDocument.lrc(lyricDocument(track));
  }

  public JSONObject lyricDocument(JSONObject track) throws Exception {
    String hash = track.optString("hash").toLowerCase(java.util.Locale.ROOT);
    if (!hash.matches("[a-f0-9]{32}")) throw new Exception("歌曲标识无效");
    synchronized (lyricCache) {
      JSONObject cached = lyricCache.get(hash);
      if (cached != null) return new JSONObject(cached.toString());
    }
    TreeMap<String, String> params = new TreeMap<>();
    params.put("ver", "1");
    params.put("client", "pc");
    params.put("hash", hash);
    params.put("keyword", track.optString("title"));
    params.put("duration", "0");
    params.put("lrctxt", "1");
    params.put("man", "yes");
    JSONObject response = request("https://lyrics.kugou.com/search", "", params);
    JSONArray candidates = response.optJSONArray("candidates");
    if (candidates == null || candidates.length() == 0) return LyricsDocument.parse("");
    JSONObject candidate = candidates.getJSONObject(0);
    params.clear();
    params.put("ver", "1");
    params.put("client", "android");
    params.put("id", candidate.getString("id"));
    params.put("accesskey", candidate.getString("accesskey"));
    params.put("charset", "utf8");
    JSONObject document = null;
    try {
      params.put("fmt", "krc");
      String wire =
          signed("https://lyrics.kugou.com/download", "", new TreeMap<>(params))
              .optString("content");
      document = LyricsDocument.parse(LyricsDocument.decode(wire));
    } catch (Exception unavailable) {
    }
    if (document == null || document.getJSONArray("lines").length() == 0) {
      params.put("fmt", "lrc");
      String wire =
          signed("https://lyrics.kugou.com/download", "", new TreeMap<>(params))
              .optString("content");
      document = LyricsDocument.parse(LyricsDocument.decode(wire));
    }
    synchronized (lyricCache) {
      lyricCache.put(hash, document);
    }
    return new JSONObject(document.toString());
  }

  private JSONObject signed(String endpoint, String router, TreeMap<String, String> params)
      throws Exception {
    return signed(endpoint, router, params, null);
  }

  private JSONObject signed(
      String endpoint, String router, TreeMap<String, String> params, Object body)
      throws Exception {
    return signed(endpoint, router, params, body, null);
  }

  private JSONObject signed(
      String endpoint,
      String router,
      TreeMap<String, String> params,
      Object body,
      String responseKey)
      throws Exception {
    return signed(endpoint, router, params, body, responseKey, true);
  }

  private JSONObject signed(
      String endpoint,
      String router,
      TreeMap<String, String> params,
      Object body,
      String responseKey,
      boolean withAccount)
      throws Exception {
    if (devicePreferences != null) dfid = devicePreferences.getString("dfid", dfid);
    params.remove("signature");
    params.putIfAbsent("appid", "3116");
    params.putIfAbsent("clientver", "11440");
    params.put("clienttime", String.valueOf(System.currentTimeMillis() / 1000));
    params.put("mid", mid);
    params.put("dfid", dfid);
    params.put("uuid", "-");
    JSONObject account = savedAccount();
    if (withAccount
        && !endpoint.contains("login_by_verifycode")
        && !endpoint.contains("send_mobile_code")
        && !endpoint.contains("r_register_dev")
        && !account.optString("token").isEmpty()) {
      params.put("token", account.optString("token"));
      params.put("userid", account.optString("userid", "0"));
    }
    StringBuilder source = new StringBuilder(SALT);
    for (Map.Entry<String, String> entry : params.entrySet())
      source.append(entry.getKey()).append('=').append(entry.getValue());
    if (body != null) source.append(body.toString());
    source.append(SALT);
    params.put("signature", md5(source.toString()));
    return request(endpoint, router, params, body, responseKey);
  }

  public void ensureRegistered() throws Exception {
    if (devicePreferences == null) return;
    synchronized (DEVICE_LOCK) {
      if (devicePreferences.getBoolean("registered", false)) {
        dfid = devicePreferences.getString("dfid", "-");
        return;
      }
      String sessionKey = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
      JSONObject details =
          new JSONObject()
              .put("brand", android.os.Build.BRAND)
              .put("manufacturer", android.os.Build.MANUFACTURER)
              .put("device", android.os.Build.DEVICE)
              .put("uuid", guid)
              .put("imei", guid)
              .put("buildSerial", "unknown")
              .put("imsi", "")
              .put("basebandVer", "");
      for (String sensor :
          new String[] {
            "accelerometer",
            "gravity",
            "gyroscope",
            "light",
            "magnetic",
            "orientation",
            "pressure",
            "step_counter",
            "temperature"
          }) details.put(sensor, false).put(sensor + "Value", "");
      TreeMap<String, String> params = new TreeMap<>();
      params.put("part", "1");
      params.put("platid", "1");
      params.put(
          "p",
          LoginProtocol.rsaSession(
              new JSONObject().put("aes", sessionKey).put("uid", 0).put("token", "").toString()));
      JSONObject result =
          signed(
              "https://userservice.kugou.com/risk/v3/r_register_dev",
              "",
              params,
              LoginProtocol.encryptRegistration(details.toString(), sessionKey),
              sessionKey);
      requireSuccess(result, "设备注册失败，请稍后重试");
      JSONObject data = result.optJSONObject("data");
      if (data == null || data.optString("dfid").isEmpty()) throw new Exception("设备注册未完成，请稍后重试");
      dfid = data.optString("dfid");
      devicePreferences.edit().putString("dfid", dfid).putBoolean("registered", true).apply();
    }
  }

  public JSONObject account() throws Exception {
    JSONObject account = savedAccount();
    return new JSONObject()
        .put(
            "loggedIn",
            !account.optString("token").isEmpty() && !account.optBoolean("invalid_session"))
        .put("name", account.optString("nickname", "酷狗用户"))
        .put("avatar", account.optString("pic", ""));
  }

  public synchronized void logout() {
    loginChoices = null;
    accounts.clear();
  }

  public JSONObject sendCode(String mobile) throws Exception {
    validateMobile(mobile);
    ensureRegistered();
    JSONObject body = new JSONObject().put("businessid", 5).put("mobile", mobile).put("plat", 3);
    JSONObject response =
        signed("https://login-user.kugou.com/v7/send_mobile_code", "", new TreeMap<>(), body);
    requireSuccess(response, "验证码发送失败，请稍后重试");
    return new JSONObject().put("sent", true);
  }

  public synchronized JSONObject loginMobile(String mobile, String code) throws Exception {
    loginChoices = null;
    return loginMobile(mobile, code, 0);
  }

  public void cancelMobileLogin() {
    loginChoices = null;
  }

  public synchronized JSONObject chooseLoginAccount(String key) throws Exception {
    LoginChoices pending = loginChoices;
    if (pending == null) throw new Exception("本次登录已过期，请重新获取验证码");
    long user;
    try {
      user = pending.select(key, System.currentTimeMillis());
    } catch (Exception error) {
      loginChoices = null;
      throw error;
    }
    return loginMobile(pending.mobile, pending.code, user);
  }

  private JSONObject loginMobile(String mobile, String code, long user) throws Exception {
    validateMobile(mobile);
    ensureRegistered();
    if (!code.matches("[0-9]{4,8}")) throw new Exception("请输入有效的短信验证码");
    long time = System.currentTimeMillis();
    String sessionKey = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    String encrypted =
        LoginProtocol.encryptSession(
            new JSONObject().put("mobile", mobile).put("code", code).toString(), sessionKey);
    String t1 =
        LoginProtocol.encrypt("|" + time, "5e4ef500e9597fe004bd09a46d8add98", "04bd09a46d8add98");
    String device = model;
    String t2 =
        LoginProtocol.encrypt(
            guid + "|0f607264fc6318a92b9e13c65db7cd3c|02:00:00:00:00:00|" + device + "|" + time,
            "fd14b35e3f81af3817a20ae7adae7020",
            "17a20ae7adae7020");
    JSONObject body =
        new JSONObject()
            .put("plat", 1)
            .put("support_multi", 1)
            .put("t1", t1)
            .put("t2", t2)
            .put("clienttime_ms", time)
            .put("mobile", mobile.substring(0, 2) + "*****" + mobile.substring(10))
            .put("key", md5("3116" + SALT + "11440" + time))
            .put(
                "pk",
                LoginProtocol.rsa(
                    new JSONObject().put("clienttime_ms", time).put("key", sessionKey).toString()))
            .put("params", encrypted)
            .put("dfid", dfid)
            .put("dev", device)
            .put("gitversion", "5f0b7c4");
    if (user > 0) body.put("userid", user);
    JSONObject response =
        signed(
            "https://loginserviceretry.kugou.com/v7/login_by_verifycode",
            "",
            new TreeMap<>(),
            body);
    if (response.optInt("error_code", response.optInt("errcode")) == 34175) {
      loginChoices = new LoginChoices(mobile, code, response, System.currentTimeMillis());
      return loginChoices.view();
    }
    requireSuccess(response, "登录失败，请检查验证码或稍后重试");
    JSONObject account = response.optJSONObject("data");
    if (account == null) throw new Exception("登录服务未返回账号信息");
    if (!account.optString("secu_params").isEmpty()) {
      String decrypted = LoginProtocol.decryptSession(account.optString("secu_params"), sessionKey);
      try {
        JSONObject values = new JSONObject(decrypted);
        java.util.Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
          String key = keys.next();
          account.put(key, values.get(key));
        }
      } catch (org.json.JSONException ignored) {
        account.put("token", decrypted);
      }
    }
    if (account.optString("token").isEmpty()) throw new Exception("登录未完成，请按平台提示验证账号");
    account.put("source_appid", 3116);
    accounts.save(account);
    loginChoices = null;
    return account();
  }

  private void validateMobile(String mobile) throws Exception {
    if (!mobile.matches("1[3-9][0-9]{9}")) throw new Exception("请输入正确的中国大陆手机号码");
  }

  private void requireSuccess(JSONObject response, String fallback) throws Exception {
    if (response.optInt("status") == 1) return;
    if (response.optInt("errcode") == 20028 || response.optInt("error_code") == 20028)
      throw new Exception("平台要求安全验证，请稍后重试或使用扫码登录");
    String message =
        response.optString(
            "error_msg", response.optString("msg", response.optString("error", fallback)));
    int code = response.optInt("error_code", response.optInt("errcode", 0));
    throw new Exception(
        (message.isEmpty() ? fallback : message) + (code == 0 ? "" : "（" + code + "）"));
  }

  public JSONObject qrKey() throws Exception {
    ensureRegistered();
    TreeMap<String, String> params = new TreeMap<>();
    params.put("appid", "1001");
    params.put("type", "1");
    params.put("plat", "4");
    params.put("srcappid", "2919");
    params.put("qrcode_txt", "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=3116&");
    return webSigned("https://login-user.kugou.com/v2/qrcode", params);
  }

  public JSONObject qrCheck(String key) throws Exception {
    if (key.length() > 300) throw new Exception("二维码无效");
    TreeMap<String, String> params = new TreeMap<>();
    params.put("appid", "3116");
    params.put("plat", "4");
    params.put("srcappid", "2919");
    params.put("qrcode", key);
    JSONObject response = webSigned("https://login-user.kugou.com/v2/get_userinfo_qrcode", params);
    JSONObject data = response.optJSONObject("data");
    if (data != null && data.optInt("status") == 4 && !data.optString("token").isEmpty()) {
      data.put("source_appid", 3116);
      accounts.save(data);
    }
    return new JSONObject()
        .put("data", new JSONObject().put("status", data == null ? -1 : data.optInt("status")));
  }

  private JSONObject webSigned(String endpoint, TreeMap<String, String> params) throws Exception {
    params.put("clienttime", String.valueOf(System.currentTimeMillis() / 1000));
    params.put("clientver", "11440");
    params.put("mid", mid);
    params.put("dfid", dfid);
    params.put("uuid", "-");
    String salt = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt";
    StringBuilder source = new StringBuilder(salt);
    for (Map.Entry<String, String> entry : params.entrySet())
      source.append(entry.getKey()).append('=').append(entry.getValue());
    params.put("signature", md5(source.append(salt).toString()));
    return request(endpoint, "", params);
  }

  public JSONObject cloudPlaylists() throws Exception {
    JSONObject account = savedAccount();
    if (account.optString("token").isEmpty()) throw new Exception("请先登录酷狗账号");
    JSONObject body =
        new JSONObject()
            .put("userid", account.optLong("userid"))
            .put("token", account.optString("token"))
            .put("total_ver", 979)
            .put("type", 2)
            .put("page", 1)
            .put("pagesize", 100);
    TreeMap<String, String> params = new TreeMap<>();
    params.put("plat", "1");
    JSONObject response =
        signed(
            "https://gateway.kugou.com/v7/get_all_list",
            "cloudlist.service.kugou.com",
            params,
            body);
    if (response.optInt("error_code") == 20017) {
      account.put("invalid_session", true);
      accounts.save(account);
      throw new Exception("账号登录信息已失效，请重新使用手机验证码登录");
    }
    requireSuccess(response, "云端歌单读取失败，请稍后重试");
    return response;
  }

  public JSONObject playlist(String id, int page) throws Exception {
    if (!id.matches("[a-zA-Z0-9_-]{1,100}")) throw new Exception("歌单标识无效");
    TreeMap<String, String> params = new TreeMap<>();
    params.put("global_collection_id", id);
    params.put("begin_idx", String.valueOf((Math.max(1, page) - 1) * 100));
    params.put("area_code", "1");
    params.put("plat", "1");
    params.put("type", "1");
    params.put("mode", "1");
    params.put("personal_switch", "1");
    params.put("pagesize", "100");
    return signed("https://gateway.kugou.com/pubsongs/v2/get_other_list_file_nofilt", "", params);
  }

  private JSONObject request(String endpoint, String router, TreeMap<String, String> params)
      throws Exception {
    return request(endpoint, router, params, null);
  }

  private JSONObject request(
      String endpoint, String router, TreeMap<String, String> params, Object body)
      throws Exception {
    return request(endpoint, router, params, body, null);
  }

  private JSONObject request(
      String endpoint,
      String router,
      TreeMap<String, String> params,
      Object body,
      String responseKey)
      throws Exception {
    StringBuilder address = new StringBuilder(endpoint).append('?');
    for (Map.Entry<String, String> entry : params.entrySet()) {
      address
          .append(URLEncoder.encode(entry.getKey(), "UTF-8"))
          .append('=')
          .append(URLEncoder.encode(entry.getValue(), "UTF-8"))
          .append('&');
    }
    HttpURLConnection connection = (HttpURLConnection) new URL(address.toString()).openConnection();
    connection.setConnectTimeout(12000);
    connection.setReadTimeout(15000);
    connection.setRequestProperty(
        "User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi");
    if (endpoint.contains("login_by_verifycode")) {
      connection.setRequestProperty("User-Agent", "Android16-1070-11440-130-0-LOGIN-wifi");
      connection.setRequestProperty("support-calm", "1");
    }
    connection.setRequestProperty("mid", mid);
    connection.setRequestProperty("dfid", dfid);
    connection.setRequestProperty("kg-rc", "1");
    connection.setRequestProperty("kg-thash", "5d816a0");
    connection.setRequestProperty("kg-rec", "1");
    connection.setRequestProperty("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");
    connection.setRequestProperty("clienttime", params.getOrDefault("clienttime", "0"));
    if (!router.isEmpty()) connection.setRequestProperty("x-router", router);
    if (endpoint.contains("/kmr/"))
      connection.setRequestProperty(
          "kg-tid",
          endpoint.contains("rank/audio") ? "369" : endpoint.contains("/albums") ? "255" : "36");
    if (endpoint.contains("/album_audio/")) connection.setRequestProperty("kg-tid", "255");
    try {
      if (body != null) {
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty(
            "Content-Type",
            body instanceof String
                ? "application/x-www-form-urlencoded"
                : "application/json; charset=UTF-8");
        try (java.io.OutputStream output = connection.getOutputStream()) {
          output.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
      }
      if (connection.getResponseCode() != 200) throw new Exception("音乐服务暂不可用，请稍后重试");
      try (InputStream stream = connection.getInputStream();
          ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) {
          if (bytes.size() + count > 4 * 1024 * 1024) throw new Exception("服务返回内容过大");
          bytes.write(buffer, 0, count);
        }
        String wire = bytes.toString("UTF-8").trim();
        String content =
            responseKey == null || wire.startsWith("{")
                ? wire
                : LoginProtocol.decryptRegistration(bytes.toByteArray(), responseKey);
        if (content.isEmpty()) throw new Exception("音乐服务未返回内容，请稍后重试");
        return new JSONObject(content);
      }
    } finally {
      connection.disconnect();
    }
  }
}
