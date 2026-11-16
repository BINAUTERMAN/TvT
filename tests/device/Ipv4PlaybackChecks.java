package cn.ting.music.tests;

import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;
import android.webkit.WebView;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.SocketFactory;
import org.json.*;

/** 真机验证 IPv4、实际播放、Range 定位、取消及过期播放器引用。 */
public final class Ipv4PlaybackChecks extends Instrumentation {
  private Object service;
  private Method execute;
  private Method snapshot;
  private WebView web;
  private int passed;
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
  private void check(String name, boolean value) {
    Bundle result = new Bundle(); result.putString("stream", name + ": " + (value ? "PASS" : "FAIL") + "\n"); sendStatus(0, result);
    if (!value) throw new AssertionError(name);
    passed++;
  }
  private Object field(Object object, String name) throws Exception {
    Field item = object.getClass().getDeclaredField(name); item.setAccessible(true); return item.get(object);
  }
  private JSONObject state() throws Exception {
    JSONObject[] result = new JSONObject[1];
    runOnMainSync(() -> {try { result[0] = (JSONObject) snapshot.invoke(service); } catch(Exception error) {throw new RuntimeException(error);} });
    return result[0];
  }
  private void command(String action, JSONObject data) {
    runOnMainSync(() -> {try { execute.invoke(service, action, data); } catch(Exception error) {throw new RuntimeException(error);} });
  }
  private String js(String source) throws Exception {
    CountDownLatch done = new CountDownLatch(1); String[] result = new String[1];
    runOnMainSync(() -> web.evaluateJavascript(source, value -> {result[0] = value; done.countDown();}));
    if (!done.await(5, TimeUnit.SECONDS)) throw new AssertionError("WebView timeout"); return result[0];
  }
  @Override public void onStart() {
    Bundle result = new Bundle(); int code = Activity.RESULT_OK;
    MediaDataSource probe = null;
    Activity launched = null;
    int originalOrientation = -1;
    try {
      Context target = getTargetContext(); long started = SystemClock.elapsedRealtime();
      Activity activity = DeviceLaunch.start(this, target);
      launched = activity;
      originalOrientation = activity.getRequestedOrientation();
      web = (WebView) field(activity, "web");
      for (int i = 0; i < 100; i++) {
        if ("true".equals(js("!!document.querySelector('.daily-mix')&&document.querySelectorAll('.recommend-grid .album-item').length>0"))) break;
        SystemClock.sleep(100);
      }
      check("Real signed homepage loads within ten seconds", "true".equals(js("!!document.querySelector('.daily-mix')&&document.querySelectorAll('.recommend-grid .album-item').length>0")));
      result.putLong("homeReadyMs", SystemClock.elapsedRealtime() - started);
      Class<?> serviceType = target.getClassLoader().loadClass("cn.ting.music.PlaybackService");
      service = serviceType.getField("instance").get(null);
      check("Playback service exists before reading snapshot", service != null);
      execute = serviceType.getMethod("execute", String.class, JSONObject.class);
      snapshot = serviceType.getMethod("snapshot");
      Class<?> http = target.getClassLoader().loadClass("cn.ting.music.AppHttp");
      Method clientMethod = http.getDeclaredMethod("client"); clientMethod.setAccessible(true);
      Object client = clientMethod.invoke(null);
      Object dns = client.getClass().getMethod("dns").invoke(client);
      Class<?> dnsType = target.getClassLoader().loadClass("okhttp3.Dns");
      for (String host : new String[] {"gateway.kugou.com", "kugouvip.kugou.com", "lyrics.kugou.com"}) {
        List<?> addresses = (List<?>) dnsType.getMethod("lookup", String.class).invoke(dns, host);
        check("Client IPv4 DNS " + host, !addresses.isEmpty() && addresses.stream().allMatch(address -> address instanceof Inet4Address));
      }
      SocketFactory sockets = (SocketFactory) client.getClass().getMethod("socketFactory").invoke(client);
      boolean denied = false;
      try (Socket socket = sockets.createSocket()) {
        socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[16]), 443), 500);
      } catch (UnknownHostException expected) { denied = true; }
      check("IPv6 literal and proxy socket cannot bypass DNS filtering", denied);
      long playAt = SystemClock.elapsedRealtime();
      js("document.querySelector('.recommend-grid .album-item').click()");
      for (int i = 0; i < 120 && !state().optBoolean("playing"); i++) SystemClock.sleep(100);
      check("Actual homepage song plays through native IPv4 reader", state().optBoolean("playing"));
      result.putLong("playReadyMs", SystemClock.elapsedRealtime() - playAt);
      double before = state().optDouble("position");
      SystemClock.sleep(1500);
      check("Online audio advances after preparation", state().optBoolean("playing") && state().optDouble("position") > before + .8);
      Object source = field(service, "mediaSource");
      check("Network audio uses native controlled data source", source != null);
      Class<?> sourceType = target.getClassLoader().loadClass("cn.ting.music.NetworkMediaSource");
      Constructor<?> constructor = sourceType.getDeclaredConstructor(String.class); constructor.setAccessible(true);
      probe = (MediaDataSource) constructor.newInstance(field(source, "url").toString());
      long length = probe.getSize();
      check("CDN provides known audio length", length > 1024);
      byte[] first = new byte[32], again = new byte[32], tail = new byte[16];
      check("Read actual audio header", probe.readAt(0, first, 0, first.length) == first.length);
      check("Read CDN range at file end", probe.readAt(length - 16, tail, 0, tail.length) == tail.length);
      check("Seek back yields identical audio bytes", probe.readAt(0, again, 0, again.length) == again.length && Arrays.equals(first, again));
      check("EOF is reported correctly", probe.readAt(length, tail, 0, 1) == -1);
      probe.close();
      boolean closed = false;
      try { probe.readAt(0, tail, 0, 1); } catch (IOException expected) { closed = true; }
      check("Closed audio source rejects late reads without null pointer", closed);
      probe = null;
      double seek = Math.min(6, state().optDouble("duration") * .3);
      command("seek", new JSONObject().put("position", seek));
      SystemClock.sleep(1200);
      check("Native HTTP audio seek resumes playback", state().optBoolean("playing") && state().optDouble("position") >= Math.max(0, seek - 2));
      js("document.querySelector('.mini-info').click()");
      for (int orientation : new int[] {android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
          android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}) {
        runOnMainSync(() -> activity.setRequestedOrientation(orientation));
        for (int attempt = 0; attempt < 50; attempt++) {
          String expected = orientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ? "innerHeight>=innerWidth" : "innerWidth>innerHeight";
          if ("true".equals(js(expected))) break;
          SystemClock.sleep(100);
        }
        SystemClock.sleep(500);
        check("Real WebView player fits orientation " + orientation,
            "true".equals(js("(()=>{const p=document.querySelector('.player-screen');if(!p)return false;const r=p.getBoundingClientRect();const t=p.querySelector('.player-tools').getBoundingClientRect();return Math.abs(r.width-innerWidth)<1&&Math.abs(r.height-innerHeight)<1&&p.scrollHeight<=p.clientHeight+1&&t.bottom<=innerHeight})()")));
        check("Portrait and landscape control placement " + orientation,
            "true".equals(js("(()=>{const p=document.querySelector('.player-screen');const a=p.querySelector('.cover-stage').getBoundingClientRect(),c=p.querySelector('.player-controls').getBoundingClientRect();return innerHeight>=innerWidth?c.top>=a.bottom:c.left>=a.right})()")));
      }
      Method owns = serviceType.getDeclaredMethod("ownsPlayer", MediaPlayer.class, int.class); owns.setAccessible(true);
      Object media = field(service, "media"); int generation = (Integer) field(service, "generation");
      check("Null or obsolete player callback is rejected", !(Boolean) owns.invoke(service, null, generation)
          && !(Boolean) owns.invoke(service, media, generation - 1));
      command("pause", new JSONObject()); SystemClock.sleep(650);
      Method release = serviceType.getDeclaredMethod("releaseMedia"); release.setAccessible(true);
      runOnMainSync(() -> {try { release.invoke(service); } catch(Exception error) {throw new RuntimeException(error);} });
      check("Released player callback and data source are retired", !(Boolean) owns.invoke(service, media, generation)
          && field(service, "mediaSource") == null && !state().optBoolean("playing"));
      SystemClock.sleep(2500);
      Field closePool = http.getDeclaredField("closeWorker"); closePool.setAccessible(true);
      check("HTTP cleanup worker retires after release", ((ThreadPoolExecutor) closePool.get(null)).getPoolSize() == 0);
      Bundle timings = new Bundle();
      timings.putString("stream", "IPV4_TIMING homeReadyMs=" + result.getLong("homeReadyMs") + " playReadyMs=" + result.getLong("playReadyMs") + "\n");
      sendStatus(0, timings);
      result.putInt("checks", passed);
      result.putString("stream", "IPV4_PLAYBACK_DEVICE_PASS\n");
    } catch(Throwable error) {
      code = Activity.RESULT_CANCELED;
      Throwable cause = error instanceof InvocationTargetException ? error.getCause() : error;
      result.putString("stream", "IPV4_PLAYBACK_DEVICE_FAIL " + cause + "\n");
    } finally {
      if (probe != null) try { probe.close(); } catch(IOException ignored) {}
      Activity restore = launched; int orientation = originalOrientation;
      if (restore != null) runOnMainSync(() -> restore.setRequestedOrientation(orientation));
    }
    finish(code, result);
  }
}
