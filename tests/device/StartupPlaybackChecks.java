package cn.ting.music.tests;

import android.app.*;
import android.content.*;
import android.os.*;
import android.webkit.WebView;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** 只记录阶段、耗时与错误，不输出账号、令牌或歌曲地址。 */
public final class StartupPlaybackChecks extends Instrumentation {
  private WebView web;
  private Object service;
  private final List<String> failures = new ArrayList<>();

  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
  private void report(String message) {
    Bundle bundle = new Bundle(); bundle.putString("stream", message + "\n"); sendStatus(0, bundle);
  }
  private Object field(Object object, String name) throws Exception {
    Field value = object.getClass().getDeclaredField(name); value.setAccessible(true); return value.get(object);
  }
  private String js(String source) throws Exception {
    CountDownLatch done = new CountDownLatch(1); String[] result = new String[1];
    runOnMainSync(() -> web.evaluateJavascript(source, value -> {result[0] = value; done.countDown();}));
    if (!done.await(5, TimeUnit.SECONDS)) throw new AssertionError("WebView 未响应"); return result[0];
  }
  private JSONObject state() {
    JSONObject[] result = new JSONObject[1];
    runOnMainSync(() -> {try { result[0] = (JSONObject) service.getClass().getMethod("snapshot").invoke(service); }
      catch (Exception error) {throw new RuntimeException(error);} }); return result[0];
  }
  private void stacks() {
    for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
      StringBuilder trace = new StringBuilder(); boolean own = false;
      for (StackTraceElement frame : entry.getValue()) {
        if (frame.getClassName().startsWith("cn.ting.music")) own = true;
        if (trace.length() < 700 || frame.getClassName().startsWith("cn.ting.music")) trace.append(frame.toString()).append(" | ");
      }
      if (own) report("THREAD " + entry.getKey().getName() + " " + entry.getKey().getState() + " " + trace);
    }
  }
  @Override public void onStart() {
    Bundle result = new Bundle(); int code = Activity.RESULT_OK;
    try {
      long started = SystemClock.elapsedRealtime();
      Activity activity = DeviceLaunch.start(this, getTargetContext());
      web = (WebView) field(activity, "web");
      Class<?> type = activity.getClassLoader().loadClass("cn.ting.music.PlaybackService");
      report("STARTUP_BEGIN");
      for (int attempt = 0; attempt < 40; attempt++) {
        if ("true".equals(js("typeof window.nativeResolve==='function'&&!!document.querySelector('.personal-home')"))) break;
        SystemClock.sleep(100);
      }
      report("WEB_READY elapsedMs=" + (SystemClock.elapsedRealtime() - started));
      for (int attempt = 0; attempt < 9; attempt++) {
        report("HOME elapsedMs=" + (SystemClock.elapsedRealtime() - started) + " " + js("JSON.stringify({busy:!!document.querySelector('.personal-home .loading'),daily:!!document.querySelector('.daily-mix'),recommendations:document.querySelectorAll('.recommend-grid .album-item').length,playlists:document.querySelectorAll('.personal-home .entity-card').length,error:document.querySelector('.personal-home .inline-error')?.textContent?.slice(0,200)||''})"));
        if (attempt == 2) stacks();
        SystemClock.sleep(2000);
      }
      for (int attempt = 0; attempt < 50; attempt++) {
        service = type.getField("instance").get(null);
        if (service != null) break;
        SystemClock.sleep(100);
      }
      if (service == null) throw new AssertionError("播放服务未启动");
      JSONObject before = state();
      JSONObject track = before.optJSONArray("queue").optJSONObject(before.optInt("index"));
      report("QUEUE tracks=" + before.getJSONArray("queue").length() + " currentHasHash=" + (track != null && !track.optString("hash").isEmpty())
          + " currentHasUri=" + (track != null && !track.optString("uri").isEmpty()) + " playing=" + before.optBoolean("playing"));
      Class<?> observerType = activity.getClassLoader().loadClass("cn.ting.music.PlaybackService$Observer");
      Object capture = Proxy.newProxyInstance(observerType.getClassLoader(), new Class<?>[]{observerType}, (proxy, method, args) -> {
        if (args != null && args.length == 1 && args[0] instanceof JSONObject) {
          String error = ((JSONObject) args[0]).optString("error");
          if (!error.isEmpty()) { synchronized(failures) { failures.add(error); }
            report("PLAYBACK_EVENT " + error.replaceAll("https?://[^ ]+", "[地址省略]")); }
        } return null;
      });
      Field observer = service.getClass().getField("observer");
      runOnMainSync(() -> {try { observer.set(service, capture); } catch(Exception error) {throw new RuntimeException(error);} });
      long playAt = SystemClock.elapsedRealtime();
      js("window.Native.request('diagnostic-resume','resume','{}')");
      for (int attempt = 0; attempt < 100; attempt++) {
        JSONObject state = state();
        if (state.optBoolean("playing")) { report("REAL_CURRENT_PLAYBACK_STARTED elapsedMs=" + (SystemClock.elapsedRealtime() - playAt)); break; }
        if (attempt == 12) stacks();
        if (attempt % 20 == 0) report("PLAYBACK_WAIT elapsedMs=" + (SystemClock.elapsedRealtime() - playAt) + " loading=" + state.optBoolean("loading") + " prepared=" + field(service, "prepared"));
        synchronized(failures) { if (!failures.isEmpty()) break; }
        SystemClock.sleep(400);
      }
      for (String host : new String[] {"gateway.kugou.com", "kugouvip.kugou.com", "lyrics.kugou.com"}) {
        long dnsAt = SystemClock.elapsedRealtime();
        java.net.InetAddress[] addresses = java.net.InetAddress.getAllByName(host);
        report("DNS " + host + " count=" + addresses.length + " elapsedMs=" + (SystemClock.elapsedRealtime()-dnsAt));
        for (int index = 0; index < Math.min(addresses.length, 4); index++) {
          long connectAt = SystemClock.elapsedRealtime();
          try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(addresses[index], 443), 1500);
            report("CONNECT " + host + " index=" + index + " family=" + (addresses[index] instanceof java.net.Inet6Address ? 6 : 4) + " elapsedMs=" + (SystemClock.elapsedRealtime()-connectAt));
          } catch (java.io.IOException unavailable) {
            report("CONNECT " + host + " index=" + index + " family=" + (addresses[index] instanceof java.net.Inet6Address ? 6 : 4) + " failed=" + unavailable.getClass().getSimpleName() + " elapsedMs=" + (SystemClock.elapsedRealtime()-connectAt));
          }
        }
      }
      report("REAL_CURRENT_PLAYBACK playing=" + state().optBoolean("playing") + " loading=" + state().optBoolean("loading"));
      result.putString("stream", "STARTUP_DIAGNOSTIC_DONE\n");
    } catch(Throwable error) {
      code = Activity.RESULT_CANCELED;
      result.putString("stream", "STARTUP_DIAGNOSTIC_FAILED " + error.getClass().getSimpleName() + "\n");
    }
    finish(code, result);
  }
}
