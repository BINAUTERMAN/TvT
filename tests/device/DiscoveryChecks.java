package cn.ting.music.tests;

import android.app.*;
import android.os.*;
import android.webkit.WebView;
import java.lang.reflect.*;
import java.util.concurrent.*;
import org.json.*;

/** 使用手机本地登录态只读验证，不输出账号、令牌、真实歌词或播放地址。 */
public final class DiscoveryChecks extends Instrumentation {
  private Activity activity;
  private boolean extrasOnly;
  private boolean searchOnly;
  private boolean coversOnly;

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    extrasOnly = "true".equals(args.getString("extrasOnly"));
    searchOnly = "true".equals(args.getString("searchOnly"));
    coversOnly = "true".equals(args.getString("coversOnly"));
    extrasOnly |= searchOnly;
    start();
  }

  private void check(String name, boolean passed) throws Exception {
    Bundle status = new Bundle();
    status.putString("stream", name + ": " + (passed ? "PASS" : "FAIL") + "\n");
    sendStatus(0, status);
    if (!passed) throw new Exception(name);
  }

  private String js(String source) throws Exception {
    Field field = activity.getClass().getDeclaredField("web");
    field.setAccessible(true);
    WebView web = (WebView) field.get(activity);
    String[] result = new String[1];
    CountDownLatch done = new CountDownLatch(1);
    runOnMainSync(
        () ->
            web.evaluateJavascript(
                source,
                value -> {
                  result[0] = value;
                  done.countDown();
                }));
    if (!done.await(8, TimeUnit.SECONDS)) throw new Exception("WebView response timeout");
    return result[0];
  }

  private void waitFor(String expression, String name) throws Exception {
    for (int attempt = 0; attempt < 160; attempt++) {
      if ("true".equals(js("!!(" + expression + ")"))) {
        check(name, true);
        return;
      }
      SystemClock.sleep(300);
    }
    Bundle debug = new Bundle();
    debug.putString(
        "stream",
        "UI_CHECK_FLAGS "
            + js(
                "({hidden:document.hidden,focused:document.activeElement?.tagName,queryLength:document.querySelector('.search-box"
                    + " input')?.value.length,options:document.querySelectorAll('#search-suggestions"
                    + " [role=option]').length,activeNav:[...document.querySelectorAll('.bottom-nav"
                    + " button')].find(item=>item.classList.contains('active'))?.textContent})")
            + "\n");
    sendStatus(0, debug);
    check(name, false);
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    try {
      Application application = (Application) getTargetContext().getApplicationContext();
      CountDownLatch resumed = new CountDownLatch(1);
      Application.ActivityLifecycleCallbacks callback =
          new Application.ActivityLifecycleCallbacks() {
            public void onActivityResumed(Activity value) {
              activity = value;
              resumed.countDown();
            }

            public void onActivityCreated(Activity value, Bundle state) {}

            public void onActivityStarted(Activity value) {}

            public void onActivityPaused(Activity value) {}

            public void onActivityStopped(Activity value) {}

            public void onActivitySaveInstanceState(Activity value, Bundle state) {}

            public void onActivityDestroyed(Activity value) {}
          };
      application.registerActivityLifecycleCallbacks(callback);
      try (ParcelFileDescriptor descriptor =
              getUiAutomation().executeShellCommand("am start -n cn.ting.music/.MainActivity");
          java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
        byte[] bytes = new byte[1024];
        while (input.read(bytes) != -1) {}
      }
      resumed.await(12, TimeUnit.SECONDS);
      application.unregisterActivityLifecycleCallbacks(callback);
      check("Activity resumed", activity != null);
      Class<?> type = getTargetContext().getClassLoader().loadClass("cn.ting.music.KugouApi");
      Field apiField = activity.getClass().getDeclaredField("api");
      apiField.setAccessible(true);
      Object api = apiField.get(activity);
      JSONObject status = (JSONObject) type.getMethod("vipStatus", boolean.class).invoke(api, true);
      check(
          "Cloud VIP status and today record available",
          status.optBoolean("claimKnown") && status.optLong("checkedAt") > 0);
      JSONArray rights = status.getJSONArray("rights");
      boolean concept = false;
      for (int index = 0; index < rights.length(); index++) {
        JSONObject right = rights.getJSONObject(index);
        if (right.optBoolean("active") && right.optString("label").equals("概念版 VIP"))
          concept = true;
      }
      check(
          "Concept rights reflected in primary status",
          !concept || status.optString("label").equals("概念版 VIP"));
      if (status.optBoolean("todayClaimed")) {
        JSONObject claim = (JSONObject) type.getMethod("autoVip", boolean.class).invoke(api, true);
        check(
            "Already-claimed day short circuits claim submission",
            claim.optBoolean("alreadyClaimed") && claim.optBoolean("claimed"));
      }
      waitFor("document.querySelector('.topbar [aria-label=\"账号与设置\"]')", "Web UI ready");
      if (coversOnly) {
        waitFor(
            "document.querySelectorAll('.personal-home .entity-shelf .entity-card').length===8",
            "Eight featured playlists rendered");
        for (int index = 0; index < 8; index++) {
          String card =
              "document.querySelectorAll('.personal-home .entity-shelf .entity-card')["
                  + index
                  + "]";
          js(card + ".scrollIntoView({block:'center',inline:'center'})");
          waitFor(
              card + ".querySelector('img')?.naturalWidth>0",
              "Featured cover decoded " + (index + 1));
          check(
              "Featured image is not creator avatar " + (index + 1),
              "true".equals(js("!" + card + ".querySelector('img').src.includes('/kugouicon/')")));
        }
        js("document.querySelector('.personal-home .entity-shelf').scrollLeft=0");
        result.putString("stream", "REAL_FEATURED_COVERS_PASS\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      js(
          "document.querySelector('.notification-panel .confirmation-actions"
              + " .secondary-button')?.click()");
      js("document.querySelector('.topbar [aria-label=\"账号与设置\"]').click()");
      waitFor(
          "document.querySelector('.account-summary .vip-badge')?.textContent === "
              + JSONObject.quote(status.getString("label")),
          "Account badge matches cloud VIP");
      js(
          "[...document.querySelectorAll('button')].find(item=>item.textContent.trim()==='概念版 VIP"
              + " 与领取记录').click()");
      waitFor(
          "document.querySelector('.vip-status')?.textContent.includes("
              + JSONObject.quote(status.getString("label"))
              + ")",
          "VIP panel matches account badge");
      if (status.optBoolean("todayClaimed"))
        waitFor(
            "[...document.querySelectorAll('.vip-panel"
                + " button')].some(item=>item.textContent.includes('今日已领取')&&item.disabled)",
            "Cloud-confirmed claimed button disabled");
      if (status.optJSONArray("todayReceipts") != null) {
        JSONArray receipts = status.getJSONArray("todayReceipts");
        for (int index = 0; index < receipts.length(); index++)
          waitFor(
              "document.querySelector('.vip-panel')?.textContent.includes("
                  + JSONObject.quote(receipts.getJSONObject(index).getString("label"))
                  + ")",
              "Actual receipt type shown");
      }
      js("document.querySelector('[aria-label=\"关闭\"]')?.click()");
      if (!extrasOnly) {
        js(
            "[...document.querySelectorAll('.bottom-nav"
                + " button')].find(item=>item.textContent.includes('发现')).click()");
        waitFor("document.querySelector('.rank-section .entity-card')", "Real rank list rendered");
        js("document.querySelector('.rank-section .entity-card').click()");
        waitFor(
            "document.querySelectorAll('.detail-page .track-row').length>0",
            "Rank opens real songs on device");
        js("document.querySelector('.detail-page .back-link').click()");
        waitFor("document.querySelector('.discover-types')", "Returned to discovery");
        js(
            "[...document.querySelectorAll('.category-groups"
                + " button')].find(item=>item.textContent.trim()==='语种').click()");
        waitFor(
            "document.querySelectorAll('.explore-page > .music-section:last-of-type"
                + " .entity-card').length>0 && !document.querySelector('.explore-page .loading')",
            "Language category has public playlists");
        js(
            "[...document.querySelectorAll('.discover-types"
                + " button')].find(item=>item.textContent.trim()==='歌曲').click()");
        waitFor(
            "document.querySelectorAll('.explore-page .track-row').length>0",
            "Scene category has real playable song metadata");
      }
      JSONObject search =
          (JSONObject)
              type.getMethod("search", String.class, int.class).invoke(api, "Shape of You", 1);
      JSONArray songs = search.getJSONObject("data").getJSONArray("lists");
      JSONObject song = songs.getJSONObject(0);
      JSONObject payload =
          new JSONObject()
              .put("hash", song.getString("FileHash"))
              .put("albumId", song.optString("AlbumID"))
              .put("audioId", song.optString("MixSongID"));
      js(
          "window.__lyricCheck=null;window.__savedResolve=window.nativeResolve;window.nativeResolve=(id,value,error)=>{if(id==='device-lyric-check'){window.__lyricCheck={ok:!error,count:value?.lines?.length||0,translated:value?.lines?.filter(item=>!!item.translation).length||0};window.nativeResolve=window.__savedResolve;delete"
              + " window.__savedResolve;}else"
              + " window.__savedResolve(id,value,error)};window.Native.request('device-lyric-check','lyricDocument',"
              + JSONObject.quote(payload.toString())
              + ")");
      waitFor(
          "window.__lyricCheck?.ok&&window.__lyricCheck.count>0&&window.__lyricCheck.translated>0",
          "Real translated lyrics cross native bridge");
      if (!extrasOnly || searchOnly) {
        js(
            "[...document.querySelectorAll('.bottom-nav"
                + " button')].find(item=>item.textContent.includes('搜索')).click()");
        waitFor(
            "document.querySelector('.search-page .search-box input')", "Search screen mounted");
        Field webField = activity.getClass().getDeclaredField("web");
        webField.setAccessible(true);
        WebView searchWeb = (WebView) webField.get(activity);
        runOnMainSync(() -> searchWeb.requestFocus());
        JSONArray inputPoint =
            new JSONArray(
                js(
                    "(()=>{const r=document.querySelector('.search-box"
                        + " input').getBoundingClientRect();return"
                        + " [(r.left+r.width/2)/innerWidth,(r.top+r.height/2)/innerHeight]})()"));
        int[] webLocation = new int[2];
        runOnMainSync(() -> searchWeb.getLocationOnScreen(webLocation));
        float touchX = webLocation[0] + (float) inputPoint.getDouble(0) * searchWeb.getWidth();
        float touchY = webLocation[1] + (float) inputPoint.getDouble(1) * searchWeb.getHeight();
        long touchTime = SystemClock.uptimeMillis();
        android.view.MotionEvent down =
            android.view.MotionEvent.obtain(
                touchTime, touchTime, android.view.MotionEvent.ACTION_DOWN, touchX, touchY, 0);
        android.view.MotionEvent up =
            android.view.MotionEvent.obtain(
                touchTime, touchTime + 50, android.view.MotionEvent.ACTION_UP, touchX, touchY, 0);
        getUiAutomation().injectInputEvent(down, true);
        getUiAutomation().injectInputEvent(up, true);
        down.recycle();
        up.recycle();
        js(
            "document.querySelector('.search-box"
                + " input').focus();document.querySelector('.search-box"
                + " input').value='周杰';document.querySelector('.search-box"
                + " input').dispatchEvent(new Event('input',{bubbles:true}))");
        js("document.querySelector('.search-box input').dispatchEvent(new Event('focus'))");
        waitFor(
            "document.querySelectorAll('#search-suggestions [role=option]').length>0",
            "Real search suggestions rendered on device");
        waitFor(
            "document.querySelector('.app-shell')?.classList.contains('search-editing')&&getComputedStyle(document.querySelector('.bottom-dock')).visibility==='hidden'",
            "Search input retracts playback and navigation dock");
        saveScreen("search-keyboard.png");
        js("document.querySelector('#search-suggestions [role=option]').click()");
        waitFor(
            "document.querySelectorAll('.search-page .track-row').length>0",
            "Suggested keyword returns real music");
        waitFor(
            "!document.querySelector('.app-shell')?.classList.contains('search-editing')&&getComputedStyle(document.querySelector('.bottom-dock')).visibility==='visible'",
            "Search submission restores dock");
        check(
            "Six search tabs fit inside the device viewport",
            "true"
                .equals(
                    js(
                        "(()=>{const group=document.querySelector('.search-types');const"
                            + " r=group.getBoundingClientRect();return"
                            + " [...group.querySelectorAll('button')].every(item=>item.getBoundingClientRect().right<=r.right+1&&item.scrollWidth<=item.clientWidth)})()")));
        js(
            "document.querySelector('.search-box"
                + " input').value='故事的小黄花';document.querySelector('.search-box"
                + " input').dispatchEvent(new"
                + " Event('input',{bubbles:true}));[...document.querySelectorAll('.search-types"
                + " button')].find(item=>item.getAttribute('aria-label')==='歌词搜歌').click()");
        waitFor(
            "document.querySelector('.search-types"
                + " [aria-selected=true]')?.getAttribute('aria-label')==='歌词搜歌'&&document.querySelectorAll('.search-page"
                + " .track-row').length>0",
            "Real lyric search returns playable metadata");
      }
      if (searchOnly) {
        result.putString("stream", "SEARCH_UI_PASSED\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      JSONObject qualityResult =
          (JSONObject)
              type.getMethod("bestSongUrl", JSONObject.class)
                  .invoke(api, payload.put("quality", "high"));
      check(
          "Highest permitted quality resolves through account authorization",
          qualityResult.optString("url").startsWith("http")
              && java.util.Arrays.asList("high", "flac", "320", "128")
                  .contains(qualityResult.optString("quality")));
      Bundle qualityStatus = new Bundle();
      qualityStatus.putString(
          "stream", "Resolved quality: " + qualityResult.optString("quality") + "\n");
      sendStatus(0, qualityStatus);
      Class<?> access =
          getTargetContext().getClassLoader().loadClass("cn.ting.music.NotificationAccess");
      Method readAccess =
          access.getDeclaredMethod("status", android.content.Context.class, JSONObject.class);
      readAccess.setAccessible(true);
      JSONObject notification =
          (JSONObject) readAccess.invoke(null, getTargetContext(), new JSONObject());
      check(
          "Own notification settings readable without broad device access",
          notification.has("enabled")
              && notification.has("lockscreen")
              && notification.has("floating"));
      if (notification.optBoolean("enabled"))
        check(
            "Notification importance never misreports vendor floating permission",
            notification.optString("floating").equals("unknown"));
      js("document.querySelector('.topbar [aria-label=\"账号与设置\"]').click()");
      waitFor(
          "[...document.querySelectorAll('button')].some(item=>item.textContent.trim()==='偏好设置')",
          "Settings entry ready");
      js(
          "[...document.querySelectorAll('button')].find(item=>item.textContent.trim()==='偏好设置').click()");
      js(
          "[...document.querySelectorAll('button')].find(item=>item.textContent.trim()==='锁屏与悬浮通知').click()");
      waitFor(
          "document.querySelectorAll('.notification-panel .confirmation-actions"
              + " button').length===3",
          "Notification three-action layout rendered");
      JSONArray boxes =
          new JSONArray(
              js(
                  "[...document.querySelectorAll('.notification-panel .confirmation-actions"
                      + " button')].map(item=>{const r=item.getBoundingClientRect();return"
                      + " [r.y,r.width,r.height]})"));
      boolean aligned = true;
      for (int index = 1; index < 3; index++)
        for (int dimension = 0; dimension < 3; dimension++)
          aligned &=
              Math.abs(
                      boxes.getJSONArray(index).getDouble(dimension)
                          - boxes.getJSONArray(0).getDouble(dimension))
                  < 1;
      check("Three notification buttons share exact size and baseline on device", aligned);
      saveScreen("notification-layout.png");
      js("document.querySelector('[aria-label=\"关闭\"]')?.click()");
      checkFloating();
      result.putString("stream", "DISCOVERY_VIP_CHECKS_PASSED\n");
      finish(Activity.RESULT_OK, result);
    } catch (Exception error) {
      Throwable cause =
          error instanceof InvocationTargetException
              ? ((InvocationTargetException) error).getCause()
              : error;
      result.putString(
          "stream", "FAIL " + cause.getClass().getSimpleName() + ": " + cause.getMessage() + "\n");
      finish(Activity.RESULT_CANCELED, result);
    }
  }

  private void saveScreen(String name) throws Exception {
    SystemClock.sleep(450);
    android.graphics.Bitmap image = getUiAutomation().takeScreenshot();
    if (image == null) throw new Exception("Screenshot unavailable");
    try (java.io.FileOutputStream output =
        new java.io.FileOutputStream(
            new java.io.File(getTargetContext().getExternalFilesDir(null), name))) {
      image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
    } finally {
      image.recycle();
    }
  }

  private Object value(Object object, String name) throws Exception {
    Field field = object.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(object);
  }

  private void checkFloating() throws Exception {
    if (!android.provider.Settings.canDrawOverlays(getTargetContext())) {
      Bundle message = new Bundle();
      message.putString(
          "stream", "Floating visual validation skipped: overlay permission not granted\n");
      sendStatus(0, message);
      return;
    }
    android.content.SharedPreferences preferences =
        getTargetContext().getSharedPreferences("preferences", 0);
    String[] keys = {
      "lyricsTranslation",
      "floatingLyrics",
      "lyricsLocked",
      "lyricsWidth",
      "lyricsSize",
      "lyricsAccent",
      "lyricsRelativeX",
      "lyricsRelativeY",
      "lyricsX",
      "lyricsY"
    };
    java.util.Map<String, ?> all = preferences.getAll();
    java.util.Map<String, Object> before = new java.util.HashMap<>();
    for (String key : keys) if (all.containsKey(key)) before.put(key, all.get(key));
    Class<?> type = getTargetContext().getClassLoader().loadClass("cn.ting.music.FloatingLyrics");
    Constructor<?> constructor = type.getDeclaredConstructor(android.content.Context.class);
    constructor.setAccessible(true);
    Object floating = constructor.newInstance(getTargetContext());
    Method show = type.getDeclaredMethod("showLine", JSONObject.class, String.class, long.class),
        refresh = type.getDeclaredMethod("refresh"),
        hide = type.getDeclaredMethod("hide");
    show.setAccessible(true);
    refresh.setAccessible(true);
    hide.setAccessible(true);
    Class<?> serviceType =
        getTargetContext().getClassLoader().loadClass("cn.ting.music.PlaybackService");
    Object service = serviceType.getField("instance").get(null);
    Object current = service == null ? null : value(service, "floatingLyrics");
    JSONObject line =
        new JSONObject()
            .put("time", 0)
            .put("end", 5000)
            .put("text", "让音乐在此刻流动")
            .put("translation", "Your music, at your pace")
            .put(
                "words",
                new JSONArray()
                    .put(new JSONObject().put("time", 0).put("end", 5000).put("text", "让音乐在此刻流动")));
    try {
      preferences
          .edit()
          .putBoolean("lyricsTranslation", true)
          .putBoolean("floatingLyrics", true)
          .putBoolean("lyricsLocked", false)
          .putInt("lyricsWidth", 88)
          .putInt("lyricsSize", 22)
          .putString("lyricsAccent", "gold")
          .commit();
      runOnMainSync(
          () -> {
            try {
              if (current != null) hide.invoke(current);
              show.invoke(floating, line, "", 2300L);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      SystemClock.sleep(350);
      check("Native floating lyric panel attached", value(floating, "panel") != null);
      android.widget.TextView text = (android.widget.TextView) value(floating, "text");
      check(
          "Native bilingual lyrics preserve source and translation",
          text.getText().toString().contains("Your music, at your pace"));
      check(
          "Desktop lyrics use centered alignment",
          (text.getGravity() & android.view.Gravity.HORIZONTAL_GRAVITY_MASK)
              == android.view.Gravity.CENTER_HORIZONTAL);
      check(
          "Editing controls hidden in normal lyric view",
          ((android.view.View) value(floating, "tools")).getVisibility() == android.view.View.GONE);
      saveScreen("floating-lyrics.png");
      preferences.edit().putBoolean("lyricsLocked", true).commit();
      runOnMainSync(
          () -> {
            try {
              refresh.invoke(floating);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      SystemClock.sleep(250);
      android.view.View panel = (android.view.View) value(floating, "panel");
      android.view.WindowManager.LayoutParams layout =
          (android.view.WindowManager.LayoutParams) value(floating, "layout");
      check(
          "Locked lyrics have transparent background and touch-through flag",
          panel.getBackground() == null
              && (layout.flags & android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0);
      if (android.os.Build.VERSION.SDK_INT >= 31)
        check(
            "Locked overlay obeys Android obscuring opacity limit",
            layout.alpha
                <= getTargetContext()
                    .getSystemService(android.hardware.input.InputManager.class)
                    .getMaximumObscuringOpacityForTouch());
      saveScreen("floating-lyrics-locked.png");
    } finally {
      runOnMainSync(
          () -> {
            try {
              hide.invoke(floating);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      android.content.SharedPreferences.Editor editor = preferences.edit();
      for (String key : keys) {
        Object value = before.get(key);
        if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof String) editor.putString(key, (String) value);
        else editor.remove(key);
      }
      editor.commit();
      if (service != null)
        runOnMainSync(
            () -> {
              try {
                serviceType.getMethod("refreshFloatingLyrics").invoke(service);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    }
  }
}
