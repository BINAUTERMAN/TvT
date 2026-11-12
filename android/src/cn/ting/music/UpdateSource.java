package cn.ting.music;

import java.net.URL;
import java.util.Locale;
import org.json.*;

/** 更新来源独立于 GitHub 项目链接，附件必须属于配置的公开发行仓库。 */
final class UpdateSource {
  final String provider;
  final String repository;

  UpdateSource(String provider, String repository) {
    if (!provider.equals("gitee") && !provider.equals("github"))
      throw new IllegalArgumentException("不支持的更新源");
    this.provider = provider;
    this.repository = repository;
  }

  boolean configured() {
    return repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
  }

  String name() { return provider.equals("gitee") ? "Gitee" : "GitHub"; }

  String latestUrl() {
    return provider.equals("gitee")
        ? "https://gitee.com/api/v5/repos/" + repository + "/releases/latest"
        : "https://api.github.com/repos/" + repository + "/releases/latest";
  }

  String downloadUrl(String tag, String filename) throws Exception {
    if (!tag.matches("[A-Za-z0-9_.-]+") || !filename.matches("[A-Za-z0-9_.-]+"))
      throw new Exception("发行版标签或文件名无效");
    String address = "https://" + (provider.equals("gitee") ? "gitee.com" : "github.com")
        + "/" + repository + "/releases/download/" + tag + "/" + filename;
    validateDownloadUrl(address);
    return address;
  }

  void validateDownloadUrl(String address) throws Exception {
    URL url = new URL(address);
    String host = provider.equals("gitee") ? "gitee.com" : "github.com";
    String path = url.getPath();
    if (!configured() || !url.getProtocol().equals("https") || !url.getHost().equals(host)
        || url.getUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443)
        || url.getQuery() != null || url.getRef() != null
        || !path.startsWith("/" + repository + "/releases/download/")
        || path.contains("/../") || path.contains("/./") || path.toLowerCase(Locale.ROOT).contains("%"))
      throw new Exception("更新地址不属于配置的 " + name() + " 仓库");
  }

  private JSONObject asset(JSONObject release, String filename) throws Exception {
    if (release.optBoolean("draft") || release.optBoolean("prerelease"))
      throw new Exception("暂无稳定发行版");
    JSONArray assets = release.optJSONArray("assets");
    if (assets != null)
      for (int index = 0; index < assets.length(); index++) {
        JSONObject entry = assets.getJSONObject(index);
        if (filename.equals(entry.optString("name"))) {
          validateDownloadUrl(entry.optString("browser_download_url"));
          return entry;
        }
      }
    throw new Exception("发行版缺少 " + filename);
  }

  String manifestUrl(JSONObject release) throws Exception {
    return asset(release, "update-manifest.json").getString("browser_download_url");
  }

  JSONObject candidate(JSONObject release, JSONObject manifest, long current) throws Exception {
    String filename = manifest.optString("file"), sha = manifest.optString("sha256");
    long version = manifest.optLong("versionCode");
    if (manifest.optInt("schema", 1) != 1 || !filename.matches("[A-Za-z0-9_.-]+\\.apk")
        || !sha.matches("[a-fA-F0-9]{64}") || version < 1)
      throw new Exception("更新清单不完整，无法验证安装包");
    JSONObject apk = asset(release, filename);
    return new JSONObject().put("configured", true).put("available", version > current)
        .put("versionCode", version).put("version", manifest.optString("versionName", release.optString("tag_name")))
        .put("tag", release.optString("tag_name")).put("file", filename)
        .put("name", release.optString("name")).put("notes", release.optString("body"))
        .put("publishedAt", release.optString("published_at", release.optString("created_at")))
        .put("url", apk.getString("browser_download_url")).put("sha256", sha.toLowerCase(Locale.ROOT))
        .put("size", apk.optLong("size")).put("rollback", manifest.optBoolean("rollback"))
        .put("source", provider);
  }
}
