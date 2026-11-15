package cn.ting.music.tests;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.webkit.WebView;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import org.json.*;

/** 真实账号只读获取 FM 与播放；不提交不喜欢或收藏等账号写操作。 */
public final class PersonalFmChecks extends Instrumentation {
  private Object service;
  private Method execute;
  private WebView web;
  private Activity activity;

  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

  private Object field(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private void check(String name, boolean passed) {
    Bundle status = new Bundle();
    status.putString("stream", name + ": " + (passed ? "PASS" : "FAIL") + "\n");
    sendStatus(0, status);
    if (!passed) throw new AssertionError(name);
  }

  private void command(String action, JSONObject data) {
    runOnMainSync(() -> {
      try { execute.invoke(service, action, data); }
      catch (Exception error) { throw new RuntimeException(error); }
    });
  }

  private JSONObject state() throws Exception {
    JSONObject[] result = new JSONObject[1];
    runOnMainSync(() -> {
      try { result[0] = (JSONObject) service.getClass().getMethod("snapshot").invoke(service); }
      catch (Exception error) { throw new RuntimeException(error); }
    });
    return result[0];
  }

  private JSONObject waitForPlaying(int minimumIndex) throws Exception {
    for (int attempt = 0; attempt < 400; attempt++) {
      JSONObject state = state();
      if (state.optBoolean("playing") && state.optInt("index") >= minimumIndex) return state;
      if (!state.optBoolean("loading") && !state.optBoolean("fmBusy")
          && (!state.optString("error").isEmpty() || !state.optString("fmError").isEmpty()))
        throw new AssertionError("FM playback unavailable; platform or audio error");
      SystemClock.sleep(100);
    }
    throw new AssertionError("FM playback timed out");
  }

  private String javascript(String source) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    String[] value = new String[1];
    runOnMainSync(() -> web.evaluateJavascript(source, text -> { value[0] = text; done.countDown(); }));
    if (!done.await(10, TimeUnit.SECONDS)) throw new AssertionError("WebView timeout");
    return value[0];
  }

  private void drainFm() throws Exception {
    ((ExecutorService) field(service, "fmNetwork")).submit(() -> {}).get(40, TimeUnit.SECONDS);
    waitForIdleSync();
  }

  @Override public void onStart() {
    Bundle result = new Bundle();
    int code = Activity.RESULT_OK;
    JSONObject previous = null;
    try {
      Context context = getTargetContext();
      activity = startActivitySync(new Intent().setClassName(context, "cn.ting.music.MainActivity")
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      Class<?> type = context.getClassLoader().loadClass("cn.ting.music.PlaybackService");
      for (int attempt = 0; attempt < 100 && service == null; attempt++) {
        service = type.getField("instance").get(null);
        if (service == null) SystemClock.sleep(100);
      }
      check("Native playback service available", service != null);
      execute = type.getMethod("execute", String.class, JSONObject.class);
      web = (WebView) field(activity, "web");
      previous = state();
      for (int attempt = 0; attempt < 100; attempt++) {
        if (javascript("!!document.querySelector('.fm-card')").equals("true")) break;
        SystemClock.sleep(100);
      }
      check("Real WebView renders FM card and approved copy", javascript(
          "document.querySelector('.fm-card')?.textContent.replace(/\\s/g,'').includes('想一直听下去？合你口味的好歌，无限续杯～')").equals("true"));
      javascript("document.querySelector('.fm-card').scrollIntoView({block:'center'})");
      SystemClock.sleep(300);
      Bitmap screenshot = getUiAutomation().takeScreenshot();
      File directory = new File(context.getExternalFilesDir(null), "personal-fm-check");
      directory.mkdirs();
      try (FileOutputStream output = new FileOutputStream(new File(directory, "home.png"))) {
        screenshot.compress(Bitmap.CompressFormat.PNG, 100, output);
      }
      screenshot.recycle();

      command("pause", new JSONObject());
      command("fmStop", new JSONObject());
      command("fmStart", new JSONObject());
      JSONObject started = waitForPlaying(0);
      check("Real logged-in FM songs prepare and play through MediaPlayer", started.optBoolean("personalFm")
          && started.getJSONArray("queue").length() > 0 && started.optDouble("duration") > 0);
      check("UI snapshot excludes native recommendation marks and credentials",
          !started.toString().contains("fmMark") && !started.toString().contains("\"token\""));
      int batch = started.getJSONArray("queue").length();
      boolean[] invalidRejected = {false};
      runOnMainSync(() -> {
        try { execute.invoke(service, "fmDislike", new JSONObject().put("id", "stale-song")); }
        catch (Exception expected) { invalidRejected[0] = true; }
      });
      check("Stale dislike is rejected before account write", invalidRejected[0]);
      command("pause", new JSONObject());
      SystemClock.sleep(650);
      check("Pausing FM releases playback and leaves mode resumable", !state().optBoolean("playing")
          && state().optBoolean("personalFm") && !state().optBoolean("fmBusy"));
      command("resume", new JSONObject());
      waitForPlaying(0);

      javascript("window.fmDevicePushes=0;const previousFmPush=window.nativePlayback;"
          + "window.nativePlayback=(value)=>{window.fmDevicePushes++;previousFmPush(value)}");
      getUiAutomation().performGlobalAction(2);
      SystemClock.sleep(900);
      check("FM continues with Activity in background", state().optBoolean("playing"));
      check("Background Activity releases WebView observer", field(service, "observer") == null);
      for (int index = 1; index <= batch; index++) {
        JSONObject before = waitForPlaying(index - 1);
        command("seek", new JSONObject().put("position", Math.max(0, before.optDouble("duration") - .4)));
        waitForPlaying(index);
      }
      JSONObject continued = state();
      check("Natural completion crosses first FM batch and appends real recommendations",
          continued.optInt("index") >= batch && continued.getJSONArray("queue").length() > batch);
      check("FM queue stays bounded", continued.getJSONArray("queue").length() <= 35);
      runOnMainSync(() -> context.startActivity(new Intent().setClassName(context, "cn.ting.music.MainActivity")
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)));
      SystemClock.sleep(600);

      command("pause", new JSONObject());
      command("fmStop", new JSONObject());
      runOnMainSync(() -> {
        try {
          execute.invoke(service, "fmStart", new JSONObject());
          execute.invoke(service, "pause", new JSONObject());
        } catch (Exception error) { throw new RuntimeException(error); }
      });
      drainFm();
      check("Pause during FM startup rejects late network results", !state().optBoolean("playing")
          && !state().optBoolean("fmStarting") && !state().optBoolean("personalFm"));
      JSONArray ordinary = continued.getJSONArray("queue");
      runOnMainSync(() -> {
        try {
          execute.invoke(service, "fmStart", new JSONObject());
          execute.invoke(service, "play", new JSONObject().put("tracks", new JSONArray().put(ordinary.getJSONObject(0))));
        } catch (Exception error) { throw new RuntimeException(error); }
      });
      drainFm();
      check("Switching to ordinary playback rejects late FM replacement", !state().optBoolean("personalFm")
          && state().getJSONArray("queue").length() == 1);
      command("pause", new JSONObject());
      result.putString("stream", "PERSONAL_FM_DEVICE_PASS\n");
    } catch (Throwable error) {
      code = Activity.RESULT_CANCELED;
      result.putString("stream", "PERSONAL_FM_DEVICE_FAIL: " + error.getClass().getSimpleName()
          + (error instanceof AssertionError ? " " + error.getMessage() : "") + "\n");
    } finally {
      if (service != null && previous != null) try {
        command("pause", new JSONObject());
        command("fmStop", new JSONObject());
        JSONArray tracks = previous.optJSONArray("queue");
        if (tracks != null && tracks.length() > 0) {
          command("play", new JSONObject().put("tracks", tracks).put("index", previous.optInt("index")));
          command("pause", new JSONObject());
          command("mode", new JSONObject().put("mode", previous.optInt("mode")));
          Field position = service.getClass().getDeclaredField("resumePosition");
          position.setAccessible(true);
          JSONObject savedState = previous;
          runOnMainSync(() -> { try { position.setInt(service, (int) (savedState.optDouble("position") * 1000)); }
            catch (Exception error) { throw new RuntimeException(error); } });
          getTargetContext().getSharedPreferences("playback", Context.MODE_PRIVATE).edit()
              .putInt("position", (int) (previous.optDouble("position") * 1000)).apply();
        }
      } catch (Exception ignored) { }
    }
    finish(code, result);
  }
}
