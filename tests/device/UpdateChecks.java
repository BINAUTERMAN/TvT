package cn.ting.music.tests;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import java.lang.reflect.*;
import org.json.JSONObject;

/** 在手机上读取构建配置并访问真实公开更新接口，使用独立测试偏好。 */
public final class UpdateChecks extends Instrumentation {
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

  private Method method(Class<?> type, String name, Class<?>... args) throws Exception {
    Method method = type.getDeclaredMethod(name, args);
    method.setAccessible(true);
    return method;
  }

  private void report(String text) {
    Bundle status = new Bundle();
    status.putString("stream", text + "\n");
    sendStatus(0, status);
  }

  private void check(String name, boolean pass) throws Exception {
    report(name + ": " + (pass ? "PASS" : "FAIL"));
    if (!pass) throw new Exception(name);
  }

  @Override public void onStart() {
    Context target = getTargetContext();
    SharedPreferences prefs = target.getSharedPreferences("update_verification", Context.MODE_PRIVATE);
    prefs.edit().clear().commit();
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
      JSONObject settings = (JSONObject) method(type, "settings", JSONObject.class).invoke(updater, new JSONObject());
      check("Installed version is 5.4", settings.getString("currentVersion").equals("5.4"));
      check("Gitee is configured as primary", settings.getBoolean("configured") && settings.getString("source").equals("gitee"));
      Intent releases = (Intent) method(type, "releases").invoke(updater);
      check("Release notes still open GitHub", releases.getDataString().equals("https://github.com/BINAUTERMAN/TvT/releases"));
      Class<?> starType = target.getClassLoader().loadClass("cn.ting.music.StarPrompt");
      Constructor<?> starConstructor = starType.getDeclaredConstructor(Context.class);
      starConstructor.setAccessible(true);
      Object star = starConstructor.newInstance(isolated);
      check("Star still opens GitHub", method(starType, "repositoryUrl").invoke(star).equals("https://github.com/BINAUTERMAN/TvT"));
      try {
        JSONObject candidate = (JSONObject) method(type, "check", boolean.class).invoke(updater, true);
        check("Live API returned validated manifest", candidate.has("sha256") && candidate.has("versionCode") && candidate.has("source"));
        report("LIVE_UPDATE_SOURCE=" + candidate.getString("source") + " VERSION=" + candidate.optString("version") + " AVAILABLE=" + candidate.optBoolean("available"));
      } catch (InvocationTargetException error) {
        String message = String.valueOf(error.getCause().getMessage());
        report("LIVE_UPDATE_UNAVAILABLE=" + message);
        check("Both source failures are explicitly reported", message.contains("Gitee") && message.contains("GitHub"));
        check("Failed requests do not poison check cache", !prefs.contains("checked") && !prefs.contains("candidate"));
      }
      result.putString("stream", "UPDATE_CONFIGURATION_AND_LIVE_API_CHECKS_PASS\n");
    } catch (Exception error) {
      code = Activity.RESULT_CANCELED;
      result.putString("stream", "UPDATE_CHECK_ERROR=" + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n");
    } finally { prefs.edit().clear().commit(); }
    finish(code, result);
  }
}
