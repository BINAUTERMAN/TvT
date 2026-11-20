package cn.ting.music.tests;

import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** 在真实应用进程验证取消、变速及重复播放，不改账号和音频输出路由。 */
public final class PlaybackCrashChecks extends Instrumentation {
  private Object service;
  private Method execute, snapshot;
  private int checks;
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
  private Field field(String name) throws Exception {
    Field field = service.getClass().getDeclaredField(name); field.setAccessible(true); return field;
  }
  private void check(String name, boolean pass) {
    Bundle result = new Bundle(); result.putString("stream", name + ": " + (pass ? "PASS" : "FAIL") + "\n"); sendStatus(0, result);
    if (!pass) throw new AssertionError(name); checks++;
  }
  private void command(String action, JSONObject data) {
    runOnMainSync(() -> { try { execute.invoke(service, action, data); } catch (Exception e) { throw new RuntimeException(e); } });
  }
  private JSONObject state() {
    JSONObject[] value = new JSONObject[1];
    runOnMainSync(() -> { try { value[0] = (JSONObject) snapshot.invoke(service); } catch (Exception e) { throw new RuntimeException(e); } });
    return value[0];
  }
  private void awaitPlaying() throws Exception {
    for (int i = 0; i < 150 && !state().optBoolean("playing"); i++) SystemClock.sleep(100);
    check("Native playback starts without ANR", state().optBoolean("playing"));
  }
  private void socketChecks(Context context) throws Exception {
    Method open = context.getClassLoader().loadClass("cn.ting.music.AppHttp").getMethod("open", URL.class);
    for (int attempt = 0; attempt < 20; attempt++) {
      CountDownLatch hold = new CountDownLatch(1), entered = new CountDownLatch(1);
      AtomicReference<Throwable> failure = new AtomicReference<>();
      try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[]{127,0,0,1}))) {
        Thread peer = new Thread(() -> {
          try (Socket socket = server.accept()) {
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), "US-ASCII"));
            String line; while ((line = input.readLine()) != null && !line.isEmpty()) { }
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 1048576\r\nConnection: close\r\n\r\nx".getBytes("US-ASCII"));
            socket.getOutputStream().flush(); hold.await(5, TimeUnit.SECONDS);
          } catch (Exception error) { failure.set(error); }
        });
        peer.start();
        HttpURLConnection connection = (HttpURLConnection) open.invoke(null, new URL("http://127.0.0.1:" + server.getLocalPort() + "/slow"));
        connection.setConnectTimeout(2000); connection.setReadTimeout(4000);
        try {
          InputStream input = connection.getInputStream();
          if (input.read() != 'x') throw new AssertionError("Missing slow response");
          Thread reader = new Thread(() -> {
            entered.countDown();
            try { input.read(); failure.set(new AssertionError("Read succeeded after cancellation")); }
            catch (IOException expected) { }
          });
          reader.start(); entered.await(1, TimeUnit.SECONDS);
          long at = SystemClock.elapsedRealtime(); input.close(); connection.disconnect();
          if (SystemClock.elapsedRealtime() - at > 200) throw new AssertionError("Cancellation blocked UI caller");
          reader.join(1500);
          if (reader.isAlive()) throw new AssertionError("Cancelled read did not exit");
        } finally { connection.disconnect(); hold.countDown(); peer.join(2000); }
        if (failure.get() != null) throw new AssertionError("Cancellation failed", failure.get());
      }
    }
    check("20 real socket cancellations complete without crash", true);
  }
  private static final class SpeedProbe extends MediaPlayer {
    int calls; float value;
    @Override public void setPlaybackParams(PlaybackParams params) { calls++; value = params.getSpeed(); }
    @Override public PlaybackParams getPlaybackParams() { throw new AssertionError("Unexpected synchronous parameter query"); }
  }
  @Override public void onStart() {
    Bundle result = new Bundle(); int code = Activity.RESULT_OK;
    try {
      Context target = getTargetContext(); DeviceLaunch.start(this, target);
      Class<?> type = target.getClassLoader().loadClass("cn.ting.music.PlaybackService");
      for (int attempt = 0; attempt < 100; attempt++) {
        service = type.getField("instance").get(null);
        if (service != null) break;
        SystemClock.sleep(100);
      }
      check("Playback service is connected", service != null);
      execute = type.getMethod("execute", String.class, JSONObject.class); snapshot = type.getMethod("snapshot");
      try { socketChecks(target); }
      catch (UnknownServiceException blocked) {
        if (!String.valueOf(blocked.getMessage()).contains("CLEARTEXT")) throw blocked;
        Bundle status = new Bundle();
        status.putString("stream", "LOCAL_SOCKET_CHECK_SKIPPED: release policy blocks cleartext localhost; JVM socket cancellation checks cover this case\n");
        sendStatus(0, status);
      }
      Method apply = type.getDeclaredMethod("applyPlaybackSpeed", MediaPlayer.class); apply.setAccessible(true);
      Field speed = field("speed"), applied = field("appliedSpeed");
      runOnMainSync(() -> {
        SpeedProbe probe = new SpeedProbe();
        try {
          float previous = speed.getFloat(service), previousApplied = applied.getFloat(service);
          try {
            speed.setFloat(service, 1); applied.setFloat(service, 1); apply.invoke(service, probe);
            check("Default speed avoids system parameter calls", probe.calls == 0);
            speed.setFloat(service, 1.5f); apply.invoke(service, probe); apply.invoke(service, probe);
            check("Speed changes apply once without querying old parameters", probe.calls == 1 && probe.value == 1.5f);
            speed.setFloat(service, 1); apply.invoke(service, probe);
            check("Returning to normal speed is applied", probe.calls == 2 && probe.value == 1);
          } finally { speed.setFloat(service, previous); applied.setFloat(service, previousApplied); }
        } catch (Exception error) { throw new RuntimeException(error); }
        finally { probe.release(); }
      });
      check("Existing playback queue is available", state().getJSONArray("queue").length() > 0);
      command("resume", new JSONObject()); awaitPlaying();
      command("speed", new JSONObject().put("speed", 1.25));
      runOnMainSync(() -> {
        try { check("Native player applies selected speed", Math.abs(((MediaPlayer) field("media").get(service)).getPlaybackParams().getSpeed() - 1.25f) < .01f); }
        catch (Exception e) { throw new RuntimeException(e); }
      });
      command("speed", new JSONObject().put("speed", 1));
      for (int i = 0; i < 6; i++) {
        command("pause", new JSONObject()); SystemClock.sleep(600);
        command("resume", new JSONObject()); awaitPlaying();
      }
      int index = state().getInt("index");
      for (int i = 0; i < 5; i++) { command("jump", new JSONObject().put("index", index)); SystemClock.sleep(160); }
      awaitPlaying();
      double before = state().optDouble("position"); SystemClock.sleep(1500);
      check("Playback advances after rapid reloads", state().optDouble("position") > before + .8);
      command("pause", new JSONObject()); SystemClock.sleep(650);
      Method release = type.getDeclaredMethod("releaseMedia"); release.setAccessible(true);
      runOnMainSync(() -> { try { release.invoke(service); } catch (Exception e) { throw new RuntimeException(e); } });
      check("Playback resources released after stress", field("media").get(service) == null && field("mediaSource").get(service) == null);
      SystemClock.sleep(2500);
      result.putString("stream", "PLAYBACK_CRASH_DEVICE_PASS checks=" + checks + "\n");
    } catch (Throwable error) {
      code = Activity.RESULT_CANCELED; result.putString("stream", "PLAYBACK_CRASH_DEVICE_FAIL " + error + "\n");
    }
    finish(code, result);
  }
}
