package cn.ting.music.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.webkit.WebView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

public final class DeviceSmoke extends Instrumentation {
  private Activity activity;
  private Object service;
  private Method execute;
  private Method snapshot;
  private File tone;
  private JSONObject previousState;
  private final JSONArray checks = new JSONArray();

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    start();
  }

  private void check(String name, boolean result) throws Exception {
    checks.put(new JSONObject().put("name", name).put("passed", result));
    Bundle status = new Bundle();
    status.putString("stream", name + ": " + (result ? "PASS" : "FAIL") + "\n");
    sendStatus(0, status);
    if (!result) throw new AssertionError(name);
  }

  private Object field(Object object, String name) throws Exception {
    Field field = object.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(object);
  }

  private JSONObject state() throws Exception {
    final JSONObject[] result = new JSONObject[1];
    runOnMainSync(
        () -> {
          try {
            result[0] = (JSONObject) snapshot.invoke(service);
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    return result[0];
  }

  private void command(String action, JSONObject payload) {
    runOnMainSync(
        () -> {
          try {
            execute.invoke(service, action, payload);
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
  }

  private String javascript(String source) throws Exception {
    CountDownLatch complete = new CountDownLatch(1);
    final String[] result = new String[1];
    WebView web = (WebView) field(activity, "web");
    runOnMainSync(
        () ->
            web.evaluateJavascript(
                source,
                value -> {
                  result[0] = value;
                  complete.countDown();
                }));
    if (!complete.await(8, TimeUnit.SECONDS)) throw new Exception("WebView response timeout");
    return result[0];
  }

  private void shell(String command) throws Exception {
    try (ParcelFileDescriptor descriptor = getUiAutomation().executeShellCommand(command);
        java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
      byte[] bytes = new byte[1024];
      while (input.read(bytes) != -1) {}
    }
  }

  private void createTone() throws Exception {
    int rate = 22050;
    int samples = rate * 90;
    tone = new File(getTargetContext().getFilesDir(), "ting-device-test.wav");
    java.nio.ByteBuffer header =
        java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    header
        .put("RIFF".getBytes("US-ASCII"))
        .putInt(samples * 2 + 36)
        .put("WAVEfmt ".getBytes("US-ASCII"))
        .putInt(16)
        .putShort((short) 1)
        .putShort((short) 1)
        .putInt(rate)
        .putInt(rate * 2)
        .putShort((short) 2)
        .putShort((short) 16)
        .put("data".getBytes("US-ASCII"))
        .putInt(samples * 2);
    try (FileOutputStream output = new FileOutputStream(tone)) {
      output.write(header.array());
      byte[] buffer = new byte[rate * 2];
      for (int second = 0; second < 90; second++) {
        for (int i = 0; i < rate; i++) {
          short value = (short) (Math.sin(2 * Math.PI * 220 * i / rate) * 80);
          buffer[i * 2] = (byte) value;
          buffer[i * 2 + 1] = (byte) (value >> 8);
        }
        output.write(buffer);
      }
    }
  }

  @Override
  public void onStart() {
    Bundle report = new Bundle();
    int resultCode = Activity.RESULT_OK;
    try {
      Intent intent =
          new Intent()
              .setClassName("cn.ting.music", "cn.ting.music.MainActivity")
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      CountDownLatch resumed = new CountDownLatch(1);
      android.app.Application application =
          (android.app.Application) getTargetContext().getApplicationContext();
      android.app.Application.ActivityLifecycleCallbacks callbacks =
          new android.app.Application.ActivityLifecycleCallbacks() {
            public void onActivityResumed(Activity candidate) {
              activity = candidate;
              resumed.countDown();
            }

            public void onActivityCreated(Activity candidate, Bundle state) {}

            public void onActivityStarted(Activity candidate) {}

            public void onActivityPaused(Activity candidate) {}

            public void onActivityStopped(Activity candidate) {}

            public void onActivitySaveInstanceState(Activity candidate, Bundle state) {}

            public void onActivityDestroyed(Activity candidate) {}
          };
      application.registerActivityLifecycleCallbacks(callbacks);
      shell("am start -n cn.ting.music/.MainActivity");
      resumed.await(12, TimeUnit.SECONDS);
      application.unregisterActivityLifecycleCallbacks(callbacks);
      if (activity == null) throw new Exception("Activity launch timed out");
      SystemClock.sleep(2500);
      Class<?> type =
          activity.getClass().getClassLoader().loadClass("cn.ting.music.PlaybackService");
      service = type.getField("instance").get(null);
      execute = type.getMethod("execute", String.class, JSONObject.class);
      snapshot = type.getMethod("snapshot");
      previousState = state();
      check("Application and native service started", service != null);
      javax.net.ssl.HttpsURLConnection login =
          (javax.net.ssl.HttpsURLConnection)
              new java.net.URL("https://login-user.kugou.com/v7/send_mobile_code").openConnection();
      login.setRequestMethod("HEAD");
      login.setConnectTimeout(12000);
      login.setReadTimeout(12000);
      try {
        check("Login TLS hostname verified without sending SMS", login.getResponseCode() == 200);
      } finally {
        login.disconnect();
      }
      check(
          "Web UI rendered",
          "true".equals(javascript("!!document.querySelector('h1')?.textContent")));
      for (int attempt = 0; attempt < 50; attempt++) {
        if ("true"
            .equals(
                javascript(
                    "[...document.querySelectorAll('.recommend-grid .artwork"
                        + " img')].some(image=>image.complete&&image.naturalWidth>0)"))) break;
        SystemClock.sleep(250);
      }
      check(
          "Personal recommendation album artwork loaded on device",
          "true"
              .equals(
                  javascript(
                      "[...document.querySelectorAll('.recommend-grid .artwork"
                          + " img')].some(image=>image.complete&&image.naturalWidth>0)")));
      createTone();
      JSONObject track =
          new JSONObject()
              .put("id", "device-smoke-test")
              .put("title", "设备验证音频")
              .put("artist", "听屿测试")
              .put("album", "")
              .put("cover", "")
              .put("hash", "")
              .put("uri", tone.toURI().toString());
      command("play", new JSONObject().put("tracks", new JSONArray().put(track)).put("index", 0));
      for (int attempt = 0; attempt < 30 && !state().optBoolean("playing"); attempt++)
        SystemClock.sleep(200);
      check("Native local audio playback", state().optBoolean("playing"));
      command("pause", new JSONObject());
      SystemClock.sleep(650);
      shell("input keyevent 3");
      SystemClock.sleep(800);
      getTargetContext()
          .stopService(new Intent().setClassName("cn.ting.music", "cn.ting.music.PlaybackService"));
      for (int attempt = 0; attempt < 30 && type.getField("instance").get(null) != null; attempt++)
        SystemClock.sleep(100);
      check(
          "Idle playback service can be reclaimed in background",
          type.getField("instance").get(null) == null);
      shell("am start -n cn.ting.music/.MainActivity");
      javascript("window.Native.request('device-reconnect','resume','{}')");
      for (int attempt = 0; attempt < 60; attempt++) {
        Object restored = type.getField("instance").get(null);
        if (restored != null) {
          service = restored;
          if (state().optBoolean("playing")) break;
        }
        SystemClock.sleep(100);
      }
      check(
          "Playback recovers through bridge after service recreation",
          state().optBoolean("playing"));
      javascript("document.querySelector('.mini-info')?.click()");
      SystemClock.sleep(600);
      check(
          "Now-playing UI received real native state",
          "true"
              .equals(
                  javascript(
                      "document.querySelector('.player-screen')?.textContent.includes('设备验证音频')")));
      shell("am start -n cn.ting.music.tests/.FocusProbeActivity");
      SystemClock.sleep(3000);
      check(
          "Other application pauses music on permanent focus loss", !state().optBoolean("playing"));
      SystemClock.sleep(7500);
      check("Permanent focus loss does not force automatic resume", !state().optBoolean("playing"));
      command("resume", new JSONObject());
      shell("am start -n cn.ting.music.tests/.FocusProbeActivity --ei gain 2");
      for (int wait = 0; wait < 40 && !state().optBoolean("interrupted"); wait++)
        SystemClock.sleep(100);
      check(
          "Transient focus loss pauses music",
          !state().optBoolean("playing") && state().optBoolean("interrupted"));
      for (int attempt = 0; attempt < 40 && !state().optBoolean("playing"); attempt++)
        SystemClock.sleep(250);
      check("Music resumes when transient focus returns", state().optBoolean("playing"));
      double before = state().optDouble("position");
      shell("input keyevent 3");
      SystemClock.sleep(1200);
      check(
          "Background UI observer detached",
          field(service, "observer") == null && !(Boolean) field(activity, "visible"));
      shell("input keyevent 223");
      SystemClock.sleep(500);
      check(
          "Screen is off during audio test",
          !((android.os.PowerManager) getTargetContext().getSystemService(Context.POWER_SERVICE))
              .isInteractive());
      long cpuBefore = android.os.Process.getElapsedCpuTime();
      SystemClock.sleep(8000);
      check(
          "Audio advances while app is backgrounded",
          state().optBoolean("playing") && state().optDouble("position") > before + 6);
      shell("input keyevent 224");
      report.putLong(
          "backgroundPlayingCpuMsOver8s", android.os.Process.getElapsedCpuTime() - cpuBefore);
      command("toggle", new JSONObject());
      SystemClock.sleep(650);
      double paused = state().optDouble("position");
      cpuBefore = android.os.Process.getElapsedCpuTime();
      SystemClock.sleep(5000);
      check(
          "Paused audio position stays fixed",
          !state().optBoolean("playing") && Math.abs(state().optDouble("position") - paused) < .2);
      report.putLong(
          "backgroundPausedCpuMsOver5s", android.os.Process.getElapsedCpuTime() - cpuBefore);
      command("sleep", new JSONObject().put("minutes", 1));
      check(
          "Sleep timer scheduled in native service",
          state().optLong("sleepAt") > System.currentTimeMillis());
      command("sleep", new JSONObject().put("minutes", 0));
      command("remove", new JSONObject().put("index", 0));
      JSONArray originalQueue = previousState.getJSONArray("queue");
      if (originalQueue.length() > 0
          && !originalQueue
              .getJSONObject(previousState.optInt("index"))
              .optString("hash")
              .isEmpty()) {
        command(
            "play",
            new JSONObject()
                .put("tracks", originalQueue)
                .put("index", previousState.optInt("index")));
        for (int attempt = 0; attempt < 100 && !state().optBoolean("playing"); attempt++)
          SystemClock.sleep(250);
        check("Authorized online audio plays through MediaPlayer", state().optBoolean("playing"));
        double onlinePosition = state().optDouble("position");
        SystemClock.sleep(2300);
        check(
            "Online playback position advances",
            state().optDouble("position") > onlinePosition + 1.5);
        command("toggle", new JSONObject());
      }
      restoreQueue();
      runOnMainSync(
          () ->
              activity.startActivity(
                  new Intent(activity, activity.getClass())
                      .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
      SystemClock.sleep(1000);
      report.putString("checks", checks.toString());
      report.putString("stream", "DEVICE_SMOKE_PASS\n");

    } catch (Throwable error) {
      report.putString(
          "stream", "DEVICE_SMOKE_FAIL: " + android.util.Log.getStackTraceString(error));
      report.putString("checks", checks.toString());
      resultCode = Activity.RESULT_CANCELED;
    } finally {
      try {
        restoreQueue();
      } catch (Exception ignored) {
      }
      if (tone != null) tone.delete();
    }
    finish(resultCode, report);
  }

  private void restoreQueue() throws Exception {
    if (previousState == null || service == null) return;
    runOnMainSync(
        () -> {
          try {
            java.lang.reflect.Method pause = service.getClass().getDeclaredMethod("pause");
            pause.setAccessible(true);
            pause.invoke(service);
            java.lang.reflect.Method release = service.getClass().getDeclaredMethod("releaseMedia");
            release.setAccessible(true);
            release.invoke(service);
            Field queueField = service.getClass().getDeclaredField("queue");
            queueField.setAccessible(true);
            queueField.set(service, previousState.getJSONArray("queue"));
            Field indexField = service.getClass().getDeclaredField("index");
            indexField.setAccessible(true);
            indexField.setInt(service, previousState.optInt("index"));
            Field positionField = service.getClass().getDeclaredField("resumePosition");
            positionField.setAccessible(true);
            positionField.setInt(service, (int) (previousState.optDouble("position") * 1000));
            execute.invoke(service, "state", new JSONObject());
            getTargetContext()
                .getSharedPreferences("playback", Context.MODE_PRIVATE)
                .edit()
                .putInt("position", (int) (previousState.optDouble("position") * 1000))
                .apply();
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    javascript(
        "localStorage.setItem('history',JSON.stringify(JSON.parse(localStorage.getItem('history')||'[]').filter(track=>track.id!=='device-smoke-test')))");
  }
}
