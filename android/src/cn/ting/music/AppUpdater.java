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
  private final long interval;

  AppUpdater(Context context) throws Exception {
    this.context = context;
    saved = context.getSharedPreferences("updates", Context.MODE_PRIVATE);
    JSONObject config = new JSONObject(read(context.getAssets().open("update.json"), 8192));
    repository = config.optString("repository");
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
        .put(
            "currentVersion",
            context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName);
  }

  private boolean configured() {
    return repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
  }

  synchronized JSONObject check(boolean manual) throws Exception {
    if (!configured()) return new JSONObject().put("configured", false);
    if (!manual && !saved.getBoolean("enabled", true)) return new JSONObject().put("skipped", true);
    JSONObject release;
    if (!manual && System.currentTimeMillis() - saved.getLong("checked", 0) < interval) {
      String cached = saved.getString("candidate", "");
      if (cached.isEmpty()) return new JSONObject().put("skipped", true);
      JSONObject result = new JSONObject(cached);
      return result
          .put("ignored", saved.getLong("skip", 0) == result.optLong("versionCode"))
          .put(
              "available",
              result.optLong("versionCode")
                  > version(
                      context.getPackageManager().getPackageInfo(context.getPackageName(), 0)));
    } else {
      release = fetch("https://api.github.com/repos/" + repository + "/releases/latest");
      if (release.optBoolean("draft") || release.optBoolean("prerelease"))
        throw new Exception("暂无稳定发行版");
      saved
          .edit()
          .putLong("checked", System.currentTimeMillis())
          .putString("release", release.toString())
          .apply();
    }
    JSONArray assets = release.optJSONArray("assets");
    JSONObject manifestAsset = null;
    if (assets != null)
      for (int index = 0; index < assets.length(); index++) {
        JSONObject asset = assets.getJSONObject(index);
        if (asset.optString("name").equals("update-manifest.json")) manifestAsset = asset;
      }
    if (manifestAsset == null) throw new Exception("该发行版尚未提供更新清单");
    String manifestUrl = manifestAsset.optString("browser_download_url");
    validateDownloadUrl(manifestUrl);
    JSONObject manifest = fetch(manifestUrl);
    String file = manifest.optString("file"), sha = manifest.optString("sha256");
    long code = manifest.optLong("versionCode");
    JSONObject apk = null;
    if (assets != null)
      for (int index = 0; index < assets.length(); index++) {
        JSONObject asset = assets.getJSONObject(index);
        if (asset.optString("name").equals(file) && file.endsWith(".apk")) apk = asset;
      }
    if (apk == null || !sha.matches("[a-fA-F0-9]{64}") || code < 1)
      throw new Exception("更新清单不完整，无法验证安装包");
    String url = apk.getString("browser_download_url");
    validateDownloadUrl(url);
    long current = version(context.getPackageManager().getPackageInfo(context.getPackageName(), 0));
    JSONObject result =
        new JSONObject()
            .put("configured", true)
            .put("available", code > current)
            .put("ignored", !manual && saved.getLong("skip", 0) == code)
            .put("versionCode", code)
            .put("version", manifest.optString("versionName", release.optString("tag_name")))
            .put("name", release.optString("name"))
            .put("notes", release.optString("body"))
            .put("publishedAt", release.optString("published_at"))
            .put("url", url)
            .put("sha256", sha.toLowerCase(java.util.Locale.ROOT))
            .put("size", apk.optLong("size"))
            .put("rollback", manifest.optBoolean("rollback"));
    saved.edit().putString("candidate", result.toString()).apply();
    return result;
  }

  synchronized JSONObject download() throws Exception {
    JSONObject candidate = new JSONObject(saved.getString("candidate", "{}"));
    if (!candidate.optBoolean("available")) throw new Exception("请先检查可用更新");
    JSONObject state = status();
    if (state.optString("state").equals("downloading")) return state;
    String url = candidate.getString("url");
    validateDownloadUrl(url);
    File file = apkFile();
    if (file.exists() && !file.delete()) throw new Exception("旧安装包清理失败");
    DownloadManager.Request request =
        new DownloadManager.Request(Uri.parse(url))
            .setTitle("TvT " + candidate.optString("version"))
            .setDescription("正在下载更新，完成后请返回TvT安装")
            .setMimeType("application/octet-stream")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context, Environment.DIRECTORY_DOWNLOADS, "ting-update.apk");
    long id =
        ((DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(request);
    saved
        .edit()
        .putLong("downloadId", id)
        .putString("downloadCandidate", candidate.toString())
        .apply();
    return new JSONObject().put("state", "downloading");
  }

  JSONObject status() throws Exception {
    long id = saved.getLong("downloadId", 0);
    if (id == 0) return new JSONObject().put("state", "idle");
    try (Cursor cursor =
        ((DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE))
            .query(new DownloadManager.Query().setFilterById(id))) {
      if (cursor == null || !cursor.moveToFirst()) return new JSONObject().put("state", "idle");
      int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
      return new JSONObject()
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
    if (!configured()) throw new Exception("此构建尚未配置更新仓库");
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
    URL url = new URL(address);
    if (!url.getProtocol().equals("https")
        || !url.getHost().equals("github.com")
        || !url.getPath().startsWith("/" + repository + "/releases/download/"))
      throw new Exception("更新地址不属于配置的 GitHub 仓库");
  }

  private JSONObject fetch(String address) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
    connection.setConnectTimeout(12000);
    connection.setReadTimeout(20000);
    connection.setRequestProperty("Accept", "application/vnd.github+json");
    connection.setRequestProperty("User-Agent", "Ting-Music-Android");
    try {
      int code = connection.getResponseCode();
      if (code == 404) throw new Exception("暂无公开发行版");
      if (code == 403 || code == 429) throw new Exception("GitHub 请求受限，请稍后再试");
      if (code != 200) throw new Exception("更新服务暂不可用");
      try (InputStream input = connection.getInputStream()) {
        return new JSONObject(read(input, 2 * 1024 * 1024));
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
