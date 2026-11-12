package cn.ting.music;

import org.json.*;

public final class UpdateSourceCheck {
  private static void reject(UpdateSource source, String url) throws Exception {
    try { source.validateDownloadUrl(url); }
    catch (Exception expected) { return; }
    throw new AssertionError("不应接受更新地址：" + url);
  }

  public static void main(String[] args) throws Exception {
    UpdateSource gitee = new UpdateSource("gitee", "binauterman/TvT");
    UpdateSource github = new UpdateSource("github", "BINAUTERMAN/TvT");
    if (!gitee.latestUrl().equals("https://gitee.com/api/v5/repos/binauterman/TvT/releases/latest"))
      throw new AssertionError("Gitee 主源 API 错误");
    if (!github.latestUrl().equals("https://api.github.com/repos/BINAUTERMAN/TvT/releases/latest"))
      throw new AssertionError("GitHub 备用 API 错误");
    String apk = gitee.downloadUrl("build-100006", "TvT-5.3.apk");
    reject(gitee, "http://gitee.com/binauterman/TvT/releases/download/v1/app.apk");
    reject(gitee, "https://gitee.com/other/TvT/releases/download/v1/app.apk");
    reject(gitee, "https://gitee.com.evil.test/binauterman/TvT/releases/download/v1/app.apk");
    reject(gitee, "https://evil@gitee.com/binauterman/TvT/releases/download/v1/app.apk");
    reject(gitee, "https://gitee.com/binauterman/TvT/releases/download/../app.apk");
    reject(gitee, "https://gitee.com/binauterman/TvT/releases/download/%2e%2e/app.apk");
    reject(gitee, "https://gitee.com/binauterman/TvT/releases/download/v1/app.apk?access_token=test");
    reject(github, apk);
    JSONArray assets = new JSONArray()
        .put(new JSONObject().put("name", "update-manifest.json").put("browser_download_url", gitee.downloadUrl("build-100006", "update-manifest.json")))
        .put(new JSONObject().put("name", "TvT-5.3.apk").put("browser_download_url", apk));
    JSONObject release = new JSONObject().put("tag_name", "build-100006").put("name", "TvT 5.3")
        .put("created_at", "2026-11-12T12:00:00+08:00").put("assets", assets);
    JSONObject manifest = new JSONObject().put("schema", 1).put("versionCode", 100006)
        .put("versionName", "5.3").put("file", "TvT-5.3.apk").put("sha256", new String(new char[64]).replace('\0', 'a'));
    JSONObject candidate = gitee.candidate(release, manifest, 100005);
    if (!candidate.getBoolean("available") || !candidate.getString("source").equals("gitee")
        || !candidate.getString("url").equals(apk) || candidate.optLong("size") != 0
        || !candidate.getString("publishedAt").equals(release.getString("created_at")))
      throw new AssertionError("Gitee 元数据未按实际结构解析");
    if (gitee.candidate(release, manifest, 100006).getBoolean("available"))
      throw new AssertionError("相同安装序号不能当成更新");
    release.put("prerelease", true);
    try { gitee.manifestUrl(release); throw new AssertionError("不能使用未完成同步的预览发行版"); }
    catch (AssertionError failure) { throw failure; }
    catch (Exception expected) {}
    release.put("prerelease", false);
    manifest.put("sha256", "bad");
    try { gitee.candidate(release, manifest, 0); throw new AssertionError("必须校验摘要格式"); }
    catch (AssertionError failure) { throw failure; }
    catch (Exception expected) {}
    System.out.println("PASS Gitee/GitHub metadata, trusted URLs, manifest validation and version selection");
  }
}
