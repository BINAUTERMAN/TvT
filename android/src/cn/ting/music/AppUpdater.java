package cn.ting.music;

import android.app.DownloadManager;
import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import org.json.*;

/** 更新仅访问公开发行版；安装前核对摘要、包名、签名和递增安装序号。 */
final class AppUpdater {
  private final Context context;
  private final SharedPreferences saved;
  private final String repository;
  private final UpdateSource gitee;
  private final UpdateSource github;
  private final String sourceKey;
  private final long interval;

  AppUpdater(Context context) throws Exception {
    this.context = context;
    saved = context.getSharedPreferences("updates", Context.MODE_PRIVATE);
    JSONObject config = new JSONObject(read(context.getAssets().open("update.json"), 8192));
    github = new UpdateSource("github", config.optString("repository"));
    gitee = new UpdateSource("gitee", config.optString("giteeRepository"));
    repository = config.optString("projectRepository", github.repository);
    sourceKey = gitee.repository + "|" + github.repository;
    interval = Math.max(1, config.optInt("checkIntervalHours", 6)) * 3600000L;
  }

  JSONObject settings(JSONObject data) throws Exception {
    SharedPreferences.Editor edit = saved.edit();
    if (data.has("enabled")) edit.putBoolean("enabled", data.optBoolean("enabled"));
    if (data.has("skip")) edit.putLong("skip", data.optLong("skip"));
    if (data.has("dontWarn")) edit.putBoolean("dontWarn", data.optBoolean("dontWarn"));
    edit.apply();
    return new JSONObject()
        .put("enabled", saved.getBoolean("enabled", true))
        .put("skip", saved.getLong("skip", 0))
        .put("dontWarn", saved.getBoolean("dontWarn", false))
        .put("configured", configured())
        .put("repository", repository)
        .put("source", gitee.configured() ? "gitee" : "github")
        .put(
            "currentVersion",
            context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName);
  }

  private boolean configured() {
    return gitee.configured() || github.configured();
  }

  synchronized JSONObject check(boolean manual) throws Exception {
    if (!configured()) return new JSONObject().put("configured", false);
    if (!manual && !saved.getBoolean("enabled", true)) return new JSONObject().put("skipped", true);
    long current = version(context.getPackageManager().getPackageInfo(context.getPackageName(), 0));
    if (!manual && sourceKey.equals(saved.getString("sourceKey", ""))
        && System.currentTimeMillis() - saved.getLong("checked", 0) < interval) {
      String cached = saved.getString("candidate", "");
      if (!cached.isEmpty()) {
        JSONObject result = new JSONObject(cached);
        return result.put("available", result.optLong("versionCode") > current)
            .put("ignored", saved.getLong("skip", 0) == result.optLong("versionCode"));
      }
    }
    StringBuilder errors = new StringBuilder();
    for (UpdateSource source : new UpdateSource[] {gitee, github}) {
      if (!source.configured()) continue;
      try {
        JSONObject release = fetch(source.latestUrl(), source);
        JSONObject manifest = fetch(source.manifestUrl(release), source);
        JSONObject result = source.candidate(release, manifest, current);
        if (source == gitee && github.configured())
          result.put("fallbackUrl", github.downloadUrl(result.getString("tag"), result.getString("file")));
        result.put("ignored", !manual && saved.getLong("skip", 0) == result.optLong("versionCode"));
        saved.edit().putLong("checked", System.currentTimeMillis()).putString("sourceKey", sourceKey)
            .putString("candidate", result.toString()).apply();
        return result;
      } catch (Exception unavailable) {
        if (errors.length() > 0) errors.append("；");
        errors.append(source.name()).append("：").append(unavailable.getMessage());
      }
    }
    throw new Exception("检查更新失败（" + errors + "）");
  }

  synchronized JSONObject download() throws Exception {
    JSONObject candidate = new JSONObject(saved.getString("candidate", "{}"));
    if (!candidate.optBoolean("available")) throw new Exception("请先检查可用更新");
    JSONObject state = downloadState();
    if (state.optString("state").equals("downloading")) return state;
    return enqueue(candidate, false);
  }

  private JSONObject enqueue(JSONObject candidate, boolean fallback) throws Exception {
    String url = candidate.getString(fallback ? "fallbackUrl" : "url");
    validateDownloadUrl(url);
    File file = apkFile();
    if (file.exists() && !file.delete()) throw new Exception("旧安装包清理失败");
    DownloadManager.Request request =
        new DownloadManager.Request(Uri.parse(url))
            .setTitle("TvT " + candidate.optString("version"))
            .setDescription("正在下载更新，完成后请返回TvT安装")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context, Environment.DIRECTORY_DOWNLOADS, "ting-update.apk");
    long id =
        ((DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(request);
    saved
        .edit()
        .putLong("downloadId", id)
        .putString("downloadCandidate", candidate.toString())
        .putBoolean("downloadFallback", fallback)
        .apply();
    return new JSONObject().put("state", "downloading");
  }

  synchronized JSONObject status() throws Exception {
    JSONObject state = downloadState();
    JSONObject candidate = new JSONObject(saved.getString("downloadCandidate", "{}"));
    int reason = state.optInt("reason");
    boolean remoteFailure = (reason >= 400 && reason <= 599)
        || reason == DownloadManager.ERROR_HTTP_DATA_ERROR
        || reason == DownloadManager.ERROR_UNHANDLED_HTTP_CODE
        || reason == DownloadManager.ERROR_TOO_MANY_REDIRECTS
        || reason == DownloadManager.ERROR_CANNOT_RESUME;
    // 同一清单与摘要下只切换一次备用附件，不启动后台轮询。
    if (state.optString("state").equals("failed") && remoteFailure
        && !saved.getBoolean("downloadFallback", false) && candidate.has("fallbackUrl"))
      return enqueue(candidate, true);
    return state;
  }

  private JSONObject downloadState() throws Exception {
    long id = saved.getLong("downloadId", 0);
    if (id == 0) return new JSONObject().put("state", "idle");
    try (Cursor cursor =
        ((DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE))
            .query(new DownloadManager.Query().setFilterById(id))) {
      if (cursor == null || !cursor.moveToFirst()) return new JSONObject().put("state", "idle");
      int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
      return new JSONObject()
          .put("reason", cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
          .put(
              "state",
              status == DownloadManager.STATUS_SUCCESSFUL
                  ? "ready"
                  : status == DownloadManager.STATUS_FAILED ? "failed" : "downloading");
    }
  }

  synchronized Intent installation() throws Exception {
    if (!status().optString("state").equals("ready")) throw new Exception("安装包尚未下载完成");
    JSONObject candidate = new JSONObject(saved.getString("downloadCandidate", "{}"));
    File file = apkFile();
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream input = new FileInputStream(file)) {
      byte[] buffer = new byte[65536];
      int count;
      while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
    }
    StringBuilder hash = new StringBuilder();
    for (byte value : digest.digest()) hash.append(String.format("%02x", value & 255));
    if (!hash.toString().equals(candidate.optString("sha256")))
      throw new Exception("安装包校验失败，请重新下载");
    PackageManager packages = context.getPackageManager();
    int flags =
        Build.VERSION.SDK_INT >= 28
            ? PackageManager.GET_SIGNING_CERTIFICATES
            : PackageManager.GET_SIGNATURES;
    PackageInfo installed = packages.getPackageInfo(context.getPackageName(), flags),
        incoming = packages.getPackageArchiveInfo(file.getAbsolutePath(), flags);
    if (incoming == null
        || !context.getPackageName().equals(incoming.packageName)
        || version(incoming) != candidate.optLong("versionCode")
        || version(incoming) <= version(installed)) throw new Exception("安装包版本与更新清单不匹配");
    if (!Arrays.equals(signatures(installed), signatures(incoming)))
      throw new Exception("安装包签名不匹配，已阻止安装");
    if (!packages.canRequestPackageInstalls())
      return new Intent(
          Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
          Uri.parse("package:" + context.getPackageName()));
    Uri uri =
        ((DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE))
            .getUriForDownloadedFile(saved.getLong("downloadId", 0));
    if (uri == null) throw new Exception("无法读取安装包，请重新下载");
    return new Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
  }

  Intent releases() throws Exception {
    if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) throw new Exception("此构建尚未配置项目仓库");
    return new Intent(
        Intent.ACTION_VIEW, Uri.parse("https://github.com/" + repository + "/releases"));
  }

  boolean ownsDownload(long id) {
    return id > 0 && id == saved.getLong("downloadId", 0);
  }

  private static long version(PackageInfo info) {
    return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
  }

  private static Signature[] signatures(PackageInfo info) {
    return Build.VERSION.SDK_INT >= 28
        ? (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners())
        : info.signatures;
  }

  private File apkFile() {
    return new File(
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ting-update.apk");
  }

  private void validateDownloadUrl(String address) throws Exception {
    String host = new URL(address).getHost();
    if (host.equals("gitee.com")) gitee.validateDownloadUrl(address);
    else if (host.equals("github.com")) github.validateDownloadUrl(address);
    else throw new Exception("更新地址不属于配置的发行仓库");
  }

  private JSONObject fetch(String address, UpdateSource source) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
    connection.setConnectTimeout(12000);
    connection.setReadTimeout(20000);
    connection.setRequestProperty("Accept", "application/json");
    connection.setRequestProperty("User-Agent", "TvT-Android");
    try {
      int code = connection.getResponseCode();
      if (code == 404) throw new Exception("暂无公开发行版或更新文件");
      if (code == 401 || code == 403 || code == 429)
        throw new Exception(source.name() + "访问受限，请确认仓库公开且附件可匿名下载");
      if (code != 200) throw new Exception("更新服务返回 HTTP " + code);
      try (InputStream input = connection.getInputStream()) {
        String body = read(input, 2 * 1024 * 1024);
        if (!body.trim().startsWith("{")) throw new Exception("未返回更新数据，可能需要登录或验证");
        return new JSONObject(body);
      }
    } finally {
      connection.disconnect();
    }
  }

  private static String read(InputStream input, int limit) throws Exception {
    try (InputStream source = input;
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = source.read(buffer)) != -1) {
        if (output.size() + count > limit) throw new Exception("更新信息过大");
        output.write(buffer, 0, count);
      }
      return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
  }
}
