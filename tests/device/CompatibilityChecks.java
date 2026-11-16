package cn.ting.music.tests;

import android.app.*;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.view.WindowManager;
import android.webkit.WebView;
import java.lang.reflect.*;
import java.util.concurrent.*;
import org.json.*;

/** 在真实系统与 WebView 中验证旧 API、偏好布局和只读接口。 */
public final class CompatibilityChecks extends Instrumentation {
  private WebView web;
  private boolean network;

  @Override public void onCreate(Bundle args) {
    super.onCreate(args);
    network = args != null && "true".equals(args.getString("network"));
    start();
  }

  private String js(String source) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    String[] value = new String[1];
    runOnMainSync(() -> web.evaluateJavascript(source, result -> {
      value[0] = result;
      done.countDown();
    }));
    if (!done.await(8, TimeUnit.SECONDS)) throw new AssertionError("WebView 未响应");
    return value[0];
  }

  private void waitFor(String expression) throws Exception {
    for (int i = 0; i < 100; i++) {
      if ("true".equals(js(expression))) return;
      SystemClock.sleep(100);
    }
    throw new AssertionError("界面等待超时：" + expression);
  }

  private void report(String message) {
    Bundle state = new Bundle();
    state.putString("stream", message + "\n");
    sendStatus(0, state);
  }

  private void networkChecks(Context context) throws Exception {
    Class<?> type = context.getClassLoader().loadClass("cn.ting.music.KugouApi");
    Object api = type.getConstructor(Context.class).newInstance(context);
    JSONObject account = (JSONObject) type.getMethod("account").invoke(api);
    if (!account.optBoolean("loggedIn")) {
      report("VIP_NETWORK_SKIPPED: 设备未登录，未执行认证或发送验证码");
      return;
    }
    for (String route : new String[] {"vip", "vipRecord"}) {
      long started = SystemClock.elapsedRealtime();
      try {
        JSONObject response = (JSONObject) type.getMethod("catalog", String.class, JSONObject.class)
            .invoke(api, route, new JSONObject());
        report("REAL_NATIVE_REQUEST " + route + " elapsedMs=" + (SystemClock.elapsedRealtime() - started)
            + " status=" + response.optInt("status", -1));
      } catch (InvocationTargetException error) {
        report("REAL_NATIVE_REQUEST " + route + " elapsedMs=" + (SystemClock.elapsedRealtime() - started)
            + " failure=" + error.getCause().getClass().getSimpleName());
      }
    }
  }

  @Override public void onStart() {
    Bundle result = new Bundle();
    int code = Activity.RESULT_OK;
    try {
      Context target = getTargetContext();
      Activity activity = DeviceLaunch.start(this, target);
      Field field = activity.getClass().getDeclaredField("web");
      field.setAccessible(true);
      web = (WebView) field.get(activity);
      report("DEVICE sdk=" + Build.VERSION.SDK_INT + " webview=" + WebView.getCurrentWebViewPackage().versionName);
      Class<?> screen = target.getClassLoader().loadClass("cn.ting.music.LyricsScreen");
      Method window = screen.getDeclaredMethod("windowContext", Context.class);
      window.setAccessible(true);
      if (Build.VERSION.SDK_INT >= 30) {
        Context first = (Context) window.invoke(null, activity);
        for (int attempt = 0; attempt < 100; attempt++) {
          if (window.invoke(null, target.getApplicationContext()) != first)
            throw new AssertionError("悬浮窗口上下文未复用");
        }
        report("OVERLAY_CONTEXT_REUSE_100_PASS");
      }
      Method safe = screen.getDeclaredMethod("safe", Context.class, WindowManager.class, android.view.WindowInsets.class);
      safe.setAccessible(true);
      Rect area = (Rect) safe.invoke(null, activity, activity.getSystemService(WindowManager.class), null);
      if (area.width() <= 0 || area.height() <= 0) throw new AssertionError("歌词安全区域无效");
      report("LYRICS_SAFE_AREA_PASS");
      waitFor("!!document.querySelector('.topbar button[aria-label=\"账号与设置\"]')");
      js("document.querySelector('.topbar button[aria-label=\"账号与设置\"]').click()");
      waitFor("!!document.querySelector('.account-panel')");
      js("[...document.querySelectorAll('.sheet-panel button')].find(el=>el.textContent.trim()==='偏好设置').click()");
      waitFor("!!document.querySelector('.preferences-panel')");
      SystemClock.sleep(500);
      JSONObject layout = new JSONObject((String) new JSONTokener(js("JSON.stringify((()=>{const p=document.querySelector('.sheet-panel');const r=p.getBoundingClientRect();return {viewport:innerWidth,height:innerHeight,width:r.width,top:r.top,bottom:r.bottom,maxHeight:getComputedStyle(p).maxHeight,columns:getComputedStyle(document.querySelector('.preferences-grid')).gridTemplateColumns,dvh:CSS.supports('height','100dvh')}})())")).nextValue());
      report("PREFERENCES_LAYOUT " + layout);
      if (layout.getDouble("top") < -1 || layout.getDouble("bottom") > layout.getDouble("height") + 1
          || "none".equals(layout.getString("maxHeight"))) throw new AssertionError("偏好弹窗超出屏幕");
      if (layout.getInt("viewport") >= 700 && layout.getDouble("width") < 600)
        throw new AssertionError("平板仍使用手机宽度");
      js("document.querySelector('.about-preferences').scrollIntoView({block:'nearest'})");
      waitFor("(()=>{const r=document.querySelector('.about-preferences').getBoundingClientRect();return r.top<innerHeight&&r.bottom>0})()");
      js("document.querySelector('.sheet-panel .close-sheet').click()");
      waitFor("!document.querySelector('.sheet-panel')");
      report("PREFERENCES_DEVICE_PASS");
      if (network) networkChecks(target);
      result.putString("stream", "COMPATIBILITY_DEVICE_PASS\n");
    } catch (Throwable error) {
      code = Activity.RESULT_CANCELED;
      Throwable cause = error instanceof InvocationTargetException ? error.getCause() : error;
      result.putString("stream", "COMPATIBILITY_DEVICE_FAIL: " + cause + "\n");
    }
    finish(code, result);
  }
}
