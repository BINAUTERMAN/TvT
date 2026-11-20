package cn.ting.music.tests;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import java.lang.reflect.*;
import org.json.JSONObject;

/** 使用独立偏好和不存在的下载编号验证失效规则，不改动用户下载记录。 */
public final class UpdateDownloadChecks extends Instrumentation {
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

  private String hash(char value) {
    char[] result = new char[64];
    java.util.Arrays.fill(result, value);
    return new String(result);
  }

  private void check(String name, boolean pass) throws Exception {
    Bundle status = new Bundle();
    status.putString("stream", name + ": " + (pass ? "PASS" : "FAIL") + "\n");
    sendStatus(0, status);
    if (!pass) throw new Exception(name);
  }

  @Override public void onStart() {
    Context target = getTargetContext();
    SharedPreferences prefs = target.getSharedPreferences("update_download_verification", 0);
    Bundle result = new Bundle();
    int code = Activity.RESULT_OK;
    try {
      Context isolated = new ContextWrapper(target) {
        @Override public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
      };
      Class<?> type = target.getClassLoader().loadClass("cn.ting.music.AppUpdater");
      Constructor<?> constructor = type.getDeclaredConstructor(Context.class);
      constructor.setAccessible(true);
      Object updater = constructor.newInstance(isolated);
      Method status = type.getDeclaredMethod("status");
      status.setAccessible(true);
      long current = target.getPackageManager().getPackageInfo(target.getPackageName(), 0).versionCode;
      JSONObject latest = new JSONObject().put("versionCode", current + 2)
          .put("sha256", hash('a')).put("file", "latest.apk");
      JSONObject old = new JSONObject(latest.toString()).put("versionCode", current + 1);
      prefs.edit().clear().putLong("downloadId", Long.MAX_VALUE)
          .putString("candidate", latest.toString()).putString("downloadCandidate", old.toString()).commit();
      check("New release invalidates an older download", ((JSONObject) status.invoke(updater)).getString("state").equals("idle") && !prefs.contains("downloadId"));
      prefs.edit().putLong("downloadId", Long.MAX_VALUE).putString("downloadCandidate", latest.toString()).commit();
      status.invoke(updater);
      check("Same manifest preserves download record", prefs.contains("downloadId"));
      JSONObject mirror = new JSONObject(latest.toString()).put("source", "github").put("sha256", hash('A'));
      prefs.edit().putString("candidate", mirror.toString()).commit();
      status.invoke(updater);
      check("Mirror and hash letter case preserve same package", prefs.contains("downloadId"));
      JSONObject changed = new JSONObject(latest.toString()).put("sha256", hash('b'));
      prefs.edit().putString("candidate", changed.toString()).commit();
      status.invoke(updater);
      check("Different hash invalidates same version download", !prefs.contains("downloadId"));
      JSONObject renamed = new JSONObject(latest.toString()).put("file", "other.apk");
      prefs.edit().putLong("downloadId", Long.MAX_VALUE).putString("candidate", renamed.toString()).commit();
      status.invoke(updater);
      check("Different filename invalidates download", !prefs.contains("downloadId"));
      JSONObject installed = new JSONObject(latest.toString()).put("versionCode", current);
      prefs.edit().remove("candidate").putLong("downloadId", Long.MAX_VALUE)
          .putString("downloadCandidate", installed.toString()).putBoolean("downloadFallback", true).commit();
      status.invoke(updater);
      check("Installed package clears obsolete download without a new check", !prefs.contains("downloadId") && !prefs.contains("downloadCandidate") && !prefs.contains("downloadFallback"));
      result.putString("stream", "UPDATE_DOWNLOAD_STATE_DEVICE_PASS\n");
    } catch (Exception error) {
      code = Activity.RESULT_CANCELED;
      result.putString("stream", "UPDATE_DOWNLOAD_ERROR=" + error + "\n");
    } finally { prefs.edit().clear().commit(); }
    finish(code, result);
  }
}
